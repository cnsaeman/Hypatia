package atlantis.hypatia;

import java.awt.Toolkit;
import java.lang.reflect.Field;
import java.util.List;
import javax.swing.SwingUtilities;
import atlantis.hypatia.ui.MainFrame;
import atlantis.hypatia.ui.Theme;

/**
 * Entry point.
 *
 *   hypatia [--line N] [--column N] [--new-instance] [file[:line] ...]
 *
 * If Hypatia is already running, the files are handed to it and this process exits,
 * so "hypatia --line %l %f" works as the PDF viewer's inverse-search command.
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        boolean[] newInstance = {false};
        List<OpenRequest> requests = OpenRequest.parse(args, newInstance);
        if (requests == null) {
            System.exit(args.length > 0 && (args[0].equals("--help") || args[0].equals("-h")) ? 0 : 2);
        }

        InstanceServer server = new InstanceServer();
        if (!newInstance[0] && server.sendToRunning(requests)) {
            System.exit(0);
        }

        // Use the system's proxy settings for downloads (dictionaries).
        if (System.getProperty("java.net.useSystemProxies") == null) System.setProperty("java.net.useSystemProxies", "true");
        if (Platform.MAC) {
            System.setProperty("apple.laf.useScreenMenuBar", "true"); // menus at the top of the screen
            System.setProperty("apple.awt.application.name", "Hypatia");
            System.setProperty("apple.awt.application.appearance", "system");
        } else {
            setWindowClassName("hypatia");
        }
        Settings settings = Settings.load();

        SwingUtilities.invokeLater(() -> {
            Theme.install(settings.isDark());
            MainFrame frame = new MainFrame(settings, newInstance[0] ? null : server);
            frame.setVisible(true);
            if (settings.restoreSession()) frame.restoreSession();
            frame.open(requests);
            if (!newInstance[0]) {
                server.start(list -> SwingUtilities.invokeLater(() -> frame.handleRemote(list)));
            }
        });
    }

    /**
     * Sets the X11 WM_CLASS so KDE/GNOME match the window to hypatia.desktop (and its icon).
     * Needs "--add-opens java.desktop/sun.awt.X11=ALL-UNNAMED" (set in the JAR manifest);
     * harmless if unavailable.
     */
    private static void setWindowClassName(String name) {
        try {
            Toolkit tk = Toolkit.getDefaultToolkit();
            Field f = tk.getClass().getDeclaredField("awtAppClassName");
            f.setAccessible(true);
            f.set(tk, name);
        } catch (Exception | LinkageError ignored) {
            // not X11, or module access denied
        }
    }
}
