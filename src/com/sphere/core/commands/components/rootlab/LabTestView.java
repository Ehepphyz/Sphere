package com.sphere.components.rootlab;

import com.sphere.components.imaging.ImagingTheme;
import com.sphere.core.rootlab.bench.LabCatalog;
import com.sphere.core.rootlab.bench.LabResult;
import com.sphere.core.rootlab.bench.LabRunner;
import com.sphere.core.rootlab.bench.LabStatus;
import com.sphere.core.rootlab.bench.LabSuite;
import com.sphere.core.rootlab.bench.LabTest;
import com.sphere.utils.AppLogger;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Tests side of the ROOT Lab: the programs of ROOT's test/ directory,
 * ported to Java, run here against the reference files they were written for
 * -- and the remote ones on root.cern when the network is allowed. Results
 * appear as they come, suite by suite, each failure with what differed.
 */
final class LabTestView extends LabSurface {

    /** One row of the tree: a test, and what became of it. */
    static final class TestRow {
        final LabSuite suite;
        final String id;
        final String title;
        LabResult result;
        boolean running;

        TestRow(LabSuite suite, LabTest test) {
            this.suite = suite;
            this.id = test.id();
            this.title = test.title();
        }

        @Override
        public String toString() {
            return title;
        }
    }

    /** One suite, and the state of its tests. */
    static final class SuiteRow {
        final LabSuite suite;
        final List<TestRow> tests = new ArrayList<>();

        SuiteRow(LabSuite suite) {
            this.suite = suite;
        }

        LabStatus status() {
            boolean any = false;
            boolean skipped = false;
            for (TestRow t : tests) {
                if (t.result == null) {
                    continue;
                }
                any = true;
                if (t.result.status() == LabStatus.FAILED || t.result.status() == LabStatus.ERROR) {
                    return LabStatus.FAILED;
                }
                skipped |= t.result.status() == LabStatus.SKIPPED;
            }
            if (!any) {
                return null;
            }
            return skipped && tests.stream().allMatch(t -> t.result != null && t.result.status() == LabStatus.SKIPPED)
                ? LabStatus.SKIPPED : LabStatus.PASSED;
        }

        boolean running() {
            return tests.stream().anyMatch(t -> t.running);
        }

        @Override
        public String toString() {
            return suite.title();
        }
    }

    private final DefaultMutableTreeNode top = new DefaultMutableTreeNode("suites");
    private final DefaultTreeModel model = new DefaultTreeModel(top);
    private final JTree tree = new JTree(model);
    private final JEditorPane details = LabViews.htmlPane();
    private final JLabel summary = new JLabel(" ");
    private final JCheckBox network = new JCheckBox("root.cern", false);
    private final JButton runAll = ImagingTheme.textButton("Run all", "Run every suite");
    private final JButton runSelected = ImagingTheme.textButton("Run", "Run the selected suite");
    private final JButton stop = ImagingTheme.textButton("Stop", "Stop the run after the tests in progress");
    private final JButton report = ImagingTheme.textButton("Report", "The run as ROOT's stress programs print it");
    private final Map<String, SuiteRow> rows = new LinkedHashMap<>();
    private final Timer spinner = new Timer(100, e -> tree.repaint());
    private volatile LabRunner runner;
    private List<LabSuite> lastSuites = List.of();
    private LabRunner.Summary lastSummary;

    LabTestView() {
        super(new BorderLayout(), true);
        add(buildToolbar(), BorderLayout.NORTH);
        add(buildBody(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);
        populate();
        intro();
    }

    private JComponent buildToolbar() {
        final ImagingTheme.Surface bar = ImagingTheme.strip(true);
        bar.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
        runAll.addActionListener(e -> run(LabCatalog.all()));
        runSelected.addActionListener(e -> runSelection());
        stop.addActionListener(e -> {
            final LabRunner r = runner;
            if (r != null) {
                r.cancel();
            }
        });
        stop.setEnabled(false);
        report.addActionListener(e -> showReport());
        report.setEnabled(false);
        network.setToolTipText("Also run the suites that read ROOT's test files from root.cern over HTTPS");
        network.setOpaque(false);
        network.setForeground(ImagingTheme.text());
        network.setFont(ImagingTheme.uiFont(Font.PLAIN, 11f));
        bar.add(runAll);
        bar.add(runSelected);
        bar.add(stop);
        bar.add(Box.createHorizontalStrut(4));
        bar.add(network);
        bar.add(Box.createHorizontalGlue());
        bar.add(report);
        ImagingTheme.framed(bar);
        return bar;
    }

    private JComponent buildBody() {
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(20);
        tree.setBackground(ImagingTheme.surface());
        tree.setCellRenderer(new Renderer());
        tree.addTreeSelectionListener(e -> {
            final TreePath p = e.getNewLeadSelectionPath();
            if (p != null) {
                show(((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject());
            }
        });
        tree.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    runSelection();
                }
            }
        });
        final JScrollPane treeScroll = new JScrollPane(tree);
        treeScroll.setBorder(BorderFactory.createEmptyBorder());
        treeScroll.getViewport().setBackground(ImagingTheme.surface());
        final JScrollPane detailScroll = new JScrollPane(details);
        detailScroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, ImagingTheme.border()));
        final JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, treeScroll, detailScroll);
        split.setResizeWeight(0.55);
        split.setBorder(null);
        split.setDividerSize(5);
        return split;
    }

    private JComponent buildFooter() {
        final JPanel bar = new ImagingTheme.Surface(new BorderLayout(), true);
        bar.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        summary.setFont(ImagingTheme.uiFont(Font.PLAIN, 10f));
        summary.setForeground(ImagingTheme.subduedText());
        bar.add(summary, BorderLayout.CENTER);
        return bar;
    }

    private void populate() {
        top.removeAllChildren();
        rows.clear();
        for (LabSuite suite : LabCatalog.all()) {
            final SuiteRow row = new SuiteRow(suite);
            final DefaultMutableTreeNode node = new DefaultMutableTreeNode(row);
            try {
                for (LabTest t : suite.tests()) {
                    final TestRow tr = new TestRow(suite, t);
                    row.tests.add(tr);
                    node.add(new DefaultMutableTreeNode(tr));
                }
            } catch (RuntimeException broken) {
                AppLogger.error("[ROOT Lab] suite " + suite.id() + " could not be listed: " + broken.getMessage());
            }
            rows.put(suite.id(), row);
            top.add(node);
        }
        model.nodeStructureChanged(top);
        int total = 0;
        for (SuiteRow r : rows.values()) {
            total += r.tests.size();
        }
        summary.setText(rows.size() + " suites, " + total + " tests ported from ROOT's test/ directory");
    }

    private void intro() {
        details.setText("<html><body><h1>ROOT's tests</h1>"
            + "<p>Each suite ports a program of ROOT's <b>test/</b> directory</p></body></html>");
    }

    private void runSelection() {
        final TreePath p = tree.getSelectionPath();
        if (p == null) {
            return;
        }
        final Object u = ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
        final LabSuite suite = u instanceof SuiteRow s ? s.suite : u instanceof TestRow t ? t.suite : null;
        if (suite != null) {
            // tests of a suite share their state (one random sequence), so a suite runs whole
            run(List.of(suite));
        }
    }

    /** Runs suites in the background; the tree follows. */
    void run(List<LabSuite> suites) {
        if (runner != null) {
            return;
        }
        final boolean net = network.isSelected();
        for (LabSuite s : suites) {
            final SuiteRow row = rows.get(s.id());
            if (row != null) {
                for (TestRow t : row.tests) {
                    t.result = null;
                    t.running = false;
                }
            }
        }
        tree.repaint();
        runAll.setEnabled(false);
        runSelected.setEnabled(false);
        stop.setEnabled(true);
        spinner.start();
        summary.setText("Running " + suites.size() + " suite(s)...");
        final LabRunner r = new LabRunner();
        runner = r;
        final Thread thread = new Thread(() -> {
            final LabRunner.Summary result = r.run(suites, net, new LabRunner.Listener() {
                @Override
                public void testStarted(LabSuite suite, LabTest test) {
                    SwingUtilities.invokeLater(() -> mark(suite, test.id(), null, true));
                }

                @Override
                public void testFinished(LabSuite suite, LabResult res) {
                    SwingUtilities.invokeLater(() -> mark(suite, res.testId(), res, false));
                }
            });
            SwingUtilities.invokeLater(() -> finished(suites, result));
        }, "sphere-rootlab-tests");
        thread.setDaemon(true);
        thread.start();
    }

    private void mark(LabSuite suite, String testId, LabResult result, boolean running) {
        final SuiteRow row = rows.get(suite.id());
        if (row == null) {
            return;
        }
        for (TestRow t : row.tests) {
            if (t.id.equals(testId)) {
                t.running = running;
                if (result != null) {
                    t.result = result;
                }
            }
        }
        tree.repaint();
        final TreePath p = tree.getSelectionPath();
        if (p != null) {
            show(((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject());
        }
    }

    private void finished(List<LabSuite> suites, LabRunner.Summary s) {
        runner = null;
        spinner.stop();
        runAll.setEnabled(true);
        runSelected.setEnabled(true);
        stop.setEnabled(false);
        report.setEnabled(true);
        lastSuites = suites;
        lastSummary = s;
        tree.repaint();
        summary.setText(String.format(Locale.ROOT, "%d passed, %d failed, %d errors, %d skipped in %.1f s",
            s.passed(), s.failed(), s.errors(), s.skipped(), s.nanos() / 1e9));
        AppLogger.info("[ROOT Lab] " + summary.getText());
        for (LabResult r : s.results()) {
            if (r.status() == LabStatus.FAILED || r.status() == LabStatus.ERROR) {
                AppLogger.error("[ROOT Lab] " + r.suiteId() + " / " + r.title() + ": " + r.message());
            }
        }
    }

    private void showReport() {
        if (lastSummary == null) {
            return;
        }
        final String text = LabRunner.report(lastSuites, lastSummary);
        com.sphere.components.ClipboardBridge.write(text);
        details.setText("<html><body><p class=sub>Copied to the clipboard.</p><pre>" + LabViews.esc(text)
            + "</pre></body></html>");
        details.setCaretPosition(0);
    }

    private void show(Object u) {
        final StringBuilder h = new StringBuilder("<html><body>");
        if (u instanceof SuiteRow s) {
            h.append("<h1>").append(LabViews.esc(s.suite.title())).append("</h1>");
            h.append("<p>").append(LabViews.esc(s.suite.description())).append("</p>");
            h.append(LabViews.table("ported from", LabViews.esc(s.suite.origin()),
                "tests", Integer.toString(s.tests.size()),
                "network", s.suite.network() ? "reads from root.cern (tick root.cern to run)" : "no"));
            final StringBuilder list = new StringBuilder("<h2>Tests</h2><table cellspacing=0 cellpadding=0>");
            for (TestRow t : s.tests) {
                list.append("<tr><td>").append(statusWord(t)).append("</td><td>").append(LabViews.esc(t.title))
                    .append("</td><td class=k>").append(t.result == null ? "" : String.format(Locale.ROOT, "%.3f s",
                        t.result.seconds())).append("</td></tr>");
            }
            h.append(list).append("</table>");
        } else if (u instanceof TestRow t) {
            h.append("<h1>").append(LabViews.esc(t.title)).append("</h1>");
            h.append("<p class=sub>").append(LabViews.esc(t.suite.title())).append("</p>");
            h.append("<p>").append(statusWord(t));
            if (t.result != null) {
                h.append(String.format(Locale.ROOT, " <span class=sub>in %.3f s</span>", t.result.seconds()));
                if (!t.result.message().isEmpty()) {
                    h.append("<br>").append(LabViews.esc(t.result.message()));
                }
            }
            h.append("</p>");
            if (t.result != null && !t.result.details().isEmpty()) {
                // lines that wrap at the panel's width (a pre block ran off its right edge)
                h.append("<h2>Details</h2><div class=mono>");
                for (String d : t.result.details()) {
                    h.append(LabViews.esc(d).replace("\n", "<br>").replace("\t", "&nbsp;&nbsp;&nbsp;&nbsp;")).append("<br>");
                }
                h.append("</div>");
            }
        } else {
            intro();
            return;
        }
        details.setText(h.append("</body></html>").toString());
        details.setCaretPosition(0);
    }

    private static String statusWord(TestRow t) {
        if (t.running) {
            return "<span class=sub>running...</span>";
        }
        if (t.result == null) {
            return "<span class=sub>not run</span>";
        }
        return switch (t.result.status()) {
            case PASSED -> "<span class=ok><b>OK</b></span>";
            case FAILED -> "<span class=bad><b>FAILED</b></span>";
            case ERROR -> "<span class=bad><b>ERROR</b></span>";
            case SKIPPED -> "<span class=warn>skipped</span>";
        };
    }

    private static final class Renderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded,
                                                      boolean leaf, int row, boolean focus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focus);
            setBackgroundNonSelectionColor(ImagingTheme.surface());
            setBackgroundSelectionColor(ImagingTheme.palette().getTerminalSelection());
            setBorderSelectionColor(null);
            setForeground(ImagingTheme.text());
            setFont(ImagingTheme.uiFont(Font.PLAIN, 11f));
            final Object u = ((DefaultMutableTreeNode) value).getUserObject();
            if (u instanceof SuiteRow s) {
                final LabStatus st = s.status();
                setIcon(LabIcons.of(s.running() ? LabIcons.Glyph.RUNNING : st == null ? LabIcons.Glyph.SUITE
                    : st == LabStatus.FAILED ? LabIcons.Glyph.FAILED : st == LabStatus.SKIPPED ? LabIcons.Glyph.SKIPPED
                    : LabIcons.Glyph.PASSED));
                setText("<html><b>" + LabViews.esc(s.suite.id()) + "</b> <font color='"
                    + LabViews.hex(ImagingTheme.subduedText()) + "'>" + LabViews.esc(s.suite.title()) + "</font></html>");
            } else if (u instanceof TestRow t) {
                LabIcons.Glyph g = LabIcons.Glyph.PENDING;
                if (t.running) {
                    g = LabIcons.Glyph.RUNNING;
                } else if (t.result != null) {
                    g = switch (t.result.status()) {
                        case PASSED -> LabIcons.Glyph.PASSED;
                        case FAILED -> LabIcons.Glyph.FAILED;
                        case ERROR -> LabIcons.Glyph.ERROR;
                        case SKIPPED -> LabIcons.Glyph.SKIPPED;
                    };
                }
                setIcon(LabIcons.of(g));
                final String time = t.result == null || t.result.status() == LabStatus.SKIPPED ? ""
                    : String.format(Locale.ROOT, "  %.2f s", t.result.seconds());
                setText("<html>" + LabViews.esc(t.title) + "<font color='" + LabViews.hex(ImagingTheme.subduedText())
                    + "'>" + time + "</font></html>");
            }
            return this;
        }
    }
}
