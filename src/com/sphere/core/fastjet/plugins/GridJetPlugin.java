package com.sphere.core.fastjet.plugins;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.RectangularGrid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * fastjet::GridJetPlugin: the particles of each cell of a rectangular
 * (rapidity, phi) grid are merged into one jet, which can then optionally be
 * clustered by a further jet definition. Particles outside the grid are
 * left unclustered.
 */
public final class GridJetPlugin implements JetDefinition.Plugin {

    private final RectangularGrid grid;
    private final JetDefinition postJetDef;

    public GridJetPlugin(double ymax, double requestedGridSpacing) {
        this(ymax, requestedGridSpacing, new JetDefinition());
    }

    public GridJetPlugin(double ymax, double requestedGridSpacing, JetDefinition postJetDef) {
        this(new RectangularGrid(ymax, requestedGridSpacing), postJetDef);
    }

    public GridJetPlugin(RectangularGrid grid) {
        this(grid, new JetDefinition());
    }

    public GridJetPlugin(RectangularGrid grid, JetDefinition postJetDef) {
        if (!grid.isInitialised()) {
            throw new FastJetException("attempt to construct GridJetPlugin with uninitialised RectangularGrid");
        }
        this.grid = new RectangularGrid(grid);
        this.postJetDef = postJetDef;
    }

    public RectangularGrid grid() {
        return grid;
    }

    @Override
    public String description() {
        final StringBuilder desc = new StringBuilder("GridJetPlugin plugin with ").append(grid.description());
        if (postJetDef.jetAlgorithm() != JetAlgorithm.UNDEFINED) {
            desc.append(", followed by ").append(postJetDef.description());
        }
        return desc.toString();
    }

    /** The radius of a circle of the cell's area. */
    @Override
    public double R() {
        return Math.sqrt(grid.drap() * grid.dphi() / Math.PI);
    }

    @Override
    public void runClustering(ClusterSequence cs) {
        final int[] cell = new int[grid.nTiles()];
        Arrays.fill(cell, -1);
        final int nparticles = cs.nJets();
        final double dijOrDiB = 1.0;
        int ngridActive = 0;
        for (int i = 0; i < nparticles; i++) {
            final int igrd = grid.tileIndex(cs.jet(i));
            if (igrd < 0) continue;
            if (cell[igrd] == -1) {
                cell[igrd] = i;
                ngridActive++;
            } else {
                cell[igrd] = cs.pluginRecordIJRecombination(cell[igrd], i, dijOrDiB);
            }
        }
        if (postJetDef.jetAlgorithm() == JetAlgorithm.UNDEFINED) {
            for (int igrd = 0; igrd < cell.length; igrd++) {
                if (cell[igrd] != -1 && grid.tileIsGood(igrd)) {
                    cs.pluginRecordIBRecombination(cell[igrd], dijOrDiB);
                }
            }
            return;
        }
        final List<PseudoJet> inputs = new ArrayList<>(ngridActive);
        final List<Integer> csIndices = new ArrayList<>(2 * ngridActive);
        for (int k : cell) {
            if (k != -1) {
                inputs.add(cs.jet(k));
                csIndices.add(k);
            }
        }
        final ClusterSequence post = new ClusterSequence(inputs, postJetDef.withPrecision(cs.precision()));
        final List<ClusterSequence.HistoryElement> history = post.history();
        for (int ihist = ngridActive; ihist < history.size(); ihist++) {
            final ClusterSequence.HistoryElement hist = history.get(ihist);
            final int ij1 = csIndices.get(history.get(hist.parent1()).jetpIndex());
            if (hist.parent2() >= 0) {
                final int ij2 = csIndices.get(history.get(hist.parent2()).jetpIndex());
                final int k = cs.pluginRecordIJRecombination(ij1, ij2, hist.dijDD(), post.jet(hist.jetpIndex()));
                csIndices.add(k);
            } else {
                cs.pluginRecordIBRecombination(ij1, hist.dijDD());
            }
        }
    }
}
