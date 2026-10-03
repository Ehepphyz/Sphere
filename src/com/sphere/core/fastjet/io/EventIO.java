package com.sphere.core.fastjet.io;

import com.sphere.core.fastjet.PseudoJet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Events read from the files a jet analysis meets: FastJet's own text
 * (px py pz E per line, "#END" between events), Les Houches event files
 * (final-state particles, status 1), HepMC 2 and 3 ASCII (status 1), and
 * CSV (px,py,pz,E[,event]). Each particle keeps its PDG code in the user
 * index when the format gives one, and the event its weight.
 */
public final class EventIO {

    private EventIO() {
    }

    public enum Format { FASTJET, LHE, HEPMC, CSV }

    /** One event: its final-state particles and weight, and its incoming partons when the file says. */
    public record Event(List<PseudoJet> particles, double weight, Incoming incoming) {
        public Event(List<PseudoJet> particles, double weight) {
            this(particles, weight, null);
        }
    }

    /**
     * The two partons that made the event and the scale they were taken at.
     *
     * Only a Les Houches file carries them, and they are what reweighting an
     * event to another PDF needs: the momentum fractions come from the beam
     * energies of the init block, the scale is SCALUP.
     */
    public record Incoming(int id1, int id2, double x1, double x2, double scale) {
    }

    /** The format a file's name or first lines say it is in. */
    public static Format guess(Path file) throws IOException {
        final String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".lhe") || name.endsWith(".lhe.txt")) return Format.LHE;
        if (name.endsWith(".hepmc") || name.endsWith(".hepmc2") || name.endsWith(".hepmc3")) return Format.HEPMC;
        if (name.endsWith(".csv")) return Format.CSV;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            for (int k = 0; k < 20; k++) {
                final String line = r.readLine();
                if (line == null) break;
                if (line.contains("<LesHouchesEvents")) return Format.LHE;
                if (line.startsWith("HepMC::")) return Format.HEPMC;
                if (line.indexOf(',') >= 0 && !line.startsWith("#")) return Format.CSV;
            }
        }
        return Format.FASTJET;
    }

    public static List<Event> read(Path file, Format format, int max) throws IOException {
        return switch (format) {
            case FASTJET -> readFastJet(file, max);
            case LHE -> readLhe(file, max);
            case HEPMC -> readHepMC(file, max);
            case CSV -> readCsv(file, max);
        };
    }

    /**
     * "px py pz E [pdg]" lines, events ending at "#END"; in a file without
     * any "#END", an empty line ends the event instead, as in fjcontrib's
     * flavour samples (pythia8_Zq_vshort.dat).
     */
    private static List<Event> readFastJet(Path file, int max) throws IOException {
        boolean endMarkers = false;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null && !endMarkers) endMarkers = line.startsWith("#END");
        }
        final List<Event> events = new ArrayList<>();
        List<PseudoJet> cur = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null && events.size() < max) {
                if (line.startsWith("#END") || (!endMarkers && line.isBlank() && !cur.isEmpty())) {
                    events.add(new Event(cur, 1.0));
                    cur = new ArrayList<>();
                    continue;
                }
                if (line.startsWith("#") || line.isBlank()) continue;
                final String[] w = line.trim().split("\\s+");
                if (w.length < 4) continue;
                final PseudoJet p = new PseudoJet(num(w[0]), num(w[1]), num(w[2]), num(w[3]));
                if (w.length >= 5) p.setUserIndex((int) num(w[4]));
                cur.add(p);
            }
        }
        if (!cur.isEmpty() && events.size() < max) events.add(new Event(cur, 1.0));
        return events;
    }

    private static List<Event> readLhe(Path file, int max) throws IOException {
        final List<Event> events = new ArrayList<>();
        double eb1 = Double.NaN;
        double eb2 = Double.NaN;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null && events.size() < max) {
                if (line.trim().startsWith("<init") && Double.isNaN(eb1)) {
                    final String[] v = r.readLine().trim().split("\\s+");
                    if (v.length > 3) {
                        eb1 = num(v[2]);
                        eb2 = num(v[3]);
                    }
                    continue;
                }
                if (!line.trim().startsWith("<event")) continue;
                final String[] head = r.readLine().trim().split("\\s+");
                final int nup = Integer.parseInt(head[0]);
                final double weight = head.length > 2 ? num(head[2]) : 1.0;
                final double scale = head.length > 3 ? num(head[3]) : Double.NaN;
                final List<PseudoJet> ps = new ArrayList<>();
                int id1 = 0;
                int id2 = 0;
                double x1 = Double.NaN;
                double x2 = Double.NaN;
                for (int k = 0; k < nup; k++) {
                    final String[] w = r.readLine().trim().split("\\s+");
                    final int status = Integer.parseInt(w[1]);
                    if (status == -1 && !Double.isNaN(eb1)) {
                        // The same assignment :lpdf reweight makes: the parton
                        // moving along +z came from the first beam.
                        final double pz = num(w[8]);
                        final double e = num(w[9]);
                        if (pz >= 0 && id1 == 0) {
                            id1 = Integer.parseInt(w[0]);
                            x1 = (e + Math.abs(pz)) / (2 * eb1);
                        } else {
                            id2 = Integer.parseInt(w[0]);
                            x2 = (e + Math.abs(pz)) / (2 * eb2);
                        }
                        continue;
                    }
                    if (status != 1) continue;
                    final PseudoJet p = new PseudoJet(num(w[6]), num(w[7]), num(w[8]), num(w[9]));
                    p.setUserIndex(Integer.parseInt(w[0]));
                    ps.add(p);
                }
                final Incoming in = id1 != 0 && id2 != 0 && !Double.isNaN(scale)
                    ? new Incoming(id1, id2, x1, x2, scale) : null;
                events.add(new Event(ps, weight, in));
            }
        }
        return events;
    }

    private static List<Event> readHepMC(Path file, int max) throws IOException {
        final List<Event> events = new ArrayList<>();
        boolean v3 = false;
        List<PseudoJet> cur = null;
        double weight = 1.0;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("HepMC::Asciiv3") || line.startsWith("HepMC::Version 3")) v3 = true;
                if (line.startsWith("E ")) {
                    if (cur != null) {
                        events.add(new Event(cur, weight));
                        if (events.size() >= max) return events;
                    }
                    cur = new ArrayList<>();
                    weight = 1.0;
                } else if (line.startsWith("W ") && cur != null) {
                    final String[] w = line.trim().split("\\s+");
                    if (w.length > 1) weight = num(w[v3 ? 1 : 2]);
                } else if (line.startsWith("P ") && cur != null) {
                    final String[] w = line.trim().split("\\s+");
                    // v2: P barcode pdg px py pz e m status ...; v3: P id parent pdg px py pz e m status
                    final int o = v3 ? 1 : 0;
                    final int status = Integer.parseInt(w[8 + o]);
                    if (status != 1) continue;
                    final PseudoJet p = new PseudoJet(num(w[3 + o]), num(w[4 + o]), num(w[5 + o]), num(w[6 + o]));
                    p.setUserIndex(Integer.parseInt(w[2 + o]));
                    cur.add(p);
                }
            }
        }
        if (cur != null && events.size() < max) events.add(new Event(cur, weight));
        return events;
    }

    private static List<Event> readCsv(Path file, int max) throws IOException {
        final List<Event> events = new ArrayList<>();
        List<PseudoJet> cur = new ArrayList<>();
        String currentId = null;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                final String[] w = line.split("[,;]");
                if (w.length < 4 || !isNumber(w[0])) continue;
                final String id = w.length > 4 ? w[4].trim() : "0";
                if (currentId != null && !id.equals(currentId)) {
                    events.add(new Event(cur, 1.0));
                    if (events.size() >= max) return events;
                    cur = new ArrayList<>();
                }
                currentId = id;
                cur.add(new PseudoJet(num(w[0]), num(w[1]), num(w[2]), num(w[3])));
            }
        }
        if (!cur.isEmpty() && events.size() < max) events.add(new Event(cur, 1.0));
        return events;
    }

    /** Writes events in FastJet's text format. */
    public static void writeFastJet(Path file, List<List<PseudoJet>> events) throws IOException {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            for (List<PseudoJet> ev : events) {
                for (PseudoJet p : ev) {
                    w.printf(Locale.ROOT, "%.17g %.17g %.17g %.17g%n", p.px(), p.py(), p.pz(), p.E());
                }
                w.println("#END");
            }
        }
    }

    private static boolean isNumber(String s) {
        try {
            Double.parseDouble(s.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static double num(String s) {
        return Double.parseDouble(s.trim().replace('D', 'E').replace('d', 'e'));
    }
}
