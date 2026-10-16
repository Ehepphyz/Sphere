package com.sphere.components.editor;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Every finding of the open files in one list: compiler errors, the editor's
 * own analysis, TODO notes. Double-click or Enter goes to it.
 */
public final class ProblemsPanel extends JPanel {

    /** One row: a finding and the tab it belongs to. */
    public static final class Entry {
        public final CodeTab tab;
        public final EditorDiagnostic diagnostic;

        Entry(CodeTab tab, EditorDiagnostic diagnostic) {
            this.tab = tab;
            this.diagnostic = diagnostic;
        }
    }

    private final DefaultListModel<Object> model = new DefaultListModel<>();
    private final JList<Object> list = new JList<>(model);
    private final JLabel summary = new JLabel();
    private final EditorWidgets.FlatToggle showErrors = new EditorWidgets.FlatToggle("Errors", "Show errors");
    private final EditorWidgets.FlatToggle showWarnings = new EditorWidgets.FlatToggle("Warnings", "Show warnings");
    private final EditorWidgets.FlatToggle showNotes = new EditorWidgets.FlatToggle("Notes", "Show notes and TODOs");
    private final EditorWidgets.FlatToggle activeOnly = new EditorWidgets.FlatToggle("Active File", "Only the active file");
    private BiConsumer<CodeTab, EditorDiagnostic> navigator;
    private List<CodeTab> lastTabs = List.of();
    private CodeTab lastActive;

    public ProblemsPanel() {
        super(new BorderLayout());
        setOpaque(true);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new Row());
        list.setFixedCellHeight(22);
        list.setOpaque(false);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() >= 1) {
                    open();
                }
            }
        });
        list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open");
        list.getActionMap().put("open", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                open();
            }
        });

        showErrors.setSelected(true);
        showWarnings.setSelected(true);
        showNotes.setSelected(true);
        for (EditorWidgets.FlatToggle t : new EditorWidgets.FlatToggle[] { showErrors, showWarnings, showNotes, activeOnly }) {
            t.setFont(EditorWidgets.uiFont(Font.PLAIN, 11));
            t.addActionListener(e -> update(lastTabs, lastActive));
        }

        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 8));
        summary.setFont(EditorWidgets.uiFont(Font.PLAIN, 12));
        JPanel filters = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        filters.setOpaque(false);
        filters.add(showErrors);
        filters.add(showWarnings);
        filters.add(showNotes);
        filters.add(activeOnly);
        bar.add(summary, BorderLayout.WEST);
        bar.add(filters, BorderLayout.EAST);

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        add(bar, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
    }

    public void setNavigator(BiConsumer<CodeTab, EditorDiagnostic> navigator) {
        this.navigator = navigator;
    }

    @Override
    protected void paintComponent(Graphics g) {
        g.setColor(EditorTheme.background());
        g.fillRect(0, 0, getWidth(), getHeight());
    }

    private void open() {
        Object v = list.getSelectedValue();
        if (v instanceof Entry e && navigator != null) {
            navigator.accept(e.tab, e.diagnostic);
        }
    }

    /** Rebuilds the list from the tabs' findings. */
    public void update(List<CodeTab> tabs, CodeTab active) {
        lastTabs = tabs == null ? List.of() : new ArrayList<>(tabs);
        lastActive = active;
        Object selected = list.getSelectedValue();
        model.clear();
        int errors = 0;
        int warnings = 0;
        int notes = 0;
        for (CodeTab tab : lastTabs) {
            if (activeOnly.isSelected() && tab != active) {
                continue;
            }
            List<Entry> rows = new ArrayList<>();
            for (EditorDiagnostic d : tab.getAllDiagnostics()) {
                switch (d.getSeverity()) {
                    case ERROR:   errors++;   if (!showErrors.isSelected()) continue; break;
                    case WARNING: warnings++; if (!showWarnings.isSelected()) continue; break;
                    default:      notes++;    if (!showNotes.isSelected()) continue; break;
                }
                rows.add(new Entry(tab, d));
            }
            if (rows.isEmpty()) {
                continue;
            }
            rows.sort((a, b) -> a.diagnostic.getSeverity() != b.diagnostic.getSeverity()
                    ? Integer.compare(a.diagnostic.getSeverity().ordinal(), b.diagnostic.getSeverity().ordinal())
                    : Integer.compare(a.diagnostic.getLine(), b.diagnostic.getLine()));
            model.addElement(tab);
            for (Entry r : rows) {
                model.addElement(r);
            }
        }
        summary.setText(errors + " error" + (errors == 1 ? "" : "s") + ", " + warnings + " warning"
                        + (warnings == 1 ? "" : "s") + ", " + notes + " note" + (notes == 1 ? "" : "s"));
        summary.setForeground(EditorTheme.textMuted());
        if (selected != null) {
            for (int i = 0; i < model.size(); i++) {
                Object o = model.get(i);
                if (o instanceof Entry e && selected instanceof Entry s && e.tab == s.tab
                        && e.diagnostic.getLine() == s.diagnostic.getLine()
                        && e.diagnostic.getMessage().equals(s.diagnostic.getMessage())) {
                    list.setSelectedIndex(i);
                    break;
                }
            }
        }
        repaint();
    }

    private final class Row extends JComponent implements ListCellRenderer<Object> {
        private Object value;
        private boolean selected;

        @Override
        public Component getListCellRendererComponent(JList<?> l, Object v, int index, boolean isSelected,
                                                      boolean focus) {
            this.value = v;
            this.selected = isSelected;
            return this;
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(200, 22);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = EditorWidgets.smooth(g);
            int w = getWidth();
            int h = getHeight();
            if (selected && value instanceof Entry) {
                g2.setColor(EditorTheme.widgetSelection());
                g2.fillRect(0, 0, w, h);
            }
            Font font = EditorWidgets.uiFont(Font.PLAIN, 12);
            Font bold = EditorWidgets.uiFont(Font.BOLD, 12);
            FontMetrics fm = g2.getFontMetrics(font);
            int base = (h + fm.getAscent() - fm.getDescent()) / 2;
            if (value instanceof CodeTab tab) {
                int x = 10;
                EditorIcons.LanguageBadge badge = EditorIcons.LanguageBadge.of(tab.getLanguage(), tab.getFile());
                badge.paintIcon(this, g2, x, (h - badge.getIconHeight()) / 2);
                x += badge.getIconWidth() + 8;
                g2.setFont(bold);
                g2.setColor(EditorTheme.foreground());
                String name = tab.getFile() != null ? tab.getFile().getName() : "Untitled";
                g2.drawString(name, x, base);
                x += g2.getFontMetrics(bold).stringWidth(name) + 8;
                g2.setFont(font);
                g2.setColor(EditorTheme.textMuted());
                if (tab.getFile() != null && tab.getFile().getParent() != null) {
                    String dir = tab.getFile().getParent();
                    String root = new java.io.File(".").getAbsoluteFile().getParent();
                    if (root != null && dir.startsWith(root)) {
                        dir = dir.substring(root.length()).replaceFirst("^[\\\\/]", "");
                    }
                    g2.drawString(dir, x, base);
                }
            } else if (value instanceof Entry e) {
                EditorDiagnostic d = e.diagnostic;
                int x = 30;
                javax.swing.Icon icon = d.getSeverity() == EditorDiagnostic.Severity.ERROR ? EditorIcons.error(14)
                        : d.getSeverity() == EditorDiagnostic.Severity.WARNING ? EditorIcons.warning(14)
                        : EditorIcons.info(14);
                icon.paintIcon(this, g2, x, (h - 14) / 2);
                x += 20;
                g2.setFont(font);
                g2.setColor(EditorTheme.foreground());
                String msg = d.getMessage();
                g2.drawString(msg, x, base);
                x += fm.stringWidth(msg) + 10;
                g2.setColor(EditorTheme.textMuted());
                String where = d.getSource() + (d.getCode().isEmpty() || d.getCode().equals("TODO") ? "" : " · " + d.getCode())
                               + "  [Ln " + d.getLine() + (d.getColumn() > 0 ? ", Col " + d.getColumn() : "") + "]";
                g2.drawString(where, x, base);
            }
            g2.dispose();
        }
    }
}
