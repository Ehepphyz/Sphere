package com.sphere.core.fastjet.plugins;

import com.sphere.core.fastjet.Citations;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.DD;
import com.sphere.core.fastjet.DefaultRecombiner;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.Precision;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.RecombinationScheme;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;

/**
 * fastjet::TrackJetPlugin, the track-jet algorithm of Rivet (A. Buckley,
 * M. Bahr): starting from the hardest particle, every particle within R of
 * the growing jet's axis (in y-phi, the axis being recombined with the track
 * scheme) is added to it, in decreasing pt; the jet is then complete and the
 * next hardest remaining particle starts a new one.
 */
public final class TrackJetPlugin implements JetDefinition.Plugin {

    private static final String BANNER = String.join("\n",
        "#-------------------------------------------------------------------------",
        "# You are running the TrackJet plugin for FastJet. It is based on         ",
        "# the implementation by Andy Buckley and Manuel Bahr that is to be        ",
        "# found in Rivet 1.1.2. See http://www.hepforge.org/downloads/rivet.      ",
        "#-------------------------------------------------------------------------");

    private final double radius;
    private final double radius2;
    private final DefaultRecombiner jetRecombiner;
    private final DefaultRecombiner trackRecombiner;

    public TrackJetPlugin(double radius) {
        this(radius, RecombinationScheme.PT_SCHEME, RecombinationScheme.PT_SCHEME);
    }

    public TrackJetPlugin(double radius, RecombinationScheme jetScheme, RecombinationScheme trackScheme) {
        this.radius = radius;
        this.radius2 = radius * radius;
        this.jetRecombiner = new DefaultRecombiner(jetScheme);
        this.trackRecombiner = new DefaultRecombiner(trackScheme);
    }

    @Override
    public String description() {
        return "TrackJet algorithm with R = " + Fmt.g(R());
    }

    @Override
    public double R() {
        return radius;
    }

    @Override
    public void runClustering(ClusterSequence cs) {
        Citations.plugin("TrackJet", BANNER);
        final boolean dd = cs.precision() == Precision.DD;
        final List<PseudoJet> jets = cs.jets();
        final int n = jets.size();

        // indices in decreasing pt, ties keeping their order
        final List<Integer> order = new ArrayList<>(n);
        for (int i = 0; i < n; i++) order.add(i);
        if (dd) {
            order.sort(Comparator.comparing((Integer i) -> jets.get(i).kt2DD()).reversed());
        } else {
            order.sort((a, b) -> Double.compare(jets.get(b).perp2(), jets.get(a).perp2()));
        }

        final List<PseudoJet> tunedParticles = new ArrayList<>(n);
        final List<PseudoJet> tunedTracks = new ArrayList<>(n);
        for (PseudoJet p : jets) {
            final PseudoJet a = p.copy();
            jetRecombiner.preprocess(a);
            tunedParticles.add(a);
            final PseudoJet b = p.copy();
            trackRecombiner.preprocess(b);
            tunedTracks.add(b);
        }

        final LinkedList<Integer> sorted = new LinkedList<>(order);
        final DD radius2DD = DD.of(radius).sqr();
        while (!sorted.isEmpty()) {
            int currentJetIndex = sorted.removeFirst();
            PseudoJet currentJet = tunedParticles.get(currentJetIndex);
            PseudoJet currentTrack = tunedTracks.get(currentJetIndex);
            final ListIterator<Integer> it = sorted.listIterator();
            while (it.hasNext()) {
                final int idx = it.next();
                final PseudoJet particle = tunedParticles.get(idx);
                final PseudoJet track = tunedTracks.get(idx);
                final DD distance2;
                final boolean inside;
                if (dd) {
                    distance2 = currentTrack.squaredDistanceDD(track);
                    inside = distance2.le(radius2DD);
                } else {
                    final double d2 = currentTrack.plainDistance(track);
                    distance2 = new DD(d2, 0.0);
                    inside = d2 <= radius2;
                }
                if (!inside) continue;
                final PseudoJet newTrack = new PseudoJet();
                final PseudoJet newJet = new PseudoJet();
                jetRecombiner.recombine(currentJet, particle, newJet);
                trackRecombiner.recombine(currentTrack, track, newTrack);
                currentJetIndex = cs.pluginRecordIJRecombination(currentJetIndex, idx, distance2, newJet);
                currentJet = newJet;
                currentTrack = newTrack;
                it.remove();
                // FastJet starts the scan over after each merging
                while (it.hasPrevious()) it.previous();
            }
            if (dd) {
                cs.pluginRecordIBRecombination(currentJetIndex, radius2DD);
            } else {
                cs.pluginRecordIBRecombination(currentJetIndex, radius2);
            }
        }
    }
}
