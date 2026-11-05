package com.sphere.components.spdf.cos;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The standard security handler (7.6.4): RC4 40 to 128 bits, AES-128 and
 * AES-256 (revisions 2 to 6). A document protected only by an owner password
 * opens with the empty user password; another one needs its password (user or
 * owner). Strings and streams are decrypted as their objects load, so the rest
 * of spdf never sees ciphertext; a document is saved without encryption.
 */
public final class PdfCrypt {

    private static final byte[] PAD = {
        (byte) 0x28, (byte) 0xBF, (byte) 0x4E, (byte) 0x5E, (byte) 0x4E, (byte) 0x75, (byte) 0x8A, (byte) 0x41,
        (byte) 0x64, (byte) 0x00, (byte) 0x4E, (byte) 0x56, (byte) 0xFF, (byte) 0xFA, (byte) 0x01, (byte) 0x08,
        (byte) 0x2E, (byte) 0x2E, (byte) 0x00, (byte) 0xB6, (byte) 0xD0, (byte) 0x68, (byte) 0x3E, (byte) 0x80,
        (byte) 0x2F, (byte) 0x0C, (byte) 0xA9, (byte) 0xFE, (byte) 0x64, (byte) 0x53, (byte) 0x69, (byte) 0x7A };

    private final byte[] key;
    private final int revision;
    private final boolean aesStrings;
    private final boolean aesStreams;
    private final boolean identityStrings;
    private final boolean identityStreams;
    public final boolean encryptMetadata;
    public final int permissions;

    /** Thrown when the password given does not open the document. */
    public static final class PasswordException extends RuntimeException {
        public PasswordException(String m) {
            super(m);
        }
    }

    public PdfCrypt(PdfDict enc, byte[] id0, String password) {
        String filter = enc.getName("Filter", "Standard");
        if (!"Standard".equals(filter)) {
            throw new PasswordException("Security handler " + filter + " is not supported");
        }
        int v = enc.getInt("V", 0);
        revision = enc.getInt("R", 2);
        permissions = enc.getInt("P", -1);
        encryptMetadata = enc.getBool("EncryptMetadata", true);
        int keyBytes = 5;
        if (v == 2 || v == 3) {
            keyBytes = clamp(enc.getInt("Length", 40) / 8);
        }
        boolean aesS = false;
        boolean aesT = false;
        boolean idS = false;
        boolean idT = false;
        if (v >= 4) {
            String strf = enc.getName("StrF", "Identity");
            String stmf = enc.getName("StmF", "Identity");
            idS = strf.equals("Identity");
            idT = stmf.equals("Identity");
            keyBytes = 16;
            PdfDict cf = enc.getDict("CF");
            if (cf != null) {
                PdfDict s = idS ? null : cf.getDict(strf);
                PdfDict t = idT ? null : cf.getDict(stmf);
                if (s != null) {
                    String cfm = s.getName("CFM", "None");
                    aesS = cfm.startsWith("AES");
                    idS = cfm.equals("None");
                }
                if (t != null) {
                    String cfm = t.getName("CFM", "None");
                    aesT = cfm.startsWith("AES");
                    idT = cfm.equals("None");
                }
                PdfDict any = s != null ? s : t;
                if (any != null && any.has("Length") && revision < 5) {
                    int l = any.getInt("Length", 16);
                    keyBytes = clamp(l <= 32 ? l : l / 8);
                }
            }
        }
        aesStrings = aesS;
        aesStreams = aesT;
        identityStrings = idS;
        identityStreams = idT;

        byte[] o = bytes(enc.get("O"));
        byte[] u = bytes(enc.get("U"));
        if (revision >= 5) {
            byte[] pw = password == null ? new byte[0] : password.getBytes(StandardCharsets.UTF_8);
            byte[] k = aes256Key(pw, o, u, bytes(enc.get("OE")), bytes(enc.get("UE")));
            if (k == null) {
                throw new PasswordException("Password required");
            }
            key = k;
            return;
        }
        byte[] pw = password == null ? new byte[0] : password.getBytes(StandardCharsets.ISO_8859_1);
        byte[] k = computeKey(pw, o, id0, keyBytes);
        if (k == null || !validUser(k, u, id0)) {
            // the owner password opens it too: the user password is recovered from /O
            byte[] userPw = ownerToUser(pw, o, keyBytes);
            k = computeKey(userPw, o, id0, keyBytes);
            if (k == null || !validUser(k, u, id0)) {
                throw new PasswordException("Password required");
            }
        }
        key = k;
    }

    private static int clamp(int n) {
        return Math.max(5, Math.min(16, n));
    }

    private static byte[] bytes(PdfObject o) {
        return o instanceof PdfString s ? s.getBytes() : new byte[0];
    }

    /** Algorithm 2: the file key from a user password. */
    private byte[] computeKey(byte[] pw, byte[] o, byte[] id0, int n) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            md5.update(pad(pw));
            md5.update(Arrays.copyOf(o, 32));
            md5.update(new byte[] { (byte) permissions, (byte) (permissions >> 8), (byte) (permissions >> 16),
                                    (byte) (permissions >> 24) });
            md5.update(id0);
            if (revision >= 4 && !encryptMetadata) {
                md5.update(new byte[] { (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff });
            }
            byte[] h = md5.digest();
            if (revision >= 3) {
                for (int i = 0; i < 50; i++) {
                    md5.reset();
                    md5.update(h, 0, n);
                    h = md5.digest();
                }
            }
            return Arrays.copyOf(h, n);
        } catch (Exception e) {
            return null;
        }
    }

    /** Algorithms 4 and 5: does the key reproduce /U? */
    private boolean validUser(byte[] k, byte[] uStored, byte[] id0) {
        try {
            if (revision == 2) {
                return Arrays.equals(rc4(k, PAD), Arrays.copyOf(uStored, 32));
            }
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            md5.update(PAD);
            md5.update(id0);
            byte[] h = rc4(k, md5.digest());
            for (int i = 1; i <= 19; i++) {
                h = rc4(xor(k, i), h);
            }
            return Arrays.equals(Arrays.copyOf(h, 16), Arrays.copyOf(uStored, 16));
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] xor(byte[] k, int v) {
        byte[] out = new byte[k.length];
        for (int j = 0; j < k.length; j++) {
            out[j] = (byte) (k[j] ^ v);
        }
        return out;
    }

    /** Algorithm 7's first steps: the user password /O hides for an owner password. */
    private byte[] ownerToUser(byte[] ownerPw, byte[] o, int n) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] h = md5.digest(pad(ownerPw));
            if (revision >= 3) {
                for (int i = 0; i < 50; i++) {
                    h = md5.digest(h);
                }
            }
            byte[] rk = Arrays.copyOf(h, n);
            byte[] userPw = Arrays.copyOf(o, 32);
            if (revision == 2) {
                return rc4(rk, userPw);
            }
            for (int i = 19; i >= 0; i--) {
                userPw = rc4(xor(rk, i), userPw);
            }
            return userPw;
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static byte[] pad(byte[] pw) {
        byte[] p = new byte[32];
        int n = Math.min(32, pw.length);
        System.arraycopy(pw, 0, p, 0, n);
        System.arraycopy(PAD, 0, p, n, 32 - n);
        return p;
    }

    /* ---------------- AES-256 (revisions 5 and 6) ---------------- */

    private byte[] aes256Key(byte[] pw, byte[] o, byte[] u, byte[] oe, byte[] ue) {
        if (pw.length > 127) {
            pw = Arrays.copyOf(pw, 127);
        }
        try {
            if (u.length >= 48) {
                byte[] hash = hash6(pw, Arrays.copyOfRange(u, 32, 40), null);
                if (Arrays.equals(hash, Arrays.copyOf(u, 32))) {
                    return aesNoPad(hash6(pw, Arrays.copyOfRange(u, 40, 48), null), ue);
                }
            }
            if (o.length >= 48 && u.length >= 48) {
                byte[] u48 = Arrays.copyOf(u, 48);
                byte[] hash = hash6(pw, Arrays.copyOfRange(o, 32, 40), u48);
                if (Arrays.equals(hash, Arrays.copyOf(o, 32))) {
                    return aesNoPad(hash6(pw, Arrays.copyOfRange(o, 40, 48), u48), oe);
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private static byte[] aesNoPad(byte[] k, byte[] data) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(k, "AES"), new IvParameterSpec(new byte[16]));
        return c.doFinal(Arrays.copyOf(data, 32));
    }

    /** Algorithm 2.A (revision 5: one SHA-256) and 2.B (revision 6). */
    private byte[] hash6(byte[] pw, byte[] salt, byte[] udata) throws Exception {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        sha256.update(pw);
        sha256.update(salt);
        if (udata != null) {
            sha256.update(udata);
        }
        byte[] k = sha256.digest();
        if (revision < 6) {
            return k;
        }
        int ulen = udata == null ? 0 : udata.length;
        for (int round = 0; ; round++) {
            int unit = pw.length + k.length + ulen;
            byte[] k1 = new byte[unit * 64];
            for (int i = 0; i < 64; i++) {
                int off = i * unit;
                System.arraycopy(pw, 0, k1, off, pw.length);
                System.arraycopy(k, 0, k1, off + pw.length, k.length);
                if (udata != null) {
                    System.arraycopy(udata, 0, k1, off + pw.length + k.length, ulen);
                }
            }
            Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Arrays.copyOf(k, 16), "AES"),
                   new IvParameterSpec(Arrays.copyOfRange(k, 16, 32)));
            byte[] e = c.doFinal(k1);
            int sum = 0;
            for (int i = 0; i < 16; i++) {
                sum += e[i] & 0xff;
            }
            String alg = switch (sum % 3) {
                case 0 -> "SHA-256";
                case 1 -> "SHA-384";
                default -> "SHA-512";
            };
            k = MessageDigest.getInstance(alg).digest(e);
            if (round >= 63 && (e[e.length - 1] & 0xff) <= round - 31) {
                break;
            }
        }
        return Arrays.copyOf(k, 32);
    }

    /* ---------------- per-object decryption ---------------- */

    private byte[] objectKey(int num, int gen, boolean aes) throws Exception {
        if (revision >= 5) {
            return key;
        }
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        md5.update(key);
        md5.update(new byte[] { (byte) num, (byte) (num >> 8), (byte) (num >> 16), (byte) gen, (byte) (gen >> 8) });
        if (aes) {
            md5.update(new byte[] { 0x73, 0x41, 0x6C, 0x54 });
        }
        return Arrays.copyOf(md5.digest(), Math.min(16, key.length + 5));
    }

    public byte[] decryptString(byte[] data, int num, int gen) {
        return identityStrings ? data : decrypt(data, num, gen, aesStrings);
    }

    public byte[] decryptStream(byte[] data, int num, int gen) {
        return identityStreams ? data : decrypt(data, num, gen, aesStreams);
    }

    private byte[] decrypt(byte[] data, int num, int gen, boolean aes) {
        try {
            byte[] k = objectKey(num, gen, aes);
            if (!aes) {
                return rc4(k, data);
            }
            if (data.length < 32) {
                return new byte[0];
            }
            int len = data.length - 16;
            len -= len % 16;
            try {
                Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
                c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(k, "AES"), new IvParameterSpec(data, 0, 16));
                return c.doFinal(data, 16, len);
            } catch (Exception badPadding) {
                Cipher raw = Cipher.getInstance("AES/CBC/NoPadding");
                raw.init(Cipher.DECRYPT_MODE, new SecretKeySpec(k, "AES"), new IvParameterSpec(data, 0, 16));
                return raw.doFinal(data, 16, len);
            }
        } catch (Exception e) {
            return data;
        }
    }

    /** Decrypts every string inside a direct object, in place. */
    public void decryptStrings(PdfObject o, int num, int gen, int depth) {
        if (depth > 64) {
            return;
        }
        if (o instanceof PdfString s) {
            s.setBytes(decryptString(s.getBytes(), num, gen));
        } else if (o instanceof PdfArray a) {
            for (PdfObject x : a.rawList()) {
                decryptStrings(x, num, gen, depth + 1);
            }
        } else if (o instanceof PdfDict d) {
            for (var e : d.entries()) {
                decryptStrings(e.getValue(), num, gen, depth + 1);
            }
        }
    }

    public static byte[] rc4(byte[] k, byte[] data) {
        int[] s = new int[256];
        for (int i = 0; i < 256; i++) {
            s[i] = i;
        }
        int j = 0;
        for (int i = 0; i < 256; i++) {
            j = (j + s[i] + (k[i % k.length] & 0xff)) & 0xff;
            int t = s[i];
            s[i] = s[j];
            s[j] = t;
        }
        byte[] out = new byte[data.length];
        int i = 0;
        j = 0;
        for (int n = 0; n < data.length; n++) {
            i = (i + 1) & 0xff;
            j = (j + s[i]) & 0xff;
            int t = s[i];
            s[i] = s[j];
            s[j] = t;
            out[n] = (byte) (data[n] ^ s[(s[i] + s[j]) & 0xff]);
        }
        return out;
    }
}
