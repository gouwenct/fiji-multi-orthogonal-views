import groovy.json.JsonOutput
import ij.IJ
import ij.ImageJ
import ij.ImagePlus
import ij.ImageStack
import ij.WindowManager
import ij.io.FileSaver
import ij.plugin.DragAndDrop
import ij.process.FloatProcessor
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// Run from Fiji.app, optionally passing a real snapshot ZIP.
Thread.startDaemon {
    Thread.sleep(45000)
    System.err.println('FAIL: snapshot drop timed out (an unexpected dialog may be open)')
    System.exit(2)
}
def temporary = Files.createTempDirectory('snapshot-drop-')
int status = 0
def writeZip = { path, entries ->
    new ZipOutputStream(Files.newOutputStream(path)).withCloseable { zip ->
        entries.each { name, bytes ->
            zip.putNextEntry(new ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
}
def closeImages = {
    (WindowManager.getIDList() ?: []).each { id ->
        def image = WindowManager.getImage(id)
        image.changes = false
        image.close()
    }
}
try {
    new ImageJ(ImageJ.NO_SHOW)
    IJ.setExceptionHandler({ Throwable error -> throw new RuntimeException('Drop handler failed', error) } as IJ.ExceptionHandler)
    assert new File(IJ.getDirectory('imagej'), 'scripts/Plugins/Snapshot_.groovy').isFile()
    def stack = new ImageStack(8, 6)
    (0..<3).each { z -> stack.addSlice(new FloatProcessor(8, 6, (0..<48).collect { it + z * 100f } as float[])) }
    def tiff = temporary.resolve('fixture.tif')
    assert new FileSaver(new ImagePlus('fixture', stack)).saveAsTiffStack(tiff.toString())
    def dimensions = [width: 8, height: 6, channels: 1, slices: 3, frames: 1, bitDepth: 32]
    def v2 = [format: 'fiji-snapshot', formatVersion: 2, name: 'drop', images: (1..2).collect { i -> [
        id: "image-${i}", title: "drop-v2-${i}", dimensions: dimensions,
        storageKind: 'EMBEDDED', storagePath: "images/image-${i}.tif",
        position: [channel: 1, slice: 2, frame: 1],
        displayState: [composite: false, channelMinimums: [1.5], channelMaximums: [120.5]],
        magnification: 1.0
    ] }]
    def v2zip = temporary.resolve('snapshot-v2.zip')
    writeZip(v2zip, ['snapshot.json': JsonOutput.toJson(v2).getBytes('UTF-8'),
        'images/image-1.tif': Files.readAllBytes(tiff), 'images/image-2.tif': Files.readAllBytes(tiff)])
    new DragAndDrop().openFile(v2zip.toFile())
    assert WindowManager.getImageCount() == 2 : 'Snapshot ZIP did not restore its images'
    WindowManager.getIDList().each { id ->
        def image = WindowManager.getImage(id)
        assert image.getSlice() == 2 && image.getNSlices() == 3
        assert image.displayRangeMin == 1.5 && image.displayRangeMax == 120.5
    }
    closeImages()
    def v1 = [format: 'fiji-snapshot', version: 1, images: [[
        id: 'image-1', title: 'drop-v1', dimensions: dimensions,
        storage: [kind: 'embedded', path: 'images/image-1.tif'],
        position: [c: 1, z: 2, t: 1], display: [channelRanges: [[min: 1.5, max: 120.5]]]
    ]]]
    def v1zip = temporary.resolve('snapshot-v1.ZIP')
    writeZip(v1zip, ['snapshot.json': JsonOutput.toJson(v1).getBytes('UTF-8'),
        'images/image-1.tif': Files.readAllBytes(tiff)])
    new DragAndDrop().openFile(v1zip.toFile())
    assert WindowManager.getImageCount() == 1 && IJ.getImage().title == 'drop-v1'
    closeImages()
    def ordinary = temporary.resolve('ordinary.zip')
    writeZip(ordinary, ['fixture.tif': Files.readAllBytes(tiff),
        'snapshot.json': '{"format":"unrelated"}'.getBytes('UTF-8')])
    new DragAndDrop().openFile(ordinary.toFile())
    assert WindowManager.getImageCount() == 1 && IJ.getImage().width == 8
    closeImages()
    new DragAndDrop().openFile(tiff.toFile())
    assert WindowManager.getImageCount() == 1 && IJ.getImage().getNSlices() == 3
    closeImages()
    println 'PASS: drop entry restores v1/v2 snapshots without a chooser; ordinary TIFF and ZIP still open'
    if (args.length > 0) {
        new DragAndDrop().openFile(new File(args[0]))
        assert WindowManager.getImageCount() == 10
        WindowManager.getIDList().each { id ->
            def image = WindowManager.getImage(id)
            assert image.width == 512 && image.height == 512 && image.getNSlices() == 32 && image.getSlice() == 17
        }
        println 'PASS: 50-final.zip restored 10 TIFFs via the drop entry, with Z=17'
        closeImages()
    }
} catch (Throwable error) {
    error.printStackTrace()
    System.err.println(IJ.getLog())
    status = 1
} finally {
    Files.walk(temporary).withCloseable { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
    System.exit(status)
}
