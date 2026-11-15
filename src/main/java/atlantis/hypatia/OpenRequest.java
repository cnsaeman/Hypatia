package atlantis.hypatia;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** "Open this file (and jump to this line)", from the command line or another instance. */
public record OpenRequest(Path file, int line, int column) {

    public static final String USAGE = """
            Usage: hypatia [options] [file[:line] ...]

              --line N, -l N     jump to line N in the (last) file
              --column N         jump to column N as well
              --new-instance     do not hand the files to a running Hypatia
              --help             show this help

            Examples:
              hypatia paper.tex
              hypatia --line 120 chapters/intro.tex
              hypatia chapters/intro.tex:120

            PDF viewers' inverse search runs e.g.  hypatia --line %l %f
            (Hypatia's Settings > Viewer Integration shows the exact command for your viewer).
            """;

    /** Parses command-line arguments; returns null and prints usage on --help or bad input. */
    public static List<OpenRequest> parse(String[] args, boolean[] newInstance) {
        List<OpenRequest> list = new ArrayList<>();
        int line = 0;
        int column = 0;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--help", "-h" -> {
                    System.out.print(USAGE);
                    return null;
                }
                case "--new-instance" -> newInstance[0] = true;
                case "--line", "-l" -> line = number(args, ++i);
                case "--column", "-c" -> column = number(args, ++i);
                default -> {
                    if (a.startsWith("--line=")) {
                        line = parseInt(a.substring(7));
                    } else if (a.startsWith("+") && a.length() > 1 && a.substring(1).chars().allMatch(Character::isDigit)) {
                        line = parseInt(a.substring(1)); // vim style: +120 file.tex
                    } else if (a.startsWith("-") && a.length() > 1) {
                        System.err.println("hypatia: unknown option " + a);
                        System.err.print(USAGE);
                        return null;
                    } else {
                        list.add(fileArgument(a, line, column));
                        line = 0;
                        column = 0;
                    }
                }
            }
        }
        // "--line" given after the file name (e.g. "hypatia file.tex --line 3")
        if (line > 0 && !list.isEmpty()) {
            OpenRequest last = list.remove(list.size() - 1);
            list.add(new OpenRequest(last.file(), line, column));
        }
        return list;
    }

    private static OpenRequest fileArgument(String a, int line, int column) {
        Path p = Path.of(a);
        // file.tex:120 — only if no file with that literal name exists
        int colon = a.lastIndexOf(':');
        if (!p.toFile().exists() && colon > 0 && colon < a.length() - 1
                && a.substring(colon + 1).chars().allMatch(Character::isDigit)) {
            p = Path.of(a.substring(0, colon));
            line = parseInt(a.substring(colon + 1));
        }
        return new OpenRequest(p.toAbsolutePath().normalize(), line, column);
    }

    private static int number(String[] args, int i) {
        return i < args.length ? parseInt(args[i]) : 0;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Wire format between instances: path TAB line TAB column. */
    public String encode() {
        return file + "\t" + line + "\t" + column;
    }

    public static OpenRequest decode(String s) {
        String[] parts = s.split("\t");
        if (parts.length < 1 || parts[0].isEmpty()) return null;
        return new OpenRequest(Path.of(parts[0]),
                parts.length > 1 ? parseInt(parts[1]) : 0,
                parts.length > 2 ? parseInt(parts[2]) : 0);
    }
}
