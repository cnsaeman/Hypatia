package atlantis.hypatia.style;

import java.util.ArrayList;

/**
 * A style checker in Java. Put the compiled class (or a jar) into
 * ~/.config/hypatia/styles/plugins and list it in styles.cfg:
 *
 * <pre>
 * javac -cp ~/.local/share/hypatia/hypatia.jar -d ~/.config/hypatia/styles/plugins MyCheck.java
 * </pre>
 *
 * The class needs a public no-argument constructor. One instance is created when a style is
 * loaded and reused for every check (from a background thread, never two at once), so it may
 * keep caches but should not depend on being called only once. A check that takes longer than
 * 5 seconds is abandoned and the plugin switched off until the style is run again.
 */
public interface StylePlugin {

    /**
     * Checks a document.
     *
     * @param s the whole source of the file, including preamble and comments
     * @return the problems found, with offsets into {@code s}
     */
    ArrayList<Problem> checkDocument(String s);
}
