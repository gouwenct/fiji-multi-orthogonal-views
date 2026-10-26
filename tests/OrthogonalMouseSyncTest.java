import ij.ImageJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Prefs;
import ij.WindowManager;
import ij.gui.ImageCanvas;
import ij.plugin.Orthogonal_Views;
import ij.process.ByteProcessor;
import java.awt.Component;
import java.awt.Checkbox;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.List;
import java.awt.event.InputEvent;
import java.awt.event.ItemEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.util.Arrays;

/** Dispatches mouse events through real canvases and selects the real Sync Windows list. */
public class OrthogonalMouseSyncTest {
    static final ImagePlus[] images = new ImagePlus[4];
    static final Orthogonal_Views[] views = new Orthogonal_Views[4];

    public static void main(String[] args) {
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(25000); } catch (InterruptedException ignored) { }
            System.err.println("FAIL: mouse synchronization timed out");
            System.exit(2);
        });
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            new ImageJ(ImageJ.NO_SHOW);
            for (int i = 0; i < images.length; i++) {
                ImageStack stack = new ImageStack(64, 48);
                for (int z = 0; z < (i == 1 ? 48 : 12); z++) stack.addSlice(new ByteProcessor(64, 48));
                images[i] = new ImagePlus("mouse-sync-" + (char)('A' + i), stack);
                if (i == 1) {
                    images[i].setDimensions(2, 12, 2);
                    images[i].setOpenAsHyperStack(true);
                    images[i].setPosition(2, 6, 2);
                }
                images[i].getCalibration().pixelDepth = 2;
                images[i].show();
            }
            boolean syncFirst = args.length == 0 || !args[0].equals("ortho-first");
            Sync_Windows[] sync = new Sync_Windows[1];
            if (syncFirst) EventQueue.invokeAndWait(() -> {
                sync[0] = new Sync_Windows();
                sync[0].run("");
            });
            EventQueue.invokeAndWait(() -> { });
            for (int i = 0; i < images.length; i++) {
                Prefs.rotateYZ = i == 2;
                Prefs.flipXZ = false;
                WindowManager.setTempCurrentImage(images[i]);
                views[i] = new Orthogonal_Views();
                views[i].run("");
                WindowManager.setTempCurrentImage(null);
                awaitView(views[i]);
            }
            if (!syncFirst) EventQueue.invokeAndWait(() -> {
                sync[0] = new Sync_Windows();
                sync[0].run("");
            });
            if (args.length > 0 && args[0].equals("reopen")) EventQueue.invokeAndWait(() -> {
                sync[0].windowClosing(new WindowEvent(sync[0], WindowEvent.WINDOW_CLOSING));
                sync[0] = new Sync_Windows();
                sync[0].run("");
            });
            EventQueue.invokeAndWait(() -> {
                List list = findList(sync[0]);
                if (list == null) throw new AssertionError("Sync Windows list not found");
                for (int i = 0; i < list.getItemCount(); i++) {
                    for (int j = 0; j < 3; j++)
                        if (list.getItem(i).equals(images[j].getTitle())) list.select(i);
                }
                sync[0].itemStateChanged(new ItemEvent(list, ItemEvent.ITEM_STATE_CHANGED,
                    Integer.valueOf(0), ItemEvent.SELECTED));
            });
            int[] unselected = views[3].getCrossLoc();
            click(images[0], 12, 19);
            expectSelected(12, 19, images[0].getSlice()-1, "XY click");
            click(views[0].getXZImage(), 27, 6);
            expectSelected(27, 19, 3, "XZ click");
            click(views[0].getYZImage(), 10, 31);
            expectSelected(27, 31, 5, "YZ click");
            drag(views[2].getYZImage(), 31, 10, 37, 8);
            expectSelected(27, 37, 4, "rotated YZ drag from C");
            EventQueue.invokeAndWait(() -> findCheckbox(sync[0], "Sync Cursor").setState(false));
            click(images[0], 9, 13);
            Thread.sleep(150);
            if (!Arrays.equals(views[1].getCrossLoc(), new int[]{27, 37, 4}))
                throw new AssertionError("Sync Cursor off must stop cross-plane updates");
            EventQueue.invokeAndWait(() -> findCheckbox(sync[0], "Sync Cursor").setState(true));
            click(images[0], 14, 16);
            expectSelected(14, 16, 4, "cursor sync re-enabled");
            EventQueue.invokeAndWait(() -> findCheckbox(sync[0], "Sync z-Slices").setState(false));
            click(views[0].getXZImage(), 23, 6);
            Thread.sleep(150);
            if (!Arrays.equals(views[0].getCrossLoc(), new int[]{23, 16, 3})
                || !Arrays.equals(views[1].getCrossLoc(), new int[]{23, 16, 4}))
                throw new AssertionError("Sync z-Slices off must preserve target Z");
            EventQueue.invokeAndWait(() -> findCheckbox(sync[0], "Sync z-Slices").setState(true));
            drag(views[0].getXZImage(), 23, 6, 30, 8);
            expectSelected(30, 16, 4, "XZ drag with Z sync restored");
            if (images[1].getC() != 2 || images[1].getT() != 2)
                throw new AssertionError("Orthogonal sync changed target channel or frame");
            if (!Arrays.equals(unselected, views[3].getCrossLoc()))
                throw new AssertionError("Unselected D changed");
            EventQueue.invokeAndWait(() ->
                sync[0].windowClosing(new WindowEvent(sync[0], WindowEvent.WINDOW_CLOSING)));
            click(images[0], 4, 8);
            Thread.sleep(150);
            if (!Arrays.equals(views[1].getCrossLoc(), new int[]{30, 16, 4}))
                throw new AssertionError("Closed Sync Windows still synchronized images");
            EventQueue.invokeAndWait(() -> images[1].close());
            EventQueue.invokeAndWait(() -> { });
            if (views[1].getXZImage().getWindow() != null || views[1].getYZImage().getWindow() != null)
                throw new AssertionError("Closing a source left its orthogonal windows open");
            if (views[0].getXZImage().getWindow() == null)
                throw new AssertionError("Closing B also closed A");
            System.out.println("PASS: " + (args.length == 0 ? "sync-first" : args[0])
                + "; mouse events, view redraw, sync controls, C/T, unselected D and window lifecycle");
            System.exit(0);
        } catch (Throwable error) {
            error.printStackTrace();
            System.exit(1);
        }
    }

    static List findList(Container container) {
        for (Component component : container.getComponents()) {
            if (component instanceof List) return (List)component;
            if (component instanceof Container) {
                List list = findList((Container)component);
                if (list != null) return list;
            }
        }
        return null;
    }

    static Checkbox findCheckbox(Container container, String label) {
        for (Component component : container.getComponents()) {
            if (component instanceof Checkbox && ((Checkbox)component).getLabel().equals(label))
                return (Checkbox)component;
            if (component instanceof Container) {
                Checkbox check = findCheckbox((Container)component, label);
                if (check != null) return check;
            }
        }
        return null;
    }

    static void awaitView(Orthogonal_Views view) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 4000;
        while (view.getXZImage().getOverlay() == null || view.getYZImage().getOverlay() == null) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("Views did not initialize");
            Thread.sleep(20);
        }
    }

    static void mouse(ImagePlus image, int id, int x, int y) {
        ImageCanvas canvas = image.getCanvas();
        int modifiers = id == MouseEvent.MOUSE_PRESSED || id == MouseEvent.MOUSE_DRAGGED
            ? InputEvent.BUTTON1_DOWN_MASK : 0;
        int button = id == MouseEvent.MOUSE_PRESSED || id == MouseEvent.MOUSE_RELEASED
            ? MouseEvent.BUTTON1 : MouseEvent.NOBUTTON;
        canvas.dispatchEvent(new MouseEvent(canvas, id, System.currentTimeMillis(), modifiers,
            canvas.screenX(x), canvas.screenY(y), 1, false, button));
    }

    static void click(ImagePlus image, int x, int y) throws Exception {
        EventQueue.invokeAndWait(() -> {
            WindowManager.setCurrentWindow(image.getWindow());
            mouse(image, MouseEvent.MOUSE_MOVED, x, y);
            mouse(image, MouseEvent.MOUSE_PRESSED, x, y);
            mouse(image, MouseEvent.MOUSE_RELEASED, x, y);
        });
    }

    static void drag(ImagePlus image, int x, int y, int toX, int toY) throws Exception {
        EventQueue.invokeAndWait(() -> {
            WindowManager.setCurrentWindow(image.getWindow());
            mouse(image, MouseEvent.MOUSE_MOVED, x, y);
            mouse(image, MouseEvent.MOUSE_PRESSED, x, y);
            mouse(image, MouseEvent.MOUSE_DRAGGED, toX, toY);
            mouse(image, MouseEvent.MOUSE_RELEASED, toX, toY);
        });
    }

    static void expectSelected(int x, int y, int z, String action) throws Exception {
        int[] expected = {x, y, z};
        long deadline = System.currentTimeMillis() + 1500;
        boolean matches;
        do {
            Thread.sleep(30);
            matches = true;
            for (int i = 0; i < 3; i++)
                matches &= Arrays.equals(expected, views[i].getCrossLoc())
                    && views[i].getXZImage().getTitle().equals("XZ " + y)
                    && views[i].getYZImage().getTitle().equals((i == 2 ? "ZY " : "YZ ") + x);
        } while (!matches && System.currentTimeMillis() < deadline);
        for (int i = 0; i < 3; i++) {
            System.out.println(action + " " + images[i].getTitle() + " " + Arrays.toString(views[i].getCrossLoc()));
            if (!matches) throw new AssertionError(action + ": expected " + Arrays.toString(expected));
        }
    }
}
