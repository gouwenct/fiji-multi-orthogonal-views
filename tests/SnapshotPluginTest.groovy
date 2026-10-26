import ij.IJ
import ij.ImagePlus
import ij.ImageStack
import ij.gui.Roi
import ij.plugin.PlugIn
import ij.process.FloatProcessor
import org.fijisnapshot.SnapshotPlugin
import org.fijisnapshot.archive.SnapshotArchive
import org.fijisnapshot.model.StorageKind
import org.fijisnapshot.service.SnapshotRestoreService
import org.fijisnapshot.service.SnapshotSaveRequest
import org.fijisnapshot.service.SnapshotSaveService
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.zip.ZipFile

// Arguments: compatibility script, stock ij JAR. Put the stock JAR first on the classpath.
assert args.length == 2
assert Paths.get(IJ.protectionDomain.codeSource.location.toURI()) == Paths.get(args[1]).toAbsolutePath().normalize()
assert new SnapshotPlugin() instanceof PlugIn
def jar = Paths.get(SnapshotPlugin.protectionDomain.codeSource.location.toURI())
new ZipFile(jar.toFile()).withCloseable { zip ->
    assert zip.getInputStream(zip.getEntry('plugins.config')).getText('UTF-8').trim() ==
        'Plugins, "Snapshot", org.fijisnapshot.SnapshotPlugin'
}
def temporary = Files.createTempDirectory('snapshot-plugin-test-').toAbsolutePath().normalize()
def originals = []
def restored = []
def verify = { images ->
    assert images.size() == 2
    images.eachWithIndex { image, n ->
        assert image.title == "standalone-${n}"
        assert [image.width, image.height, image.NSlices, image.bitDepth] == [8, 6, 3, 32]
        assert image.getSlice() == 2
        assert image.displayRangeMin == 1.5 && image.displayRangeMax == 120.5
        assert image.roi.bounds == new java.awt.Rectangle(1, 1, 3, 2)
        for (int z = 0; z < 3; z++)
            for (int p = 0; p < 48; p++)
                assert image.getStack().getProcessor(z + 1).getf(p) == n * 1000 + z * 100 + p
    }
}
try {
    def destination = temporary.resolve('roundtrip.zip')
    def request = new SnapshotSaveRequest('standalone', destination)
    (0..<2).each { n ->
        def stack = new ImageStack(8, 6)
        (0..<3).each { z ->
            stack.addSlice(new FloatProcessor(8, 6, (0..<48).collect { p -> n * 1000f + z * 100f + p } as float[]))
        }
        def image = new ImagePlus("standalone-${n}", stack)
        image.setPosition(1, 2, 1)
        image.setDisplayRange(1.5, 120.5)
        image.setRoi(new Roi(1, 1, 3, 2))
        originals.add(image)
        request.addImage("image-${n}", image, StorageKind.EMBEDDED)
    }
    assert new SnapshotSaveService().save(request).embeddedCount == 2
    def manifest = new SnapshotArchive().readManifest(destination)
    assert manifest.format == 'fiji-snapshot' && manifest.formatVersion == 2
    assert manifest.images.every { it.position.slice == 2 } : manifest.images.collect { it.position.slice }
    def before = MessageDigest.getInstance('SHA-256').digest(Files.readAllBytes(destination))
    def service = new SnapshotRestoreService()
    assert service.preflight(destination).empty
    def result = service.restore(destination, [:], [] as Set, false, null)
    assert result.complete && result.issues.empty
    restored.addAll(result.images)
    verify(result.images)

    System.setProperty('snapshot.testMode', 'true')
    def shell = new GroovyShell(this.class.classLoader)
    shell.evaluate(new File(args[0]).getText('UTF-8'), 'Snapshot_.groovy')
    def compatible = shell.classLoader.loadClass('SnapshotService').restore(destination, [:], [] as Set, false)
    assert compatible.errors.empty
    restored.addAll(compatible.images)
    verify(compatible.images)
    assert Arrays.equals(before, MessageDigest.getInstance('SHA-256').digest(Files.readAllBytes(destination)))
    println 'PASS: plugin entry loads with stock ImageJ; JAR save/restore and Groovy restore preserve two TIFFs, pixels, Z, display range and ROI; input ZIP unchanged'
} finally {
    (originals + restored).each { it.flush() }
    Files.walk(temporary).withCloseable { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach {
            assert it.toAbsolutePath().normalize().startsWith(temporary)
            Files.deleteIfExists(it)
        }
    }
}
