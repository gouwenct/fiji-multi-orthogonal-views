import ij.ImageJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Menus;
import ij.WindowManager;
import ij.plugin.Orthogonal_Views;
import ij.process.ByteProcessor;
import java.awt.EventQueue;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Arrays;

/** Run with the candidate ij JAR on the classpath; requires a desktop. */
public class CloseAllOrthogonalTest {
    public static void main(String[] args) {
        Thread timeout = new Thread(() -> {
            try { Thread.sleep(15000); } catch (InterruptedException ignored) { }
            System.err.println("FAIL: Close All timed out");
            System.exit(2);
        });
        timeout.setDaemon(true);
        timeout.start();
        try {
            ImageJ imageJ = new ImageJ(ImageJ.NO_SHOW);
            for (int i = 0; i < 2; i++) {
                ImageStack stack = new ImageStack(32, 24);
                for (int z = 0; z < 8; z++)
                    stack.addSlice(new ByteProcessor(32, 24));
                ImagePlus source = new ImagePlus("close-all-" + i, stack);
                source.show();
                WindowManager.setCurrentWindow(source.getWindow());
                new Orthogonal_Views().run("");
                Orthogonal_Views views = Orthogonal_Views.getInstance();
                long deadline = System.currentTimeMillis() + 5000;
                while (views.getXZImage().getOverlay() == null || views.getYZImage().getOverlay() == null) {
                    if (System.currentTimeMillis() > deadline)
                        throw new AssertionError("Orthogonal Views did not initialize");
                    Thread.sleep(20);
                }
            }
            Thread.sleep(200);
            int before = WindowManager.getImageCount();
            if (before != 6)
                throw new AssertionError("expected 6 images, got " + before);
            Object shortcut = Menus.getShortcuts().get(KeyEvent.VK_W + 200);
            if (!"Close All".equals(shortcut))
                throw new AssertionError("Ctrl+Shift+W must map to Close All");
            System.out.println("shortcut=" + shortcut);
            EventQueue.invokeAndWait(() -> imageJ.keyPressed(new KeyEvent(imageJ,
                KeyEvent.KEY_PRESSED, System.currentTimeMillis(),
                InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK, KeyEvent.VK_W, 'W')));
            long deadline = System.currentTimeMillis() + 8000;
            while (WindowManager.getImageCount() > 0 && System.currentTimeMillis() < deadline)
                Thread.sleep(20);
            EventQueue.invokeAndWait(() -> { });
            int after = WindowManager.getImageCount();
            System.out.println("before=" + before + " remaining=" + after
                + " titles=" + Arrays.toString(WindowManager.getImageTitles()));
            if (after != 0 || Orthogonal_Views.getInstance() != null)
                throw new AssertionError("one Close All must close every Orthogonal Views group");
            System.out.println("PASS");
            System.exit(0);
        } catch (Throwable error) {
            error.printStackTrace();
            System.exit(1);
        }
    }
}
