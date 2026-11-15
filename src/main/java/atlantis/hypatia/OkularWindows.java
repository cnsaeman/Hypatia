package atlantis.hypatia;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One Okular window per PDF (Linux). Okular's --unique sends every forward search to a single
 * window, which then switches documents. Instead, Hypatia asks the running Okular windows over
 * D-Bus which one shows the PDF and sends the forward search there; if none does, it starts a
 * new Okular (without --unique). D-Bus is reached through whichever command-line tool is
 * installed: dbus-send, gdbus or qdbus.
 *
 * <p>Okular's own "Open new files in tabs" setting must be off, or Okular puts the new
 * documents into tabs of one window itself.
 */
public final class OkularWindows {

    private OkularWindows() {}

    /** Runs the D-Bus queries one at a time, off the event thread. */
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "okular-windows");
        t.setDaemon(true);
        return t;
    });

    /** Okulars this session started, by PDF: a second forward search may come before it is on D-Bus. */
    private static final Map<Path, Process> started = new HashMap<>();

    private static final Pattern SERVICE = Pattern.compile("org\\.kde\\.okular-\\d+");

    /** True if {@code command} (a viewer command, split into words) starts Okular. */
    public static boolean isOkular(List<String> command) {
        if (command.isEmpty()) return false;
        String prog = Path.of(command.get(0)).getFileName().toString();
        return prog.equals("okular");
    }

    /** True if a D-Bus tool is installed, without which every forward search would open a new window. */
    public static boolean available() {
        return tool() != null;
    }

    /**
     * Shows {@code line} of {@code tex} in the Okular window that has {@code pdf} open, or
     * starts {@code command} (with --unique removed) for a new window. Returns at once;
     * {@code onError} gets a message (on the worker thread) if Okular could not be started.
     */
    public static void show(List<String> command, Path pdf, Path tex, int line, Path dir, Consumer<String> onError) {
        Path key = Platform.canonical(pdf);
        List<String> launch = new ArrayList<>(command);
        launch.remove("--unique");
        boolean raise = !command.contains("--noraise");
        String url = sourceUrl(key, tex, line);
        WORKER.execute(() -> {
            try {
                String service = find(key);
                if (service == null) service = justStarted(key);
                if (service != null && send(service, url)) {
                    if (raise) call(service, "/okularshell", "org.kde.okular.tryRaise", "");
                    return;
                }
                Process p = Platform.processBuilder(launch).directory(dir.toFile())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                synchronized (started) {
                    started.values().removeIf(q -> !q.isAlive());
                    started.put(key, p);
                }
            } catch (IOException e) {
                onError.accept(String.join(" ", launch) + "\n\n" + e.getMessage());
            }
        });
    }

    /** file:///…/main.pdf#src:LINE/…/chapter.tex, Okular's form of a forward search. */
    static String sourceUrl(Path pdf, Path tex, int line) {
        String fragment = "src:" + line + tex.toAbsolutePath();
        try {
            return new URI("file", "", pdf.toAbsolutePath().toString(), fragment).toASCIIString();
        } catch (URISyntaxException e) {
            return pdf.toUri() + "#" + fragment;
        }
    }

    /** The D-Bus name of a running Okular whose current document is {@code pdf}, or null. */
    private static String find(Path pdf) {
        List<String> names = call("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus.ListNames");
        if (names == null) return null;
        for (String name : names) {
            if (!SERVICE.matcher(name).matches()) continue;
            List<String> doc = call(name, "/okular", "org.kde.okular.currentDocument");
            if (doc == null || doc.isEmpty() || doc.get(0).isBlank()) continue;
            try {
                if (same(Path.of(doc.get(0)), pdf)) return name;
            } catch (RuntimeException e) {
                // not a path (e.g. a remote URL)
            }
        }
        return null;
    }

    /** If this session started an Okular for {@code pdf} that is still starting up: waits for it on D-Bus. */
    private static String justStarted(Path pdf) {
        Process p;
        synchronized (started) {
            p = started.get(pdf);
        }
        if (p == null || !p.isAlive()) return null;
        String name = "org.kde.okular-" + p.pid();
        for (int i = 0; i < 50 && p.isAlive(); i++) { // up to 5 s
            List<String> doc = call(name, "/okular", "org.kde.okular.currentDocument");
            if (doc != null && !doc.isEmpty() && !doc.get(0).isBlank()) {
                return same(Path.of(doc.get(0)), pdf) ? name : null;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /** True if both paths name the same file (also through symbolic links). */
    private static boolean same(Path a, Path b) {
        if (Platform.canonical(a).equals(b)) return true;
        try {
            return java.nio.file.Files.isSameFile(a, b);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Sends the forward search; false if that window did not answer (closed in the meantime). */
    private static boolean send(String service, String url) {
        return call(service, "/okular", "org.kde.okular.openDocument", url) != null;
    }

    // --- the D-Bus command-line tools ---------------------------------------------------------

    private enum Tool { DBUS_SEND, GDBUS, QDBUS }

    private record Found(Tool tool, Path path) {}

    private static volatile Found tool;
    private static volatile boolean looked;

    private static Found tool() {
        if (!looked) {
            Found f = null;
            for (String name : List.of("dbus-send", "gdbus", "qdbus6", "qdbus", "qdbus-qt6", "qdbus-qt5")) {
                Path p = Platform.findProgram(name);
                if (p == null) continue;
                f = new Found(name.equals("dbus-send") ? Tool.DBUS_SEND : name.equals("gdbus") ? Tool.GDBUS : Tool.QDBUS, p);
                break;
            }
            tool = f;
            looked = true;
        }
        return tool;
    }

    /**
     * Calls a D-Bus method with string arguments on the session bus and returns the strings
     * in its answer (empty if there are none), or null if the call failed.
     */
    static List<String> call(String dest, String path, String method, String... args) {
        Found t = tool();
        if (t == null) return null;
        List<String> cmd = new ArrayList<>();
        cmd.add(t.path().toString());
        switch (t.tool()) {
            case DBUS_SEND -> {
                cmd.addAll(List.of("--session", "--print-reply", "--dest=" + dest, path, method));
                for (String a : args) cmd.add("string:" + a);
            }
            case GDBUS -> {
                cmd.addAll(List.of("call", "--session", "--dest", dest, "--object-path", path, "--method", method));
                // gdbus parses arguments as GVariant text; a quoted string is always taken as a string
                for (String a : args) cmd.add("'" + a.replace("\\", "\\\\").replace("'", "\\'") + "'");
            }
            case QDBUS -> {
                cmd.addAll(List.of(dest, path, method));
                cmd.addAll(List.of(args));
            }
        }
        String out;
        try {
            Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            p.getOutputStream().close();
            byte[] bytes;
            try (InputStream in = p.getInputStream()) {
                bytes = in.readAllBytes();
            }
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) return null;
            out = new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return switch (t.tool()) {
            case DBUS_SEND -> dbusSendStrings(out);
            case GDBUS -> gvariantStrings(out);
            case QDBUS -> out.lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
        };
    }

    private static final Pattern DBUS_SEND_STRING = Pattern.compile("^\\s*string \"(.*)\"\\s*$", Pattern.MULTILINE);

    /** The strings in dbus-send's answer: lines like {@code string "…"} (printed without escaping). */
    static List<String> dbusSendStrings(String out) {
        List<String> list = new ArrayList<>();
        Matcher m = DBUS_SEND_STRING.matcher(out);
        while (m.find()) list.add(m.group(1));
        return list;
    }

    private static final Pattern GVARIANT_STRING = Pattern.compile("'((?:[^'\\\\]|\\\\.)*)'|\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** The strings in gdbus's answer, e.g. {@code (['a', 'b'],)} or {@code ('/x.pdf',)}. */
    static List<String> gvariantStrings(String out) {
        List<String> list = new ArrayList<>();
        Matcher m = GVARIANT_STRING.matcher(out);
        while (m.find()) {
            String s = m.group(1) != null ? m.group(1) : m.group(2);
            list.add(s.replaceAll("\\\\(.)", "$1"));
        }
        return list;
    }
}
