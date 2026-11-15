package atlantis.hypatia;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Keeps Hypatia single-instance. The first instance listens on a Unix domain
 * socket; later invocations (e.g. from Okular's inverse search) hand their
 * files and line numbers over and exit.
 *
 * Protocol: the client sends one line per request ("path TAB line TAB column"),
 * then "END"; the server answers "OK".
 */
public final class InstanceServer {

    private final Path socketPath;
    private ServerSocketChannel server;

    public InstanceServer() {
        String runtime = System.getenv("XDG_RUNTIME_DIR");
        Path dir = (runtime != null && !runtime.isBlank() && Files.isDirectory(Path.of(runtime)))
                ? Path.of(runtime)
                : Settings.configDir();
        socketPath = dir.resolve("hypatia.sock");
    }

    /** Sends requests to a running instance. Returns false if none is running. */
    public boolean sendToRunning(List<OpenRequest> requests) {
        if (!Files.exists(socketPath)) {
            System.err.println("Hypatia: no running instance (" + socketPath + " does not exist)");
            return false;
        }
        ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "hypatia-client");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<Boolean> f = ex.submit(() -> {
                try (SocketChannel ch = SocketChannel.open(UnixDomainSocketAddress.of(socketPath))) {
                    PrintWriter out = new PrintWriter(Channels.newWriter(ch, StandardCharsets.UTF_8), true);
                    BufferedReader in = new BufferedReader(Channels.newReader(ch, StandardCharsets.UTF_8));
                    for (OpenRequest r : requests) out.println(r.encode());
                    out.println("END");
                    return "OK".equals(in.readLine());
                }
            });
            boolean ok = f.get(3, TimeUnit.SECONDS);
            if (!ok) System.err.println("Hypatia: running instance at " + socketPath + " did not confirm");
            return ok;
        } catch (Exception e) {
            // stale socket or unresponsive instance: start a new one
            Throwable c = e instanceof java.util.concurrent.ExecutionException && e.getCause() != null ? e.getCause() : e;
            System.err.println("Hypatia: could not reach running instance at " + socketPath + ": " + c);
            return false;
        } finally {
            ex.shutdownNow();
        }
    }

    /** Starts listening; requests are passed to {@code handler} on a background thread. */
    public void start(Consumer<List<OpenRequest>> handler) {
        try {
            Files.createDirectories(socketPath.getParent());
            Files.deleteIfExists(socketPath); // left over from a crashed instance
            server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            server.bind(UnixDomainSocketAddress.of(socketPath));
            socketPath.toFile().deleteOnExit();
        } catch (IOException e) {
            System.err.println("Hypatia: single-instance socket unavailable: " + e.getMessage());
            return;
        }
        Thread t = new Thread(() -> {
            while (server.isOpen()) {
                try (SocketChannel ch = server.accept()) {
                    BufferedReader in = new BufferedReader(Channels.newReader(ch, StandardCharsets.UTF_8));
                    List<OpenRequest> list = new ArrayList<>();
                    String line;
                    while ((line = in.readLine()) != null && !line.equals("END")) {
                        OpenRequest r = OpenRequest.decode(line);
                        if (r != null) list.add(r);
                    }
                    handler.accept(list);
                    PrintWriter out = new PrintWriter(Channels.newWriter(ch, StandardCharsets.UTF_8), true);
                    out.println("OK");
                } catch (IOException e) {
                    if (!server.isOpen()) break;
                } catch (RuntimeException e) {
                    System.err.println("Hypatia: bad request from another instance: " + e); // keep listening
                }
            }
        }, "hypatia-instance-server");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        try {
            if (server != null) server.close();
            Files.deleteIfExists(socketPath);
        } catch (IOException ignored) {
            // shutting down anyway
        }
    }
}
