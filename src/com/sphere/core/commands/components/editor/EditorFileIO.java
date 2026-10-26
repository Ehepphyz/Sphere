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
        /** The file began with a UTF-8 byte order mark. */
        public final boolean bom;

        Loaded(String text, Charset charset, boolean fellBack) {
            this(text, charset, fellBack, false);
        }

        Loaded(String text, Charset charset, boolean fellBack, boolean bom) {
            this.text = text;
            this.charset = charset;
            this.fellBack = fellBack;
            this.bom = bom;
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
                              StandardCharsets.UTF_8, false, true);
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
     *
     * The move replaces whatever sits at the path, so two things are carried
     * over by hand. A symbolic link is written through, to the file it names:
     * moving onto the link put a plain file in its place and left the real one
     * unchanged. And the permissions of the old file are given to the new one:
     * a temporary file is created 0600 on Unix, so a saved script lost its
     * execute bit and became unreadable to the group.
     */
    public static void write(File file, String text, Charset charset) throws IOException {
        write(file, text, charset, false);
    }

    /**
     * The same, starting with a UTF-8 byte order mark when the file had one.
     * The load set it aside and the save never wrote it back, so opening and
     * saving a file changed it: MSVC, for one, then read its accents in the
     * local code page.
     */
    public static void write(File file, String text, Charset charset, boolean bom) throws IOException {
        final Charset encoding = charset == null ? StandardCharsets.UTF_8 : charset;
        Path target = file.toPath();
        if (Files.isSymbolicLink(target) && Files.exists(target)) {
            target = target.toRealPath();
        }
        Path directory = target.toAbsolutePath().getParent();
        if (directory == null) {
            directory = Path.of(".");
        }
        Files.createDirectories(directory);

        Path temp = Files.createTempFile(directory, "." + target.getFileName() + ".", ".tmp");
        try {
            if (bom && StandardCharsets.UTF_8.equals(encoding)) {
                final byte[] body = text.getBytes(StandardCharsets.UTF_8);
                final byte[] all = new byte[body.length + 3];
                all[0] = (byte) 0xEF;
                all[1] = (byte) 0xBB;
                all[2] = (byte) 0xBF;
                System.arraycopy(body, 0, all, 3, body.length);
                Files.write(temp, all);
            } else {
                Files.writeString(temp, text, encoding);
            }
            keepPermissions(target, temp);
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

    /** Gives the replacement the Unix permissions of the file it replaces; nothing on Windows. */
    private static void keepPermissions(Path original, Path replacement) {
        if (!Files.exists(original)
                || !original.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        try {
            Files.setPosixFilePermissions(replacement, Files.getPosixFilePermissions(original));
        } catch (IOException | UnsupportedOperationException notCarried) {
            // The file is still saved, with the permissions of a new file.
        }
    }
}
