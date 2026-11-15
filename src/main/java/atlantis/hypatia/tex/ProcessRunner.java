package atlantis.hypatia.tex;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** Runs external programs: the TeX build (with output) and the PDF viewer (detached). */
public final class ProcessRunner {

    private volatile Process process;
    private volatile boolean cancelled;

    /** Runs {@code command} in {@code dir}, feeding each output line to {@code sink}. Returns the exit code. */
    public int run(List<String> command, Path dir, Consumer<String> sink)
            throws IOException, InterruptedException {
        cancelled = false;
        ProcessBuilder pb = atlantis.hypatia.Platform.processBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true);
        // TeX wraps log lines at 79 characters by default; ask for longer lines so
        // file paths and messages stay intact (honoured by TeX Live).
        pb.environment().putIfAbsent("max_print_line", "10000");
        process = pb.start();
        process.getOutputStream().close(); // no stdin: TeX must never wait for input
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sink.accept(line);
            }
        }
        return process.waitFor();
    }

    public void cancel() {
        cancelled = true;
        Process p = process;
        if (p != null && p.isAlive()) {
            p.descendants().forEach(ProcessHandle::destroy);
            p.destroy();
        }
    }

    public boolean wasCancelled() {
        return cancelled;
    }

    /** Starts a program without waiting for it (e.g. Okular). */
    public static void launchDetached(List<String> command, Path dir) throws IOException {
        atlantis.hypatia.Platform.processBuilder(command)
                .directory(dir.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }
}
