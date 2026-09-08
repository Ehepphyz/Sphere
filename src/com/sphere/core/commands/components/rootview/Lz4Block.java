package com.sphere.components.rootview;

import java.io.IOException;

/**
 * LZ4 block decompression.
 *
 * The block format is small enough to carry here, and ROOT writes LZ4 often
 * enough that refusing it would leave many files unreadable without the engine.
 * Zstandard, which recent ROOT prefers, is another matter and is left to it.
 */
public final class Lz4Block {

    private Lz4Block() {
    }

    /**
     * Decodes one LZ4 block. Returns the number of bytes written.
     *
     * The format alternates a token, a run of literals, then a match copied from
     * earlier in the output. The match may overlap what is being written, so the
     * copy has to go byte by byte rather than through arraycopy.
     */
    public static int decode(byte[] input, int inputOffset, int inputLength,
                             byte[] output, int outputOffset, int maxOutput)
            throws IOException {
        int in = inputOffset;
        final int inEnd = inputOffset + inputLength;
        int out = outputOffset;
        final int outEnd = outputOffset + maxOutput;

        if (inputLength <= 0) {
            return 0;
        }

        while (in < inEnd) {
            final int token = input[in++] & 0xFF;

            int literals = token >>> 4;
            if (literals == 15) {
                int more;
                do {
                    if (in >= inEnd) {
                        throw new IOException("LZ4 block ends inside a literal length");
                    }
                    more = input[in++] & 0xFF;
                    literals += more;
                } while (more == 255);
            }

            if (in + literals > inEnd || out + literals > outEnd) {
                throw new IOException("LZ4 literal run runs past the end");
            }
            System.arraycopy(input, in, output, out, literals);
            in += literals;
            out += literals;

            // The last sequence of a block is literals only, with no match.
            if (in >= inEnd) {
                break;
            }
            if (in + 2 > inEnd) {
                throw new IOException("LZ4 block ends inside a match offset");
            }
            final int offset = (input[in] & 0xFF) | ((input[in + 1] & 0xFF) << 8);
            in += 2;
            if (offset <= 0 || out - offset < outputOffset) {
                throw new IOException("LZ4 match points outside the block");
            }

            int matchLength = token & 0x0F;
            if (matchLength == 15) {
                int more;
                do {
                    if (in >= inEnd) {
                        throw new IOException("LZ4 block ends inside a match length");
                    }
                    more = input[in++] & 0xFF;
                    matchLength += more;
                } while (more == 255);
            }
            matchLength += 4; // the minimum match, not stored

            if (out + matchLength > outEnd) {
                throw new IOException("LZ4 match runs past the end");
            }
            // Overlapping by design: a short offset repeats a pattern forward.
            int from = out - offset;
            for (int i = 0; i < matchLength; i++) {
                output[out++] = output[from++];
            }
        }
        return out - outputOffset;
    }
}
