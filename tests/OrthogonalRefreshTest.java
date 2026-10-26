package ij.plugin;

import ij.*;
import ij.gui.ImageCanvas;
import ij.process.FloatProcessor;
import java.awt.*;
import java.awt.event.*;
import java.lang.management.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Desktop benchmark plus a final-position regression while a refresh is busy. */
public class OrthogonalRefreshTest {
    static class Probe extends Orthogonal_Views {
        volatile int frames;
        volatile long renderNanos;
        volatile CountDownLatch entered, release;

        @Override void updateViews(Point point, ImageStack stack) {
            long start = System.nanoTime();
            super.updateViews(point, stack);
            renderNanos += System.nanoTime() - start;
            frames++;
            CountDownLatch gate = entered;
            if (gate != null) {
                entered = null;
                gate.countDown();
                try {
                    if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("Refresh gate timed out");
                } catch (InterruptedException error) { throw new AssertionError(error); }
            }
        }
    }

    static final ImagePlus[] images = new ImagePlus[3];
    static final Probe[] views = new Probe[3];

    public static void main(String[] args) {
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(30000); } catch (InterruptedException ignored) { }
            System.err.println("FAIL: refresh test timed out");
            System.exit(2);
        });
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            new ImageJ(ImageJ.NO_SHOW);
            for (int i = 0; i < 3; i++) {
                ImageStack stack = new ImageStack(512, 512);
                for (int z = 0; z < 32; z++) {
                    float[] pixels = new float[512 * 512];
                    for (int p = 0; p < pixels.length; p++) pixels[p] = z * 1000000 + p;
                    stack.addSlice(new FloatProcessor(512, 512, pixels));
                }
                images[i] = new ImagePlus("refresh-" + i, stack);
                images[i].getCalibration().pixelDepth = 2;
                images[i].setSlice(16);
                images[i].show();
                WindowManager.setTempCurrentImage(images[i]);
                views[i] = new Probe();
                views[i].run("");
                WindowManager.setTempCurrentImage(null);
                await(views[i], 256, 256);
            }
            EventQueue.invokeAndWait(() -> {
                Object controller = IJ.runPlugIn("Sync_Windows", "");
                java.awt.List list = findList((Container)controller);
                for (int n = 0; n < list.getItemCount(); n++)
                    for (ImagePlus image : images)
                        if (list.getItem(n).equals(image.getTitle())) list.select(n);
                ((ItemListener)controller).itemStateChanged(new ItemEvent(list,
                    ItemEvent.ITEM_STATE_CHANGED, Integer.valueOf(0), ItemEvent.SELECTED));
            });
            Thread.sleep(200);
            if (args.length > 0 && args[0].equals("race")) {
                CountDownLatch entered = new CountDownLatch(1);
                views[1].release = new CountDownLatch(1);
                views[1].entered = entered;
                EventQueue.invokeAndWait(() -> drag(100, 120, true, false));
                if (!entered.await(3, TimeUnit.SECONDS)) throw new AssertionError("Refresh did not start");
                EventQueue.invokeAndWait(() -> drag(200, 220, false, true));
                // Drain source-image notifications before allowing the busy render to finish.
                EventQueue.invokeAndWait(() -> { });
                views[1].release.countDown();
                for (Probe view : views) await(view, 200, 220);
                System.out.println("PASS: final coordinates rendered after an update arrived during a busy refresh");
            } else {
                benchmark("XY", false);
                benchmark("Z-only", true);
            }
            System.exit(0);
        } catch (Throwable error) {
            error.printStackTrace();
            System.exit(1);
        }
    }

    static void benchmark(String label, boolean zOnly) throws Exception {
        com.sun.management.OperatingSystemMXBean cpu =
            (com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
        long beforeCpu = cpu.getProcessCpuTime(), beforeRender = 0;
        int beforeFrames = 0;
        for (Probe view : views) { beforeFrames += view.frames; beforeRender += view.renderNanos; }
        long start = System.nanoTime();
        for (int n = 0; n < 240; n++) {
            final int event = n;
            EventQueue.invokeAndWait(() -> {
                if (zOnly) {
                    ImagePlus image = views[0].getXZImage();
                    mouse(image, event == 0 ? MouseEvent.MOUSE_PRESSED : MouseEvent.MOUSE_DRAGGED,
                        200, 2 * (event % 30));
                    if (event == 239) mouse(image, MouseEvent.MOUSE_RELEASED, 200, 58);
                } else drag(50 + event, 70 + event, event == 0, event == 239);
            });
            long remaining = start + (n + 1) * 8000000L - System.nanoTime();
            if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining);
        }
        for (Probe view : views) {
            await(view, zOnly ? 200 : 289, 309);
            if (zOnly && view.getCrossLoc()[2] != 29)
                throw new AssertionError("Last Z position was not synchronized");
        }
        Thread.sleep(100);
        long afterCpu = cpu.getProcessCpuTime(), afterRender = 0;
        int afterFrames = 0;
        for (Probe view : views) { afterFrames += view.frames; afterRender += view.renderNanos; }
        System.out.printf("%s: events=240 wall=%.0fms CPU=%.0fms refreshes=%d section-render=%.0fms%n",
            label, (System.nanoTime() - start)/1e6, (afterCpu-beforeCpu)/1e6,
            afterFrames-beforeFrames, (afterRender-beforeRender)/1e6);
    }

    static void drag(int x, int y, boolean press, boolean release) {
        mouse(images[0], press ? MouseEvent.MOUSE_PRESSED : MouseEvent.MOUSE_DRAGGED, x, y);
        if (release) mouse(images[0], MouseEvent.MOUSE_RELEASED, x, y);
    }

    static void mouse(ImagePlus image, int id, int x, int y) {
        ImageCanvas canvas = image.getCanvas();
        if (id == MouseEvent.MOUSE_PRESSED)
            canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(),
                0, canvas.screenX(x), canvas.screenY(y), 0, false, MouseEvent.NOBUTTON));
        canvas.dispatchEvent(new MouseEvent(canvas, id, System.currentTimeMillis(),
            id == MouseEvent.MOUSE_RELEASED ? 0 : InputEvent.BUTTON1_DOWN_MASK,
            canvas.screenX(x), canvas.screenY(y), 1, false,
            id == MouseEvent.MOUSE_DRAGGED ? MouseEvent.NOBUTTON : MouseEvent.BUTTON1));
    }

    static void await(Probe view, int x, int y) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!view.getXZImage().getTitle().equals("XZ " + y)
            || !view.getYZImage().getTitle().equals("YZ " + x)
            || view.getXZImage().getProcessor().getPixelValue(0, 0) != y * 512
            || view.getYZImage().getProcessor().getPixelValue(0, 0) != x) {
            if (System.nanoTime() > deadline) throw new AssertionError("Last position was not rendered: "
                + view.getXZImage().getTitle() + ", " + view.getYZImage().getTitle());
            Thread.sleep(10);
        }
    }

    static java.awt.List findList(Container parent) {
        for (Component child : parent.getComponents()) {
            if (child instanceof java.awt.List) return (java.awt.List)child;
            if (child instanceof Container) {
                java.awt.List found = findList((Container)child);
                if (found != null) return found;
            }
        }
        return null;
    }
}
