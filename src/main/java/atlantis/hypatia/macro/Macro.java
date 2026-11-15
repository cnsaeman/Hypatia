package atlantis.hypatia.macro;

import java.util.List;

/**
 * A macro from macros.cfg: a name, a trigger and a list of commands.
 *
 * @param trigger     {@link Trigger#KEY} (shortcut "runmacro.name" in keys.properties)
 *                    or {@link Trigger#TEXT} (typed character sequence {@code text})
 * @param line        line of the header in macros.cfg (for messages)
 */
public record Macro(String name, Trigger trigger, String text, List<Command> commands, int line) {

    public enum Trigger { KEY, TEXT }

    /** One command of a macro. */
    public sealed interface Command permits SelectBlock, ClearEnvironment, Insert, InsertLines, Execute {}

    /** >>SELECT CURRENT BLOCK */
    public record SelectBlock() implements Command {}

    /** >>CLEAR ENVIRONMENT */
    public record ClearEnvironment() implements Command {}

    /** >>INSERT text (may contain >CURSOR<) */
    public record Insert(String text) implements Command {}

    /** >>INSERT LINES followed by the lines (may contain >CURSOR<) */
    public record InsertLines(List<String> lines) implements Command {}

    /** >>EXECUTE Class.method (class name may include a package) */
    public record Execute(String className, String method) implements Command {}

    public static final String CURSOR = ">CURSOR<";

    /** The id of this macro's shortcut in keys.properties. */
    public String keyId() {
        return "runmacro." + name;
    }
}
