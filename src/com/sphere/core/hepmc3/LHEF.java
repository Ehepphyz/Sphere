package com.sphere.core.hepmc3;

import com.sphere.core.hepmc3.cxx.CFiles;
import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.CInput;
import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.COutput;
import com.sphere.core.hepmc3.cxx.CStr;
import com.sphere.core.hepmc3.cxx.StdStreams;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The Les Houches Event File library of HepMC3 (LHEF.h, by Leif Lönnblad):
 * the HEPRUP and HEPEUP common blocks of the 2001 accord with the extensions
 * of versions 2 and 3 (weights, scales, cuts, process and merging
 * information, event groups), its own XML reader, and the Reader and Writer
 * of .lhe files.
 *
 * <p>Classes are nested as in the C++ namespace: LHEF.HEPRUP is
 * LHEF::HEPRUP. Printing goes through an ostream with the same precision
 * and widths, so a file written here is the file HepMC3 writes.
 */
public final class LHEF {

    private LHEF() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /* ---- printing helpers --------------------------------------------------- */

    /** os << oattr(name, value): ' name="value"' with value printed by the stream. */
    static void oattr(COStream os, String name, Object val) {
        os.put(" ").put(name).put("=\"");
        if (val instanceof Double d) os.put(d);
        else if (val instanceof Long l) os.put(l);
        else if (val instanceof Integer i) os.put(i);
        else os.put(String.valueOf(val));
        os.put("\"");
    }

    /** Every non-blank line made a comment with '#' unless it is one already. */
    public static String hashline(String s) {
        final StringBuilder ret = new StringBuilder();
        final CIStream is = new CIStream(s);
        String ss;
        while ((ss = is.getline()) != null) {
            if (ss.isEmpty()) continue;
            final int firstNotBlank = firstNotOf(ss, " \t", 0);
            if (firstNotBlank < 0) continue;
            if (ss.indexOf('#') < 0 || ss.indexOf('#') != firstNotBlank) ss = "# " + ss;
            ret.append(ss).append('\n');
        }
        return ret.toString();
    }

    static int firstNotOf(String s, String chars, int from) {
        for (int i = Math.max(0, from); i < s.length(); i++) if (chars.indexOf(s.charAt(i)) < 0) return i;
        return -1;
    }

    static int firstOf(String s, String chars, int from) {
        for (int i = Math.max(0, from); i < s.length(); i++) if (chars.indexOf(s.charAt(i)) >= 0) return i;
        return -1;
    }

    /* ======================================================================= */
    /* XMLTag                                                                   */
    /* ======================================================================= */

    /** A tag of the light XML reader: name, attributes, sub-tags, and the text not in them. */
    public static final class XMLTag {
        public String name = "";
        public final TreeMap<String, String> attr = new TreeMap<>();
        public List<XMLTag> tags = new ArrayList<>();
        public String contents = "";

        public boolean getattr(String n, double[] v) {
            final String s = attr.get(n);
            if (s == null) return false;
            v[0] = CStr.atof(s);
            return true;
        }

        public String getattr(String n) {
            return attr.get(n);
        }

        /**
         * The tags of a string, in order; the text between them becomes
         * tags without a name. Comments and CDATA are kept as text. What is
         * not inside a tag is added to leftover when one is given.
         */
        public static List<XMLTag> findXMLTags(String str, StringBuilder leftover) {
            final List<XMLTag> tags = new ArrayList<>();
            int curr = 0;
            final int len = str.length();
            while (curr != -1) {
                final int begin = str.indexOf('<', curr);
                if (begin != -1 && str.indexOf("<!--", curr) == begin) {
                    final int endcom = str.indexOf("-->", begin);
                    final XMLTag t = new XMLTag();
                    tags.add(t);
                    if (endcom == -1) {
                        t.contents = str.substring(curr);
                        if (leftover != null) leftover.append(str.substring(curr));
                        return tags;
                    }
                    t.contents = str.substring(curr, endcom);
                    if (leftover != null) leftover.append(str, curr, endcom);
                    curr = endcom;
                    continue;
                }
                if (begin != -1 && str.indexOf("<![CDATA[", curr) == begin) {
                    final int endcom = str.indexOf("]]>", begin);
                    final XMLTag t = new XMLTag();
                    tags.add(t);
                    if (endcom == -1) {
                        t.contents = str.substring(curr);
                        if (leftover != null) leftover.append(str.substring(curr));
                        return tags;
                    }
                    t.contents = str.substring(curr, endcom);
                    if (leftover != null) leftover.append(str, curr, endcom);
                    curr = endcom;
                    continue;
                }
                if (begin != curr) {
                    final XMLTag t = new XMLTag();
                    tags.add(t);
                    final String piece = begin == -1 ? str.substring(curr) : str.substring(curr, begin);
                    t.contents = piece;
                    if (leftover != null) leftover.append(piece);
                }
                // str[begin + 1] past the end is the terminating '\0' in C++
                if (begin == -1 || (len >= 3 && begin > len - 3) || (begin + 1 < len && str.charAt(begin + 1) == '/')) {
                    return tags;
                }
                final int close = str.indexOf('>', curr);
                if (close == -1) return tags;
                curr = firstOf(str, " \t\n/>", begin);
                final XMLTag tag = new XMLTag();
                tags.add(tag);
                tag.name = str.substring(begin + 1, curr == -1 ? len : curr);
                while (true) {
                    curr = curr == -1 ? -1 : firstNotOf(str, " \t\n", curr);
                    if (curr == -1 || curr >= close) break;
                    final int tend = firstOf(str, "= \t\n", curr);
                    if (tend == -1 || tend >= close) break;
                    final String namex = str.substring(curr, tend);
                    final int eq = str.indexOf('=', curr);
                    curr = eq == -1 ? 0 : eq + 1;
                    curr = firstOf(str, "\"'", curr);
                    if (curr == -1 || curr >= close) break;
                    final char quote = str.charAt(curr);
                    final int bega = ++curr;
                    curr = str.indexOf(quote, curr);
                    while (curr != -1 && str.charAt(curr - 1) == '\\') curr = str.indexOf(quote, curr + 1);
                    final String value = curr == -1 ? str.substring(bega) : str.substring(bega, curr);
                    tag.attr.put(namex, value);
                    if (curr == -1) break;
                    ++curr;
                }
                curr = close + 1;
                if (close > 0 && str.charAt(close - 1) == '/') continue;
                final int endtag = str.indexOf("</" + tag.name + ">", curr);
                if (endtag == -1) {
                    tag.contents = curr <= len ? str.substring(Math.min(curr, len)) : "";
                    curr = -1;
                } else {
                    tag.contents = str.substring(curr, endtag);
                    curr = endtag + tag.name.length() + 3;
                }
                final StringBuilder leftovers = new StringBuilder();
                tag.tags = findXMLTags(tag.contents, leftovers);
                String lo = leftovers.toString();
                if (firstNotOf(lo, " \t\n", 0) == -1) lo = "";
                tag.contents = lo;
            }
            return tags;
        }

        public static List<XMLTag> findXMLTags(String str) {
            return findXMLTags(str, null);
        }

        public void print(COStream os) {
            if (name.isEmpty()) {
                os.put(contents);
                return;
            }
            os.put("<").put(name);
            for (Map.Entry<String, String> it : attr.entrySet()) oattr(os, it.getKey(), it.getValue());
            if (contents.isEmpty() && tags.isEmpty()) {
                os.put("/>").endl();
                return;
            }
            os.put(">");
            for (XMLTag t : tags) t.print(os);
            os.put(contents).put("</").put(name).put(">").endl();
        }
    }

    /* ======================================================================= */
    /* TagBase and the small tags                                              */
    /* ======================================================================= */

    /** Attributes and contents of a tag, with the getters that consume what they read. */
    public static class TagBase {
        public TreeMap<String, String> attributes = new TreeMap<>();
        public String contents = "";

        public TagBase() {
        }

        public TagBase(Map<String, String> attr, String conts) {
            attributes = new TreeMap<>(attr);
            contents = conts == null ? "" : conts;
        }

        public TagBase(TagBase o) {
            attributes = new TreeMap<>(o.attributes);
            contents = o.contents;
        }

        /** A double attribute (atof), removed once read; null when absent. */
        protected Double getDouble(String n) {
            final String s = attributes.remove(n);
            return s == null ? null : CStr.atof(s);
        }

        /** A bool attribute: true for "yes"; null when absent (C++ then leaves the value alone). */
        protected Boolean getBool(String n) {
            final String s = attributes.remove(n);
            if (s == null) return null;
            return s.equals("yes") ? Boolean.TRUE : null;
        }

        /** Whether a bool attribute is present at all (and removed). */
        protected boolean hasAttr(String n) {
            return attributes.containsKey(n);
        }

        /** A long attribute, read with atoi as the C++ does; null when absent. */
        protected Long getLong(String n) {
            final String s = attributes.remove(n);
            return s == null ? null : (long) CStr.atoi(s);
        }

        protected Integer getInt(String n) {
            final String s = attributes.remove(n);
            return s == null ? null : CStr.atoi(s);
        }

        protected String getString(String n) {
            return attributes.remove(n);
        }

        public void printattrs(COStream file) {
            for (Map.Entry<String, String> it : attributes.entrySet()) oattr(file, it.getKey(), it.getValue());
        }

        public void closetag(COStream file, String tag) {
            if (contents.isEmpty()) file.put("/>\n");
            else if (contents.indexOf('\n') >= 0) file.put(">\n").put(contents).put("\n</").put(tag).put(">\n");
            else file.put(">").put(contents).put("</").put(tag).put(">\n");
        }

        public static String yes() {
            return "yes";
        }
    }

    /** A generator of the run. */
    public static final class Generator extends TagBase {
        public String name = "";
        public String version = "";

        public Generator(XMLTag tag) {
            super(tag.attr, tag.contents);
            final String n = getString("name");
            if (n != null) name = n;
            final String v = getString("version");
            if (v != null) version = v;
        }

        public void print(COStream file) {
            file.put("<generator");
            if (!name.isEmpty()) oattr(file, "name", name);
            if (!version.isEmpty()) oattr(file, "version", version);
            printattrs(file);
            closetag(file, "generator");
        }
    }

    /** Cross-section information of the run (v3). */
    public static final class XSecInfo extends TagBase {
        public long neve = -1;
        public long ntries = -1;
        public double totxsec = 0.0;
        public double xsecerr = 0.0;
        public double maxweight = 1.0;
        public double meanweight = 1.0;
        public boolean negweights;
        public boolean varweights;
        public String weightname = "";

        public XSecInfo() {
        }

        public XSecInfo(XMLTag tag) {
            super(tag.attr, tag.contents);
            final Long n = getLong("neve");
            if (n == null) throw new IllegalStateException("Found xsecinfo tag without neve attribute in Les Houches Event File.");
            neve = n;
            ntries = neve;
            final Long nt = getLong("ntries");
            if (nt != null) ntries = nt;
            final Double t = getDouble("totxsec");
            if (t == null) throw new IllegalStateException("Found xsecinfo tag without totxsec attribute in Les Houches Event File.");
            totxsec = t;
            final Double e = getDouble("xsecerr");
            if (e != null) xsecerr = e;
            final String w = getString("weightname");
            if (w != null) weightname = w;
            final Double mx = getDouble("maxweight");
            if (mx != null) maxweight = mx;
            final Double mn = getDouble("meanweight");
            if (mn != null) meanweight = mn;
            if (getBool("negweights") != null) negweights = true;
            if (getBool("varweights") != null) varweights = true;
        }

        public XSecInfo(XSecInfo o) {
            super(o);
            neve = o.neve;
            ntries = o.ntries;
            totxsec = o.totxsec;
            xsecerr = o.xsecerr;
            maxweight = o.maxweight;
            meanweight = o.meanweight;
            negweights = o.negweights;
            varweights = o.varweights;
            weightname = o.weightname;
        }

        public void print(COStream file) {
            file.put("<xsecinfo");
            oattr(file, "neve", neve);
            oattr(file, "totxsec", totxsec);
            if (maxweight != 1.0) {
                oattr(file, "maxweight", maxweight);
                oattr(file, "meanweight", meanweight);
            }
            if (ntries > neve) oattr(file, "ntries", ntries);
            if (xsecerr > 0.0) oattr(file, "xsecerr", xsecerr);
            if (!weightname.isEmpty()) oattr(file, "weightname", weightname);
            if (negweights) oattr(file, "negweights", yes());
            if (varweights) oattr(file, "varweights", yes());
            printattrs(file);
            closetag(file, "xsecinfo");
        }
    }

    /** A file of events the run is split into (v3). */
    public static final class EventFile extends TagBase {
        public String filename = "";
        public long neve = -1;
        public long ntries = -1;

        public EventFile() {
        }

        public EventFile(XMLTag tag) {
            super(tag.attr, tag.contents);
            final String n = getString("name");
            if (n == null) throw new IllegalStateException("Found eventfile tag without name attribute in Les Houches Event File.");
            filename = n;
            final Long ne = getLong("neve");
            if (ne != null) neve = ne;
            ntries = neve;
            final Long nt = getLong("ntries");
            if (nt != null) ntries = nt;
        }

        public void print(COStream file) {
            if (filename.isEmpty()) return;
            file.put("  <eventfile");
            oattr(file, "name", filename);
            if (neve > 0) oattr(file, "neve", neve);
            if (ntries > neve) oattr(file, "ntries", ntries);
            printattrs(file);
            closetag(file, "eventfile");
        }
    }

    /** A generator cut (v3), on one or two particle types. */
    public static final class Cut extends TagBase {
        public String type = "";
        public final TreeSet<Long> p1 = new TreeSet<>();
        public String np1 = "";
        public final TreeSet<Long> p2 = new TreeSet<>();
        public String np2 = "";
        public double min = -0.99 * Double.MAX_VALUE;
        public double max = 0.99 * Double.MAX_VALUE;

        public Cut() {
        }

        public Cut(XMLTag tag, Map<String, TreeSet<Long>> ptypes) {
            super(tag.attr, "");
            final String t = getString("type");
            if (t == null) throw new IllegalStateException("Found cut tag without type attribute in Les Houches file");
            type = t;
            final String n1 = tag.getattr("p1");
            if (n1 != null) {
                np1 = n1;
                if (ptypes.containsKey(np1)) {
                    p1.addAll(ptypes.get(np1));
                    attributes.remove("p1");
                } else {
                    final Long tmp = getLong("p1");
                    p1.add(tmp == null ? 0L : tmp);
                    np1 = "";
                }
            }
            final String n2 = tag.getattr("p2");
            if (n2 != null) {
                np2 = n2;
                if (ptypes.containsKey(np2)) {
                    p2.addAll(ptypes.get(np2));
                    attributes.remove("p2");
                } else {
                    final Long tmp = getLong("p2");
                    p2.add(tmp == null ? 0L : tmp);
                    np2 = "";
                }
            }
            final CIStream iss = new CIStream(tag.contents);
            min = iss.nextDouble();
            if (!iss.fail()) {
                final double mx = iss.nextDouble();
                if (!iss.fail()) {
                    max = mx;
                    if (min >= max) min = -0.99 * Double.MAX_VALUE;
                } else {
                    max = 0.99 * Double.MAX_VALUE;
                }
            } else {
                max = 0.99 * Double.MAX_VALUE;
            }
        }

        public void print(COStream file) {
            file.put("<cut");
            oattr(file, "type", type);
            if (!np1.isEmpty()) oattr(file, "p1", np1);
            else if (p1.size() == 1) oattr(file, "p1", p1.first());
            if (!np2.isEmpty()) oattr(file, "p2", np2);
            else if (p2.size() == 1) oattr(file, "p2", p2.first());
            printattrs(file);
            file.put(">");
            if (min > -0.9 * Double.MAX_VALUE) file.put(min);
            else file.put(max);
            if (max < 0.9 * Double.MAX_VALUE) file.put(" ").put(max);
            if (!contents.isEmpty()) file.endl().put(contents).endl();
            file.put("</cut>").endl();
        }

        public boolean match(long id1, long id2) {
            boolean first = false;
            boolean second = false;
            if (id2 == 0) second = true;
            if (id1 == 0) first = true;
            if (p1.contains(0L)) first = true;
            if (p1.contains(id1)) first = true;
            if (p2.contains(0L)) second = true;
            if (p2.contains(id2)) second = true;
            return first && second;
        }

        public boolean match(long id1) {
            return match(id1, 0);
        }

        /** Whether particles (ids, momenta as {?, px, py, pz, e, m}) pass this cut. */
        public boolean passCuts(List<Long> id, List<double[]> p) {
            if ((type.equals("m") && p2.isEmpty()) || type.equals("kt") || type.equals("eta") || type.equals("y") || type.equals("E")) {
                for (int i = 0; i < id.size(); ++i) {
                    if (match(id.get(i))) {
                        final double[] pi = p.get(i);
                        if (type.equals("m")) {
                            double v = pi[4] * pi[4] - pi[3] * pi[3] - pi[2] * pi[2] - pi[1] * pi[1];
                            v = v >= 0.0 ? Math.sqrt(v) : -Math.sqrt(-v);
                            if (outside(v)) return false;
                        } else if (type.equals("kt")) {
                            if (outside(Math.sqrt(pi[2] * pi[2] + pi[1] * pi[1]))) return false;
                        } else if (type.equals("E")) {
                            if (outside(pi[4])) return false;
                        } else if (type.equals("eta")) {
                            if (outside(eta(pi))) return false;
                        } else if (type.equals("y")) {
                            if (outside(rap(pi))) return false;
                        }
                    }
                }
            } else if (type.equals("m") || type.equals("deltaR")) {
                for (int i = 1; i < id.size(); ++i) {
                    for (int j = 0; j < i; ++j) {
                        if (match(id.get(i), id.get(j)) || match(id.get(j), id.get(i))) {
                            final double[] a = p.get(i);
                            final double[] b = p.get(j);
                            if (type.equals("m")) {
                                double v = (a[4] + b[4]) * (a[4] + b[4]) - (a[3] + b[3]) * (a[3] + b[3])
                                    - (a[2] + b[2]) * (a[2] + b[2]) - (a[1] + b[1]) * (a[1] + b[1]);
                                v = v >= 0.0 ? Math.sqrt(v) : -Math.sqrt(-v);
                                if (outside(v)) return false;
                            } else if (outside(deltaR(a, b))) {
                                return false;
                            }
                        }
                    }
                }
            } else if (type.equals("ETmiss")) {
                double x = 0.0;
                double y = 0.0;
                for (int i = 0; i < id.size(); ++i) {
                    if (match(id.get(i)) && !match(0, id.get(i))) {
                        x += p.get(i)[1];
                        y += p.get(i)[2];
                    }
                }
                if (outside(Math.sqrt(x * x + y * y))) return false;
            } else if (type.equals("HT")) {
                double pt = 0.0;
                for (int i = 0; i < id.size(); ++i) {
                    if (match(id.get(i)) && !match(0, id.get(i))) {
                        pt += Math.sqrt(p.get(i)[1] * p.get(i)[1] + p.get(i)[2] * p.get(i)[2]);
                    }
                }
                if (outside(pt)) return false;
            }
            return true;
        }

        public static double eta(double[] p) {
            final double pt2 = p[2] * p[2] + p[1] * p[1];
            if (pt2 != 0.0) {
                final double dum = Math.sqrt(pt2 + p[3] * p[3]) + p[3];
                if (dum != 0.0) return Math.log(dum / Math.sqrt(pt2));
            }
            return p[3] < 0.0 ? -Double.MAX_VALUE : Double.MAX_VALUE;
        }

        public static double rap(double[] p) {
            final double pt2 = p[5] * p[5] + p[2] * p[2] + p[1] * p[1];
            if (pt2 != 0.0) {
                final double dum = Math.sqrt(pt2 + p[3] * p[3]) + p[3];
                if (dum != 0.0) return Math.log(dum / Math.sqrt(pt2));
            }
            return p[3] < 0.0 ? -Double.MAX_VALUE : Double.MAX_VALUE;
        }

        public static double deltaR(double[] p1, double[] p2) {
            final double deta = eta(p1) - eta(p2);
            double dphi = Math.atan2(p1[1], p1[2]) - Math.atan2(p2[1], p2[2]);
            if (dphi > Math.PI) dphi -= 2.0 * Math.PI;
            if (dphi < -Math.PI) dphi += 2.0 * Math.PI;
            return Math.sqrt(dphi * dphi + deta * deta);
        }

        public boolean outside(double value) {
            return value < min || value >= max;
        }
    }

    /** Information about one process (v3). */
    public static final class ProcInfo extends TagBase {
        public int iproc;
        public int loops;
        public int qcdorder = -1;
        public int eworder = -1;
        public String fscheme = "";
        public String rscheme = "";
        public String scheme = "";

        public ProcInfo() {
        }

        public ProcInfo(XMLTag tag) {
            super(tag.attr, tag.contents);
            Integer v;
            if ((v = getInt("iproc")) != null) iproc = v;
            if ((v = getInt("loops")) != null) loops = v;
            if ((v = getInt("qcdorder")) != null) qcdorder = v;
            if ((v = getInt("eworder")) != null) eworder = v;
            String s;
            if ((s = getString("rscheme")) != null) rscheme = s;
            if ((s = getString("fscheme")) != null) fscheme = s;
            if ((s = getString("scheme")) != null) scheme = s;
        }

        public void print(COStream file) {
            file.put("<procinfo");
            oattr(file, "iproc", iproc);
            if (loops >= 0) oattr(file, "loops", loops);
            if (qcdorder >= 0) oattr(file, "qcdorder", qcdorder);
            if (eworder >= 0) oattr(file, "eworder", eworder);
            if (!rscheme.isEmpty()) oattr(file, "rscheme", rscheme);
            if (!fscheme.isEmpty()) oattr(file, "fscheme", fscheme);
            if (!scheme.isEmpty()) oattr(file, "scheme", scheme);
            printattrs(file);
            closetag(file, "procinfo");
        }
    }

    /** Merging information of one process (v3). */
    public static final class MergeInfo extends TagBase {
        public int iproc;
        public double mergingscale;
        public boolean maxmult;

        public MergeInfo() {
        }

        public MergeInfo(XMLTag tag) {
            super(tag.attr, tag.contents);
            final Integer v = getInt("iproc");
            if (v != null) iproc = v;
            final Double d = getDouble("mergingscale");
            if (d != null) mergingscale = d;
            if (getBool("maxmult") != null) maxmult = true;
        }

        public void print(COStream file) {
            file.put("<mergeinfo");
            oattr(file, "iproc", iproc);
            if (mergingscale > 0.0) oattr(file, "mergingscale", mergingscale);
            if (maxmult) oattr(file, "maxmult", yes());
            printattrs(file);
            closetag(file, "mergeinfo");
        }
    }

    /** What a weight is: its name and the scale and PDF variations it stands for. */
    public static final class WeightInfo extends TagBase {
        public int inGroup = -1;
        public boolean isrwgt;
        public String name = "";
        public double muf = 1.0;
        public double mur = 1.0;
        public long pdf;
        public long pdf2;

        public WeightInfo() {
        }

        public WeightInfo(XMLTag tag) {
            super(tag.attr, tag.contents);
            isrwgt = tag.name.equals("weight");
            Double d;
            if ((d = getDouble("mur")) != null) mur = d;
            if ((d = getDouble("muf")) != null) muf = d;
            Long l;
            if ((l = getLong("pdf")) != null) pdf = l;
            if ((l = getLong("pdf2")) != null) pdf2 = l;
            final String n = isrwgt ? getString("id") : getString("name");
            if (n != null) name = n;
        }

        public WeightInfo(WeightInfo o) {
            super(o);
            inGroup = o.inGroup;
            isrwgt = o.isrwgt;
            name = o.name;
            muf = o.muf;
            mur = o.mur;
            pdf = o.pdf;
            pdf2 = o.pdf2;
        }

        public void print(COStream file) {
            if (isrwgt) {
                file.put("<weight");
                oattr(file, "id", name);
            } else {
                file.put("<weightinfo");
                oattr(file, "name", name);
            }
            if (mur != 1.0) oattr(file, "mur", mur);
            if (muf != 1.0) oattr(file, "muf", muf);
            if (pdf != 0) oattr(file, "pdf", pdf);
            if (pdf2 != 0) oattr(file, "pdf2", pdf2);
            printattrs(file);
            closetag(file, isrwgt ? "weight" : "weightinfo");
        }
    }

    /** A group of weights. */
    public static final class WeightGroup extends TagBase {
        public String name = "";
        public String type = "";
        public String combine = "";

        public WeightGroup() {
        }

        public WeightGroup(XMLTag tag, int groupIndex, List<WeightInfo> wiv) {
            super(tag.attr, "");
            String s;
            if ((s = getString("name")) != null) name = s;
            if ((s = getString("type")) != null) type = s;
            if ((s = getString("combine")) != null) combine = s;
            for (XMLTag t : tag.tags) {
                if (t.name.equals("weight") || t.name.equals("weightinfo")) {
                    final WeightInfo wi = new WeightInfo(t);
                    wi.inGroup = groupIndex;
                    wiv.add(wi);
                }
            }
        }
    }

    /** A weight given by name in an event (wgt in rwgt, or weight). */
    public static final class Weight extends TagBase {
        public String name = "";
        public boolean iswgt;
        public double born;
        public double sudakov;
        public List<Double> weights = new ArrayList<>();
        public List<Integer> indices = new ArrayList<>();

        public Weight() {
        }

        public Weight(XMLTag tag) {
            super(tag.attr, tag.contents);
            iswgt = tag.name.equals("wgt");
            final String n = iswgt ? getString("id") : getString("name");
            if (n != null) name = n;
            Double d;
            if ((d = getDouble("born")) != null) born = d;
            if ((d = getDouble("sudakov")) != null) sudakov = d;
            final CIStream iss = new CIStream(tag.contents);
            while (true) {
                final double w = iss.nextDouble();
                if (iss.fail()) break;
                weights.add(w);
            }
            for (int i = 0; i < weights.size(); i++) indices.add(0);
        }

        public Weight(Weight o) {
            super(o);
            name = o.name;
            iswgt = o.iswgt;
            born = o.born;
            sudakov = o.sudakov;
            weights = new ArrayList<>(o.weights);
            indices = new ArrayList<>(o.indices);
        }

        public void print(COStream file) {
            if (iswgt) {
                file.put("<wgt");
                oattr(file, "id", name);
            } else {
                file.put("<weight");
                if (!name.isEmpty()) oattr(file, "name", name);
            }
            if (born != 0.0) oattr(file, "born", born);
            if (sudakov != 0.0) oattr(file, "sudakov", sudakov);
            file.put(">");
            for (double w : weights) file.put(" ").put(w);
            if (iswgt) file.put("</wgt>").endl();
            else file.put("</weight>").endl();
        }
    }

    /** A clustering step of a merged event (v3). */
    public static final class Clus extends TagBase {
        public int p1;
        public int p2;
        public int p0;
        public double scale = -1.0;
        public double alphas = -1.0;

        public Clus() {
        }

        public Clus(XMLTag tag) {
            super(tag.attr, tag.contents);
            Double d;
            if ((d = getDouble("scale")) != null) scale = d;
            if ((d = getDouble("alphas")) != null) alphas = d;
            final CIStream iss = new CIStream(tag.contents);
            p1 = iss.nextInt();
            if (!iss.fail()) p2 = iss.nextInt();
            final int z = iss.fail() ? 0 : iss.nextInt();
            if (iss.fail()) p0 = p1;
            else p0 = z;
        }

        public void print(COStream file) {
            file.put("<clus");
            if (scale > 0.0) oattr(file, "scale", scale);
            if (alphas > 0.0) oattr(file, "alphas", alphas);
            file.put(">").put(p1).put(" ").put(p2);
            if (p1 != p0) file.put(" ").put(p0);
            file.put("</clus>").endl();
        }
    }

    /** A scale of a shower emission (v3). */
    public static final class Scale extends TagBase {
        public String stype = "veto";
        public int emitter;
        public final TreeSet<Integer> recoilers = new TreeSet<>();
        public final TreeSet<Integer> emitted = new TreeSet<>();
        public double scale;

        public Scale(String st, int emtr, double sc) {
            stype = st;
            emitter = emtr;
            scale = sc;
        }

        public Scale() {
            this("veto", 0, 0.0);
        }

        public Scale(XMLTag tag) {
            super(tag.attr, tag.contents);
            final String st = getString("stype");
            if (st == null) throw new IllegalStateException("Found scale tag without stype attribute in Les Houches Event File.");
            stype = st;
            final String pattr = getString("pos");
            if (pattr != null) {
                final CIStream pis = new CIStream(pattr);
                emitter = pis.nextInt();
                if (pis.fail()) {
                    emitter = 0;
                } else {
                    while (true) {
                        final int rec = pis.nextInt();
                        if (pis.fail()) break;
                        recoilers.add(rec);
                    }
                }
            }
            String eattr = getString("etype");
            if (eattr != null) {
                if (eattr.equals("QCD")) eattr = "-5 -4  -3 -2 -1 1 2 3 4 5 21";
                if (eattr.equals("EW")) eattr = "-13 -12 -11 11 12 13 22 23 24";
                final CIStream eis = new CIStream(eattr);
                while (true) {
                    final int pdg = eis.nextInt();
                    if (eis.fail()) break;
                    emitted.add(pdg);
                }
            }
            final CIStream cis = new CIStream(tag.contents);
            scale = cis.nextDouble();
        }

        public void print(COStream file) {
            file.put("<scale");
            oattr(file, "stype", stype);
            if (emitter > 0) {
                final COStream pos = new COStream();
                pos.put(emitter);
                for (int r : recoilers) pos.put(" ").put(r);
                oattr(file, "pos", pos.str());
            }
            if (!emitted.isEmpty()) {
                final COStream eos = new COStream();
                boolean first = true;
                for (int e : emitted) {
                    if (!first) eos.put(" ");
                    eos.put(e);
                    first = false;
                }
                if (eos.str().equals("-5 -4  -3 -2 -1 1 2 3 4 5 21")) oattr(file, "etype", "QCD");
                else if (eos.str().equals("-13 -12 -11 11 12 13 22 23 24")) oattr(file, "etype", "EW");
                else oattr(file, "etype", eos.str());
            }
            final COStream os = new COStream();
            os.put(scale);
            contents = os.str();
            closetag(file, "scale");
        }
    }

    /** The scales of an event (v3). */
    public static final class Scales extends TagBase {
        public double muf;
        public double mur;
        public double mups;
        public double SCALUP;
        public List<Scale> scales = new ArrayList<>();

        public Scales(double defscale, int npart) {
            muf = defscale;
            mur = defscale;
            mups = defscale;
            SCALUP = defscale;
        }

        public Scales() {
            this(-1.0, 0);
        }

        public Scales(XMLTag tag, double defscale, int npart) {
            super(tag.attr, tag.contents);
            muf = defscale;
            mur = defscale;
            mups = defscale;
            SCALUP = defscale;
            Double d;
            if ((d = getDouble("muf")) != null) muf = d;
            if ((d = getDouble("mur")) != null) mur = d;
            if ((d = getDouble("mups")) != null) mups = d;
            for (XMLTag t : tag.tags) if (t.name.equals("scale")) scales.add(new Scale(t));
            for (int i = 0; i < npart; ++i) {
                final Double sc = getDouble("pt_start_" + (i + 1));
                if (sc != null) scales.add(new Scale("start", i + 1, sc));
            }
        }

        public boolean hasInfo() {
            return muf != SCALUP || mur != SCALUP || mups != SCALUP || !scales.isEmpty();
        }

        public void print(COStream file) {
            if (!hasInfo()) return;
            file.put("<scales");
            if (muf != SCALUP) oattr(file, "muf", muf);
            if (mur != SCALUP) oattr(file, "mur", mur);
            if (mups != SCALUP) oattr(file, "mups", mups);
            printattrs(file);
            if (!scales.isEmpty()) {
                final COStream os = new COStream();
                for (Scale s : scales) s.print(os);
                contents = os.str();
            }
            closetag(file, "scales");
        }

        public double getScale(String st, int pdgem, int emr, int rec) {
            for (Scale s : scales) {
                if (s.emitter == emr && st.equals(s.stype) && (emr == rec || s.recoilers.contains(rec))
                        && s.emitted.contains(pdgem)) return s.scale;
            }
            for (Scale s : scales) {
                if (s.emitter == emr && st.equals(s.stype) && (emr == rec || s.recoilers.contains(rec))
                        && s.emitted.isEmpty()) return s.scale;
            }
            if (emr != rec) return getScale(st, pdgem, emr, emr);
            if (emr != 0) return getScale(st, pdgem, 0, 0);
            return mups;
        }
    }

    /** PDF information of an event (v3). */
    public static final class PDFInfo extends TagBase {
        public long p1;
        public long p2;
        public double x1 = -1.0;
        public double x2 = -1.0;
        public double xf1 = -1.0;
        public double xf2 = -1.0;
        public double scale;
        public double SCALUP;

        public PDFInfo(double defscale) {
            scale = defscale;
            SCALUP = defscale;
        }

        public PDFInfo() {
            this(-1.0);
        }

        public PDFInfo(XMLTag tag, double defscale) {
            super(tag.attr, tag.contents);
            scale = defscale;
            SCALUP = defscale;
            Double d;
            if ((d = getDouble("scale")) != null) scale = d;
            Long l;
            if ((l = getLong("p1")) != null) p1 = l;
            if ((l = getLong("p2")) != null) p2 = l;
            if ((d = getDouble("x1")) != null) x1 = d;
            if ((d = getDouble("x2")) != null) x2 = d;
        }

        public void print(COStream file) {
            if (xf1 <= 0) return;
            file.put("<pdfinfo");
            if (p1 != 0) oattr(file, "p1", p1);
            if (p2 != 0) oattr(file, "p2", p2);
            if (x1 > 0) oattr(file, "x1", x1);
            if (x2 > 0) oattr(file, "x2", x2);
            if (scale != SCALUP) oattr(file, "scale", scale);
            printattrs(file);
            file.put(">").put(xf1).put(" ").put(xf2).put("</pdfinfo>").endl();
        }
    }

    /* ======================================================================= */
    /* HEPRUP                                                                   */
    /* ======================================================================= */

    /** The run: beams, PDFs, weighting strategy, processes, and the version 3 extras. */
    public static final class HEPRUP extends TagBase {
        public long[] IDBMUP = new long[2];
        public double[] EBMUP = new double[2];
        public int[] PDFGUP = new int[2];
        public int[] PDFSUP = new int[2];
        public int IDWTUP;
        public int NPRUP;
        public List<Double> XSECUP = new ArrayList<>();
        public List<Double> XERRUP = new ArrayList<>();
        public List<Double> XMAXUP = new ArrayList<>();
        public List<Integer> LPRUP = new ArrayList<>();
        public TreeMap<String, XSecInfo> xsecinfos = new TreeMap<>();
        public List<EventFile> eventfiles = new ArrayList<>();
        public List<Cut> cuts = new ArrayList<>();
        public TreeMap<String, TreeSet<Long>> ptypes = new TreeMap<>();
        public TreeMap<Long, ProcInfo> procinfo = new TreeMap<>();
        public TreeMap<Long, MergeInfo> mergeinfo = new TreeMap<>();
        public List<Generator> generators = new ArrayList<>();
        public List<WeightInfo> weightinfo = new ArrayList<>();
        public TreeMap<String, Integer> weightmap = new TreeMap<>();
        public List<WeightGroup> weightgroup = new ArrayList<>();
        public String junk = "";
        public int version = 3;
        public int dprec = 15;

        public HEPRUP() {
        }

        /** A copy (C++ copy constructor, default member-wise). */
        public HEPRUP(HEPRUP o) {
            super(o);
            IDBMUP = o.IDBMUP.clone();
            EBMUP = o.EBMUP.clone();
            PDFGUP = o.PDFGUP.clone();
            PDFSUP = o.PDFSUP.clone();
            IDWTUP = o.IDWTUP;
            NPRUP = o.NPRUP;
            XSECUP = new ArrayList<>(o.XSECUP);
            XERRUP = new ArrayList<>(o.XERRUP);
            XMAXUP = new ArrayList<>(o.XMAXUP);
            LPRUP = new ArrayList<>(o.LPRUP);
            xsecinfos = new TreeMap<>(o.xsecinfos);
            eventfiles = new ArrayList<>(o.eventfiles);
            cuts = new ArrayList<>(o.cuts);
            ptypes = new TreeMap<>(o.ptypes);
            procinfo = new TreeMap<>(o.procinfo);
            mergeinfo = new TreeMap<>(o.mergeinfo);
            generators = new ArrayList<>(o.generators);
            weightinfo = new ArrayList<>();
            for (WeightInfo w : o.weightinfo) weightinfo.add(new WeightInfo(w));
            weightmap = new TreeMap<>(o.weightmap);
            weightgroup = new ArrayList<>(o.weightgroup);
            junk = o.junk;
            version = o.version;
            dprec = o.dprec;
        }

        /** From the init tag of a file of the given version. */
        public HEPRUP(XMLTag tagin, int versin) {
            super(tagin.attr, tagin.contents);
            version = versin;
            final List<XMLTag> tags = tagin.tags;
            final CIStream iss = new CIStream(tags.isEmpty() ? "" : tags.get(0).contents);
            IDBMUP[0] = iss.nextLong();
            if (!iss.fail()) IDBMUP[1] = iss.nextLong();
            if (!iss.fail()) EBMUP[0] = iss.nextDouble();
            if (!iss.fail()) EBMUP[1] = iss.nextDouble();
            if (!iss.fail()) PDFGUP[0] = iss.nextInt();
            if (!iss.fail()) PDFGUP[1] = iss.nextInt();
            if (!iss.fail()) PDFSUP[0] = iss.nextInt();
            if (!iss.fail()) PDFSUP[1] = iss.nextInt();
            if (!iss.fail()) IDWTUP = iss.nextInt();
            if (!iss.fail()) NPRUP = iss.nextInt();
            if (iss.fail()) throw new IllegalStateException("Could not parse init block in Les Houches Event File.");
            resize();
            for (int i = 0; i < NPRUP; ++i) {
                XSECUP.set(i, iss.nextDouble());
                if (!iss.fail()) XERRUP.set(i, iss.nextDouble());
                if (!iss.fail()) XMAXUP.set(i, iss.nextDouble());
                if (!iss.fail()) LPRUP.set(i, iss.nextInt());
                if (iss.fail()) throw new IllegalStateException("Could not parse processes in init block in Les Houches Event File.");
            }
            for (int i = 1; i < tags.size(); ++i) {
                final XMLTag tag = tags.get(i);
                if (tag.name.isEmpty()) junk += tag.contents;
                if (tag.name.equals("initrwgt")) readInitrwgt(tag);
                if (tag.name.equals("weightinfo")) weightinfo.add(new WeightInfo(tag));
                if (tag.name.equals("weightgroup")) weightgroup.add(new WeightGroup(tag, weightgroup.size(), weightinfo));
                if (tag.name.equals("eventfiles")) {
                    for (XMLTag eftag : tag.tags) if (eftag.name.equals("eventfile")) eventfiles.add(new EventFile(eftag));
                }
                if (tag.name.equals("xsecinfo")) {
                    final XSecInfo xsecinfo = new XSecInfo(tag);
                    xsecinfos.put(xsecinfo.weightname, xsecinfo);
                }
                if (tag.name.equals("generator")) {
                    generators.add(new Generator(tag));
                } else if (tag.name.equals("cutsinfo")) {
                    for (XMLTag ctag : tag.tags) {
                        if (ctag.name.equals("ptype")) {
                            final String tname = ctag.attr.computeIfAbsent("name", k -> "");
                            final CIStream isss = new CIStream(ctag.contents);
                            while (true) {
                                final long id = isss.nextLong();
                                if (isss.fail()) break;
                                ptypes.computeIfAbsent(tname, k -> new TreeSet<>()).add(id);
                            }
                        } else if (ctag.name.equals("cut")) {
                            cuts.add(new Cut(ctag, ptypes));
                        }
                    }
                } else if (tag.name.equals("procinfo")) {
                    final ProcInfo proc = new ProcInfo(tag);
                    procinfo.put((long) proc.iproc, proc);
                } else if (tag.name.equals("mergeinfo")) {
                    final MergeInfo merge = new MergeInfo(tag);
                    mergeinfo.put((long) merge.iproc, merge);
                }
            }
            mapWeightNames();
        }

        public void mapWeightNames() {
            weightmap.clear();
            for (int i = 0; i < weightinfo.size(); ++i) weightmap.put(weightinfo.get(i).name, i + 1);
        }

        public void readInitrwgt(XMLTag tag) {
            if (!tag.name.equals("initrwgt")) return;
            for (XMLTag t : tag.tags) {
                if (t.name.equals("weightgroup")) weightgroup.add(new WeightGroup(t, weightgroup.size(), weightinfo));
                if (t.name.equals("weight")) weightinfo.add(new WeightInfo(t));
            }
        }

        /** The name HepMC3 gives weight i: "GROUP=g__COMBINE=c__name" as arXiv:2203.08230 has it. */
        public String weightNameHepMC(int i) {
            final StringBuilder name = new StringBuilder();
            if (i < 0 || i >= weightinfo.size()) return "";
            if (weightinfo.get(i).inGroup >= 0) {
                final WeightGroup wg = weightgroup.get(weightinfo.get(i).inGroup);
                if (!wg.name.isEmpty()) name.append("GROUP=").append(wg.name).append("__");
                if (!wg.combine.isEmpty()) {
                    final String upper = wg.combine.toUpperCase(java.util.Locale.ROOT);
                    if (!upper.equals("NONE")) name.append("COMBINE=").append(wg.combine).append("__");
                }
            }
            name.append(weightinfo.get(i).name);
            return name.toString();
        }

        public void print(COStream file) {
            file.precision(dprec);
            file.put("<init>\n");
            file.put(" ").setw(8).put(IDBMUP[0]);
            file.put(" ").setw(8).put(IDBMUP[1]);
            file.put(" ").setw(14).put(EBMUP[0]);
            file.put(" ").setw(14).put(EBMUP[1]);
            file.put(" ").setw(4).put(PDFGUP[0]);
            file.put(" ").setw(4).put(PDFGUP[1]);
            file.put(" ").setw(4).put(PDFSUP[0]);
            file.put(" ").setw(4).put(PDFSUP[1]);
            file.put(" ").setw(4).put(IDWTUP);
            file.put(" ").setw(4).put(NPRUP).endl();
            for (int i = 0; i < NPRUP; ++i) {
                file.put(" ").setw(14).put(XSECUP.get(i));
                file.put(" ").setw(14).put(XERRUP.get(i));
                file.put(" ").setw(14).put(XMAXUP.get(i));
                file.put(" ").setw(6).put(LPRUP.get(i)).endl();
            }
            for (Generator g : generators) g.print(file);
            if (!eventfiles.isEmpty()) {
                file.put("<eventfiles>\n");
                for (EventFile ef : eventfiles) ef.print(file);
                file.put("</eventfiles>\n");
            }
            for (XSecInfo xi : xsecinfos.values()) if (xi.neve > 0) xi.print(file);
            if (!cuts.isEmpty()) {
                file.put("<cutsinfo>").endl();
                for (Map.Entry<String, TreeSet<Long>> ptit : ptypes.entrySet()) {
                    file.put("<ptype");
                    oattr(file, "name", ptit.getKey());
                    file.put(">");
                    for (long id : ptit.getValue()) file.put(" ").put(id);
                    file.put("</ptype>").endl();
                }
                for (Cut c : cuts) c.print(file);
                file.put("</cutsinfo>").endl();
            }
            for (ProcInfo p : procinfo.values()) p.print(file);
            for (MergeInfo m : mergeinfo.values()) m.print(file);
            boolean isrwgt = false;
            int ingroup = -1;
            for (WeightInfo wi : weightinfo) {
                if (wi.isrwgt) {
                    if (!isrwgt) file.put("<initrwgt>\n");
                    isrwgt = true;
                } else {
                    if (isrwgt) file.put("</initrwgt>\n");
                    isrwgt = false;
                }
                final int group = wi.inGroup;
                if (group != ingroup) {
                    if (ingroup != -1) file.put("</weightgroup>\n");
                    if (group != -1) {
                        file.put("<weightgroup");
                        oattr(file, "type", weightgroup.get(group).type);
                        if (!weightgroup.get(group).combine.isEmpty()) oattr(file, "combine", weightgroup.get(group).combine);
                        file.put(">\n");
                    }
                    ingroup = group;
                }
                wi.print(file);
            }
            if (ingroup != -1) file.put("</weightgroup>\n");
            if (isrwgt) file.put("</initrwgt>\n");
            file.put(hashline(junk)).put("</init>").endl();
        }

        public void clear() {
            procinfo.clear();
            mergeinfo.clear();
            weightinfo.clear();
            weightgroup.clear();
            cuts.clear();
            ptypes.clear();
            junk = "";
        }

        public void resize(int nrup) {
            NPRUP = nrup;
            resize();
        }

        public void resize() {
            resizeList(XSECUP, NPRUP, 0.0);
            resizeList(XERRUP, NPRUP, 0.0);
            resizeList(XMAXUP, NPRUP, 0.0);
            resizeList(LPRUP, NPRUP, 0);
        }

        public int weightIndex(String name) {
            final Integer i = weightmap.get(name);
            return i == null ? 0 : i;
        }

        public int nWeights() {
            return weightmap.size() + 1;
        }

        public XSecInfo getXSecInfo(String weightname) {
            final XSecInfo xi = xsecinfos.computeIfAbsent(weightname, k -> new XSecInfo());
            xi.weightname = weightname;
            return xi;
        }
    }

    static <T> void resizeList(List<T> l, int n, T fill) {
        while (l.size() > Math.max(0, n)) l.remove(l.size() - 1);
        while (l.size() < n) l.add(fill);
    }

    /* ======================================================================= */
    /* HEPEUP                                                                   */
    /* ======================================================================= */

    /** The subevents of an event group, with their counts. */
    public static final class EventGroup extends ArrayList<HEPEUP> {
        public int nreal = -1;
        public int ncounter = -1;

        public EventGroup() {
        }

        public EventGroup(EventGroup eg) {
            super(eg.size());
            nreal = 0;
            ncounter = 0;
            for (HEPEUP h : eg) add(new HEPEUP(h));
        }

        public EventGroup assign(EventGroup x) {
            if (x == this) return this;
            clear();
            nreal = x.nreal;
            ncounter = x.ncounter;
            for (HEPEUP h : x) add(new HEPEUP(h));
            return this;
        }
    }

    /** A weight value and what it stands for (null for the plain ones). */
    public static final class WeightValue {
        public double first;
        public WeightInfo second;

        public WeightValue(double w, WeightInfo info) {
            first = w;
            second = info;
        }

        public WeightValue(WeightValue o) {
            first = o.first;
            second = o.second;
        }
    }

    /** An event: the HEPEUP common block and the version 3 extras. */
    public static final class HEPEUP extends TagBase {
        public int NUP;
        public int IDPRUP;
        public double XWGTUP;
        public double[] XPDWUP = new double[2];
        public double SCALUP;
        public double AQEDUP;
        public double AQCDUP;
        public List<Long> IDUP = new ArrayList<>();
        public List<Integer> ISTUP = new ArrayList<>();
        public List<int[]> MOTHUP = new ArrayList<>();
        public List<int[]> ICOLUP = new ArrayList<>();
        public List<double[]> PUP = new ArrayList<>();
        public List<Double> VTIMUP = new ArrayList<>();
        public List<Double> SPINUP = new ArrayList<>();
        public HEPRUP heprup;
        public WeightInfo currentWeight;
        public List<Weight> namedweights = new ArrayList<>();
        public List<WeightValue> weights = new ArrayList<>();
        public List<Clus> clustering = new ArrayList<>();
        public PDFInfo pdfinfo = new PDFInfo();
        public int[] PDFGUPsave = new int[2];
        public int[] PDFSUPsave = new int[2];
        public Scales scales = new Scales();
        public int ntries = 1;
        public boolean isGroup;
        public EventGroup subevents = new EventGroup();
        public String junk = "";

        public HEPEUP() {
        }

        /** A copy, as the C++ copy constructor: TagBase copied, then everything set from x. */
        public HEPEUP(HEPEUP x) {
            super(x);
            assign(x);
        }

        public HEPEUP setEvent(HEPEUP x) {
            NUP = x.NUP;
            IDPRUP = x.IDPRUP;
            XWGTUP = x.XWGTUP;
            XPDWUP = x.XPDWUP.clone();
            SCALUP = x.SCALUP;
            AQEDUP = x.AQEDUP;
            AQCDUP = x.AQCDUP;
            IDUP = new ArrayList<>(x.IDUP);
            ISTUP = new ArrayList<>(x.ISTUP);
            MOTHUP = new ArrayList<>();
            for (int[] m : x.MOTHUP) MOTHUP.add(m.clone());
            ICOLUP = new ArrayList<>();
            for (int[] c : x.ICOLUP) ICOLUP.add(c.clone());
            PUP = new ArrayList<>();
            for (double[] p : x.PUP) PUP.add(p.clone());
            VTIMUP = new ArrayList<>(x.VTIMUP);
            SPINUP = new ArrayList<>(x.SPINUP);
            heprup = x.heprup;
            namedweights = new ArrayList<>();
            for (Weight w : x.namedweights) namedweights.add(new Weight(w));
            weights = new ArrayList<>();
            for (WeightValue w : x.weights) weights.add(new WeightValue(w));
            pdfinfo = x.pdfinfo;
            PDFGUPsave = x.PDFGUPsave.clone();
            PDFSUPsave = x.PDFSUPsave.clone();
            clustering = new ArrayList<>(x.clustering);
            scales = x.scales;
            junk = x.junk;
            currentWeight = x.currentWeight;
            ntries = x.ntries;
            return this;
        }

        public HEPEUP assign(HEPEUP x) {
            if (x == this) return this;
            attributes = new TreeMap<>(x.attributes);
            contents = x.contents;
            clear();
            setEvent(x);
            subevents = new EventGroup(x.subevents);
            subevents.nreal = x.subevents.nreal;
            subevents.ncounter = x.subevents.ncounter;
            isGroup = x.isGroup;
            return this;
        }

        /** From an event or eventgroup tag, against the run it belongs to. */
        public HEPEUP(XMLTag tagin, HEPRUP heprupin) {
            super(tagin.attr, "");
            heprup = heprupin;
            isGroup = tagin.name.equals("eventgroup");
            if (heprup.NPRUP < 0) {
                throw new IllegalStateException("Tried to read events but no processes defined in init block of Les Houches file.");
            }
            final List<XMLTag> tags = tagin.tags;
            if (isGroup) {
                final Integer nreal = getInt("nreal");
                if (nreal != null) subevents.nreal = nreal;
                final Integer ncounter = getInt("ncounter");
                if (ncounter != null) subevents.ncounter = ncounter;
                for (XMLTag t : tags) if (t.name.equals("event")) subevents.add(new HEPEUP(t, heprupin));
                return;
            }
            final Integer nt = getInt("ntries");
            if (nt != null) ntries = nt;
            final CIStream iss = new CIStream(tags.isEmpty() ? "" : tags.get(0).contents);
            NUP = iss.nextInt();
            if (!iss.fail()) IDPRUP = iss.nextInt();
            if (!iss.fail()) XWGTUP = iss.nextDouble();
            if (!iss.fail()) SCALUP = iss.nextDouble();
            if (!iss.fail()) AQEDUP = iss.nextDouble();
            if (!iss.fail()) AQCDUP = iss.nextDouble();
            if (iss.fail()) throw new IllegalStateException("Failed to parse event in Les Houches file.");
            resize();
            for (int i = 0; i < NUP; ++i) {
                IDUP.set(i, iss.nextLong());
                if (!iss.fail()) ISTUP.set(i, iss.nextInt());
                if (!iss.fail()) MOTHUP.get(i)[0] = iss.nextInt();
                if (!iss.fail()) MOTHUP.get(i)[1] = iss.nextInt();
                if (!iss.fail()) ICOLUP.get(i)[0] = iss.nextInt();
                if (!iss.fail()) ICOLUP.get(i)[1] = iss.nextInt();
                for (int k = 0; k < 5; k++) if (!iss.fail()) PUP.get(i)[k] = iss.nextDouble();
                if (!iss.fail()) VTIMUP.set(i, iss.nextDouble());
                if (!iss.fail()) SPINUP.set(i, iss.nextDouble());
                if (iss.fail()) throw new IllegalStateException("Failed to parse event in Les Houches file.");
            }
            junk = "";
            final StringBuilder j = new StringBuilder();
            String ss;
            while ((ss = iss.getline()) != null) j.append(ss).append('\n');
            junk = j.toString();
            scales = new Scales(SCALUP, NUP);
            pdfinfo = new PDFInfo(SCALUP);
            namedweights.clear();
            weights.clear();
            resetWeights();
            for (int i = 1; i < tags.size(); ++i) {
                final XMLTag tag = tags.get(i);
                if (tag.name.isEmpty()) junk += tag.contents;
                if (tag.name.equals("weights")) {
                    resetWeights();
                    final CIStream isss = new CIStream(tag.contents);
                    int iii = 0;
                    while (true) {
                        final double w = isss.nextDouble();
                        if (isss.fail()) break;
                        if (++iii < weights.size()) weights.get(iii).first = w;
                        else weights.add(new WeightValue(w, null));
                    }
                }
                if (tag.name.equals("weight")) namedweights.add(new Weight(tag));
                if (tag.name.equals("rwgt")) {
                    for (XMLTag t : tag.tags) if (t.name.equals("wgt")) namedweights.add(new Weight(t));
                } else if (tag.name.equals("clustering")) {
                    for (XMLTag t : tag.tags) if (t.name.equals("clus")) clustering.add(new Clus(t));
                } else if (tag.name.equals("pdfinfo")) {
                    pdfinfo = new PDFInfo(tag, SCALUP);
                } else if (tag.name.equals("scales")) {
                    scales = new Scales(tag, SCALUP, NUP);
                }
            }
            for (Weight nw : namedweights) {
                if (nw.weights.isEmpty()) continue;
                final int indx = heprup.weightIndex(nw.name);
                if (indx > 0) {
                    weights.get(indx).first = nw.weights.get(0);
                    nw.indices.set(0, indx);
                } else {
                    weights.add(new WeightValue(nw.weights.get(0), null));
                    nw.indices.set(0, weights.size() - 1);
                }
                for (int jj = 1; jj < nw.weights.size(); ++jj) {
                    weights.add(new WeightValue(nw.weights.get(jj), null));
                    nw.indices.set(jj, weights.size() - 1);
                }
            }
        }

        /** weights.resize(nWeights, XWGTUP), the first set to XWGTUP and the others pointing to the weight infos. */
        private void resetWeights() {
            final int n = heprup.nWeights();
            while (weights.size() > n) weights.remove(weights.size() - 1);
            while (weights.size() < n) weights.add(new WeightValue(XWGTUP, null));
            weights.get(0).first = XWGTUP;
            for (int ii = 1; ii < weights.size(); ++ii) {
                weights.get(ii).second = ii - 1 < heprup.weightinfo.size() ? heprup.weightinfo.get(ii - 1) : null;
            }
        }

        public void print(COStream file) {
            file.precision(heprup.dprec);
            if (isGroup) {
                file.put("<eventgroup");
                if (subevents.nreal > 0) oattr(file, "nreal", subevents.nreal);
                if (subevents.ncounter > 0) oattr(file, "ncounter", subevents.ncounter);
                printattrs(file);
                file.put(">\n");
                for (HEPEUP h : subevents) h.print(file);
                file.put("</eventgroup>\n");
                return;
            }
            file.put("<event");
            if (ntries > 1) oattr(file, "ntries", ntries);
            printattrs(file);
            file.put(">\n");
            file.put(" ").setw(4).put(NUP);
            file.put(" ").setw(6).put(IDPRUP);
            file.put(" ").setw(14).put(XWGTUP);
            file.put(" ").setw(14).put(SCALUP);
            file.put(" ").setw(14).put(AQEDUP);
            file.put(" ").setw(14).put(AQCDUP).put("\n");
            for (int i = 0; i < NUP; ++i) {
                file.put(" ").setw(8).put(IDUP.get(i));
                file.put(" ").setw(2).put(ISTUP.get(i));
                file.put(" ").setw(4).put(MOTHUP.get(i)[0]);
                file.put(" ").setw(4).put(MOTHUP.get(i)[1]);
                file.put(" ").setw(4).put(ICOLUP.get(i)[0]);
                file.put(" ").setw(4).put(ICOLUP.get(i)[1]);
                for (int k = 0; k < 5; k++) file.put(" ").setw(14).put(PUP.get(i)[k]);
                file.put(" ").setw(1).put(VTIMUP.get(i));
                file.put(" ").setw(1).put(SPINUP.get(i)).endl();
            }
            if (weights.size() > 1) {
                file.put("<weights>");
                for (int i = 1; i < weights.size(); ++i) file.put(" ").put(weights.get(i).first);
                file.put("</weights>\n");
            }
            boolean iswgt = false;
            for (Weight nw : namedweights) {
                if (nw.iswgt) {
                    if (!iswgt) file.put("<rwgt>\n");
                    iswgt = true;
                } else {
                    if (iswgt) file.put("</rwgt>\n");
                    iswgt = false;
                }
                for (int j = 0; j < nw.indices.size(); ++j) nw.weights.set(j, weight(nw.indices.get(j)));
                nw.print(file);
            }
            if (iswgt) file.put("</rwgt>\n");
            if (!clustering.isEmpty()) {
                file.put("<clustering>").endl();
                for (Clus c : clustering) c.print(file);
                file.put("</clustering>").endl();
            }
            pdfinfo.print(file);
            scales.print(file);
            file.put(hashline(junk)).put("</event>\n");
        }

        public void reset() {
            setWeightInfo(0);
            NUP = 0;
            clustering.clear();
            weights.clear();
        }

        public void clear() {
            reset();
            subevents.clear();
        }

        public void resize(int nup) {
            NUP = nup;
            resize();
        }

        public double totalWeight(int i) {
            if (subevents.isEmpty()) return weight(i);
            double w = 0.0;
            for (HEPEUP h : subevents) w += h.weight(i);
            return w;
        }

        public double totalWeight(String name) {
            return totalWeight(heprup.weightIndex(name));
        }

        public double weight(int i) {
            return weights.get(i).first;
        }

        public double weight(String name) {
            return weight(heprup.weightIndex(name));
        }

        public void setWeight(int i, double w) {
            weights.get(i).first = w;
        }

        public boolean setWeight(String name, double w) {
            final int i = heprup.weightIndex(name);
            if (i >= weights.size()) return false;
            setWeight(i, w);
            return true;
        }

        public void resize() {
            resizeList(IDUP, NUP, 0L);
            resizeList(ISTUP, NUP, 0);
            while (MOTHUP.size() > Math.max(0, NUP)) MOTHUP.remove(MOTHUP.size() - 1);
            while (MOTHUP.size() < NUP) MOTHUP.add(new int[2]);
            while (ICOLUP.size() > Math.max(0, NUP)) ICOLUP.remove(ICOLUP.size() - 1);
            while (ICOLUP.size() < NUP) ICOLUP.add(new int[2]);
            while (PUP.size() > Math.max(0, NUP)) PUP.remove(PUP.size() - 1);
            while (PUP.size() < NUP) PUP.add(new double[5]);
            resizeList(VTIMUP, NUP, 0.0);
            resizeList(SPINUP, NUP, 0.0);
        }

        public boolean setWeightInfo(int i) {
            if (i >= weights.size()) return false;
            if (currentWeight != null) {
                scales.mur /= currentWeight.mur;
                scales.muf /= currentWeight.muf;
                heprup.PDFGUP = PDFGUPsave.clone();
                heprup.PDFSUP = PDFSUPsave.clone();
            }
            XWGTUP = weights.get(i).first;
            currentWeight = weights.get(i).second;
            if (currentWeight != null) {
                scales.mur *= currentWeight.mur;
                scales.muf *= currentWeight.muf;
                PDFGUPsave = heprup.PDFGUP.clone();
                PDFSUPsave = heprup.PDFSUP.clone();
                if (currentWeight.pdf != 0) {
                    heprup.PDFGUP[0] = heprup.PDFGUP[1] = 0;
                    heprup.PDFSUP[0] = heprup.PDFSUP[1] = (int) currentWeight.pdf;
                }
                if (currentWeight.pdf2 != 0) heprup.PDFSUP[1] = (int) currentWeight.pdf2;
            }
            return true;
        }

        public boolean setSubEvent(int i) {
            if (i > subevents.size() || subevents.isEmpty()) return false;
            if (i == 0) {
                reset();
                weights = new ArrayList<>();
                for (WeightValue w : subevents.get(0).weights) weights.add(new WeightValue(w));
                for (int ii = 1; ii < subevents.size(); ++ii) {
                    for (int j = 0; j < weights.size(); ++j) weights.get(j).first += subevents.get(ii).weights.get(j).first;
                }
                currentWeight = null;
            } else {
                setEvent(subevents.get(i - 1));
            }
            return true;
        }
    }

    /* ======================================================================= */
    /* Reader                                                                   */
    /* ======================================================================= */

    /** Reads a Les Houches Event File: the init block at construction, then one event per readEvent. */
    public static final class Reader {
        private CInput file;
        private CInput initfile;
        private CInput efile;
        private String currentLine = "";
        public int version;
        public StringBuilder outsideBlock = new StringBuilder();
        public String headerBlock = "";
        public HEPRUP heprup = new HEPRUP();
        public String initComments = "";
        public HEPEUP hepeup = new HEPEUP();
        public String eventComments = "";
        public int currevent = -1;
        public int curreventfile = -1;
        public int currfileevent = -1;
        public String dirpath = "";

        public Reader(CInput is) {
            file = is;
            init();
        }

        public Reader(Path filename) {
            file = CInput.open(filename);
            final String f = filename.toString().replace('\\', '/');
            final int slash = f.lastIndexOf('/');
            if (slash >= 0) dirpath = f.substring(0, slash + 1);
            init();
        }

        private void init() {
            initfile = file;
            boolean readingHeader = false;
            boolean readingInit = false;
            getline();
            if (!currentFind("<LesHouchesEvents")) {
                throw new IllegalStateException("Tried to read a file which does not start with the LesHouchesEvents tag.");
            }
            version = 1;
            if (currentFind("version=\"3")) version = 3;
            else if (currentFind("version=\"2")) version = 2;
            else if (!currentFind("version=\"1")) {
                throw new IllegalStateException("Tried to read a LesHouchesEvents file which is above version 3.");
            }
            final StringBuilder header = new StringBuilder();
            final StringBuilder initc = new StringBuilder();
            while (getline() && !currentFind("</init>")) {
                if (currentFind("<header")) {
                    readingHeader = true;
                    header.setLength(0);
                    header.append(currentLine).append('\n');
                } else if (currentFind("<init>")) {
                    readingInit = true;
                    initc.setLength(0);
                    initc.append(currentLine).append('\n');
                } else if (currentFind("</header>")) {
                    readingHeader = false;
                    header.append(currentLine).append('\n');
                } else if (readingHeader) {
                    header.append(currentLine).append('\n');
                } else if (readingInit) {
                    initc.append(currentLine).append('\n');
                } else {
                    outsideBlock.append(currentLine).append('\n');
                }
            }
            headerBlock = header.toString();
            if (!currentFind("</init>")) throw new IllegalStateException("Found incomplete init tag in Les Houches file.");
            initc.append(currentLine).append('\n');
            initComments = initc.toString();
            for (XMLTag t : XMLTag.findXMLTags(initComments)) {
                if (t.name.equals("init")) {
                    heprup = new HEPRUP(t, version);
                    break;
                }
            }
            boolean foundrwgt = false;
            for (WeightInfo wi : heprup.weightinfo) {
                if (wi.isrwgt) {
                    foundrwgt = true;
                    break;
                }
            }
            if (!foundrwgt) {
                for (XMLTag htag : XMLTag.findXMLTags(headerBlock)) {
                    if (htag.name.equals("header")) {
                        for (XMLTag tag : htag.tags) if (tag.name.equals("initrwgt")) heprup.readInitrwgt(tag);
                        heprup.mapWeightNames();
                        break;
                    }
                }
            }
            if (!heprup.eventfiles.isEmpty()) openeventfile(0);
        }

        /** Reads the next event into hepeup; false at the end. */
        public boolean readEvent() {
            if (heprup.NPRUP < 0) return false;
            final StringBuilder eventLines = new StringBuilder();
            int inEvent = 0;
            while (getline()) {
                if (inEvent != 0) {
                    eventLines.append(currentLine).append('\n');
                    if (inEvent == 1 && currentFind("</event>")) break;
                    if (inEvent == 2 && currentFind("</eventgroup>")) break;
                } else if (currentFind("<eventgroup")) {
                    eventLines.append(currentLine).append('\n');
                    inEvent = 2;
                } else if (currentFind("<event")) {
                    eventLines.append(currentLine).append('\n');
                    inEvent = 1;
                } else {
                    outsideBlock.append(currentLine).append('\n');
                }
            }
            if ((inEvent == 1 && !currentFind("</event>")) || (inEvent == 2 && !currentFind("</eventgroup>"))) {
                if (heprup.eventfiles.isEmpty() || ++curreventfile >= heprup.eventfiles.size()) return false;
                openeventfile(curreventfile);
                return readEvent();
            }
            for (XMLTag t : XMLTag.findXMLTags(eventLines.toString())) {
                if (t.name.equals("event") || t.name.equals("eventgroup")) {
                    hepeup = new HEPEUP(t, heprup);
                    ++currevent;
                    if (curreventfile >= 0) ++currfileevent;
                    return true;
                }
            }
            if (!heprup.eventfiles.isEmpty() && ++curreventfile < heprup.eventfiles.size()) {
                openeventfile(curreventfile);
                return readEvent();
            }
            return false;
        }

        public void openeventfile(int ifile) {
            StdStreams.cerr().println("opening file " + ifile);
            if (efile != null) efile.close();
            String fname = heprup.eventfiles.get(ifile).filename;
            if (fname.isEmpty() || fname.charAt(0) != '/') fname = dirpath + fname;
            efile = CInput.open(CFiles.path(fname));
            if (!efile.isOpen()) throw new IllegalStateException("Could not open event file " + fname);
            file = efile;
            curreventfile = ifile;
            currfileevent = 0;
        }

        private boolean getline() {
            final String l = file.getline();
            if (l == null) return false;
            currentLine = l;
            return true;
        }

        private boolean currentFind(String str) {
            return currentLine.contains(str);
        }

        /** The state of the file the init block came from. */
        public int initfileRdstate() {
            return initfile != null ? initfile.rdstate() : CInput.GOOD;
        }

        /** The state of the file events are read from now. */
        public int fileRdstate() {
            return file != null ? file.rdstate() : CInput.GOOD;
        }

        public void close() {
            if (efile != null) efile.close();
            if (initfile != null) initfile.close();
        }
    }

    /* ======================================================================= */
    /* Writer                                                                   */
    /* ======================================================================= */

    /** Writes a Les Houches Event File: init once, then events; close ends it. */
    public static final class Writer implements AutoCloseable {
        private final COutput initfileOut;
        private COutput fileOut;
        private COutput efile;
        private COStream file;
        private final boolean owns;
        private int lastevent = -1;
        private int curreventfile = -1;
        private int currfileevent = -1;
        private String dirpath = "";
        public HEPRUP heprup = new HEPRUP();
        public HEPEUP hepeup = new HEPEUP();
        private final StringBuilder headerStream = new StringBuilder();
        private final StringBuilder initStream = new StringBuilder();
        private final StringBuilder eventStream = new StringBuilder();
        private boolean closed;

        public Writer(COutput os) {
            initfileOut = os;
            fileOut = os;
            owns = false;
            file = streamOn(os);
        }

        public Writer(Path filename) {
            initfileOut = COutput.create(filename);
            fileOut = initfileOut;
            owns = true;
            file = streamOn(initfileOut);
            final String f = filename.toString().replace('\\', '/');
            final int slash = f.lastIndexOf('/');
            if (slash >= 0) dirpath = f.substring(0, slash + 1);
        }

        private static COStream streamOn(COutput out) {
            return new COStream() {
                @Override
                protected void write(CharSequence text) {
                    out.write(text);
                }
            };
        }

        public StringBuilder headerBlock() {
            return headerStream;
        }

        public StringBuilder initComments() {
            return initStream;
        }

        public StringBuilder eventComments() {
            return eventStream;
        }

        public void headerBlock(String a) {
            headerStream.append(a);
        }

        public void initComments(String a) {
            initStream.append(a);
        }

        public void eventComments(String a) {
            eventStream.append(a);
        }

        public void init() {
            if (heprup.eventfiles.isEmpty()) writeinit();
            lastevent = 0;
            curreventfile = currfileevent = -1;
            if (!heprup.eventfiles.isEmpty()) openeventfile(0);
        }

        public boolean openeventfile(int ifile) {
            if (heprup.eventfiles.isEmpty()) return false;
            if (ifile < 0 || ifile >= heprup.eventfiles.size()) return false;
            if (curreventfile >= 0) {
                final EventFile ef = heprup.eventfiles.get(curreventfile);
                if (ef.neve > 0 && ef.neve != currfileevent) {
                    StdStreams.cerr().println("LHEF::Writer number of events in event file " + ef.filename
                        + " does not match the given number.");
                }
                ef.neve = currfileevent;
            }
            if (efile != null) efile.close();
            String fname = heprup.eventfiles.get(ifile).filename;
            if (fname.isEmpty() || fname.charAt(0) != '/') fname = dirpath + fname;
            efile = COutput.create(CFiles.path(fname));
            if (!efile.isOpen()) throw new IllegalStateException("Could not open event file " + fname);
            StdStreams.cerr().println("Opened event file " + fname);
            fileOut = efile;
            file = streamOn(efile);
            curreventfile = ifile;
            currfileevent = 0;
            return true;
        }

        public void writeinit() {
            if (heprup.version == 3) file.put("<LesHouchesEvents version=\"3.0\">\n");
            else if (heprup.version == 2) file.put("<LesHouchesEvents version=\"2.0\">\n");
            else file.put("<LesHouchesEvents version=\"1.0\">\n");
            file.precision(10);
            String headBlock = headerStream.toString();
            if (!headBlock.isEmpty()) {
                if (!headBlock.contains("<header>")) file.put("<header>\n");
                if (headBlock.charAt(headBlock.length() - 1) != '\n') headBlock += '\n';
                file.put(headBlock);
                if (!headBlock.contains("</header>")) file.put("</header>\n");
            }
            heprup.print(file);
        }

        public void writeEvent() {
            if (!heprup.eventfiles.isEmpty()) {
                if (currfileevent == heprup.eventfiles.get(curreventfile).neve
                        && curreventfile + 1 < heprup.eventfiles.size()) {
                    openeventfile(curreventfile + 1);
                }
            }
            hepeup.print(file);
            ++lastevent;
            ++currfileevent;
        }

        /** What the C++ destructor does: the event file counts, the init when events went elsewhere, the end tag. */
        @Override
        public void close() {
            if (closed) return;
            closed = true;
            file = streamOn(initfileOut);
            if (!heprup.eventfiles.isEmpty()) {
                if (curreventfile >= 0 && curreventfile < heprup.eventfiles.size()
                        && heprup.eventfiles.get(curreventfile).neve < 0) {
                    heprup.eventfiles.get(curreventfile).neve = currfileevent;
                }
                writeinit();
            }
            file.put("</LesHouchesEvents>").endl();
            if (efile != null) efile.close();
            if (owns) initfileOut.close();
            else initfileOut.flush();
        }
    }
}
