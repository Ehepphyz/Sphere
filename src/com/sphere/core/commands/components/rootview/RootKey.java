package com.sphere.components.rootview;

import java.time.LocalDateTime;

/** One TKey record: where an object sits, how big it is, and what class it is. */
public final class RootKey {

    public long offset;
    public int nbytes;
    public short version;
    public int objlen;
    public int datime;
    public short keylen;
    public short cycle;
    public long seekKey;
    public long seekPdir;
    public String className = "";
    public String name = "";
    public String title = "";

    /** Where the object's bytes start, right after the key header. */
    public long payloadAt() {
        return seekKey + keylen;
    }

    /** True when the stored bytes are fewer than the object needs. */
    public boolean compressed() {
        return nbytes - keylen != objlen;
    }

    public int storedBytes() {
        return nbytes - keylen;
    }

    public double compressionRatio() {
        final int stored = storedBytes();
        return stored <= 0 ? 1.0 : (double) objlen / stored;
    }

    /**
     * ROOT packs a date into 32 bits: year since 1995 in the top 6, then month,
     * day, hour, minute and second.
     */
    public LocalDateTime written() {
        try {
            final int year = ((datime >>> 26) & 0x3F) + 1995;
            final int month = (datime >>> 22) & 0x0F;
            final int day = (datime >>> 17) & 0x1F;
            final int hour = (datime >>> 12) & 0x1F;
            final int minute = (datime >>> 6) & 0x3F;
            final int second = datime & 0x3F;
            if (month < 1 || month > 12 || day < 1 || day > 31) {
                return null;
            }
            return LocalDateTime.of(year, month, day,
                                    Math.min(hour, 23), Math.min(minute, 59),
                                    Math.min(second, 59));
        } catch (RuntimeException notADate) {
            return null;
        }
    }

    @Override
    public String toString() {
        return className + " " + name + ";" + cycle;
    }
}
