package com.sphere.components.rootlab;

import com.sphere.components.imaging.ImagingTheme;
import com.sphere.core.rootlab.bench.LabCatalog;

import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.nio.file.Path;

/**
 * The ROOT Lab tab, beside the File Explorer: CERN data files opened and
 * understood (Data), and ROOT's own test programs run in Java against the
 * reference files they were written for (Tests).
 *
 * One panel for the whole window, reachable from the File Explorer's menu and
 * from the console (:rootlab), so a file sent here from anywhere lands in the
 * same list.
 */
public final class RootLabPanel extends LabSurface {

    private static RootLabPanel shared;

    private final ImagingTheme.Tabs tabs = new ImagingTheme.Tabs();
    private final LabDataView data = new LabDataView();
    private final LabTestView tests = new LabTestView();
    private Runnable reveal;

    private RootLabPanel() {
        super(new BorderLayout(), false);
        tabs.addTab("Data", data);
        tabs.addTab("Tests", tests);
        tabs.setSelectedIndex(0);
        add(tabs, BorderLayout.CENTER);
    }

    /** The panel of the window, built on first use (on the event thread). */
    public static synchronized RootLabPanel instance() {
        if (shared == null) {
            shared = new RootLabPanel();
        }
        return shared;
    }

    /** True once the window has built the panel: the File Explorer offers it only then. */
    public static synchronized boolean exists() {
        return shared != null;
    }

    /** What brings the tab forward when a file is sent to it. */
    public void setReveal(Runnable action) {
        this.reveal = action;
    }

    private void bringForward(int tab) {
        if (reveal != null) {
            reveal.run();
        }
        tabs.setSelectedIndex(tab);
    }

    /** Opens a file or a folder of data files in the Data view. */
    public void open(Path path) {
        onSwing(() -> {
            bringForward(0);
            data.openPath(path);
        });
    }

    /** Opens a location as ROOT accepts it: URL, root://, archive.zip#member. */
    public void openLocation(String spec) {
        onSwing(() -> {
            bringForward(0);
            data.openLocation(spec);
        });
    }

    /** Runs every suite (and the network ones when asked) in the Tests view. */
    public void runTests(boolean network) {
        onSwing(() -> {
            bringForward(1);
            tests.run(network ? LabCatalog.all()
                              : LabCatalog.all().stream().filter(s -> !s.network()).toList());
        });
    }

    /** Draws a histogram of the ROOT Lab in the Plots tab (projected on x when it has more axes). */
    public static void plot(com.sphere.core.rootlab.hist.LabHist h, String caption) {
        com.sphere.components.rootview.RootPlotsPanel.instance().showHistogram(LabViews.toRootHistogram(h), caption);
    }

    private static void onSwing(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }
}
