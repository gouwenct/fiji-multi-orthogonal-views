import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import ij.ImagePlus
import ij.ImageStack
import ij.gui.Roi
import ij.io.FileSaver
import ij.process.FloatProcessor
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

// Arguments: deployed Snapshot_.groovy, optional real snapshot directory.
assert args.length in 1..2
System.setProperty('snapshot.testMode', 'true')
def shell = new GroovyShell(this.class.classLoader)
shell.evaluate(new File(args[0]).getText('UTF-8'), 'Snapshot_.groovy')
def archive = shell.classLoader.loadClass('SnapshotArchive')
def service = shell.classLoader.loadClass('SnapshotService')
def state = shell.classLoader.loadClass('SnapshotState')
def temporary = Files.createTempDirectory('snapshot-compat-test-')

def writeZip = { target, manifest, assets ->
    new ZipOutputStream(Files.newOutputStream(target)).withCloseable { zip ->
        zip.putNextEntry(new ZipEntry('snapshot.json'))
        zip.write(JsonOutput.toJson(manifest).getBytes('UTF-8'))
        zip.closeEntry()
        assets.each { name, path ->
            zip.putNextEntry(new ZipEntry(name))
            Files.copy(path, zip)
            zip.closeEntry()
        }
    }
}
def hash = { path ->
    def digest = MessageDigest.getInstance('SHA-256')
    Files.newInputStream(path).withCloseable { input ->
        byte[] buffer = new byte[1024 * 1024]
        for (int n = input.read(buffer); n >= 0; n = input.read(buffer))
            if (n > 0) digest.update(buffer, 0, n)
    }
    digest.digest().encodeHex().toString()
}
def verifyRestore = { path ->
    def before = hash(path)
    def manifest = archive.readManifest(path)
    assert service.preflight(path).issues.empty
    boolean showWindows = Boolean.getBoolean('snapshot.testWindows')
    def result = service.restore(path, [:], [] as Set, showWindows)
    try {
        assert result.errors.empty : result.errors
        assert result.images.size() == manifest.images.size()
        result.images.eachWithIndex { image, i ->
            def expected = manifest.images[i]
            assert image.title == expected.title
            assert [image.width, image.height, image.NChannels, image.NSlices, image.NFrames, image.bitDepth] ==
                ['width', 'height', 'channels', 'slices', 'frames', 'bitDepth'].collect { expected.dimensions[it] }
            assert [image.c, image.z, image.t] == [expected.position.c, expected.position.z, expected.position.t]
            expected.display.channelRanges.eachWithIndex { range, c ->
                image.setPosition(c + 1, image.z, image.t)
                assert Math.abs(image.displayRangeMin - range.min.doubleValue()) < 1e-6
                assert Math.abs(image.displayRangeMax - range.max.doubleValue()) < 1e-6
            }
            assert image.getProcessor().getPixels().length == image.width * image.height
            if (showWindows && expected.canvas)
                assert image.getCanvas().getMagnification() == expected.canvas.magnification.doubleValue()
        }
        println "PASS: ${path.fileName}: restored ${result.images.size()} images; dimensions, C/Z/T and display ranges match"
    } finally {
        result.images.each { if (showWindows) it.close() else it.flush() }
    }
    assert hash(path) == before : 'Input snapshot changed'
}

try {
    def stack = new ImageStack(9, 7)
    (0..<3).each { z -> stack.addSlice(new FloatProcessor(9, 7, (0..<63).collect { it + z * 100f } as float[])) }
    def image = new ImagePlus('compat-fixture', stack)
    assert new FileSaver(image).saveAsTiffStack(temporary.resolve('fixture.tif').toString())
    def v2 = [format: 'fiji-snapshot', formatVersion: 2, name: 'compat', images: [[
        id: 'image-0001', title: 'compat-fixture',
        dimensions: [width: 9, height: 7, channels: 1, slices: 3, frames: 1, bitDepth: 32],
        storageKind: 'EMBEDDED', storagePath: 'images/image-0001.tif',
        position: [channel: 1, slice: 2, frame: 1],
        displayState: [channelMinimums: [1.5], channelMaximums: [120.5], composite: false, displayMode: 0],
        magnification: 0.5, roiPath: 'rois/current.roi', overlayPath: 'rois/overlay.zip'
    ]]]
    state.saveRoi(new Roi(1, 1, 3, 2), temporary.resolve('current.roi'))
    state.saveRoiSet([new Roi(2, 1, 2, 2)] as Roi[], temporary.resolve('overlay.zip'))
    def fixture = temporary.resolve('v2.zip')
    writeZip(fixture, v2, ['images/image-0001.tif': temporary.resolve('fixture.tif'),
        'rois/current.roi': temporary.resolve('current.roi'), 'rois/overlay.zip': temporary.resolve('overlay.zip')])
    verifyRestore(fixture)
    def normalized = archive.readManifest(fixture)
    assert !normalized.containsKey('formatVersion') && normalized.version == 1
    assert normalized.images[0].canvas.magnification == 0.5
    def restored = service.restore(fixture, [:], [] as Set, false)
    assert restored.images[0].getRoi().bounds == new java.awt.Rectangle(1, 1, 3, 2)
    assert restored.images[0].getOverlay().size() == 1
    restored.images.each { it.flush() }
    def remapped = temporary.resolve('remapped.zip')
    archive.writeRemapped(fixture, remapped, normalized)
    verifyRestore(remapped)
    def v1 = temporary.resolve('v1.zip')
    service.save(v1, 'embedded', [[imp: image, action: 'embedded']], null)
    verifyRestore(v1)
    v2.formatVersion = 99
    writeZip(temporary.resolve('unsupported.zip'), v2, [:])
    try {
        archive.readManifest(temporary.resolve('unsupported.zip'))
        assert false : 'Unsupported schema was accepted'
    } catch (IllegalArgumentException expected) {
        assert expected.message.contains('99')
    }
    if (args.length == 2) {
        def files = new File(args[1]).listFiles().findAll { it.name.endsWith('.zip') }.sort { it.name }
        assert !files.empty
        files.each { verifyRestore(it.toPath()) }
    }
    println 'PASS: v1, v2, ROI, remapped manifest, unsupported version and unchanged input checks'
} finally {
    Files.walk(temporary).withCloseable { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
}
