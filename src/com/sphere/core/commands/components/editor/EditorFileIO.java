package com.sphere.components.editor;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Reading and writing an editor buffer. Reading never fails on encoding alone,
 * and writing never leaves a half-written file behind.
 */
public final class EditorFileIO {

    /** Above this a file is refused rather than pulled whole into the heap. */
    public static final long MAX_OPEN_BYTES = 64L * 1024 * 1024;

    private EditorFileIO() {
    }

    /** What a load produced, including the charset it had to settle on. */
    public static final class Loaded {
        public final String text;
        public final Charset charset;
        public final boolean fellBack;

        Loaded(String text, Charset charset, boolean fellBack) {
            this.text = text;
            this.charset = charset;
            this.fellBack = fellBack;
        }
    }

    public static final class TooLargeException extends IOException {
        public final long size;

        TooLargeException(long size) {
            super("File is " + (size / (1024 * 1024)) + " MiB, over the "
                  + (MAX_OPEN_BYTES / (1024 * 1024)) + " MiB editor limit.");
            this.size = size;
        }
    }

    /**
     * Decodes as UTF-8 and falls back to ISO-8859-1 rather than throwing. Reading
     * strictly as UTF-8 used to leave the tab blank on any Latin-1 source.
     */
    public static Loaded read(File file) throws IOException {
        Path path = file.toPath();
        long size = Files.size(path);
        if (size > MAX_OPEN_BYTES) {
            throw new TooLargeException(size);
        }

        byte[] bytes = Files.readAllBytes(path);

        // A BOM settles the question on its own.
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new Loaded(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8),
                              StandardCharsets.UTF_8, false);
        }

        CharsetDecoder strict = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer decoded = strict.decode(ByteBuffer.wrap(bytes));
            return new Loaded(decoded.toString(), StandardCharsets.UTF_8, false);
        } catch (CharacterCodingException notUtf8) {
            return new Loaded(new String(bytes, StandardCharsets.ISO_8859_1),
                              StandardCharsets.ISO_8859_1, true);
        }
    }

    /**
     * Writes through a temporary file in the same directory, then moves it into
     * place. A truncating write left the user's file destroyed if anything failed
     * between the truncate and the last byte.
     */
    public static void write(File file, String text, Charset charset) throws IOException {
        Path target = file.toPath();
        Path directory = target.toAbsolutePath().getParent();
        if (directory == null) {
            directory = Path.of(".");
        }
        Files.createDirectories(directory);

        Path temp = Files.createTempFile(directory, "." + file.getName() + ".", ".tmp");
        try {
            Files.writeString(temp, text, charset == null ? StandardCharsets.UTF_8 : charset);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                           StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException notAtomic) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
