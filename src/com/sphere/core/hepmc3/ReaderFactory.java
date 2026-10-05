package com.sphere.core.hepmc3;

import com.sphere.core.hepmc3.cxx.CFiles;
import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.CInput;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * deduce_reader: the right reader for a file or a stream, from what it
 * starts with. Asciiv3, HepMC2 IO_GenEvent, LHEF, HEPEVT, protobuf ("hmpb"),
 * ROOT ("root"), compressed (gzip/zlib, zstd) inputs.
 */
public final class ReaderFactory {

    private ReaderFactory() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /** What the first lines of an input say it is (HepMC3's InputInfo). */
    public static final class InputInfo {
        public final List<String> head = new ArrayList<>();
        public boolean remote;
        public boolean pipe;
        public boolean error;
        public boolean init;
        public boolean root;
        public boolean protobuf;
        public boolean asciiv3;
        public boolean iogenevent;
        public boolean lhef;
        public boolean hepevt;
        public Reader reader;

        public InputInfo() {
        }

        public InputInfo(String filename) {
            if (filename.contains("http://") || filename.contains("https://") || filename.contains("root://")
                    || filename.contains("gsidcap://")) {
                remote = true;
            }
            if (!remote) {
                final Path p = CFiles.path(filename);
                if (!Files.exists(p) || !(Files.isRegularFile(p) || Files.isSymbolicLink(p))) {
                    Setup.error(100, "deduce_reader: file " + filename + " does not exist or is not a regular file/FIFO/link");
                    reader = null;
                    error = true;
                    return;
                }
                try (CInput file = new CInput(Files.newInputStream(p))) {
                    String line;
                    int nonempty = 0;
                    while ((line = file.getline()) != null && nonempty < 3) {
                        if (line.isEmpty()) continue;
                        nonempty++;
                        head.add(line);
                    }
                } catch (IOException | RuntimeException e) {
                    Setup.error(100, "deduce_reader could not open file for testing HepMC version: " + filename);
                    reader = null;
                    error = true;
                    return;
                }
            }
            head.add("");
            head.add("");
            classify();
            init = true;
        }

        /** Sets the flags from the first lines. */
        public void classify() {
            final String h0 = head.get(0);
            final String h1 = head.size() > 1 ? head.get(1) : "";
            if (h0.startsWith("root")) root = true;
            if (h0.startsWith("hmpb")) protobuf = true;
            if (h0.startsWith("HepMC::Version") && h1.startsWith("HepMC::Asciiv3")) asciiv3 = true;
            if (h0.startsWith("HepMC::Version") && h1.startsWith("HepMC::IO_GenEvent")) iogenevent = true;
            if (h0.startsWith("<LesHouchesEvents")) lhef = true;
            final CIStream stE = new CIStream(h0);
            boolean isHepevt;
            while (true) {
                final int attr = stE.nextChar();
                if (stE.fail()) {
                    isHepevt = false;
                    break;
                }
                if (attr == ' ') continue;
                if (attr != 'E') {
                    isHepevt = false;
                    break;
                }
                stE.nextInt();
                if (!stE.fail()) stE.nextInt();
                isHepevt = !stE.fail();
                break;
            }
            if (isHepevt) hepevt = true;
        }

        /** The built-in reader of the format found, on a file. */
        public Reader nativeReader(Path filename) {
            if (asciiv3) {
                if (Setup.debugging(10)) Setup.debug(10, "Attempt ReaderAscii");
                return new ReaderAscii(filename);
            }
            if (iogenevent) {
                if (Setup.debugging(10)) Setup.debug(10, "Attempt ReaderAsciiHepMC2");
                return new ReaderAsciiHepMC2(filename);
            }
            if (lhef) {
                if (Setup.debugging(10)) Setup.debug(10, "Attempt ReaderLHEF");
                return new ReaderLHEF(filename);
            }
            if (hepevt) {
                if (Setup.debugging(10)) Setup.debug(10, "Attempt ReaderHEPEVT");
                return new ReaderHEPEVT(filename);
            }
            if (Setup.debugging(10)) Setup.debug(10, "deduce_reader: all attempts failed");
            return null;
        }

        /** The built-in reader of the format found, on a stream. */
        public Reader nativeReader(CInput stream) {
            if (asciiv3) return new ReaderAscii(stream);
            if (iogenevent) return new ReaderAsciiHepMC2(stream);
            if (lhef) return new ReaderLHEF(stream);
            if (hepevt) return new ReaderHEPEVT(stream);
            if (Setup.debugging(10)) Setup.debug(10, "deduce_reader: all attempts failed");
            return null;
        }
    }

    /** The reader for a file, or null when nothing fits (a message says why). */
    public static Reader deduceReader(String filename) {
        final InputInfo input = new InputInfo(filename);
        if (input.init && !input.error && input.reader != null) return input.reader;
        if (input.error) return null;
        if (input.root || input.remote) {
            if (Setup.debugging(10)) Setup.debug(10, "deduce_reader: Attempt ReaderRootTree for " + filename);
            return ReaderRootTree.open(filename);
        }
        if (input.protobuf) {
            if (Setup.debugging(10)) Setup.debug(10, "deduce_reader: Attempt ProtobufIO for " + filename);
            return new Readerprotobuf(CFiles.path(filename));
        }
        if (Setup.debugging(10)) Setup.debug(10, "Attempt ReaderGZ for " + filename);
        final Compression det = Compression.detect(CFiles.path(filename));
        if (det != Compression.PLAINTEXT) {
            if (Setup.debugging(10)) Setup.debug(10, "Detected supported compression: " + det.label());
            try {
                return deduceReader(Compression.open(CFiles.path(filename)));
            } catch (IOException e) {
                Setup.error(100, "deduce_reader: " + e.getMessage());
                return null;
            }
        }
        return input.nativeReader(CFiles.path(filename));
    }

    public static Reader deduceReader(Path file) {
        return deduceReader(file.toString());
    }

    /**
     * The reader for a stream: its first 100 bytes looked at, then given
     * back. A stream shorter than that is refused, as in HepMC3.
     */
    public static Reader deduceReader(InputStream raw) {
        if (raw == null) {
            Setup.warning(100, "Input stream is too short or invalid.");
            return null;
        }
        final InputStream stream = raw.markSupported() ? raw : new BufferedInputStream(raw, 1 << 16);
        final int rawHeaderSize = 100;
        final byte[] rawHeader;
        try {
            stream.mark(rawHeaderSize + 8);
            rawHeader = stream.readNBytes(rawHeaderSize);
            stream.reset();
        } catch (IOException e) {
            Setup.warning(100, "Input stream is too short or invalid.");
            return null;
        }
        if (rawHeader.length < rawHeaderSize) {
            Setup.warning(100, "Input stream is too short or invalid.");
            return null;
        }
        final List<String> head = new ArrayList<>();
        head.add("");
        final StringBuilder cur = new StringBuilder();
        for (int i = 0; i < rawHeaderSize; ++i) {
            final char c = (char) (rawHeader[i] & 0xFF);
            if (c == '\0') break;
            if (c == '\n') {
                if (cur.length() > 0) {
                    head.set(head.size() - 1, cur.toString());
                    cur.setLength(0);
                    head.add("");
                }
            } else {
                cur.append(c);
            }
        }
        head.set(head.size() - 1, cur.toString());
        head.add("");
        final InputInfo input = new InputInfo();
        input.head.addAll(head);
        input.classify();
        if (input.protobuf) return new Readerprotobuf(stream);
        return input.nativeReader(new CInput(stream));
    }

    /** The first bytes of a file as text, for a quick look. */
    public static String peek(Path file, int bytes) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return new String(in.readNBytes(bytes), StandardCharsets.ISO_8859_1);
        }
    }
}
