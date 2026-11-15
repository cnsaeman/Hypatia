package atlantis.hypatia.style;

/**
 * A style problem: {@code explanation} is shown when the cursor is in [start, end),
 * which is underlined in purple. Offsets are character positions in the string given to
 * {@link StylePlugin#checkDocument(String)} (the whole source of the file).
 */
public record Problem(String explanation, int start, int end) {}
