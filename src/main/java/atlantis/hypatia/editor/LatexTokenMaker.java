package atlantis.hypatia.editor;

import java.util.Set;
import javax.swing.text.Segment;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMakerBase;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

/**
 * Hand-written LaTeX tokenizer for RSyntaxTextArea.
 *
 * Distinguishes text, commands, structure commands, environment names,
 * reference keys, math (inline, display and math environments), verbatim and
 * comment environments. Multi-line state (math, verbatim, comment environment)
 * is carried over to the next line through the type of the last token on the
 * line, which is the standard RSyntaxTextArea mechanism.
 *
 * RSTA has a fixed set of token types, so LaTeX roles are mapped onto them;
 * the colours for each role are set in {@link EditorPalette}.
 */
public class LatexTokenMaker extends TokenMakerBase {

    public static final String SYNTAX_STYLE = "text/x-hypatia-latex";

    // Role -> RSTA token type
    public static final int TEXT = TokenTypes.IDENTIFIER;
    public static final int WHITESPACE = TokenTypes.WHITESPACE;
    public static final int COMMENT = TokenTypes.COMMENT_EOL;
    public static final int COMMENT_ENV = TokenTypes.COMMENT_MULTILINE;
    public static final int COMMAND = TokenTypes.RESERVED_WORD;
    public static final int STRUCTURE = TokenTypes.RESERVED_WORD_2;
    public static final int ENV_NAME = TokenTypes.DATA_TYPE;
    public static final int REF_KEY = TokenTypes.VARIABLE;
    public static final int MATH = TokenTypes.LITERAL_STRING_DOUBLE_QUOTE;
    public static final int MATH_COMMAND = TokenTypes.FUNCTION;
    public static final int MATH_DELIMITER = TokenTypes.OPERATOR;
    public static final int SPECIAL = TokenTypes.LITERAL_CHAR;
    public static final int BRACE = TokenTypes.SEPARATOR;
    public static final int VERBATIM = TokenTypes.LITERAL_BACKQUOTE;

    private static final Set<String> MATH_ENVS = Set.of(
            "equation", "equation*", "align", "align*", "alignat", "alignat*",
            "gather", "gather*", "multline", "multline*", "flalign", "flalign*",
            "eqnarray", "eqnarray*", "displaymath", "math", "dmath", "dmath*");

    private static final Set<String> VERBATIM_ENVS = Set.of(
            "verbatim", "verbatim*", "Verbatim", "lstlisting", "minted", "alltt");

    private static final Set<String> COMMENT_ENVS = Set.of("comment");

    private static final Set<String> STRUCTURE_COMMANDS = Set.of(
            "\\documentclass", "\\usepackage", "\\RequirePackage",
            "\\part", "\\chapter", "\\section", "\\subsection", "\\subsubsection",
            "\\paragraph", "\\subparagraph",
            "\\part*", "\\chapter*", "\\section*", "\\subsection*", "\\subsubsection*",
            "\\paragraph*", "\\subparagraph*", "\\appendix",
            "\\newcommand", "\\renewcommand", "\\providecommand", "\\DeclareMathOperator",
            "\\newenvironment", "\\renewenvironment", "\\newtheorem", "\\def",
            "\\input", "\\include", "\\bibliography", "\\bibliographystyle", "\\addbibresource",
            "\\maketitle", "\\tableofcontents", "\\item");

    private static final Set<String> KEY_COMMANDS = Set.of(
            "\\label", "\\ref", "\\eqref", "\\pageref", "\\autoref", "\\cref", "\\Cref",
            "\\cite", "\\citep", "\\citet", "\\citealt", "\\citealp", "\\nocite",
            "\\parencite", "\\textcite", "\\autocite", "\\footcite");

    // Current-line scanning state
    private char[] a;
    private int delta; // document offset = array index + delta
    private int state; // NORMAL (=TokenTypes.NULL), MATH, VERBATIM or COMMENT_ENV
    private static final int NORMAL = TokenTypes.NULL;

    @Override
    public Token getTokenList(Segment text, int initialTokenType, int startOffset) {
        resetTokenList();
        a = text.array;
        int begin = text.offset;
        int end = text.offset + text.count;
        delta = startOffset - begin;
        state = (initialTokenType == MATH || initialTokenType == VERBATIM
                || initialTokenType == COMMENT_ENV) ? initialTokenType : NORMAL;

        // A paragraph break inside inline math is a TeX error anyway; resetting
        // here stops a stray '$' from colouring the rest of the document.
        if (state == MATH && isBlank(begin, end)) {
            state = NORMAL;
        }

        int i = begin;
        while (i < end) {
            if (state == VERBATIM || state == COMMENT_ENV) {
                int close = findEnvEnd(i, end, state == VERBATIM ? VERBATIM_ENVS : COMMENT_ENVS);
                if (close < 0) {
                    add(i, end, state);
                    i = end;
                    break;
                }
                if (close > i) {
                    add(i, close, state);
                }
                state = NORMAL;
                i = close; // "\end{...}" is tokenized normally below
                continue;
            }
            char c = a[i];
            if (c == '%') {
                add(i, end, COMMENT);
                i = end;
            } else if (c == ' ' || c == '\t') {
                int j = i + 1;
                while (j < end && (a[j] == ' ' || a[j] == '\t')) j++;
                add(i, j, WHITESPACE);
                i = j;
            } else if (c == '\\') {
                i = scanCommand(i, end);
            } else if (c == '$') {
                int j = (i + 1 < end && a[i + 1] == '$') ? i + 2 : i + 1;
                add(i, j, MATH_DELIMITER);
                state = (state == MATH) ? NORMAL : MATH;
                i = j;
            } else if (c == '{' || c == '}' || c == '[' || c == ']') {
                add(i, i + 1, BRACE);
                i++;
            } else if (c == '&' || c == '~') {
                add(i, i + 1, SPECIAL);
                i++;
            } else {
                int j = i + 1;
                while (j < end && !isSpecial(a[j])) j++;
                add(i, j, state == MATH ? MATH : TEXT);
                i = j;
            }
        }

        if (state == NORMAL) {
            addNullToken();
        } else {
            // Zero-length token whose type carries the state to the next line.
            addToken(a, end, end - 1, state, end + delta);
        }
        return firstToken;
    }

    /** Scans a control sequence starting at the backslash at {@code i}; returns the next index. */
    private int scanCommand(int i, int end) {
        int j;
        if (i + 1 >= end) {
            add(i, i + 1, state == MATH ? MATH_COMMAND : COMMAND);
            return i + 1;
        }
        char d = a[i + 1];
        if (isLetter(d)) {
            j = i + 1;
            while (j < end && isLetter(a[j])) j++;
            if (j < end && a[j] == '*') j++;
        } else {
            j = i + 2;
        }
        String cmd = new String(a, i, j - i);

        switch (cmd) {
            case "\\(", "\\[" -> {
                add(i, j, MATH_DELIMITER);
                if (state == NORMAL) state = MATH;
                return j;
            }
            case "\\)", "\\]" -> {
                add(i, j, MATH_DELIMITER);
                if (state == MATH) state = NORMAL;
                return j;
            }
            case "\\\\" -> {
                add(i, j, SPECIAL);
                return j;
            }
            case "\\begin", "\\end" -> {
                add(i, j, STRUCTURE);
                return scanEnvironmentName(j, end, cmd.equals("\\begin"));
            }
            case "\\verb", "\\verb*" -> {
                if (j < end) {
                    char delim = a[j];
                    int k = j + 1;
                    while (k < end && a[k] != delim) k++;
                    int stop = Math.min(k + 1, end);
                    add(i, stop, VERBATIM);
                    return stop;
                }
                add(i, j, COMMAND);
                return j;
            }
            default -> {
                if (KEY_COMMANDS.contains(cmd)) {
                    add(i, j, COMMAND);
                    return scanKeyArgument(j, end);
                }
                if (STRUCTURE_COMMANDS.contains(cmd)) {
                    add(i, j, STRUCTURE);
                } else {
                    add(i, j, state == MATH ? MATH_COMMAND : COMMAND);
                }
                return j;
            }
        }
    }

    /** After \begin or \end: tokenizes "{name}" and updates the state. */
    private int scanEnvironmentName(int j, int end, boolean isBegin) {
        int k = j;
        while (k < end && (a[k] == ' ' || a[k] == '\t')) k++;
        if (k > j) add(j, k, WHITESPACE);
        if (k >= end || a[k] != '{') return k;
        int close = k + 1;
        while (close < end && a[close] != '}' && a[close] != '{' && a[close] != '\\') close++;
        add(k, k + 1, BRACE);
        String name = new String(a, k + 1, close - k - 1).trim();
        if (close > k + 1) add(k + 1, close, ENV_NAME);
        if (close < end && a[close] == '}') {
            add(close, close + 1, BRACE);
            close++;
        } else {
            return close;
        }
        if (isBegin) {
            if (state == NORMAL && MATH_ENVS.contains(name)) state = MATH;
            else if (state == NORMAL && VERBATIM_ENVS.contains(name)) state = VERBATIM;
            else if (state == NORMAL && COMMENT_ENVS.contains(name)) state = COMMENT_ENV;
        } else if (state == MATH && MATH_ENVS.contains(name)) {
            state = NORMAL;
        }
        return close;
    }

    /** After \label, \ref, \cite...: optional [..] arguments, then {key}. */
    private int scanKeyArgument(int j, int end) {
        int k = j;
        while (k < end && a[k] == '[') {
            int close = k + 1;
            while (close < end && a[close] != ']') close++;
            add(k, k + 1, BRACE);
            if (close > k + 1) add(k + 1, close, TEXT);
            if (close >= end) return end;
            add(close, close + 1, BRACE);
            k = close + 1;
        }
        if (k >= end || a[k] != '{') return k;
        int close = k + 1;
        while (close < end && a[close] != '}') close++;
        add(k, k + 1, BRACE);
        if (close > k + 1) add(k + 1, close, REF_KEY);
        if (close < end) {
            add(close, close + 1, BRACE);
            return close + 1;
        }
        return end;
    }

    /** Index of "\end{name}" for a name in {@code names}, or -1. */
    private int findEnvEnd(int from, int end, Set<String> names) {
        for (int i = from; i + 5 < end; i++) {
            if (a[i] == '\\' && a[i + 1] == 'e' && a[i + 2] == 'n' && a[i + 3] == 'd') {
                int k = i + 4;
                while (k < end && a[k] == ' ') k++;
                if (k < end && a[k] == '{') {
                    int close = k + 1;
                    while (close < end && a[close] != '}') close++;
                    if (close < end && names.contains(new String(a, k + 1, close - k - 1).trim())) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    private void add(int start, int endExclusive, int type) {
        addToken(a, start, endExclusive - 1, type, start + delta);
    }

    private boolean isBlank(int begin, int end) {
        for (int i = begin; i < end; i++) {
            if (!Character.isWhitespace(a[i])) return false;
        }
        return true;
    }

    private static boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '@';
    }

    private static boolean isSpecial(char c) {
        return c == '%' || c == '\\' || c == '$' || c == '{' || c == '}' || c == '['
                || c == ']' || c == '&' || c == '~' || c == ' ' || c == '\t';
    }

    @Override
    public String[] getLineCommentStartAndEnd(int languageIndex) {
        return new String[] {"%", null};
    }

    @Override
    public javax.swing.Action getInsertBreakAction() {
        return LatexIndenter.INSERT_BREAK; // environment-aware auto-indent on Enter
    }

    @Override
    public boolean getMarkOccurrencesOfTokenType(int type) {
        return false;
    }

    @Override
    public boolean isIdentifierChar(int languageIndex, char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_';
    }
}
