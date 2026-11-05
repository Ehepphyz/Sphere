package com.sphere.components.spdf;

import com.sphere.components.spdf.edit.OutputFont;
import com.sphere.components.spdf.edit.RichText;
import com.sphere.components.spdf.edit.TextComposer;
import com.sphere.components.spdf.font.SystemFonts;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingWorker;

/**
 * The formatting bar of the text editor: font family (any installed font),
 * size, bold, italic, underline, strike-through, color (the document's own
 * colors offered first), alignment, line spacing, superscript and
 * subscript, shrink-to-fit, and a line telling which font the PDF will
 * actually get (the document's, or one embedded).
 */
final class FormatBar extends JPanel {

    private final SpdfPanel panel;
    private final JComboBox<String> family = new JComboBox<>();
    private final JComboBox<String> size = new JComboBox<>(new String[] { "6", "7", "8", "9", "10", "10.5", "11", "12",
        "14", "16", "18", "20", "24", "28", "32", "36", "48", "60", "72" });
    private final JToggleButton bold = Widgets.toggle("bold", "Bold (Ctrl+B)");
    private final JToggleButton italic = Widgets.toggle("italic", "Italic (Ctrl+I)");
    private final JToggleButton underline = Widgets.toggle("underline", "Underline (Ctrl+U)");
    private final JToggleButton strike = Widgets.toggle("strike", "Strikethrough");
    private final JToggleButton left = Widgets.toggle("alignleft", "Align left");
    private final JToggleButton center = Widgets.toggle("aligncenter", "Center");
    private final JToggleButton right = Widgets.toggle("alignright", "Align right");
    private final JToggleButton justify = Widgets.toggle("alignjustify", "Justify");
    private final JComboBox<String> spacing = new JComboBox<>(new String[] { "1.0", "1.15", "1.2", "1.3", "1.5", "2.0" });
    private final Widgets.ColorButton color;
    private final JLabel fontInfo = Widgets.label(" ");
    private boolean updating;

    FormatBar(SpdfPanel panel) {
        this.panel = panel;
        setLayout(new FlowLayout(FlowLayout.LEFT, 2, 2));
        setBackground(SpdfTheme.chrome());
        setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, SpdfTheme.border()));
        family.setEditable(true);
        family.setPreferredSize(new Dimension(190, 28));
        family.setFont(SpdfTheme.ui(Font.PLAIN, 12));
        family.setToolTipText("Font (any installed font; it is embedded in the PDF)");
        size.setEditable(true);
        size.setPreferredSize(new Dimension(78, 28));
        size.setFont(SpdfTheme.ui(Font.PLAIN, 12));
        size.setToolTipText("Size (points)");
        spacing.setEditable(true);
        spacing.setPreferredSize(new Dimension(62, 28));
        spacing.setFont(SpdfTheme.ui(Font.PLAIN, 12));
        spacing.setToolTipText("Line spacing");
        color = new Widgets.ColorButton(Color.BLACK, "Text color", rgb -> {
            if (!updating) {
                panel.withEditor(e -> e.apply(s -> s.color(rgb)));
            }
        });
        color.documentColors = panel::documentColors;
        ButtonGroup g = new ButtonGroup();
        for (JToggleButton b : new JToggleButton[] { left, center, right, justify }) {
            g.add(b);
        }
        loadFamilies();

        family.addActionListener(e -> {
            if (updating) {
                return;
            }
            Object v = family.getSelectedItem();
            if (v != null && !v.toString().isBlank()) {
                String f = v.toString().trim();
                panel.withEditor(ed -> ed.apply(s -> s.family(f)));
            }
        });
        size.addActionListener(e -> {
            if (updating) {
                return;
            }
            try {
                float v = Float.parseFloat(String.valueOf(size.getSelectedItem()).replace(',', '.'));
                if (v >= 1 && v <= 1000) {
                    panel.withEditor(ed -> ed.apply(s -> s.size(v)));
                }
            } catch (NumberFormatException ex) {
                // not a number: ignored
            }
        });
        bold.addActionListener(e -> panel.withEditor(ed -> ed.apply(s -> s.bold(bold.isSelected()))));
        italic.addActionListener(e -> panel.withEditor(ed -> ed.apply(s -> s.italic(italic.isSelected()))));
        underline.addActionListener(e -> panel.withEditor(ed -> ed.apply(s -> s.underline(underline.isSelected()))));
        strike.addActionListener(e -> panel.withEditor(ed -> ed.apply(s -> s.strike(strike.isSelected()))));
        left.addActionListener(e -> align(RichText.Align.LEFT));
        center.addActionListener(e -> align(RichText.Align.CENTER));
        right.addActionListener(e -> align(RichText.Align.RIGHT));
        justify.addActionListener(e -> align(RichText.Align.JUSTIFY));
        spacing.addActionListener(e -> {
            if (updating) {
                return;
            }
            try {
                float v = Float.parseFloat(String.valueOf(spacing.getSelectedItem()).replace(',', '.'));
                if (v >= 0.5 && v <= 5) {
                    panel.withEditor(ed -> ed.applyPara(p -> p.lineHeight = v));
                }
            } catch (NumberFormatException ex) {
                // ignored
            }
        });
        JButton sup = Widgets.button("superscript", "Superscript", () -> panel.withEditor(ed -> ed.apply(s -> s.rise != 0
            ? s.rise(0).size(s.size / 0.7f) : s.size(s.size * 0.7f).rise(s.size * 0.33f))));
        JButton sub = Widgets.button("subscript", "Subscript", () -> panel.withEditor(ed -> ed.apply(s -> s.rise != 0
            ? s.rise(0).size(s.size / 0.7f) : s.size(s.size * 0.7f).rise(-s.size * 0.15f))));
        JButton fit = Widgets.button("fit", "Fit the text to its original height (reduces the size)",
                                     () -> panel.withEditor(TextEditor::fitToBox));
        JButton ok = Widgets.textButton("Apply", "Write the edit into the PDF (Ctrl+Enter)",
                                        () -> panel.view.commitEdit());
        ok.setIcon(SpdfTheme.icon("check", 16, SpdfTheme.accent()));
        ok.setForeground(SpdfTheme.accent());
        JButton cancel = Widgets.textButton("Cancel", "Discard this edit (Esc)", () -> panel.view.cancelEdit());
        add(Widgets.label(" Text "));
        add(family);
        add(size);
        add(Widgets.separator());
        add(bold);
        add(italic);
        add(underline);
        add(strike);
        add(color);
        add(Widgets.separator());
        add(left);
        add(center);
        add(right);
        add(justify);
        add(spacing);
        add(Widgets.separator());
        add(sup);
        add(sub);
        add(fit);
        add(Widgets.separator());
        add(ok);
        add(cancel);
        add(fontInfo);
        setEnabledAll(false);
    }

    private void align(RichText.Align a) {
        if (!updating) {
            panel.withEditor(ed -> ed.applyPara(p -> p.align = a));
        }
    }

    private void loadFamilies() {
        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() {
                return SystemFonts.embeddableFamilies();
            }

            @Override
            protected void done() {
                try {
                    Object cur = family.getSelectedItem();
                    updating = true;
                    family.removeAllItems();
                    for (String f : get()) {
                        family.addItem(f);
                    }
                    if (cur != null) {
                        family.setSelectedItem(cur);
                    }
                } catch (Exception e) {
                    // no fonts listed: typing a name still works
                } finally {
                    updating = false;
                }
            }
        }.execute();
    }

    private void setEnabledAll(boolean on) {
        for (java.awt.Component c : getComponents()) {
            if (c != fontInfo) {
                c.setEnabled(on);
            }
        }
    }

    /** Shows the style at the editor's caret (or nothing when not editing). */
    void update(TextEditor e) {
        updating = true;
        try {
            setEnabledAll(e != null);
            if (e == null) {
                fontInfo.setText(" ");
                return;
            }
            RichText.Style st = e.currentStyle();
            if (st != null) {
                family.setSelectedItem(st.family);
                size.setSelectedItem(trim(st.size));
                bold.setSelected(st.bold);
                italic.setSelected(st.italic);
                underline.setSelected(st.underline);
                strike.setSelected(st.strike);
                color.setColor(new Color(st.color));
            }
            RichText.Para p = e.currentPara();
            switch (p.align) {
                case CENTER -> center.setSelected(true);
                case RIGHT -> right.setSelected(true);
                case JUSTIFY -> justify.setSelected(true);
                default -> left.setSelected(true);
            }
            spacing.setSelectedItem(trim(p.lineHeight));
            fontInfo.setText(fontsUsed(e));
        } finally {
            updating = false;
        }
    }

    private static String trim(float v) {
        String s = String.format(java.util.Locale.ROOT, "%.1f", v);
        s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }

    /** "PDF: Times New Roman (document), Arial (embedded)" */
    private static String fontsUsed(TextEditor e) {
        Set<String> doc = new LinkedHashSet<>();
        Set<String> emb = new LinkedHashSet<>();
        for (TextComposer.Line l : e.layout.lines) {
            for (TextComposer.Placed p : l.glyphs) {
                OutputFont f = p.font;
                if (f == null || p.space) {
                    continue;
                }
                (f.key().startsWith("doc:") ? doc : emb).add(f.displayName());
            }
        }
        List<String> parts = new ArrayList<>();
        for (String s : doc) {
            parts.add(s + " (document)");
        }
        for (String s : emb) {
            parts.add(s + " (embedded)");
        }
        String s = parts.isEmpty() ? "" : "  PDF: " + String.join(", ", parts);
        if (e.layout.missing > 0) {
            s += "  ⚠ " + e.layout.missing + " character(s) with no font";
        }
        return s.length() > 110 ? s.substring(0, 110) + "…" : s;
    }
}
