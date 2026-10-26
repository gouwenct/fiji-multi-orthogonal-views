import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import ij.CompositeImage
import ij.IJ
import ij.ImagePlus
import ij.WindowManager
import ij.gui.ImageCanvas
import ij.gui.ImageWindow
import ij.gui.Overlay
import ij.gui.Roi
import ij.gui.GenericDialog
import ij.gui.YesNoCancelDialog
import ij.io.FileInfo
import ij.io.FileSaver
import ij.io.OpenDialog
import ij.io.RoiDecoder
import ij.io.RoiEncoder
import ij.io.SaveDialog
import ij.plugin.frame.RoiManager

import java.awt.Rectangle
import java.awt.GraphicsEnvironment
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Snapshot archive primitives.
 *
 * A Snapshot file is a UTF-8 ZIP archive containing snapshot.json and optional
 * relative-path assets. New files use .zip; legacy .ijsnapshot files remain
 * readable. Writes use a sibling temporary file so failures leave no partial
 * destination.
 */
class SnapshotArchive {
    static final String FORMAT = 'fiji-snapshot'
    static final int VERSION = 1
    static final String MANIFEST_ENTRY = 'snapshot.json'

    static void write(Path archive, Map manifest, Path contentRoot) {
        requireArchivePath(archive)
        validateManifest(manifest)
        Path parent = archive.toAbsolutePath().normalize().parent
        if (parent != null) Files.createDirectories(parent)
        Path temporary = temporarySibling(archive)
        try {
            ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary), StandardCharsets.UTF_8)
            try {
                putBytes(zip, MANIFEST_ENTRY, manifestBytes(manifest))
                if (contentRoot != null && Files.isDirectory(contentRoot)) {
                    Path root = contentRoot.toAbsolutePath().normalize()
                    List<Path> assets = []
                    Files.walk(root).withCloseable { stream ->
                        stream.filter { Files.isRegularFile(it) }.forEach { assets.add(it) }
                    }
                    assets.sort { left, right ->
                        entryName(root.relativize(left)) <=> entryName(root.relativize(right))
                    }
                    assets.each { asset ->
                        String name = entryName(root.relativize(asset))
                        if (name == MANIFEST_ENTRY) return
                        validateEntryName(name)
                        zip.putNextEntry(new ZipEntry(name))
                        Files.copy(asset, zip)
                        zip.closeEntry()
                    }
                }
            } finally {
                zip.close()
            }
            replaceAtomically(temporary, archive)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    static Map readManifest(Path archive) {
        requireReadableArchive(archive)
        ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)
        try {
            ZipEntry entry = zip.getEntry(MANIFEST_ENTRY)
            if (entry == null || entry.directory) {
                throw new IllegalArgumentException("Missing ${MANIFEST_ENTRY}")
            }
            Map manifest = (Map) new JsonSlurper().parseText(
                    new String(zip.getInputStream(entry).bytes, StandardCharsets.UTF_8))
            manifest = normalizeManifest(manifest)
            validateManifest(manifest)
            manifest
        } finally {
            zip.close()
        }
    }

    static boolean isSnapshot(Path archive) {
        new ZipFile(archive.toFile(), StandardCharsets.UTF_8).withCloseable { zip ->
            def entry = zip.getEntry(MANIFEST_ENTRY)
            if (entry == null || entry.directory) return false
            zip.getInputStream(entry).withCloseable { input ->
                def header = new JsonSlurper().parseText(new String(input.bytes, StandardCharsets.UTF_8))
                header instanceof Map && header.format == FORMAT
            }
        }
    }

    /** Adapt Snapshot2 formatVersion 2 entries to this script's v1 restore model. */
    private static Map normalizeManifest(Map manifest) {
        if (manifest?.format != FORMAT || !manifest.containsKey('formatVersion'))
            return manifest
        if (manifest.formatVersion != 2)
            throw new IllegalArgumentException("Unsupported snapshot formatVersion: ${manifest.formatVersion}")
        manifest.remove('formatVersion')
        manifest.version = VERSION
        manifest.images = ((List<Map>) (manifest.images ?: [])).collect { Map image ->
            Map position = (Map) (image.position ?: [:])
            Map displayState = (Map) (image.displayState ?: [:])
            List minimums = (List) (displayState.channelMinimums ?: [])
            List maximums = (List) (displayState.channelMaximums ?: [])
            List<Map> ranges = []
            int rangeCount = Math.min(minimums.size(), maximums.size())
            for (int channel = 0; channel < rangeCount; channel++)
                ranges.add([min: minimums[channel], max: maximums[channel]])
            Map sourceMetadata = (Map) (image.sourceMetadata ?: [:])
            Map canvas = image.magnification != null ?
                    [magnification: image.magnification, sourceRect: image.sourceRectangle] : null
            [
                    id          : image.id,
                    title       : image.title,
                    storage     : [
                            kind: image.storageKind == 'EMBEDDED' ? 'embedded' :
                                    image.storageKind == 'ABSOLUTE_PATH' ? 'reference' : image.storageKind,
                            path: image.storagePath
                    ],
                    source      : [
                            path        : sourceMetadata.absolutePath,
                            size        : sourceMetadata.fileSize,
                            lastModified: sourceMetadata.modifiedTimeMillis
                    ],
                    dimensions  : image.dimensions,
                    position    : [c: position.channel, z: position.slice, t: position.frame],
                    display     : [
                            composite    : displayState.composite ?: false,
                            mode         : displayState.displayMode ?: 0,
                            activeChannels: displayState.activeChannels ?: [],
                            channelRanges: ranges
                    ],
                    window      : image.windowBounds,
                    canvas      : canvas,
                    unsavedChanges: false,
                    currentRoi : image.roiPath,
                    overlayRois: image.overlayPath ? [image.overlayPath] : []
            ]
        }
        manifest
    }

    static void extract(Path archive, Path destination) {
        requireReadableArchive(archive)
        Path root = destination.toAbsolutePath().normalize()
        Files.createDirectories(root)
        ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)
        try {
            List<? extends ZipEntry> entries = zip.entries().toList()
            entries.each { validateEntryName(it.name) }
            entries.each { entry ->
                Path target = safeTarget(root, entry.name)
                if (entry.directory) {
                    Files.createDirectories(target)
                } else {
                    if (target.parent != null) Files.createDirectories(target.parent)
                    zip.getInputStream(entry).withCloseable { input ->
                        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        } finally {
            zip.close()
        }
    }

    static void writeRemapped(Path source, Path destination, Map remappedManifest) {
        requireReadableArchive(source)
        requireArchivePath(destination)
        validateManifest(remappedManifest)
        if (source.toAbsolutePath().normalize() == destination.toAbsolutePath().normalize()) {
            throw new IllegalArgumentException('Remapped snapshot must use a new file path')
        }
        Path parent = destination.toAbsolutePath().normalize().parent
        if (parent != null) Files.createDirectories(parent)
        Path temporary = temporarySibling(destination)
        ZipFile inputZip = new ZipFile(source.toFile(), StandardCharsets.UTF_8)
        try {
            List<? extends ZipEntry> entries = inputZip.entries().toList()
            entries.each { validateEntryName(it.name) }
            ZipOutputStream outputZip = new ZipOutputStream(Files.newOutputStream(temporary), StandardCharsets.UTF_8)
            try {
                putBytes(outputZip, MANIFEST_ENTRY, manifestBytes(remappedManifest))
                entries.findAll { it.name != MANIFEST_ENTRY }.each { entry ->
                    ZipEntry copy = new ZipEntry(entry.name)
                    copy.time = entry.time
                    outputZip.putNextEntry(copy)
                    if (!entry.directory) {
                        inputZip.getInputStream(entry).withCloseable { input ->
                            byte[] buffer = new byte[8192]
                            for (int count = input.read(buffer); count >= 0; count = input.read(buffer)) {
                                if (count > 0) outputZip.write(buffer, 0, count)
                            }
                        }
                    }
                    outputZip.closeEntry()
                }
            } finally {
                outputZip.close()
            }
            replaceAtomically(temporary, destination)
        } finally {
            inputZip.close()
            Files.deleteIfExists(temporary)
        }
    }

    private static void validateManifest(Map manifest) {
        if (manifest == null) throw new IllegalArgumentException('Snapshot manifest is missing')
        if (manifest.format != FORMAT) throw new IllegalArgumentException("Unsupported snapshot format: ${manifest.format}")
        if ((manifest.version as Integer) != VERSION) {
            throw new IllegalArgumentException("Unsupported snapshot version: ${manifest.version}")
        }
    }

    private static byte[] manifestBytes(Map manifest) {
        String json = JsonOutput.prettyPrint(JsonOutput.toJson(manifest)) + System.lineSeparator()
        json.getBytes(StandardCharsets.UTF_8)
    }

    private static void putBytes(ZipOutputStream zip, String name, byte[] bytes) {
        validateEntryName(name)
        zip.putNextEntry(new ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private static String entryName(Path relativePath) {
        relativePath.toString().replace('\\', '/')
    }

    private static void validateEntryName(String name) {
        if (name == null || name.isEmpty() || name.contains('\\') || name.startsWith('/') ||
                name == '..' || name.startsWith('../') || name.contains('/../') || name.endsWith('/..') ||
                name.contains('\u0000') || name.matches('^[A-Za-z]:.*')) {
            throw new IllegalArgumentException("Unsafe ZIP entry: ${name}")
        }
    }

    private static Path safeTarget(Path root, String name) {
        validateEntryName(name)
        Path target = root.resolve(name).normalize()
        if (!target.startsWith(root)) throw new IllegalArgumentException("Unsafe ZIP entry: ${name}")
        target
    }

    private static void requireReadableArchive(Path archive) {
        requireArchivePath(archive)
        if (!Files.isRegularFile(archive) || !Files.isReadable(archive)) {
            throw new IllegalArgumentException("Snapshot is not readable: ${archive}")
        }
    }

    private static void requireArchivePath(Path archive) {
        if (archive == null) throw new IllegalArgumentException('Snapshot path is missing')
    }

    private static Path temporarySibling(Path destination) {
        Path absolute = destination.toAbsolutePath().normalize()
        absolute.resolveSibling(".${absolute.fileName}.${UUID.randomUUID()}.tmp")
    }

    private static void replaceAtomically(Path source, Path destination) {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

/**
 * Converts live ImageJ objects to manifest-safe maps and persists individual
 * ROIs without changing the source image or its current C/Z/T position.
 */
class SnapshotState {
    static Map captureImage(ImagePlus imp, String storageKind, String storagePath, int index) {
        if (imp == null) throw new IllegalArgumentException('Image is missing')
        if (!(storageKind in ['embedded', 'reference'])) {
            throw new IllegalArgumentException("Unsupported storage kind: ${storageKind}")
        }
        String id = String.format(Locale.ROOT, 'image-%04d', index)
        int currentC = imp.getC()
        int currentZ = imp.getZ()
        int currentT = imp.getT()

        Map display = captureDisplay(imp, currentC, currentZ, currentT)
        ImageWindow window = imp.getWindow()
        ImageCanvas canvas = imp.getCanvas()
        Rectangle windowBounds = window?.getBounds()
        Rectangle sourceRect = canvas?.getSrcRect()
        FileInfo sourceInfo = imp.getOriginalFileInfo()
        Path sourcePath = originalPath(sourceInfo)

        [
                id              : id,
                title           : imp.getTitle(),
                storage         : [kind: storageKind, path: storagePath],
                source          : fileMetadata(sourcePath),
                dimensions      : [
                        width   : imp.getWidth(),
                        height  : imp.getHeight(),
                        channels: imp.getNChannels(),
                        slices  : imp.getNSlices(),
                        frames  : imp.getNFrames(),
                        bitDepth: imp.getBitDepth()
                ],
                position        : [c: currentC, z: currentZ, t: currentT],
                display         : display,
                window          : windowBounds == null ? null : rectangleMap(windowBounds),
                canvas          : canvas == null || sourceRect == null ? null : [
                        magnification: canvas.getMagnification(),
                        sourceRect  : rectangleMap(sourceRect)
                ],
                unsavedChanges  : imp.changes,
                currentRoi      : null,
                overlayRois     : []
        ]
    }

    static void saveRoi(Roi roi, Path path) {
        if (roi == null) throw new IllegalArgumentException('ROI is missing')
        if (path == null) throw new IllegalArgumentException('ROI path is missing')
        if (path.parent != null) Files.createDirectories(path.parent)
        if (!RoiEncoder.save(roi, path.toString())) {
            throw new IOException("Could not save ROI: ${path}")
        }
    }

    static Roi loadRoi(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("ROI is not readable: ${path}")
        }
        new RoiDecoder(path.toString()).getRoi()
    }

    static Map fileMetadata(Path path) {
        if (path == null) return [path: null, size: null, lastModified: null]
        boolean regular = Files.isRegularFile(path)
        [
                path        : path.toAbsolutePath().normalize().toString(),
                size        : regular ? Files.size(path) : null,
                lastModified: regular ? Files.getLastModifiedTime(path).toMillis() : null
        ]
    }

    static Path sourcePath(ImagePlus imp) {
        imp == null ? null : originalPath(imp.getOriginalFileInfo())
    }

    static void saveRoiSet(Roi[] rois, Path path) {
        if (rois == null || rois.length == 0) return
        if (path.parent != null) Files.createDirectories(path.parent)
        ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path), StandardCharsets.UTF_8)
        try {
            rois.eachWithIndex { Roi roi, int index ->
                byte[] bytes = RoiEncoder.saveAsByteArray(roi)
                if (bytes == null) throw new IOException("Could not encode ROI ${index + 1}")
                String name = String.format(Locale.ROOT, 'roi-%04d.roi', index + 1)
                zip.putNextEntry(new ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        } finally {
            zip.close()
        }
    }

    static void restoreImage(ImagePlus imp, Map state, Path assetRoot, boolean showWindow) {
        imp.setTitle(state.title as String)
        Map display = (Map) state.display
        int targetZ = asInt(state.position.z)
        int targetT = asInt(state.position.t)
        if (display?.composite && imp instanceof CompositeImage) {
            CompositeImage composite = (CompositeImage) imp
            composite.setMode(asInt(display.mode))
            List active = (List) display.activeChannels
            if (active) imp.setActiveChannels(active.collect { it ? '1' : '0' }.join(''))
        }
        List<Map> ranges = (List<Map>) (display?.channelRanges ?: [])
        ranges.eachWithIndex { Map range, int channelIndex ->
            if (channelIndex < imp.getNChannels()) {
                imp.setPosition(channelIndex + 1, targetZ, targetT)
                imp.setDisplayRange(asDouble(range.min), asDouble(range.max))
            }
        }
        imp.setPosition(asInt(state.position.c), targetZ, targetT)

        if (state.currentRoi) {
            Path roiPath = resolveAsset(assetRoot, state.currentRoi as String)
            if (Files.isRegularFile(roiPath)) imp.setRoi(loadRoi(roiPath))
        }
        List overlayPaths = (List) (state.overlayRois ?: [])
        if (!overlayPaths.isEmpty()) {
            Overlay overlay = new Overlay()
            overlayPaths.each { relative ->
                Path roiPath = resolveAsset(assetRoot, relative as String)
                if (Files.isRegularFile(roiPath)) {
                    if (roiPath.toString().toLowerCase(Locale.ROOT).endsWith('.zip')) {
                        new ZipFile(roiPath.toFile(), StandardCharsets.UTF_8).withCloseable { zip ->
                            zip.entries().each { entry ->
                                if (!entry.directory && entry.name.toLowerCase(Locale.ROOT).endsWith('.roi')) {
                                    zip.getInputStream(entry).withCloseable { input ->
                                        overlay.add(new RoiDecoder(input.bytes, entry.name).getRoi())
                                    }
                                }
                            }
                        }
                    } else {
                        overlay.add(loadRoi(roiPath))
                    }
                }
            }
            imp.setOverlay(overlay)
        }
        imp.changes = false

        if (showWindow) {
            imp.show()
            if (state.window) imp.getWindow()?.setBounds(clampBounds((Map) state.window))
            if (state.canvas && imp.getCanvas() != null) {
                Map canvasState = (Map) state.canvas
                imp.getCanvas().setMagnification(asDouble(canvasState.magnification))
                if (canvasState.sourceRect)
                    imp.getCanvas().setSourceRect(mapRectangle((Map) canvasState.sourceRect))
            }
            imp.updateAndDraw()
        }
    }

    static Rectangle clampBounds(Map bounds) {
        Rectangle requested = mapRectangle(bounds)
        if (GraphicsEnvironment.isHeadless()) return requested
        List<Rectangle> screens = GraphicsEnvironment.localGraphicsEnvironment.screenDevices.collect {
            it.defaultConfiguration.bounds
        }
        if (screens.isEmpty()) return requested
        Rectangle union = new Rectangle(screens[0])
        screens.drop(1).each { union.add(it) }
        int width = Math.max(100, Math.min(requested.width, union.width))
        int height = Math.max(100, Math.min(requested.height, union.height))
        int x = Math.max(union.x, Math.min(requested.x, union.x + union.width - width))
        int y = Math.max(union.y, Math.min(requested.y, union.y + union.height - height))
        new Rectangle(x, y, width, height)
    }

    private static Map captureDisplay(ImagePlus imp, int currentC, int currentZ, int currentT) {
        List<Map> channelRanges = []
        try {
            int channelCount = Math.max(1, imp.getNChannels())
            (1..channelCount).each { channel ->
                imp.setPosition(channel, currentZ, currentT)
                channelRanges.add([min: imp.getDisplayRangeMin(), max: imp.getDisplayRangeMax()])
            }
        } finally {
            imp.setPosition(currentC, currentZ, currentT)
        }

        if (imp instanceof CompositeImage) {
            CompositeImage composite = (CompositeImage) imp
            return [
                    composite    : true,
                    mode         : composite.getMode(),
                    activeChannels: composite.getActiveChannels().collect { it }.take(imp.getNChannels()),
                    channelRanges: channelRanges
            ]
        }
        [composite: false, mode: null, activeChannels: [], channelRanges: channelRanges]
    }

    private static Path originalPath(FileInfo info) {
        if (info == null || !info.fileName) return null
        File file = info.directory ? new File(info.directory, info.fileName) : new File(info.fileName)
        file.toPath().toAbsolutePath().normalize()
    }

    private static Map rectangleMap(Rectangle rectangle) {
        [x: rectangle.x, y: rectangle.y, width: rectangle.width, height: rectangle.height]
    }

    private static Rectangle mapRectangle(Map value) {
        new Rectangle(asInt(value.x), asInt(value.y), asInt(value.width), asInt(value.height))
    }

    private static Path resolveAsset(Path root, String relative) {
        if (root == null) throw new IllegalArgumentException('Asset root is missing')
        if (relative == null || relative.contains('\\') || relative.startsWith('/') || relative.contains('..')) {
            throw new IllegalArgumentException("Unsafe snapshot asset path: ${relative}")
        }
        Path normalizedRoot = root.toAbsolutePath().normalize()
        Path resolved = normalizedRoot.resolve(relative).normalize()
        if (!resolved.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Unsafe snapshot asset path: ${relative}")
        }
        resolved
    }

    private static int asInt(Object value) {
        ((Number) value).intValue()
    }

    private static double asDouble(Object value) {
        ((Number) value).doubleValue()
    }
}

/**
 * UI-independent save and restore workflows. Dialog code supplies explicit
 * per-image actions, while this service performs validation and IO.
 */
class SnapshotService {
    static String recommendedAction(ImagePlus imp, String requestedMode) {
        if (requestedMode == 'embedded') return 'embedded'
        if (requestedMode != 'reference') {
            throw new IllegalArgumentException("Unsupported save mode: ${requestedMode}")
        }
        Path source = SnapshotState.sourcePath(imp)
        source != null && Files.isRegularFile(source) && Files.isReadable(source) && !imp.changes ?
                'reference' : 'needs-decision'
    }

    static Map save(Path archive, String requestedMode, List<Map> items, RoiManager roiManager) {
        if (!(requestedMode in ['embedded', 'reference'])) {
            throw new IllegalArgumentException("Unsupported save mode: ${requestedMode}")
        }
        if (items == null || items.isEmpty()) throw new IllegalArgumentException('No images were selected')
        Path staging = Files.createTempDirectory('fiji-snapshot-save-')
        try {
            List<Map> imageStates = []
            Map<ImagePlus, String> imageIds = new IdentityHashMap<>()
            items.eachWithIndex { Map item, int itemIndex ->
                String action = item.action as String
                if (action == 'skip') return
                if (!(action in ['embedded', 'reference'])) {
                    throw new IllegalArgumentException("Unsupported image action: ${action}")
                }
                ImagePlus imp = (ImagePlus) item.imp
                if (imp == null) throw new IllegalArgumentException("Image ${itemIndex + 1} is missing")
                String id = String.format(Locale.ROOT, 'image-%04d', itemIndex + 1)
                String storagePath
                Path referencePath = null
                if (action == 'embedded') {
                    storagePath = "images/${id}.tif"
                } else {
                    referencePath = item.referencePath ? new File(item.referencePath as String).toPath() : SnapshotState.sourcePath(imp)
                    if (referencePath == null || !Files.isRegularFile(referencePath) || !Files.isReadable(referencePath)) {
                        throw new IllegalArgumentException("Referenced TIFF is not readable: ${referencePath}")
                    }
                    storagePath = referencePath.toAbsolutePath().normalize().toString()
                }

                Map state = SnapshotState.captureImage(imp, action, storagePath, itemIndex + 1)
                if (referencePath != null) state.source = SnapshotState.fileMetadata(referencePath)
                if (action == 'embedded') {
                    saveTiffWithoutChangingSource(imp, staging.resolve(storagePath))
                }

                Roi current = imp.getRoi()
                if (current != null) {
                    String roiRelative = "rois/${id}-current.roi"
                    SnapshotState.saveRoi((Roi) current.clone(), staging.resolve(roiRelative))
                    state.currentRoi = roiRelative
                }
                Roi[] overlayRois = imp.getOverlay()?.toArray()
                if (overlayRois != null) {
                    overlayRois.eachWithIndex { Roi roi, int roiIndex ->
                        String relative = String.format(Locale.ROOT,
                                'overlays/%s/roi-%04d.roi', id, roiIndex + 1)
                        SnapshotState.saveRoi((Roi) roi.clone(), staging.resolve(relative))
                        state.overlayRois.add(relative)
                    }
                }
                imageStates.add(state)
                imageIds.put(imp, id)
            }
            if (imageStates.isEmpty()) throw new IllegalArgumentException('All images were skipped')

            String roiManagerPath = null
            if (roiManager != null && roiManager.getCount() > 0) {
                roiManagerPath = 'rois/roi-manager.zip'
                SnapshotState.saveRoiSet(roiManager.getRoisAsArray(), staging.resolve(roiManagerPath))
            }
            ImagePlus active = WindowManager.getCurrentImage()
            Map manifest = [
                    format              : SnapshotArchive.FORMAT,
                    version             : SnapshotArchive.VERSION,
                    createdAt           : Instant.now().toString(),
                    requestedStorageMode: requestedMode,
                    images              : imageStates,
                    roiManager          : roiManagerPath,
                    activeImageId       : imageIds.get(active)
            ]
            SnapshotArchive.write(archive, manifest, staging)
            manifest
        } finally {
            deleteTree(staging)
        }
    }

    static Map preflight(Path archive) {
        Map manifest = SnapshotArchive.readManifest(archive)
        List<Map> issues = []
        ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)
        try {
            ((List<Map>) manifest.images).each { Map state ->
                Map storage = (Map) state.storage
                if (storage.kind == 'embedded') {
                    if (zip.getEntry(storage.path as String) == null) {
                        issues.add(issue(state, 'missing', "Embedded TIFF is missing: ${storage.path}", []))
                    }
                } else if (storage.kind == 'reference') {
                    Path path = pathOrNull(storage.path)
                    if (path == null || !Files.isRegularFile(path) || !Files.isReadable(path)) {
                        issues.add(issue(state, 'missing', "Referenced TIFF is missing: ${storage.path}", []))
                    } else {
                        List<Map> metadataDifferences = lightweightMetadataDifferences(state, path)
                        if (!metadataDifferences.isEmpty()) {
                            issues.add(issue(state, 'changed', 'Referenced TIFF file metadata changed',
                                    metadataDifferences))
                        }
                    }
                } else {
                    issues.add(issue(state, 'invalid', "Unsupported storage kind: ${storage.kind}", []))
                }
            }
        } finally {
            zip.close()
        }
        [manifest: manifest, issues: issues]
    }

    static Map validateReplacement(Map state, Path path) {
        if (path == null || !Files.isRegularFile(path) || !Files.isReadable(path)) {
            return [valid: false, differences: [[field: 'path', expected: 'readable TIFF', actual: path?.toString()]]]
        }
        ImagePlus imp = IJ.openImage(path.toString())
        if (imp == null) {
            return [valid: false, differences: [[field: 'format', expected: 'readable TIFF', actual: 'unreadable']]]
        }
        try {
            Map actual = imageDimensions(imp)
            List<Map> differences = dimensionDifferences(state, actual)
            [valid: differences.isEmpty(), differences: differences, actual: actual]
        } finally {
            imp.changes = false
            imp.flush()
        }
    }

    static Map remapManifest(Map manifest, Map<String, String> replacements) {
        Map copy = (Map) new JsonSlurper().parseText(JsonOutput.toJson(manifest))
        ((List<Map>) copy.images).each { Map state ->
            String replacement = replacements[state.id as String]
            if (replacement) {
                Path path = new File(replacement).toPath().toAbsolutePath().normalize()
                state.storage.path = path.toString()
                state.source = SnapshotState.fileMetadata(path)
            }
        }
        copy
    }

    static Map restore(Path archive, Map<String, String> replacements, Set<String> skippedIds,
                       boolean showWindows, Closure sourceLogger = null) {
        Map manifest = SnapshotArchive.readManifest(archive)
        if (replacements) manifest = remapManifest(manifest, replacements)
        Set<String> skipped = skippedIds ?: [] as Set<String>
        Path extracted = Files.createTempDirectory('fiji-snapshot-restore-')
        List<ImagePlus> images = []
        List<Map> errors = []
        Map<String, ImagePlus> restoredById = [:]
        try {
            SnapshotArchive.extract(archive, extracted)
            ((List<Map>) manifest.images).each { Map state ->
                String id = state.id as String
                if (skipped.contains(id)) return
                ImagePlus imp = null
                try {
                    Path imagePath
                    if (state.storage.kind == 'embedded') {
                        imagePath = resolveExtracted(extracted, state.storage.path as String)
                        sourceLogger?.call("[打开] ${state.title}\nstorage=embedded\n来源：快照内嵌 TIFF\n" +
                                "快照包：${archive.toAbsolutePath().normalize()}\n包内路径：${state.storage.path}")
                    } else {
                        imagePath = new File(state.storage.path as String).toPath()
                        sourceLogger?.call("[打开] ${state.title}\nstorage=reference\n来源：外部 TIFF\n" +
                                "路径：${imagePath.toAbsolutePath().normalize()}")
                    }
                    if (!Files.isRegularFile(imagePath)) throw new IOException("TIFF is missing: ${imagePath}")
                    imp = IJ.openImage(imagePath.toString())
                    if (imp == null) throw new IOException("ImageJ could not open: ${imagePath}")
                    List<Map> differences = dimensionDifferences(state, imageDimensions(imp))
                    if (!differences.isEmpty()) throw new IOException("TIFF dimensions differ: ${differences}")
                    if (state.storage.kind == 'embedded') imp.setFileInfo(null)
                    SnapshotState.restoreImage(imp, state, extracted, showWindows)
                    images.add(imp)
                    restoredById[id] = imp
                } catch (Throwable error) {
                    if (imp != null && !images.contains(imp)) {
                        imp.changes = false
                        imp.flush()
                    }
                    sourceLogger?.call("[失败] ${state.title}\n原因：${error.message ?: error.class.simpleName}")
                    errors.add([id: id, title: state.title, message: error.message ?: error.class.simpleName])
                }
            }
            if (showWindows && manifest.roiManager) {
                Path roiManagerPath = resolveExtracted(extracted, manifest.roiManager as String)
                if (Files.isRegularFile(roiManagerPath)) RoiManager.getRoiManager().open(roiManagerPath.toString())
            }
            if (showWindows && manifest.activeImageId && restoredById[manifest.activeImageId as String] != null) {
                restoredById[manifest.activeImageId as String].getWindow()?.toFront()
            }
            [images: images, errors: errors]
        } finally {
            deleteTree(extracted)
        }
    }

    private static void saveTiffWithoutChangingSource(ImagePlus imp, Path path) {
        if (path.parent != null) Files.createDirectories(path.parent)
        FileInfo originalInfo = imp.getOriginalFileInfo()
        boolean originalChanges = imp.changes
        String originalTitle = imp.getTitle()
        int originalC = imp.getC(), originalZ = imp.getZ(), originalT = imp.getT()
        try {
            FileSaver saver = new FileSaver(imp)
            boolean saved = imp.getStackSize() > 1 ? saver.saveAsTiffStack(path.toString()) : saver.saveAsTiff(path.toString())
            if (!saved) throw new IOException("Could not save embedded TIFF: ${path}")
        } finally {
            imp.setFileInfo(originalInfo)
            imp.setTitle(originalTitle)
            imp.setPosition(originalC, originalZ, originalT)
            imp.changes = originalChanges
        }
    }

    private static Map issue(Map state, String status, String message, List differences) {
        [
                id          : state.id,
                title       : state.title,
                originalPath: state.storage.path,
                storageKind : state.storage.kind,
                status      : status,
                message     : message,
                differences : differences
        ]
    }

    private static List<Map> lightweightMetadataDifferences(Map state, Path path) {
        Map source = (Map) state.source
        if (source == null || source.size == null) return []
        long expectedSize = ((Number) source.size).longValue()
        long actualSize = Files.size(path)
        expectedSize == actualSize ? [] : [[field: 'size', expected: expectedSize, actual: actualSize]]
    }

    private static Map imageDimensions(ImagePlus imp) {
        [
                width   : imp.getWidth(), height: imp.getHeight(), channels: imp.getNChannels(),
                slices  : imp.getNSlices(), frames: imp.getNFrames(), bitDepth: imp.getBitDepth()
        ]
    }

    private static List<Map> dimensionDifferences(Map state, Map actual) {
        Map expected = (Map) state.dimensions
        List<Map> differences = []
        ['width', 'height', 'channels', 'slices', 'frames', 'bitDepth'].each { field ->
            int expectedValue = ((Number) expected[field]).intValue()
            int actualValue = ((Number) actual[field]).intValue()
            if (expectedValue != actualValue) {
                differences.add([field: field, expected: expectedValue, actual: actualValue])
            }
        }
        differences
    }

    private static Path pathOrNull(Object value) {
        try {
            value ? new File(value as String).toPath() : null
        } catch (Exception ignored) {
            null
        }
    }

    private static Path resolveExtracted(Path root, String relative) {
        if (relative == null || relative.contains('\\') || relative.startsWith('/') || relative.contains('..')) {
            throw new IllegalArgumentException("Unsafe snapshot path: ${relative}")
        }
        Path normalizedRoot = root.toAbsolutePath().normalize()
        Path resolved = normalizedRoot.resolve(relative).normalize()
        if (!resolved.startsWith(normalizedRoot)) throw new IllegalArgumentException("Unsafe snapshot path: ${relative}")
        resolved
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return
        Files.walk(root).withCloseable { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}

/**
 * Fiji menu entry and all user interaction. The service layer receives only
 * explicit choices, so canceling a dialog never changes the live session.
 */
class SnapshotApp {
    private static final String[] SAVE_MODES = [
            '嵌入 TIFF（可迁移）',
            '仅记录 TIFF 路径（本机快速）'
    ] as String[]

    static void run() {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException('Snapshot requires the Fiji graphical interface')
        }
        GenericDialog dialog = new GenericDialog('Snapshot')
        dialog.addChoice('操作', ['保存快照', '恢复快照'] as String[], '保存快照')
        dialog.addMessage('保存或恢复当前全部 ImageJ 图像窗口、C/Z/T、ROI 和显示状态。')
        dialog.showDialog()
        if (dialog.wasCanceled()) return
        if (dialog.getNextChoiceIndex() == 0) saveSnapshot() else restoreSnapshot()
    }

    static Path ensureSnapshotExtension(Path path) {
        String fileName = path.fileName.toString()
        String lowerName = fileName.toLowerCase(Locale.ROOT)
        if (lowerName.endsWith('.zip') || lowerName.endsWith('.ijsnapshot')) return path
        path.resolveSibling(fileName + '.zip')
    }

    static Path defaultRemappedPath(Path source) {
        String fileName = source.fileName.toString()
        String lowerName = fileName.toLowerCase(Locale.ROOT)
        String base = fileName
        if (lowerName.endsWith('.zip')) {
            base = fileName.substring(0, fileName.length() - '.zip'.length())
        } else if (lowerName.endsWith('.ijsnapshot')) {
            base = fileName.substring(0, fileName.length() - '.ijsnapshot'.length())
        }
        source.resolveSibling(base + '_remapped.zip')
    }

    static String cleanPastedPath(String value) {
        String cleaned = value == null ? '' : value.trim()
        if (cleaned.length() >= 2 && ((cleaned.startsWith('"') && cleaned.endsWith('"')) ||
                (cleaned.startsWith("'") && cleaned.endsWith("'")))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim()
        }
        cleaned
    }

    private static void saveSnapshot() {
        int[] ids = WindowManager.getIDList()
        if (ids == null || ids.length == 0) {
            IJ.showMessage('Snapshot', '当前没有打开的图像窗口。')
            return
        }
        List<ImagePlus> images = ids.collect { WindowManager.getImage(it) }.findAll { it != null }

        GenericDialog options = new GenericDialog('保存 Snapshot')
        options.addChoice('图像保存方式', SAVE_MODES, SAVE_MODES[0])
        options.addMessage('嵌入模式可迁移到其他电脑；路径模式不复制原 TIFF。')
        options.showDialog()
        if (options.wasCanceled()) return
        String requestedMode = options.getNextChoiceIndex() == 0 ? 'embedded' : 'reference'

        List<Map> items = buildSaveItems(images, requestedMode)
        if (items == null) return
        if (!items.any { it.action != 'skip' }) {
            IJ.showMessage('Snapshot', '所有图像都被跳过，没有生成快照。')
            return
        }

        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern('yyyyMMdd-HHmmss'))
        Path destination = chooseSnapshotSavePath('保存 Snapshot', null, "Snapshot-${stamp}.zip")
        if (destination == null || !confirmOverwrite(destination)) return
        Map manifest = SnapshotService.save(destination, requestedMode, items, RoiManager.getInstance2())
        IJ.showMessage('Snapshot', "快照保存完成。\n\n图像数：${manifest.images.size()}\n${destination}")
    }

    private static List<Map> buildSaveItems(List<ImagePlus> images, String requestedMode) {
        List<Map> items = []
        for (ImagePlus imp : images) {
            String recommendation = SnapshotService.recommendedAction(imp, requestedMode)
            if (recommendation == 'embedded') {
                items.add([imp: imp, action: 'embedded'])
                continue
            }
            Path source = SnapshotState.sourcePath(imp)
            if (recommendation == 'reference') {
                items.add([imp: imp, action: 'reference', referencePath: source.toString()])
                continue
            }

            boolean readableSource = source != null && Files.isRegularFile(source) && Files.isReadable(source)
            List<String> choices = ['嵌入当前图像（推荐）']
            if (readableSource) choices.add('仅引用原 TIFF（不保留未保存的像素修改）')
            choices.add('跳过这个窗口')
            String reason = readableSource ?
                    '该图像包含尚未保存的像素修改。' :
                    '该图像没有可读取的原始 TIFF 路径。'
            GenericDialog decision = new GenericDialog('Snapshot：需要选择')
            decision.addMessage("窗口：${imp.getTitle()}\n${reason}")
            decision.addChoice('处理方式', choices as String[], choices[0])
            decision.showDialog()
            if (decision.wasCanceled()) return null
            int selected = decision.getNextChoiceIndex()
            if (selected == 0) {
                items.add([imp: imp, action: 'embedded'])
            } else if (readableSource && selected == 1) {
                items.add([imp: imp, action: 'reference', referencePath: source.toString()])
            } else {
                items.add([imp: imp, action: 'skip'])
            }
        }
        items
    }

    private static void restoreSnapshot() {
        OpenDialog openDialog = new OpenDialog('打开 Snapshot', null)
        String selected = openDialog.getPath()
        if (!selected) return
        restoreSnapshotArchive(new File(selected).toPath(), true)
    }

    static void restoreSnapshotArchive(Path archive, boolean showSummary) {
        Path source = archive.toAbsolutePath().normalize()
        IJ.log('')
        IJ.log('=== Snapshot 恢复开始 ===')
        IJ.log("快照包：${source}")

        Map preflight = SnapshotService.preflight(source)
        Map manifest = (Map) preflight.manifest
        List<Map> issues = (List<Map>) preflight.issues
        Set<String> skipped = issues.findAll { it.storageKind != 'reference' }.collect { it.id as String } as Set<String>
        List<Map> repairable = issues.findAll { it.storageKind == 'reference' }
        Map repair = repairable.isEmpty() ? [cancelled: false, replacements: [:], skipped: [] as Set] :
                repairReferencedPaths(repairable, manifest)
        if (repair.cancelled) {
            IJ.log('=== Snapshot 恢复已取消 ===')
            return
        }
        skipped.addAll((Set<String>) repair.skipped)

        Path archiveToRestore = source
        Map<String, String> replacements = (Map<String, String>) repair.replacements
        if (!replacements.isEmpty()) {
            Path suggested = defaultRemappedPath(source)
            Path remappedPath = chooseSnapshotSavePath('另存修复后的 Snapshot', suggested.parent,
                    suggested.fileName.toString())
            if (remappedPath == null || !confirmOverwrite(remappedPath)) return
            if (remappedPath.toAbsolutePath().normalize() == source) {
                IJ.showMessage('Snapshot', '修复后的快照必须使用新的文件名，原快照不会被覆盖。')
                return
            }
            Map remappedManifest = SnapshotService.remapManifest(manifest, replacements)
            SnapshotArchive.writeRemapped(source, remappedPath, remappedManifest)
            archiveToRestore = remappedPath
            IJ.log("修复后的快照包：${archiveToRestore}")
        }

        Map result = SnapshotService.restore(archiveToRestore, [:], skipped, true,
                { String message -> IJ.log(message) })
        StringBuilder summary = new StringBuilder()
        summary.append("已恢复图像：${result.images.size()}")
        if (!skipped.isEmpty()) summary.append("\n已跳过图像：${skipped.size()}")
        if (!result.errors.isEmpty()) {
            summary.append("\n\n恢复失败：${result.errors.size()}")
            result.errors.each { summary.append("\n- ${it.title}: ${it.message}") }
        }
        if (archiveToRestore != source) summary.append("\n\n修复后的快照：\n${archiveToRestore}")
        IJ.log("=== Snapshot 恢复完成：成功 ${result.images.size()}，跳过 ${skipped.size()}，" +
                "失败 ${result.errors.size()} ===")
        if (showSummary) IJ.showMessage('Snapshot：恢复完成', summary.toString())
        else IJ.showStatus("Snapshot: restored ${result.images.size()} images, failed ${result.errors.size()}")
    }

    private static Map repairReferencedPaths(List<Map> issues, Map manifest) {
        Map<String, Map> states = ((List<Map>) manifest.images).collectEntries { [(it.id as String): it] }
        Map<String, String> values = issues.collectEntries {
            [(it.id as String): (it.originalPath ?: '') as String]
        }
        Map<String, String> replacements = [:]
        Set<String> skipped = [] as Set<String>
        List<List<Map>> pages = issues.collate(6)

        for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
            List<Map> page = pages[pageIndex]
            boolean pageValid = false
            while (!pageValid) {
                GenericDialog dialog = new GenericDialog(
                        "修复图像路径 (${pageIndex + 1}/${pages.size()})")
                dialog.addMessage('可以直接粘贴路径或点击“Browse”选择 TIFF；清空路径表示跳过该图像。')
                page.each { issue ->
                    String status = issue.status == 'mismatch' ? '特征不符' : '缺失'
                    dialog.addFileField("${shortLabel(issue.title as String)} [${status}]",
                            values[issue.id as String] ?: '', 58)
                }
                dialog.showDialog()
                if (dialog.wasCanceled()) return [cancelled: true]

                List<String> validationMessages = []
                page.each { issue ->
                    String id = issue.id as String
                    String cleaned = cleanPastedPath(dialog.getNextString())
                    values[id] = cleaned
                    if (!cleaned) {
                        skipped.add(id)
                        replacements.remove(id)
                        return
                    }
                    Path replacement
                    try {
                        replacement = new File(cleaned).toPath().toAbsolutePath().normalize()
                    } catch (Exception error) {
                        validationMessages.add("${issue.title}: 路径格式无效")
                        return
                    }
                    Map validation = SnapshotService.validateReplacement(states[id], replacement)
                    if (!validation.valid) {
                        String differences = validation.differences.collect {
                            "${it.field}: ${it.expected} -> ${it.actual}"
                        }.join(', ')
                        validationMessages.add("${issue.title}: ${differences}")
                    } else {
                        skipped.remove(id)
                        replacements[id] = replacement.toString()
                    }
                }
                pageValid = validationMessages.isEmpty()
                if (!pageValid) {
                    IJ.showMessage('Snapshot：路径校验失败', validationMessages.join('\n'))
                }
            }
        }
        [cancelled: false, replacements: replacements, skipped: skipped]
    }

    private static Path chooseSnapshotSavePath(String title, Path defaultDirectory, String defaultFileName) {
        String name = defaultFileName
        String lowerName = name.toLowerCase(Locale.ROOT)
        if (lowerName.endsWith('.zip')) {
            name = name.substring(0, name.length() - '.zip'.length())
        } else if (lowerName.endsWith('.ijsnapshot')) {
            name = name.substring(0, name.length() - '.ijsnapshot'.length())
        }
        SaveDialog dialog = defaultDirectory == null ?
                new SaveDialog(title, name, '.zip') :
                new SaveDialog(title, defaultDirectory.toString() + File.separator, name, '.zip')
        if (!dialog.getFileName()) return null
        ensureSnapshotExtension(new File(dialog.getDirectory(), dialog.getFileName()).toPath()
                .toAbsolutePath().normalize())
    }

    private static boolean confirmOverwrite(Path path) {
        if (!Files.exists(path)) return true
        YesNoCancelDialog confirmation = new YesNoCancelDialog(IJ.getInstance(), 'Snapshot：文件已存在',
                "文件已经存在：\n${path}\n\n是否覆盖？")
        confirmation.yesPressed()
    }

    private static String shortLabel(String title) {
        String oneLine = (title ?: '未命名图像').replaceAll('[\\r\\n]+', ' ')
        oneLine.length() <= 28 ? oneLine : oneLine.substring(0, 25) + '...'
    }
}

if (!Boolean.getBoolean('snapshot.testMode')) {
    try {
        if (binding.hasVariable('snapshotDropPath')) {
            Path archive = new File(snapshotDropPath as String).toPath()
            if (!SnapshotArchive.isSnapshot(archive)) return false
            SnapshotApp.restoreSnapshotArchive(archive, false)
            return true
        }
        SnapshotApp.run()
    } catch (Throwable error) {
        IJ.handleException(error)
        if (binding.hasVariable('snapshotDropPath')) return true
    }
}
