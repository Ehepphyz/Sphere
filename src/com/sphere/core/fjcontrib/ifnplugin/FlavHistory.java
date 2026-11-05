package com.sphere.core.fjcontrib.ifnplugin;

import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.PseudoJet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * How the flavor of a particle or jet changed along a clustering,
 * fastjet::contrib::FlavHistory: pairs (history step, flavor), the first
 * the initial flavor, the last the current one. Flavor neutralization
 * appends to it; the user information of a PseudoJet holds it.
 */
public class FlavHistory {

    /** One entry: the cluster-sequence step at which the flavor became this one. */
    public record Step(int histIndex, FlavInfo flavour) {
    }

    private final List<Step> history = new ArrayList<>();

    public FlavHistory(int initialPdgId) {
        history.add(new Step(-1, new FlavInfo(initialPdgId)));
    }

    public FlavHistory(FlavInfo initial) {
        history.add(new Step(-1, initial));
    }

    public FlavHistory(FlavInfo initial, int initialHistStep) {
        history.add(new Step(initialHistStep, initial));
    }

    /** A copy, for a subclass that adds to it. */
    protected FlavHistory(FlavHistory other) {
        history.addAll(other.history);
    }

    public FlavInfo currentFlavour() {
        return history.get(history.size() - 1).flavour();
    }

    public FlavInfo initialFlavour() {
        return history.get(0).flavour();
    }

    /** The step at which the current flavor was acquired. */
    public int currentHistIndex() {
        return history.get(history.size() - 1).histIndex();
    }

    /** The step at which the object was created. */
    public int initialHistIndex() {
        return history.get(0).histIndex();
    }

    public void applyModulo2() {
        for (int i = 0; i < history.size(); i++) {
            final Step s = history.get(i);
            history.set(i, new Step(s.histIndex(), s.flavour().modulo2()));
        }
    }

    /** Labels the latest entry as an incoming beam. */
    public void labelAsBeam() {
        final Step s = history.get(history.size() - 1);
        history.set(history.size() - 1, new Step(s.histIndex(), s.flavour().labelledAsBeam()));
    }

    /** Appends a flavor, unless it is the current one already. */
    public void updateFlavourHistory(FlavInfo newFlavour, int histStep) {
        if (!newFlavour.equals(currentFlavour())) history.add(new Step(histStep, newFlavour));
    }

    /** Sets the step of the latest entry, e.g. when it was created with -1. */
    public void amendLastHistoryIndex(int newHistStep) {
        final Step s = history.get(history.size() - 1);
        history.set(history.size() - 1, new Step(newHistStep, s.flavour()));
    }

    public List<Step> history() {
        return Collections.unmodifiableList(history);
    }

    /** The flavor the object had at a given step. */
    public FlavInfo flavourAtStep(int step) {
        if (history.get(0).histIndex() > step) {
            throw new FastJetException("A particle without FlavHistory was searched for FlavHistory.");
        }
        int indexNeeded = -1;
        for (int i = 1; i < history.size(); i++) {
            if (history.get(i).histIndex() > step && history.get(i - 1).histIndex() <= step) indexNeeded = i - 1;
        }
        return indexNeeded == -1 ? currentFlavour() : history.get(indexNeeded).flavour();
    }

    /** Back to the initial flavor alone. */
    public void resetFlavourHistory() {
        final Step first = history.get(0);
        history.clear();
        history.add(first);
    }

    /** How many times the flavor changed after creation. */
    public int changes() {
        return history.size() - 1;
    }

    /* ------------------------------------------------------------------ */
    /* Of a particle                                                       */
    /* ------------------------------------------------------------------ */

    /** The current flavor of a particle with a FlavHistory, or its FlavInfo. */
    public static FlavInfo currentFlavourOf(PseudoJet particle) {
        if (particle.hasUserInfo(FlavHistory.class)) return particle.userInfo(FlavHistory.class).currentFlavour();
        if (particle.hasUserInfo(FlavInfo.class)) return particle.userInfo(FlavInfo.class);
        throw new FastJetException("A particle without FlavHistory was searched for FlavHistory.");
    }

    /** The initial flavor of a particle with a FlavHistory, or its FlavInfo. */
    public static FlavInfo initialFlavourOf(PseudoJet particle) {
        if (particle.hasUserInfo(FlavHistory.class)) return particle.userInfo(FlavHistory.class).initialFlavour();
        if (particle.hasUserInfo(FlavInfo.class)) return particle.userInfo(FlavInfo.class);
        throw new FastJetException("A particle without FlavHistory was searched for FlavHistory.");
    }

    public static int initialIndexOf(PseudoJet jet) {
        if (jet.hasUserInfo(FlavHistory.class)) return jet.userInfo(FlavHistory.class).initialHistIndex();
        throw new FastJetException("A particle without FlavHistory was searched for FlavHistory.");
    }

    public static int currentIndexOf(PseudoJet jet) {
        if (jet.hasUserInfo(FlavHistory.class)) return jet.userInfo(FlavHistory.class).currentHistIndex();
        throw new FastJetException("A particle without FlavHistory was searched for FlavHistory.");
    }

    /** Whether a particle carries a flavor this package can read. */
    public static boolean hasFlavour(PseudoJet particle) {
        return particle.hasUserInfo(FlavHistory.class) || particle.hasUserInfo(FlavInfo.class);
    }

    @Override
    public String toString() {
        final StringBuilder s = new StringBuilder();
        for (Step st : history) {
            if (s.length() > 0) s.append(" -> ");
            s.append(st.flavour().description().trim()).append('@').append(st.histIndex());
        }
        return s.toString();
    }
}
