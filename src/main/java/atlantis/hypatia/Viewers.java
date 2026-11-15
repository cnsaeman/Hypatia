package atlantis.hypatia;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Ready-made PDF viewer settings for each system: the forward-search command (Hypatia → PDF),
 * a command for a separate viewer window, and how to set up inverse search (PDF → Hypatia).
 */
public final class Viewers {

    private Viewers() {}

    /**
     * @param forward    viewer command with %p %l %c %t placeholders
     * @param newWindow  command for LaTeX > Open PDF in New Viewer
     * @param inverse    the command line to enter in the viewer for inverse search, or "" if none
     * @param howTo      where to enter it, and how to click
     */
    public record Viewer(String name, String forward, String newWindow, String inverse, String howTo) {
        @Override public String toString() { return name; }
    }

    /** The presets for this system; the first one that is installed is the default. */
    public static List<Viewer> presets() {
        String hyp = Platform.hypatiaCommandLine();
        List<Viewer> list = new ArrayList<>();
        if (Platform.MAC) {
            String displayline = "/Applications/Skim.app/Contents/SharedSupport/displayline";
            List<String> cmd = Platform.hypatiaCommand();
            String program = cmd.get(0);
            String args = cmd.size() > 1
                    ? String.join(" ", cmd.subList(1, cmd.size()).stream().map(Viewers::quote).toList()) + " "
                    : "";
            list.add(new Viewer("Skim", displayline + " -r %l %p %t", "open -a Skim %p",
                    program + "   with arguments   " + args + "--line %line \"%file\"",
                    "Skim > Settings > Sync: Preset \"Custom\", Command and Arguments as shown."
                            + " Then Cmd+Shift+click in the PDF."));
            list.add(new Viewer("Preview (no SyncTeX)", "open -a Preview %p", "open -a Preview %p", "",
                    "Preview cannot jump to lines; Skim (skim-app.sourceforge.io) can."));
            list.add(new Viewer("Default PDF program (no SyncTeX)", "open %p", "open %p", "", ""));
        } else if (Platform.WINDOWS) {
            Path sumatra = Platform.findProgram("SumatraPDF");
            String exe = sumatra != null ? quote(sumatra.toString()) : "SumatraPDF";
            list.add(new Viewer("SumatraPDF", exe + " -reuse-instance %p -forward-search %t %l",
                    exe + " -new-window %p",
                    hyp + " --line %l \"%f\"",
                    "SumatraPDF: Menu > Settings > Options > \"Set inverse search command-line\"."
                            + " Then double-click in the PDF."));
            list.add(new Viewer("Default PDF program (no SyncTeX)", "explorer.exe %p", "explorer.exe %p", "",
                    "Jumping to lines needs SumatraPDF (sumatrapdfreader.org)."));
        } else {
            list.add(new Viewer("Okular", "okular --unique %p#src:%l%t", "okular %p#src:%l%t",
                    hyp + " --line %l %f",
                    "Okular: Settings > Configure Okular > Editor > Custom Text Editor."
                            + " Then Shift+click in the PDF."));
            list.add(new Viewer("Zathura",
                    "zathura -x \"" + hyp.replace("\"", "\\\"") + " --line %{line} %{input}\" --synctex-forward %l:%c:%t %p",
                    "zathura %p", "(set by the viewer command)",
                    "Zathura gets the inverse-search command from Hypatia. Ctrl+click in the PDF."));
            list.add(new Viewer("Evince (no SyncTeX)", "evince %p", "evince %p", "",
                    "Evince cannot be told to jump to a line from the command line; Okular and Zathura can."));
            list.add(new Viewer("Default PDF program (no SyncTeX)", "xdg-open %p", "xdg-open %p", "", ""));
        }
        return list;
    }

    /** The viewer used when the user has not chosen one. */
    public static Viewer defaultViewer() {
        Viewer d = defaultViewer;
        if (d == null) defaultViewer = d = findDefault();
        return d;
    }

    private static volatile Viewer defaultViewer;

    private static Viewer findDefault() {
        List<Viewer> all = presets();
        for (Viewer v : all) {
            if (installed(v)) return v;
        }
        return all.get(0);
    }

    /** The preset whose forward command is {@code command}, or null. */
    public static Viewer byCommand(String command) {
        for (Viewer v : presets()) {
            if (v.forward().equals(command)) return v;
        }
        return null;
    }

    private static boolean installed(Viewer v) {
        if (Platform.MAC && v.name().equals("Skim")) return Files.isDirectory(Path.of("/Applications/Skim.app"));
        List<String> words = atlantis.hypatia.tex.CommandTemplate.build(v.forward(), java.util.Map.of());
        return !words.isEmpty() && Platform.findProgram(words.get(0)) != null;
    }

    private static String quote(String s) {
        return s.contains(" ") ? "\"" + s + "\"" : s;
    }
}
