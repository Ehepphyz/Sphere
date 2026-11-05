package com.sphere.components.spdf.font;

import java.nio.charset.Charset;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;

/**
 * Glyph names and the simple-font encodings (Annex D): Standard, WinAnsi,
 * MacRoman, Symbol. Names map to Unicode through a core of the Adobe Glyph
 * List, the uniXXXX/uXXXXXX forms, ligatures written f_f_i, suffixes (a.sc),
 * and accented letters composed from their parts (Ccaron = C + caron), which
 * covers the Latin names without carrying the whole list.
 */
public final class GlyphNames {

    private GlyphNames() {
    }

    private static final Map<String, String> NAME_TO_UNI = new HashMap<>(1200);
    private static final Map<Integer, String> UNI_TO_NAME = new HashMap<>(1200);

    public static final String[] STANDARD = new String[256];
    public static final String[] WIN_ANSI = new String[256];
    public static final String[] MAC_ROMAN = new String[256];
    /** The Symbol font's codes, as Unicode (its glyph names are not needed). */
    public static final char[] SYMBOL_UNICODE = new char[256];

    private static final String CORE =
        // ASCII
        "space 0020 exclam 0021 quotedbl 0022 numbersign 0023 dollar 0024 percent 0025 ampersand 0026 "
        + "quotesingle 0027 parenleft 0028 parenright 0029 asterisk 002A plus 002B comma 002C hyphen 002D "
        + "period 002E slash 002F zero 0030 one 0031 two 0032 three 0033 four 0034 five 0035 six 0036 seven 0037 "
        + "eight 0038 nine 0039 colon 003A semicolon 003B less 003C equal 003D greater 003E question 003F at 0040 "
        + "bracketleft 005B backslash 005C bracketright 005D asciicircum 005E underscore 005F grave 0060 "
        + "braceleft 007B bar 007C braceright 007D asciitilde 007E "
        // Latin-1
        + "exclamdown 00A1 cent 00A2 sterling 00A3 currency 00A4 yen 00A5 brokenbar 00A6 section 00A7 "
        + "dieresis 00A8 copyright 00A9 ordfeminine 00AA guillemotleft 00AB logicalnot 00AC registered 00AE "
        + "macron 00AF degree 00B0 plusminus 00B1 twosuperior 00B2 threesuperior 00B3 acute 00B4 mu 00B5 "
        + "paragraph 00B6 periodcentered 00B7 cedilla 00B8 onesuperior 00B9 ordmasculine 00BA "
        + "guillemotright 00BB onequarter 00BC onehalf 00BD threequarters 00BE questiondown 00BF AE 00C6 "
        + "Eth 00D0 multiply 00D7 Oslash 00D8 Thorn 00DE germandbls 00DF ae 00E6 eth 00F0 divide 00F7 "
        + "oslash 00F8 thorn 00FE "
        // WinAnsi and Standard extras
        + "Euro 20AC quotesinglbase 201A florin 0192 quotedblbase 201E ellipsis 2026 dagger 2020 daggerdbl 2021 "
        + "circumflex 02C6 perthousand 2030 guilsinglleft 2039 OE 0152 quoteleft 2018 quoteright 2019 "
        + "quotedblleft 201C quotedblright 201D bullet 2022 endash 2013 emdash 2014 tilde 02DC trademark 2122 "
        + "guilsinglright 203A oe 0153 fraction 2044 fi FB01 fl FB02 ff FB00 ffi FB03 ffl FB04 dotlessi 0131 "
        + "dotlessj 0237 Lslash 0141 lslash 0142 breve 02D8 dotaccent 02D9 ring 02DA ogonek 02DB hungarumlaut 02DD "
        + "caron 02C7 Dcroat 0110 dcroat 0111 Hbar 0126 hbar 0127 IJ 0132 ij 0133 kgreenlandic 0138 Ldot 013F "
        + "ldot 0140 napostrophe 0149 Eng 014A eng 014B Tbar 0166 tbar 0167 longs 017F nbspace 00A0 sfthyphen 00AD "
        + "minus 2212 periodcentered 00B7 onedotenleader 2024 twodotenleader 2025 figuredash 2012 "
        + "quotereversed 201B exclamdbl 203C minute 2032 second 2033 prime 2032 primerev 2035 "
        // MacRoman and math
        + "notequal 2260 infinity 221E lessequal 2264 greaterequal 2265 partialdiff 2202 summation 2211 "
        + "product 220F integral 222B radical 221A approxequal 2248 Delta 2206 lozenge 25CA apple F8FF "
        + "Omega 2126 franc 20A3 lira 20A4 peseta 20A7 estimated 212E "
        + "universal 2200 existential 2203 suchthat 220B asteriskmath 2217 congruent 2245 therefore 2234 "
        + "perpendicular 22A5 similar 223C equivalence 2261 proportional 221D gradient 2207 element 2208 "
        + "notelement 2209 intersection 2229 union 222A propersubset 2282 propersuperset 2283 reflexsubset 2286 "
        + "reflexsuperset 2287 notsubset 2284 emptyset 2205 logicaland 2227 logicalor 2228 angle 2220 "
        + "angleleft 2329 angleright 232A aleph 2135 Ifraktur 2111 Rfraktur 211C weierstrass 2118 "
        + "circlemultiply 2297 circleplus 2295 dotmath 22C5 arrowleft 2190 arrowup 2191 arrowright 2192 "
        + "arrowdown 2193 arrowboth 2194 arrowupdn 2195 arrowdblleft 21D0 arrowdblup 21D1 arrowdblright 21D2 "
        + "arrowdbldown 21D3 arrowdblboth 21D4 carriagereturn 21B5 club 2663 diamond 2666 heart 2665 spade 2660 "
        + "copyrightserif 00A9 registerserif 00AE trademarkserif 2122 copyrightsans 00A9 registersans 00AE "
        + "trademarksans 2122 parenlefttp 239B parenleftex 239C parenleftbt 239D bracketlefttp 23A1 "
        + "bracketleftex 23A2 bracketleftbt 23A3 bracelefttp 23A7 braceleftmid 23A8 braceleftbt 23A9 braceex 23AA "
        + "integraltp 2320 integralex 23AE integralbt 2321 parenrighttp 239E parenrightex 239F parenrightbt 23A0 "
        + "bracketrighttp 23A4 bracketrightex 23A5 bracketrightbt 23A6 bracerighttp 23AB bracerightmid 23AC "
        + "bracerightbt 23AD arrowvertex 23D0 arrowhorizex 23AF radicalex F8E5 openbullet 25E6 filledbox 25A0 "
        + "filledrect 25AC triagup 25B2 triagdn 25BC triagrt 25BA triaglf 25C4 circle 25CB invcircle 25D9 "
        + "blackcircle 25CF whitesquare 25A1 checkmark 2713 dagger 2020 house 2302 "
        // Greek
        + "Alpha 0391 Beta 0392 Gamma 0393 Epsilon 0395 Zeta 0396 Eta 0397 Theta 0398 Iota 0399 Kappa 039A "
        + "Lambda 039B Mu 039C Nu 039D Xi 039E Omicron 039F Pi 03A0 Rho 03A1 Sigma 03A3 Tau 03A4 Upsilon 03A5 "
        + "Phi 03A6 Chi 03A7 Psi 03A8 Omega 03A9 alpha 03B1 beta 03B2 gamma 03B3 delta 03B4 epsilon 03B5 "
        + "zeta 03B6 eta 03B7 theta 03B8 iota 03B9 kappa 03BA lambda 03BB nu 03BD xi 03BE omicron 03BF pi 03C0 "
        + "rho 03C1 sigma1 03C2 sigma 03C3 tau 03C4 upsilon 03C5 phi 03C6 chi 03C7 psi 03C8 omega 03C9 "
        + "theta1 03D1 Upsilon1 03D2 phi1 03D5 omega1 03D6 epsilon1 03F5 rho1 03F1 kappa1 03F0 "
        // TeX math names worth knowing
        + "summationtext 2211 summationdisplay 2211 producttext 220F productdisplay 220F integraltext 222B "
        + "integraldisplay 222B contintegraltext 222E contintegraldisplay 222E uniondisplay 22C3 uniontext 22C3 "
        + "intersectiondisplay 22C2 intersectiontext 22C2 radicalbig 221A radicalBig 221A radicalbigg 221A "
        + "radicalBigg 221A parenleftbig 0028 parenrightbig 0029 parenleftBig 0028 parenrightBig 0029 "
        + "parenleftbigg 0028 parenrightbigg 0029 bracketleftbig 005B bracketrightbig 005D braceleftbig 007B "
        + "bracerightbig 007D hatwide 0302 tildewide 0303 slashbig 002F backslashbig 005C "
        + "arrowsoutheast 2198 arrownortheast 2197 arrowsouthwest 2199 arrownorthwest 2196 lessmuch 226A "
        + "greatermuch 226B precedes 227A follows 227B lessorequalslant 2A7D greaterorequalslant 2A7E "
        + "minusplus 2213 circledot 2299 openbullet 25E6 negationslash 0338 mapsto 21A6 owner 220B "
        + "latticetop 22A4 turnstileleft 22A2 turnstileright 22A3 floorleft 230A floorright 230B ceilingleft 2308 "
        + "ceilingright 2309 bardbl 2016 nabla 2207 asteriskcentered 2217 diamondmath 22C4 star 22C6 "
        + "unionsq 2294 intersectionsq 2293 subsetsqequal 2291 supersetsqequal 2292 dagger 2020 section 00A7 "
        + "triangle 25B3 triangleinv 25BD triangleleft 25C1 triangleright 25B7 infinity 221E "
        + "visiblespace 2423 dotlessi 0131 Gamma 0393 Delta 0394 ";

    static {
        String[] t = CORE.trim().split("\\s+");
        for (int i = 0; i + 1 < t.length; i += 2) {
            int u = Integer.parseInt(t[i + 1], 16);
            NAME_TO_UNI.putIfAbsent(t[i], String.valueOf((char) u));
            UNI_TO_NAME.putIfAbsent(u, t[i]);
        }
        // the standard names win over aliases in the reverse map
        for (String n : new String[] { "space", "hyphen", "quotesingle", "grave", "Delta", "Omega", "mu" }) {
            UNI_TO_NAME.put((int) NAME_TO_UNI.get(n).charAt(0), n);
        }
        UNI_TO_NAME.put(0x2206, "Delta");
        UNI_TO_NAME.put(0x0394, "Delta");
        UNI_TO_NAME.put(0x2126, "Omega");
        UNI_TO_NAME.put(0x03A9, "Omega");
        NAME_TO_UNI.put("Delta", "Δ");
        NAME_TO_UNI.put("Omega", "Ω");
        NAME_TO_UNI.put("mu", "µ");
        NAME_TO_UNI.put("dieresis", "¨");
        // single letters and digits
        for (char c = 'A'; c <= 'Z'; c++) {
            NAME_TO_UNI.put(String.valueOf(c), String.valueOf(c));
            UNI_TO_NAME.put((int) c, String.valueOf(c));
            char l = Character.toLowerCase(c);
            NAME_TO_UNI.put(String.valueOf(l), String.valueOf(l));
            UNI_TO_NAME.put((int) l, String.valueOf(l));
        }

        // StandardEncoding
        for (int c = 32; c < 127; c++) {
            String n = UNI_TO_NAME.get(c);
            STANDARD[c] = n;
        }
        STANDARD[39] = "quoteright";
        STANDARD[96] = "quoteleft";
        String std = "161 exclamdown 162 cent 163 sterling 164 fraction 165 yen 166 florin 167 section 168 currency "
            + "169 quotesingle 170 quotedblleft 171 guillemotleft 172 guilsinglleft 173 guilsinglright 174 fi 175 fl "
            + "177 endash 178 dagger 179 daggerdbl 180 periodcentered 182 paragraph 183 bullet 184 quotesinglbase "
            + "185 quotedblbase 186 quotedblright 187 guillemotright 188 ellipsis 189 perthousand 191 questiondown "
            + "193 grave 194 acute 195 circumflex 196 tilde 197 macron 198 breve 199 dotaccent 200 dieresis 202 ring "
            + "203 cedilla 205 hungarumlaut 206 ogonek 207 caron 208 emdash 225 AE 227 ordfeminine 232 Lslash "
            + "233 Oslash 234 OE 235 ordmasculine 241 ae 245 dotlessi 248 lslash 249 oslash 250 oe 251 germandbls";
        String[] s = std.split(" ");
        for (int i = 0; i + 1 < s.length; i += 2) {
            STANDARD[Integer.parseInt(s[i])] = s[i + 1];
        }

        // WinAnsi and MacRoman from their Java charsets, named through the reverse list
        fillFromCharset(WIN_ANSI, "windows-1252");
        fillFromCharset(MAC_ROMAN, "x-MacRoman");
        WIN_ANSI[39] = "quotesingle";
        WIN_ANSI[96] = "grave";
        WIN_ANSI[160] = "space";
        WIN_ANSI[173] = "hyphen";
        for (int c : new int[] { 127, 129, 141, 143, 144, 157 }) {
            WIN_ANSI[c] = "bullet";
        }
        MAC_ROMAN[39] = "quotesingle";
        MAC_ROMAN[96] = "grave";
        MAC_ROMAN[202] = "space";

        // Symbol
        String sym = " !∀#∃%&∋()∗+,−./0123456789:;<=>?≅"
            + "ΑΒΧΔΕΦΓΗΙϑΚΛΜΝΟΠ"
            + "ΘΡΣΤΥςΩΞΨΖ[∴]⊥_"
            + "αβχδεφγηιϕκλμνοπ"
            + "θρστυϖωξψζ{|}∼";
        for (int i = 0; i < sym.length(); i++) {
            SYMBOL_UNICODE[0x20 + i] = sym.charAt(i);
        }
        String symHigh = "€ϒ′≤⁄∞ƒ♣♦♥♠↔←↑"
            + "→↓°±″≥×∝∂•÷≠≡≈…⏐"
            + "⎯↵ℵℑℜ℘⊗⊕∅∩∪⊃⊇⊄⊂⊆"
            + "∈∉∠∇®©™∏√⋅¬∧∨⇔⇐⇑"
            + "⇒⇓◊〈®©™∑⎛⎜⎝⎡⎢⎣⎧⎨"
            + "⎩⎪�〉∫⌠⎮⌡⎞⎟⎠⎤⎥⎦⎫⎬"
            + "⎭";
        for (int i = 0; i < symHigh.length(); i++) {
            SYMBOL_UNICODE[0xA0 + i] = symHigh.charAt(i);
        }
    }

    private static void fillFromCharset(String[] table, String charset) {
        try {
            Charset cs = Charset.forName(charset);
            for (int c = 32; c < 256; c++) {
                String s = new String(new byte[] { (byte) c }, cs);
                if (s.isEmpty() || s.charAt(0) == '�') {
                    continue;
                }
                table[c] = nameOf(s.charAt(0));
            }
        } catch (RuntimeException e) {
            for (int c = 32; c < 127; c++) {
                table[c] = UNI_TO_NAME.get(c);
            }
        }
    }

    /** The encoding of a simple font's /Encoding name, or null. */
    public static String[] encoding(String name) {
        if (name == null) {
            return null;
        }
        return switch (name) {
            case "WinAnsiEncoding" -> WIN_ANSI;
            case "MacRomanEncoding", "MacExpertEncoding" -> MAC_ROMAN;
            case "StandardEncoding" -> STANDARD;
            default -> null;
        };
    }

    private static final Map<String, Character> DIACRITICS = Map.ofEntries(
        Map.entry("acute", '́'), Map.entry("grave", '̀'), Map.entry("circumflex", '̂'),
        Map.entry("tilde", '̃'), Map.entry("dieresis", '̈'), Map.entry("ring", '̊'),
        Map.entry("cedilla", '̧'), Map.entry("caron", '̌'), Map.entry("breve", '̆'),
        Map.entry("macron", '̄'), Map.entry("ogonek", '̨'), Map.entry("dotaccent", '̇'),
        Map.entry("hungarumlaut", '̋'), Map.entry("commaaccent", '̦'), Map.entry("dotbelow", '̣'));

    /** The Unicode text of a glyph name, or null when the name says nothing. */
    public static String toUnicode(String name) {
        if (name == null || name.isEmpty() || name.equals(".notdef")) {
            return null;
        }
        String u = NAME_TO_UNI.get(name);
        if (u != null) {
            return u;
        }
        int dot = name.indexOf('.');
        if (dot > 0) {
            return toUnicode(name.substring(0, dot));
        }
        if (name.indexOf('_') > 0) {
            StringBuilder sb = new StringBuilder();
            for (String part : name.split("_")) {
                String p = toUnicode(part);
                if (p == null) {
                    return null;
                }
                sb.append(p);
            }
            return sb.toString();
        }
        if (name.startsWith("uni") && name.length() >= 7 && (name.length() - 3) % 4 == 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 3; i + 4 <= name.length(); i += 4) {
                int v = hex(name.substring(i, i + 4));
                if (v < 0) {
                    return null;
                }
                sb.append((char) v);
            }
            return sb.toString();
        }
        if (name.length() >= 5 && name.length() <= 7 && name.charAt(0) == 'u') {
            int v = hex(name.substring(1));
            if (v >= 0 && v <= 0x10FFFF) {
                return new String(Character.toChars(v));
            }
        }
        // accented letters: base + diacritic composed by Unicode
        for (Map.Entry<String, Character> d : DIACRITICS.entrySet()) {
            String key = d.getKey();
            if (name.length() > key.length() && name.endsWith(key)) {
                String base = toUnicode(name.substring(0, name.length() - key.length()));
                if (base != null && base.length() == 1) {
                    String c = Normalizer.normalize(base + d.getValue(), Normalizer.Form.NFC);
                    if (c.length() == 1) {
                        return c;
                    }
                    if (key.equals("commaaccent")) {
                        c = Normalizer.normalize(base + '̧', Normalizer.Form.NFC);
                        if (c.length() == 1) {
                            return c;
                        }
                    }
                }
            }
        }
        if (name.equals("Idot")) {
            return "İ";
        }
        // gXX, cidXX, aXX glyph names of subset fonts carry no meaning
        return null;
    }

    private static int hex(String s) {
        int v = 0;
        if (s.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < s.length(); i++) {
            int h = Character.digit(s.charAt(i), 16);
            if (h < 0) {
                return -1;
            }
            v = v * 16 + h;
        }
        return v;
    }

    /** The standard glyph name of a character: the list's name, or uniXXXX. */
    public static String nameOf(int unicode) {
        String n = UNI_TO_NAME.get(unicode);
        if (n != null) {
            return n;
        }
        if (unicode >= 'a' && unicode <= 'z' || unicode >= 'A' && unicode <= 'Z') {
            return String.valueOf((char) unicode);
        }
        // accented Latin letters by their decomposition (eacute, Ccaron...)
        String d = Normalizer.normalize(String.valueOf((char) unicode), Normalizer.Form.NFD);
        if (d.length() == 2) {
            String base = UNI_TO_NAME.get((int) d.charAt(0));
            for (Map.Entry<String, Character> e : DIACRITICS.entrySet()) {
                if (e.getValue() == d.charAt(1) && base != null) {
                    String cand = base + e.getKey();
                    String back = toUnicode(cand);
                    if (back != null && back.length() == 1 && back.charAt(0) == unicode) {
                        return cand;
                    }
                }
            }
        }
        if (unicode > 0xFFFF) {
            return String.format("u%05X", unicode);
        }
        return String.format("uni%04X", unicode);
    }
}
