package com.sphere.core.fjcontrib.ifnplugin;

import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.PseudoJet;

import java.util.Arrays;

/**
 * The flavour content of a particle, fastjet::contrib::FlavInfo (IFNPlugin
 * 1.0.4; F. Caola, R. Grabarczyk, M. Hutt, G.P. Salam, L. Scyboz and
 * J. Thaler, Phys. Rev. D 108 (2023) 094010, arXiv:2306.07314): the net
 * number of d, u, s, c, b and t quarks, read from a PDG code or given
 * directly, with flags for incoming beams and spectators.
 *
 * <p>The C++ class is a mutable value copied at every assignment; here it is
 * immutable, each operation answering a new flavour, so that a flavour kept
 * in a history can never be changed behind its back. Equality compares the
 * six flavours and the flags, as the C++ operator== does.
 */
public final class FlavInfo {

    /** Flag of an incoming beam particle. */
    public static final int BEAM = 2;
    /** Flag of a spectator, a W for instance: it sets beam distances but takes part in no clustering. */
    public static final int SPECTATOR = 4;
    static final int IS_FLAVOURLESS = 1;

    private static final String FLAVS = "duscbt";
    private static final FlavInfo NO_FLAV = new FlavInfo();

    /** [0] the flags, [1..6] the net number of d, u, s, c, b, t. */
    private final int[] content = new int[7];
    private final int pdgCode;

    /** No flavour: a gluon, a photon, a lepton. */
    public FlavInfo() {
        this(0, 0);
    }

    /** The flavour of a PDG code: quarks, diquarks, mesons and baryons are understood. */
    public FlavInfo(int pdgCode) {
        this(pdgCode, 0);
    }

    /**
     * The flavour of a PDG code, with flags ({@link #BEAM}, {@link #SPECTATOR}).
     * A code whose quark content cannot be read is refused, where the C++
     * prints a message and exits the program.
     */
    public FlavInfo(int pdgCode, int flags) {
        this.pdgCode = pdgCode;
        content[0] = flags;
        if (pdgCode != 0) {
            int netsign = pdgCode >= 0 ? +1 : -1;
            int code = Math.abs(pdgCode);
            final int[] digit = new int[4];
            int ndigits = 0;
            for (int i = 0; i < 4; i++) {
                digit[i] = code % 10;
                if (digit[i] != 0) ndigits = i + 1;
                code /= 10;
            }
            if (ndigits == 1) {
                // a lone quark
                if (digit[0] > 6 || digit[0] == 0) throw unreadable(pdgCode);
                content[digit[0]] = netsign;
            } else if (ndigits == 2) {
                // a lepton, a photon, a gluon, a cluster: no flavour kept
            } else {
                for (int i = 1; i < ndigits; i++) {
                    if (digit[i] > 6) throw unreadable(pdgCode);
                }
                if (ndigits == 4) {
                    // diquark [nm0x] or baryon [nmpx]
                    for (int i = 1; i < ndigits; i++) {
                        if (digit[i] > 0) content[digit[i]] += netsign;
                    }
                } else if (ndigits == 3) {
                    // meson [nmx]: by the PDG convention a K+ or a B+ is the
                    // particle, so the signs of down-type heavy mesons flip
                    if (digit[2] == 3 || digit[2] == 5) netsign = -netsign;
                    content[digit[2]] += netsign;
                    content[digit[1]] -= netsign;
                } else {
                    throw unreadable(pdgCode);
                }
            }
        }
        updateFlavourlessAttribute();
    }

    /** From the net numbers of each flavour. */
    public FlavInfo(int nd, int nu, int ns, int nc, int nb, int nt) {
        this(nd, nu, ns, nc, nb, nt, 0);
    }

    public FlavInfo(int nd, int nu, int ns, int nc, int nb, int nt, int flags) {
        this.pdgCode = 0;
        content[0] = flags;
        content[1] = nd;
        content[2] = nu;
        content[3] = ns;
        content[4] = nc;
        content[5] = nb;
        content[6] = nt;
        updateFlavourlessAttribute();
    }

    /** A flavour from its seven words as they stand, the flavourless flag recomputed. */
    FlavInfo(int[] words, int pdgCode) {
        this.pdgCode = pdgCode;
        System.arraycopy(words, 0, content, 0, 7);
        updateFlavourlessAttribute();
    }

    private static FastJetException unreadable(int code) {
        return new FastJetException("FlavInfo failed to understand pdg_code = " + code);
    }

    private void updateFlavourlessAttribute() {
        for (int i = 1; i <= 6; i++) {
            if (content[i] != 0) {
                content[0] &= ~IS_FLAVOURLESS;
                return;
            }
        }
        content[0] |= IS_FLAVOURLESS;
    }

    /* ------------------------------------------------------------------ */
    /* Reading                                                             */
    /* ------------------------------------------------------------------ */

    /** The net number of quarks of flavour iflv, 1 = d to 6 = t (0 gives the flags). */
    public int get(int iflv) {
        return content[iflv];
    }

    /** The seven words, a copy. */
    int[] words() {
        return content.clone();
    }

    /** The PDG code it was made from, 0 when unknown (a recombination, for instance). */
    public int pdgCode() {
        return pdgCode;
    }

    public boolean isBeam() {
        return (content[0] & BEAM) != 0;
    }

    public boolean isSpectator() {
        return (content[0] & SPECTATOR) != 0;
    }

    public boolean isFlavourless() {
        return (content[0] & IS_FLAVOURLESS) != 0;
    }

    public boolean isFlavorless() {
        return isFlavourless();
    }

    /** More than one unit of flavour in all. */
    public boolean isMultiflavoured() {
        int sum = 0;
        for (int i = 1; i <= 6; i++) sum += Math.abs(content[i]);
        return sum > 1;
    }

    /**
     * Whether the particle carries a FlavInfo with some flavour of the
     * opposite sign to this one's. Not a test that the two cancel: add them
     * and ask whether the sum is flavourless for that.
     */
    public boolean hasOppositeFlavour(PseudoJet particle) {
        if (!particle.hasUserInfo(FlavInfo.class)) return false;
        final FlavInfo other = particle.userInfo(FlavInfo.class);
        for (int i = 1; i <= 6; i++) {
            if (other.content[i] * content[i] < 0) return true;
        }
        return false;
    }

    /** The total number of heavy quarks (c, b, t) whatever their sign, for summaries. */
    public int heavyCount() {
        return Math.abs(content[4]) + Math.abs(content[5]) + Math.abs(content[6]);
    }

    /* ------------------------------------------------------------------ */
    /* New flavours from this one                                          */
    /* ------------------------------------------------------------------ */

    /** The sum; beam, spectator and PDG code are lost, as in the C++. */
    public FlavInfo plus(FlavInfo o) {
        return new FlavInfo(content[1] + o.content[1], content[2] + o.content[2], content[3] + o.content[3],
            content[4] + o.content[4], content[5] + o.content[5], content[6] + o.content[6]);
    }

    /** The difference; beam, spectator and PDG code are lost. */
    public FlavInfo minus(FlavInfo o) {
        return new FlavInfo(content[1] - o.content[1], content[2] - o.content[2], content[3] - o.content[3],
            content[4] - o.content[4], content[5] - o.content[5], content[6] - o.content[6]);
    }

    /** Each flavour modulo 2, apply_modulo_2(). */
    public FlavInfo modulo2() {
        final int[] w = content.clone();
        for (int i = 1; i <= 6; i++) w[i] = Math.abs(w[i] % 2);
        return new FlavInfo(w, pdgCode);
    }

    /** Each flavour present counted once, apply_any_abs(). */
    public FlavInfo anyAbs() {
        final int[] w = content.clone();
        for (int i = 1; i <= 6; i++) w[i] = w[i] == 0 ? 0 : 1;
        return new FlavInfo(w, pdgCode);
    }

    /**
     * Only the flavour iflv kept, reset_all_but_flav(): b-tagging at hadron
     * level, where the other flavours are neither needed nor well known.
     */
    public FlavInfo onlyFlav(int iflv) {
        final int[] w = content.clone();
        for (int i = 1; i <= 6; i++) {
            if (i != iflv) w[i] = 0;
        }
        return new FlavInfo(w, pdgCode);
    }

    /** With n quarks of flavour iflv, set_flav(). */
    public FlavInfo withFlav(int iflv, int n) {
        final int[] w = content.clone();
        w[iflv] = n;
        return new FlavInfo(w, pdgCode);
    }

    public FlavInfo labelledAsBeam() {
        final int[] w = content.clone();
        w[0] |= BEAM;
        return new FlavInfo(w, pdgCode);
    }

    public FlavInfo labelledAsSpectator() {
        final int[] w = content.clone();
        w[0] |= SPECTATOR;
        return new FlavInfo(w, pdgCode);
    }

    /* ------------------------------------------------------------------ */
    /* Text                                                                */
    /* ------------------------------------------------------------------ */

    /** "[u ]", "[bbar bbar ]", "[g]" for no flavour, as the C++ writes them. */
    public String description() {
        final StringBuilder s = new StringBuilder("[");
        if (isFlavourless()) {
            s.append('g');
        } else {
            for (int iflav = 1; iflav <= 6; iflav++) {
                final int n = content[iflav];
                for (int i = 0; i < Math.abs(n); i++) {
                    s.append(FLAVS.charAt(iflav - 1));
                    if (n < 0) s.append("bar");
                    s.append(' ');
                }
            }
        }
        s.append(']');
        if (isBeam()) s.append("(beam) ");
        if (isSpectator()) s.append("(spectator) ");
        return s.toString();
    }

    /** The same without brackets or spaces, "u", "bbar-bbar", "g": for tables and file columns. */
    public String label() {
        if (isFlavourless()) return "g";
        final StringBuilder s = new StringBuilder();
        for (int iflav = 1; iflav <= 6; iflav++) {
            final int n = content[iflav];
            for (int i = 0; i < Math.abs(n); i++) {
                if (s.length() > 0) s.append('-');
                s.append(FLAVS.charAt(iflav - 1));
                if (n < 0) s.append("bar");
            }
        }
        return s.toString();
    }

    @Override
    public String toString() {
        return description();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FlavInfo f && Arrays.equals(content, f.content);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(content);
    }

    /* ------------------------------------------------------------------ */
    /* Of a particle                                                       */
    /* ------------------------------------------------------------------ */

    /**
     * The FlavInfo a particle carries, or no flavour when it carries none. A
     * particle carrying a FlavHistory is refused: its flavour depends on the
     * step asked for, see {@link FlavHistory#currentFlavourOf}.
     */
    public static FlavInfo flavourOf(PseudoJet particle) {
        if (particle.hasUserInfo(FlavInfo.class)) return particle.userInfo(FlavInfo.class);
        if (particle.hasUserInfo(FlavHistory.class)) {
            throw new FastJetException("FlavInfo::flavour_of called on particle with FlavHistory. "
                + "Use FlavHistory::current_flavour_of(...) or FlavHistory::initial_flavour_of(...) instead");
        }
        return NO_FLAV;
    }

    /** No flavour, shared. */
    public static FlavInfo none() {
        return NO_FLAV;
    }

    /**
     * The flavour of the PDG code a particle carries as its user index, where
     * Sphere's event readers (LHE, HepMC, FastJet text with a fifth column)
     * put it; no flavour when the index is not a code FlavInfo can read.
     * Sphere's addition, so that any loaded event can feed a flavour algorithm.
     *
     * <p>-1 is read as a dbar: it is FastJet's default index too, so whether
     * an event carries PDG codes at all is for the caller to decide, from the
     * event as a whole (one whose indices are all -1 carries none).
     */
    public static FlavInfo fromUserIndex(PseudoJet particle) {
        final int code = particle.userIndex();
        if (code == 0) return NO_FLAV;
        try {
            return new FlavInfo(code);
        } catch (FastJetException unreadable) {
            return NO_FLAV;
        }
    }
}
