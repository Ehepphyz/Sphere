package com.sphere.core.fjcontrib.nsubjettiness;

import com.sphere.core.fastjet.PseudoJet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** How the particles were shared between the N jet regions and the beam, TauPartition. */
public class TauPartition {

    private final List<List<Integer>> jetsList = new ArrayList<>();
    private final List<Integer> beamList = new ArrayList<>();
    private final List<List<PseudoJet>> jetsPartition = new ArrayList<>();
    private final List<PseudoJet> beamPartition = new ArrayList<>();

    public TauPartition() {
    }

    public TauPartition(int nJet) {
        for (int i = 0; i < nJet; i++) {
            jetsList.add(new ArrayList<>());
            jetsPartition.add(new ArrayList<>());
        }
    }

    public void pushBackJet(int jetNum, PseudoJet partToAdd, int partIndex) {
        jetsList.get(jetNum).add(partIndex);
        jetsPartition.get(jetNum).add(partToAdd);
    }

    public void pushBackBeam(PseudoJet partToAdd, int partIndex) {
        beamList.add(partIndex);
        beamPartition.add(partToAdd);
    }

    /** Jet region jetNum, a composite of its particles. */
    public PseudoJet jet(int jetNum) {
        return PseudoJet.join(jetsPartition.get(jetNum));
    }

    public PseudoJet beam() {
        return PseudoJet.join(beamPartition);
    }

    public List<PseudoJet> jets() {
        final List<PseudoJet> out = new ArrayList<>();
        for (int i = 0; i < jetsPartition.size(); i++) out.add(jet(i));
        return out;
    }

    /** The particles of a region, by their index in the input. */
    public List<Integer> jetList(int jetNum) {
        return Collections.unmodifiableList(jetsList.get(jetNum));
    }

    public List<Integer> beamList() {
        return Collections.unmodifiableList(beamList);
    }

    /** Copies of every region's index list. */
    public List<List<Integer>> jetsList() {
        final List<List<Integer>> out = new ArrayList<>();
        for (List<Integer> l : jetsList) out.add(new ArrayList<>(l));
        return out;
    }

    /** The particles of a region themselves (not joined). */
    List<PseudoJet> jetParticles(int jetNum) {
        return jetsPartition.get(jetNum);
    }

    List<PseudoJet> beamParticles() {
        return beamPartition;
    }
}
