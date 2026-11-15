package atlantis.hypatia.tex;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads text files as UTF-8, falling back to Latin-1 for older files. */
public final class TextFiles {

    private TextFiles() {}

    public static String read(Path p) throws IOException {
        byte[] bytes = Files.readAllBytes(p);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    /** Like {@link #read} but returns null instead of throwing. */
    public static String readOrNull(Path p) {
        try {
            return Files.isRegularFile(p) ? read(p) : null;
        } catch (IOException e) {
            return null;
        }
    }
}
