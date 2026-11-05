package com.sphere.components.spdf;

import com.sphere.components.spdf.edit.EditHistory;
import com.sphere.components.spdf.text.Insights;
import com.sphere.components.spdf.text.SmartSearch;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.event.HyperlinkEvent;

/**
 * The side of :spdf: an activity strip choosing what shows next to the
 * pages: thumbnails, the outline (bookmarks, or headings found in the text),
 * search results, the document insights, and the edit history.
 */
final class SidePanel extends JPanel {

    private final SpdfPanel panel;
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final Map<String, JToggleButton> tabs = new LinkedHashMap<>();
    final Thumbnails thumbnails;
    final JTextField searchField = new JTextField();
    final JComboBox<String> searchMode = new JComboBox<>(new String[] { "Smart", "Exact", "Fuzzy", "Regex" });
    private final DefaultListModel<Object> results = new DefaultListModel<>();
    private final JList<Object> resultList = new JList<>(results);
    private final JLabel resultCount = Widgets.label(" ");
    private final DefaultListModel<Object> outline = new DefaultListModel<>();
    private final JList<Object> outlineList = new JList<>(outline);
    private final JEditorPane insights = new JEditorPane("text/html", "");
    private final DefaultListModel<EditHistory.Step> steps = new DefaultListModel<>();
    private final JList<EditHistory.Step> stepList = new JList<>(steps);
    String shown = "pages";

    SidePanel(SpdfPanel panel) {
        this.panel = panel;
        setLayout(new BorderLayout());
        setBackground(SpdfTheme.chrome());
        thumbnails = new Thumbnails(panel);

        JPanel strip = new JPanel();
        strip.setLayout(new BoxLayout(strip, BoxLayout.Y_AXIS));
        strip.setBackground(SpdfTheme.chrome());
        strip.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, SpdfTheme.border()));
        ButtonGroup g = new ButtonGroup();
        tab(strip, g, "pages", "pages", "Pages (thumbnails)", scroll(thumbnails));
        tab(strip, g, "outline", "outline", "Document outline", outlinePanel());
        tab(strip, g, "search", "search", "Smart search (Ctrl+F)", searchPanel());
        tab(strip, g, "insights", "insights", "Analysis: summary, key terms, entities", insightsPanel());
        tab(strip, g, "history", "history", "Edit history", historyPanel());
        strip.add(Box.createVerticalGlue());
        add(strip, BorderLayout.WEST);
        add(content, BorderLayout.CENTER);
        tabs.get("pages").setSelected(true);
        setPreferredSize(new Dimension(250, 400));
    }

    private void tab(JPanel strip, ButtonGroup g, String key, String icon, String tip, JComponent comp) {
        JToggleButton b = Widgets.toggle(icon, tip);
        b.setAlignmentX(Component.CENTER_ALIGNMENT);
        b.setMaximumSize(new Dimension(40, 40));
        b.addActionListener(e -> show(key));
        g.add(b);
        tabs.put(key, b);
        strip.add(Box.createVerticalStrut(4));
        strip.add(b);
        content.add(comp, key);
    }

    void show(String key) {
        shown = key;
        cards.show(content, key);
        JToggleButton b = tabs.get(key);
        if (b != null) {
            b.setSelected(true);
        }
        if (key.equals("insights") || key.equals("outline")) {
            panel.ensureInsights();
        }
        if (key.equals("search")) {
            searchField.requestFocusInWindow();
            searchField.selectAll();
        }
    }

    private static JScrollPane scroll(JComponent c) {
        JScrollPane s = new JScrollPane(c);
        s.setBorder(BorderFactory.createEmptyBorder());
        s.getViewport().setBackground(SpdfTheme.chrome());
        s.getVerticalScrollBar().setUnitIncrement(24);
        return s;
    }

    private static JLabel title(String t) {
        JLabel l = new JLabel(t);
        l.setFont(SpdfTheme.ui(Font.BOLD, 11));
        l.setForeground(SpdfTheme.muted());
        l.setBorder(BorderFactory.createEmptyBorder(8, 10, 6, 10));
        return l;
    }

    /* ------------------------------------------------------------- outline */

    private JComponent outlinePanel() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(SpdfTheme.chrome());
        p.add(title("OUTLINE"), BorderLayout.NORTH);
        outlineList.setBackground(SpdfTheme.chrome());
        outlineList.setForeground(SpdfTheme.fg());
        outlineList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                JLabel l = (JLabel) super.getListCellRendererComponent(list, value, index, sel, focus);
                int level = 0;
                String text;
                int page = -1;
                if (value instanceof SpdfDoc.Bookmark b) {
                    level = b.level();
                    text = b.title();
                    page = b.page();
                } else if (value instanceof Insights.Heading h) {
                    level = h.level() - 1;
                    text = h.text();
                    page = h.page();
                } else {
                    text = String.valueOf(value);
                }
                l.setText("<html>" + esc(text) + (page >= 0 ? " <font color='#888888'>" + (page + 1) + "</font>" : "")
                          + "</html>");
                l.setBorder(BorderFactory.createEmptyBorder(3, 10 + 14 * Math.min(level, 6), 3, 6));
                l.setFont(SpdfTheme.ui(level == 0 ? Font.BOLD : Font.PLAIN, 12));
                if (!sel) {
                    l.setBackground(SpdfTheme.chrome());
                    l.setForeground(SpdfTheme.fg());
                }
                return l;
            }
        });
        outlineList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                Object v = outlineList.getSelectedValue();
                if (v instanceof SpdfDoc.Bookmark b && b.page() >= 0) {
                    panel.pushPosition();
                    panel.view.scrollToPage(b.page(), b.y());
                } else if (v instanceof Insights.Heading h) {
                    panel.pushPosition();
                    panel.goToText(h.page(), h.start(), h.start() + 1);
                }
            }
        });
        p.add(scroll(outlineList), BorderLayout.CENTER);
        return p;
    }

    /** Fills the outline: bookmarks when the PDF has some, else the headings found. */
    void setOutline(List<SpdfDoc.Bookmark> bookmarks, Insights in) {
        outline.clear();
        if (!bookmarks.isEmpty()) {
            for (SpdfDoc.Bookmark b : bookmarks) {
                outline.addElement(b);
            }
        } else if (in != null) {
            for (Insights.Heading h : in.outline) {
                outline.addElement(h);
            }
            if (in.outline.isEmpty()) {
                outline.addElement("(no headings found)");
            }
        } else {
            outline.addElement("Analyzing the document…");
        }
    }

    /* ------------------------------------------------------------- search */

    private JComponent searchPanel() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(SpdfTheme.chrome());
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBackground(SpdfTheme.chrome());
        top.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        top.add(title("SEARCH"));
        searchField.setFont(SpdfTheme.ui(Font.PLAIN, 13));
        searchField.setToolTipText("Ignores case, accents, ligatures and words broken across lines");
        searchField.addActionListener(e -> panel.search(searchField.getText()));
        searchField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        top.add(searchField);
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 4));
        row.setBackground(SpdfTheme.chrome());
        searchMode.setFont(SpdfTheme.ui(Font.PLAIN, 12));
        searchMode.addActionListener(e -> {
            if (!searchField.getText().isBlank()) {
                panel.search(searchField.getText());
            }
        });
        row.add(searchMode);
        row.add(Widgets.button("up", "Previous match (Shift+F3)", () -> panel.nextHit(-1)));
        row.add(Widgets.button("down", "Next match (F3)", () -> panel.nextHit(1)));
        row.add(Widgets.button("highlight", "Highlight all matches", () -> panel.highlightAllHits()));
        top.add(row);
        top.add(resultCount);
        p.add(top, BorderLayout.NORTH);
        resultList.setBackground(SpdfTheme.chrome());
        resultList.setForeground(SpdfTheme.fg());
        resultList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                JLabel l = (JLabel) super.getListCellRendererComponent(list, value, index, sel, focus);
                if (value instanceof SmartSearch.Hit h) {
                    String c = esc(h.context()).replace("\u0001", "<b><font color='#E08000'>").replace("\u0002",
                                                                                                    "</font></b>");
                    l.setText("<html><font color='#888888'>p. " + (h.page() + 1) + "</font>&nbsp; " + c + "</html>");
                }
                l.setFont(SpdfTheme.ui(Font.PLAIN, 12));
                l.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
                if (!sel) {
                    l.setBackground(index % 2 == 0 ? SpdfTheme.chrome() : SpdfTheme.mix(SpdfTheme.chrome(),
                                                                                       SpdfTheme.fg(), 0.03f));
                    l.setForeground(SpdfTheme.fg());
                }
                return l;
            }
        });
        resultList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && resultList.getSelectedIndex() >= 0) {
                panel.showHit(resultList.getSelectedIndex());
            }
        });
        p.add(scroll(resultList), BorderLayout.CENTER);
        return p;
    }

    SmartSearch.Mode mode() {
        return switch (searchMode.getSelectedIndex()) {
            case 1 -> SmartSearch.Mode.EXACT;
            case 2 -> SmartSearch.Mode.FUZZY;
            case 3 -> SmartSearch.Mode.REGEX;
            default -> SmartSearch.Mode.SMART;
        };
    }

    void setResults(List<SmartSearch.Hit> hits, String query) {
        results.clear();
        for (SmartSearch.Hit h : hits) {
            results.addElement(h);
        }
        long pages = hits.stream().mapToInt(SmartSearch.Hit::page).distinct().count();
        resultCount.setText(hits.isEmpty() ? (query.isBlank() ? " " : "No matches")
                                : hits.size() + (hits.size() == 1 ? " match" : " matches") + " on " + pages + (pages == 1 ? " page" : " pages"));
    }

    void selectResult(int i) {
        if (i >= 0 && i < results.size() && resultList.getSelectedIndex() != i) {
            resultList.setSelectedIndex(i);
            resultList.ensureIndexIsVisible(i);
        }
    }

    /* ------------------------------------------------------------- insights */

    private JComponent insightsPanel() {
        insights.setEditable(false);
        insights.setBackground(SpdfTheme.chrome());
        insights.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        insights.setFont(SpdfTheme.ui(Font.PLAIN, 12));
        insights.setForeground(SpdfTheme.fg());
        insights.addHyperlinkListener(e -> {
            if (e.getEventType() != HyperlinkEvent.EventType.ACTIVATED) {
                return;
            }
            String d = e.getDescription();
            if (d.startsWith("search:")) {
                panel.search(d.substring(7));
            } else if (d.startsWith("text:")) {
                String[] p = d.substring(5).split(":");
                panel.pushPosition();
                panel.goToText(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
            } else if (d.startsWith("page:")) {
                panel.pushPosition();
                panel.goToPage(Integer.parseInt(d.substring(5)));
            } else if (d.startsWith("http") || d.startsWith("doi:") || d.startsWith("arxiv:")) {
                String url = d.startsWith("doi:") ? "https://doi.org/" + d.substring(4)
                    : d.startsWith("arxiv:") ? "https://arxiv.org/abs/" + d.substring(6) : d;
                try {
                    java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
                } catch (Exception ex) {
                    panel.status(url);
                }
            }
        });
        insights.setText(html("<p>Analyzing…</p>"));
        return scroll(insights);
    }

    private String html(String body) {
        Color fg = SpdfTheme.fg();
        Color mu = SpdfTheme.muted();
        Color ac = SpdfTheme.accent();
        return "<html><head><style>body{font-family:sans-serif;font-size:11px;color:" + hex(fg) + ";margin:8px}"
            + "h3{font-size:10px;color:" + hex(mu) + ";margin:12px 0 4px 0}a{color:" + hex(ac) + ";text-decoration:none}"
            + ".k{background:" + hex(SpdfTheme.mix(SpdfTheme.chrome(), ac, 0.18f)) + "}"
            + ".m{color:" + hex(mu) + "}p{margin:3px 0}</style></head><body>" + body + "</body></html>";
    }

    private static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    void setInsights(Insights in, SpdfDoc doc) {
        if (in == null) {
            insights.setText(html("<p>Analyzing…</p>"));
            return;
        }
        StringBuilder b = new StringBuilder();
        b.append("<h3>DOCUMENT</h3><p><b>").append(esc(doc.title())).append("</b></p>");
        b.append("<p class='m'>").append(doc.pageCount()).append(" pages · ").append(String.format(java.util.Locale.US, "%,d", in.words))
         .append(" words · ~").append(in.minutes).append(" min read · ").append(esc(in.language))
         .append("</p>");
        if (!in.keywords.isEmpty()) {
            b.append("<h3>KEY TERMS</h3><p>");
            boolean first = true;
            for (String k : in.keywords) {
                if (!first) {
                    b.append(" <span class='m'>·</span> ");
                }
                b.append("<a href='search:").append(esc(k)).append("'>").append(esc(k)).append("</a>");
                first = false;
            }
            b.append("</p>");
        }
        if (!in.summary.isEmpty()) {
            b.append("<h3>AUTOMATIC SUMMARY</h3>");
            for (String[] s : in.summary) {
                b.append("<p>• ").append(esc(s[1])).append(" <a href='page:").append(Integer.parseInt(s[0]) - 1)
                 .append("'>p.").append(s[0]).append("</a></p>");
            }
        }
        Map<String, List<Insights.Entity>> byKind = new LinkedHashMap<>();
        for (Insights.Entity e : in.entities) {
            byKind.computeIfAbsent(e.kind(), k -> new ArrayList<>()).add(e);
        }
        for (Map.Entry<String, List<Insights.Entity>> en : byKind.entrySet()) {
            String kind = en.getKey();
            String head = switch (kind) {
                case "Quantity" -> "PHYSICAL QUANTITIES";
                case "Link" -> "LINKS";
                case "Email" -> "EMAILS";
                case "Date" -> "DATES";
                case "Amount" -> "AMOUNTS";
                default -> kind.toUpperCase();
            };
            b.append("<h3>").append(head).append(" (").append(en.getValue().size()).append(")</h3><p>");
            int n = 0;
            for (Insights.Entity e : en.getValue()) {
                if (n++ >= 40) {
                    b.append("…");
                    break;
                }
                String href = switch (kind) {
                    case "Link" -> e.text().startsWith("www.") ? "http://" + e.text() : e.text();
                    case "DOI" -> "doi:" + e.text();
                    case "arXiv" -> "arxiv:" + e.text().replaceFirst("(?i)arXiv:\\s?", "");
                    default -> "text:" + e.page() + ":" + e.start() + ":" + e.end();
                };
                b.append("<a href='").append(esc(href)).append("'>").append(esc(e.text())).append("</a>")
                 .append(" <span class='m'>p.").append(e.page() + 1).append("</span> &nbsp; ");
            }
            b.append("</p>");
        }
        if (!in.captions.isEmpty()) {
            b.append("<h3>FIGURES AND TABLES (").append(in.captions.size()).append(")</h3>");
            for (Insights.Caption c : in.captions) {
                String t = c.text().length() > 80 ? c.text().substring(0, 80) + "…" : c.text();
                b.append("<p><a href='text:").append(c.page()).append(':').append(c.start()).append(':')
                 .append(c.start() + 1).append("'>").append(c.kind()).append(' ').append(esc(c.number())).append("</a> ")
                 .append(esc(t)).append(" <span class='m'>p.").append(c.page() + 1).append("</span></p>");
            }
        }
        if (!in.references.isEmpty()) {
            b.append("<h3>BIBLIOGRAPHY (").append(in.references.size()).append(" references)</h3>")
             .append("<p class='m'>Hover over a citation such as [12] in the text to see its reference.</p>");
        }
        insights.setText(html(b.toString()));
        insights.setCaretPosition(0);
    }

    /* ------------------------------------------------------------- history */

    private JComponent historyPanel() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(SpdfTheme.chrome());
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        top.setBackground(SpdfTheme.chrome());
        top.add(title("HISTORY"));
        top.add(Widgets.button("undo", "Undo (Ctrl+Z)", panel::undo));
        top.add(Widgets.button("redo", "Redo (Ctrl+Y)", panel::redo));
        p.add(top, BorderLayout.NORTH);
        stepList.setBackground(SpdfTheme.chrome());
        stepList.setForeground(SpdfTheme.fg());
        SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss");
        stepList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                JLabel l = (JLabel) super.getListCellRendererComponent(list, value, index, sel, focus);
                EditHistory.Step s = (EditHistory.Step) value;
                l.setText("<html>" + esc(s.label) + " <font color='#888888'>" + f.format(new Date(s.time)) + "</font></html>");
                l.setFont(SpdfTheme.ui(Font.PLAIN, 12));
                l.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 8));
                if (!sel) {
                    l.setBackground(SpdfTheme.chrome());
                    l.setForeground(SpdfTheme.fg());
                }
                return l;
            }
        });
        stepList.setToolTipText("Double-click to go back to the state before this step");
        stepList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && stepList.getSelectedIndex() >= 0) {
                    int n = stepList.getSelectedIndex() + 1;
                    for (int i = 0; i < n; i++) {
                        panel.undo();
                    }
                }
            }
        });
        p.add(scroll(stepList), BorderLayout.CENTER);
        return p;
    }

    void setHistory(List<EditHistory.Step> list) {
        steps.clear();
        for (EditHistory.Step s : list) {
            steps.addElement(s);
        }
    }
}
