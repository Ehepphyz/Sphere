package com.sphere.core.fjcontrib.validation;

import com.sphere.core.fastjet.Precision;
import com.sphere.core.fastjet.PseudoJet;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The read_event functions of the fjcontrib examples: lines of
 * "px py pz E [more]" up to "#END", comment lines skipped, "#SUBSTART"
 * separating the hard event from the pileup ones.
 */
public final class Events {

    private Events() {
    }

    /** The first numbers of a line, as istream >> double reads them (0 where the line ends). */
    public static double[] numbers(String line, int n) {
        final double[] v = new double[n];
        final String[] w = line.trim().split("\\s+");
        for (int k = 0; k < n && k < w.length; k++) {
            if (w[k].isEmpty()) break;
            try {
                v[k] = Double.parseDouble(w[k]);
            } catch (NumberFormatException e) {
                break;
            }
        }
        return v;
    }

    public static PseudoJet particle(double px, double py, double pz, double e) {
        return new PseudoJet(px, py, pz, e, Precision.defaultPrecision());
    }

    /** Every particle up to #END. */
    public static List<PseudoJet> readEvent(BufferedReader in) throws IOException {
        final List<PseudoJet> event = new ArrayList<>();
        String line;
        while ((line = in.readLine()) != null) {
            if (line.startsWith("#END")) break;
            if (line.startsWith("#")) continue;
            final double[] v = numbers(line, 4);
            event.add(particle(v[0], v[1], v[2], v[3]));
        }
        return event;
    }

    /**
     * The read_event of the flavour contribs (IFNPlugin, CMPPlugin, GHSAlgo,
     * SDFPlugin): "px py pz E pdg" lines, '#' lines skipped, an event ending
     * where the next line is empty or the file ends (the C++'s cin.peek()).
     *
     * @param info the user information of a particle from its PDG code, or
     *             null to leave it none and keep the code in the user index
     */
    public static List<PseudoJet> readFlavourEvent(BufferedReader in, java.util.function.IntFunction<Object> info)
            throws IOException {
        final List<PseudoJet> event = new ArrayList<>();
        String line;
        while ((line = in.readLine()) != null) {
            if (line.startsWith("#")) continue;
            final double[] v = numbers(line, 5);
            final PseudoJet p = particle(v[0], v[1], v[2], v[3]);
            final int pdg = (int) v[4];
            if (info != null) p.setUserInfo(info.apply(pdg));
            else p.setUserIndex(pdg);
            event.add(p);
            in.mark(2);
            final int next = in.read();
            in.reset();
            if (next == '\n' || next == '\r' || next == -1) {
                in.readLine();
                break;
            }
        }
        return event;
    }

    /**
     * The read_event of DynamicR: particles up to an empty line or #END,
     * '#' lines skipped.
     */
    public static List<PseudoJet> readUntilEmptyLine(BufferedReader in) throws IOException {
        final List<PseudoJet> event = new ArrayList<>();
        String line;
        while ((line = in.readLine()) != null) {
            if (line.startsWith("#END")) break;
            if (line.isEmpty()) break;
            if (line.startsWith("#")) continue;
            final double[] v = numbers(line, 4);
            event.add(particle(v[0], v[1], v[2], v[3]));
        }
        return event;
    }

    /** An unsigned command-line argument as the C++ reads it (stoi into an unsigned: -1 is "no limit"). */
    public static long unsignedArg(String[] args, int k, long fallback) {
        if (args == null || k >= args.length) return fallback;
        return Integer.parseInt(args[k].trim()) & 0xFFFFFFFFL;
    }

    /** The hard event (the first sub-event) and the full one, with the count of pileup events. */
    public record HardAndFull(List<PseudoJet> hard, List<PseudoJet> full, int nsub) {
    }

    /** The hard and full events with their charged particles (pid and charge columns), and the pileup count. */
    public record WithCharged(List<PseudoJet> hard, List<PseudoJet> full, List<PseudoJet> hardCharged,
                              List<PseudoJet> pileupCharged, int nsub) {
    }

    /**
     * The read_event of ConstituentSubtractor's functions.hh: "px py pz E pid
     * charge", or "pt y phi pid charge" after a #PTRAPPHI line; |charge| &gt;
     * 0.99 marks a charged particle.
     */
    public static WithCharged readWithCharged(BufferedReader in) throws IOException {
        List<PseudoJet> hard = new ArrayList<>();
        final List<PseudoJet> full = new ArrayList<>();
        final List<PseudoJet> hardCharged = new ArrayList<>();
        final List<PseudoJet> pileupCharged = new ArrayList<>();
        int nsub = 0;
        boolean ptRapPhi = false;
        String line;
        while ((line = in.readLine()) != null) {
            if (line.startsWith("#PTRAPPHI")) ptRapPhi = true;
            if (line.startsWith("#END")) break;
            if (line.startsWith("#SUBSTART")) {
                if (nsub == 1) hard = new ArrayList<>(full);
                nsub += 1;
            }
            if (line.startsWith("#")) continue;
            final PseudoJet particle = particle(0, 0, 1, 1);
            final double charge;
            if (ptRapPhi) {
                final double[] v = numbers(line, 5);
                particle.resetPtYPhiM(v[0], v[1], v[2], 0.0);
                charge = v[4];
            } else {
                final double[] v = numbers(line, 6);
                particle.reset(v[0], v[1], v[2], v[3]);
                charge = v[5];
            }
            if (nsub <= 1) {
                if (Math.abs(charge) > 0.99) hardCharged.add(particle);
            } else {
                if (Math.abs(charge) > 0.99) pileupCharged.add(particle);
            }
            full.add(particle);
        }
        if (nsub == 1) hard = new ArrayList<>(full);
        if (nsub == 0) throw new IOException("Error: read empty event");
        return new WithCharged(hard, full, hardCharged, pileupCharged, nsub);
    }

    public static HardAndFull readHardAndFull(BufferedReader in) throws IOException {
        List<PseudoJet> hard = new ArrayList<>();
        final List<PseudoJet> full = new ArrayList<>();
        int nsub = 0;
        String line;
        while ((line = in.readLine()) != null) {
            if (line.startsWith("#END")) break;
            if (line.startsWith("#SUBSTART")) {
                if (nsub == 1) hard = new ArrayList<>(full);
                nsub += 1;
            }
            if (line.startsWith("#")) continue;
            final double[] v = numbers(line, 4);
            full.add(particle(v[0], v[1], v[2], v[3]));
        }
        if (nsub == 1) hard = new ArrayList<>(full);
        if (nsub == 0) throw new IOException("Error: read empty event");
        return new HardAndFull(hard, full, nsub);
    }
}
