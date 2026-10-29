package com.sphere.components.g4editor;

import com.sphere.core.geant4.Geant4Api;

import javax.swing.BorderFactory;
import javax.swing.ListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.HyperlinkEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Geant4's C++ API, browsable: a search over the 3400 classes of Geant4
 * master and their methods, and the page of a class -- its description, its
 * bases and subclasses, its constructors with each parameter explained, its
 * methods with those it inherits. A double click, or Enter, writes the call
 * into the code being edited.
 */
final class ApiBrowser extends JPanel {

    private static final long serialVersionUID = 1L;

    /** What a physicist reaches for first, shown before anything is typed. */
    private static final List<String> FIRST = List.of("G4Box", "G4Tubs", "G4Cons", "G4Sphere", "G4Trd", "G4Polycone",
        "G4SubtractionSolid", "G4UnionSolid", "G4LogicalVolume", "G4PVPlacement", "G4PVReplica", "G4NistManager",
        "G4Material", "G4Element", "G4VisAttributes", "G4RotationMatrix", "G4Transform3D",
        "G4VUserDetectorConstruction", "G4VUserPrimaryGeneratorAction", "G4ParticleGun", "G4GeneralParticleSource",
        "G4UserRunAction", "G4UserEventAction", "G4UserSteppingAction", "G4VSensitiveDetector", "G4SDManager",
        "G4Step", "G4StepPoint", "G4Track", "G4Event", "G4Run", "G4RunManagerFactory", "G4UImanager",
        "G4GDMLParser", "G4Region", "G4ProductionCuts", "G4UserLimits", "G4GlobalMagFieldMessenger",
        "G4UniformMagField", "G4THitsCollection", "G4VHit", "G4AnalysisManager", "G4GenericMessenger");

    private final JTextField query = new JTextField();
    private final DefaultListModel<Object> results = new DefaultListModel<>();
    private final JList<Object> list = new JList<>(results);
    private final JEditorPane page = new JEditorPane();
    private final JLabel info = new JLabel(" ");
    private final Consumer<String> insert;
    private Geant4Api api;

    /** @param insert writes text at the caret of the code being edited */
    ApiBrowser(Consumer<String> insert) {
        super(new BorderLayout(0, 4));
        this.insert = insert;
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        query.putClientProperty("JTextField.placeholderText", "Search Geant4: G4Tubs, placement, energy deposit...");
        query.setToolTipText("A class name, a method name or words of a description");
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new Renderer());
        javax.swing.ToolTipManager.sharedInstance().registerComponent(list);
        page.setEditable(false);
        page.setContentType("text/html");
        page.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        page.addHyperlinkListener(e -> {
            if (e.getEventType() != HyperlinkEvent.EventType.ACTIVATED || e.getDescription() == null) {
                return;
            }
            final String d = e.getDescription();
            if (d.startsWith("class:")) {
                showClass(d.substring(6));
            } else if (d.startsWith("insert:")) {
                insert.accept(d.substring(7));
            }
        });
        info.setForeground(new Color(0x7D8796));

        final JPanel top = new JPanel(new BorderLayout(0, 2));
        top.add(query, BorderLayout.NORTH);
        top.add(info, BorderLayout.SOUTH);
        final JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(list), new JScrollPane(page));
        split.setResizeWeight(0.35);
        split.setDividerLocation(220);
        add(top, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);

        query.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                search();
            }

            public void removeUpdate(DocumentEvent e) {
                search();
            }

            public void changedUpdate(DocumentEvent e) {
            }
        });
        query.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_DOWN && !results.isEmpty()) {
                    list.requestFocusInWindow();
                    list.setSelectedIndex(0);
                } else if (e.getKeyCode() == KeyEvent.VK_ENTER && !results.isEmpty()) {
                    list.setSelectedIndex(0);
                    insertSelected();
                }
            }
        });
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                show(list.getSelectedValue());
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    insertSelected();
                }
            }
        });
        list.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    insertSelected();
                }
            }
        });
        info.setText("Reading Geant4's API...");
        new SwingWorker<Geant4Api, Void>() {
            protected Geant4Api doInBackground() {
                return Geant4Api.get();
            }

            protected void done() {
                try {
                    api = get();
                    info.setText(api.size() + " classes, " + api.version());
                    search();
                } catch (Exception e) {
                    info.setText("Geant4's API could not be read: " + e.getMessage());
                }
            }
        }.execute();
    }

    void focusSearch(String text) {
        if (text != null) {
            query.setText(text);
        }
        query.requestFocusInWindow();
        query.selectAll();
    }

    private void search() {
        if (api == null) {
            return;
        }
        results.clear();
        final String q = query.getText().strip();
        if (q.isEmpty()) {
            for (String n : FIRST) {
                final Geant4Api.ClassInfo c = api.find(n);
                if (c != null) {
                    results.addElement(c);
                }
            }
        } else {
            for (Object o : api.search(q, 120)) {
                results.addElement(o);
            }
        }
        if (!results.isEmpty()) {
            list.setSelectedIndex(0);
        } else {
            page.setText("<html><body style='font-family:sans-serif;font-size:11px'>Nothing in Geant4 matches <b>"
                + esc(q) + "</b>.</body></html>");
        }
    }

    /** Shows a class by name, as a link from another page does. */
    void showClass(String name) {
        if (api == null) {
            return;
        }
        final Geant4Api.ClassInfo c = api.find(name.replaceAll("<.*", ""));
        if (c != null) {
            show(c);
        }
    }

    private void insertSelected() {
        final Object o = list.getSelectedValue();
        if (o instanceof Geant4Api.ClassInfo c) {
            insert.accept(constructorCall(c));
        } else if (o instanceof Geant4Api.Method m) {
            insert.accept(m.name() + "(" + paramNames(m.params()) + ")");
        }
    }

    /** "new G4Box("name", pX, pY, pZ)" from the documented constructor, its parameter names as placeholders. */
    static String constructorCall(Geant4Api.ClassInfo c) {
        Geant4Api.Method best = null;
        for (Geant4Api.Method m : c.constructors()) {
            if (m.params().contains("__void__")) {
                continue;
            }
            if (best == null || (best.doc().isEmpty() && !m.doc().isEmpty())) {
                best = m;
            }
        }
        if (best == null) {
            return c.name();
        }
        final String args = paramNames(best.params());
        return "new " + c.name() + "(" + args.replaceFirst("^pName\\b|^name\\b", "\"" + c.name().substring(2) + "\"")
            + ")";
    }

    private static String paramNames(String params) {
        if (params.isBlank()) {
            return "";
        }
        final List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i <= params.length(); i++) {
            final char ch = i < params.length() ? params.charAt(i) : ',';
            if (ch == '<' || ch == '(') {
                depth++;
            } else if (ch == '>' || ch == ')') {
                depth--;
            } else if (ch == ',' && depth == 0) {
                String p = params.substring(start, i).strip();
                start = i + 1;
                if (p.contains("=")) {
                    break;   // the defaulted ones are left out
                }
                final String[] w = p.split("[\\s*&]+");
                out.add(w.length == 0 ? "?" : w[w.length - 1]);
            }
        }
        return String.join(", ", out);
    }

    private void show(Object o) {
        if (o instanceof Geant4Api.Method m) {
            final Geant4Api.ClassInfo c = api.find(m.owner());
            if (c != null) {
                show(c);
            }
            return;
        }
        if (!(o instanceof Geant4Api.ClassInfo c)) {
            return;
        }
        // One text colour; what matters more is larger or bold, never another shade. Links are the accent.
        final String accent = hex(accent(), "#2f6fde");
        final StringBuilder b = new StringBuilder("<html><body style='font-family:sans-serif;font-size:11px'>");
        b.append("<div style='font-size:15px'><b>").append(esc(c.name())).append("</b></div>");
        b.append("<div style='font-size:10px'>").append(esc(c.category())).append(" &middot; #include &quot;")
         .append(esc(c.header())).append("&quot;</div>");
        if (!c.doc().isEmpty()) {
            b.append("<p>").append(esc(c.doc())).append("</p>");
        }
        final List<Geant4Api.ClassInfo> lineage = api.lineage(c.name());
        if (lineage.size() > 1 || !c.bases().isEmpty()) {
            b.append("<p><b>Derives from</b> ");
            for (String base : c.bases()) {
                b.append(link("class:" + base, base, accent)).append(' ');
            }
            if (lineage.size() > 2) {
                b.append("(then ");
                for (int i = 2; i < lineage.size(); i++) {
                    b.append(i > 2 ? ", " : "").append(esc(lineage.get(i).name()));
                }
                b.append(')');
            }
            b.append("</p>");
        }
        final List<Geant4Api.ClassInfo> subs = api.subclasses(c.name());
        if (!subs.isEmpty()) {
            b.append("<p><b>Derived</b> ");
            for (int i = 0; i < subs.size() && i < 24; i++) {
                b.append(link("class:" + subs.get(i).name(), subs.get(i).name(), accent)).append(' ');
            }
            if (subs.size() > 24) {
                b.append("and ").append(subs.size() - 24).append(" more");
            }
            b.append("</p>");
        }
        if (!c.constructors().isEmpty()) {
            b.append("<p><b>Constructors</b> &nbsp;").append(link("insert:" + constructorCall(c), "insert", accent))
             .append("</p>");
            for (Geant4Api.Method m : c.constructors()) {
                if (!m.params().contains("__void__")) {
                    method(b, m, accent);
                }
            }
        }
        final List<Geant4Api.Method> all = api.allMethods(c.name());
        if (!all.isEmpty()) {
            b.append("<p><b>Methods</b> (").append(all.size()).append(", the inherited ones too)</p>");
            for (Geant4Api.Method m : all) {
                method(b, m, accent);
            }
        }
        b.append("</body></html>");
        page.setText(b.toString());
        page.setCaretPosition(0);
    }

    private static void method(StringBuilder b, Geant4Api.Method m, String accent) {
        b.append("<div style='margin-top:5px'><code>");
        if (!m.returns().isEmpty()) {
            b.append(m.isStatic() ? "static " : "").append(esc(m.returns())).append(' ');
        }
        b.append("<b>").append(esc(m.name())).append("</b>(").append(esc(m.params())).append(")</code>");
        if (!m.isConstructor()) {
            b.append(" &nbsp;").append(link("insert:" + m.name() + "(" + paramNames(m.params()) + ")", "insert", accent));
            if (!m.owner().equals(m.name())) {
                b.append(" &nbsp;<i>").append(esc(m.owner())).append("</i>");
            }
        }
        if (!m.brief().isEmpty()) {
            b.append("<br>").append(esc(m.brief()));
        }
        for (String note : m.paramNotes()) {
            final int colon = note.indexOf(": ");
            b.append("<br>&nbsp;&nbsp;&nbsp;");
            if (colon > 0) {
                b.append("<i>").append(esc(note.substring(0, colon))).append("</i>").append(esc(note.substring(colon)));
            } else {
                b.append(esc(note));
            }
        }
        b.append("</div>");
    }

    private static Color accent() {
        try {
            return com.sphere.theme.ThemeManager.getCurrentPalette().getAccent();
        } catch (Throwable e) {
            return UIManager.getColor("List.selectionBackground");
        }
    }

    private static String link(String href, String text, String color) {
        return "<a href='" + esc(href) + "' style='color:" + color + "'>" + esc(text) + "</a>";
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;");
    }

    private static String hex(Color c, String fallback) {
        return c == null ? fallback : String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    /** A row: the class (or method) on the left, its category on the right, both in the list's one colour. */
    private static final class Renderer extends JPanel implements ListCellRenderer<Object> {
        private static final long serialVersionUID = 1L;
        private final JLabel name = new JLabel(), where = new JLabel();

        Renderer() {
            super(new BorderLayout(10, 0));
            setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
            add(name, BorderLayout.CENTER);
            add(where, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean selected, boolean focus) {
            final Color fg = selected ? l.getSelectionForeground() : l.getForeground();
            setOpaque(true);
            setBackground(selected ? l.getSelectionBackground() : l.getBackground());
            name.setForeground(fg);
            where.setForeground(fg);
            name.setFont(l.getFont());
            where.setFont(l.getFont().deriveFont(l.getFont().getSize2D() - 1f));
            if (value instanceof Geant4Api.ClassInfo ci) {
                name.setText(ci.name());
                where.setText(ci.category());
                setToolTipText(ci.doc().isEmpty() ? null : ci.doc());
            } else if (value instanceof Geant4Api.Method m) {
                name.setText(m.owner() + "::" + m.name() + "(" + shorten(m.params()) + ")");
                where.setText("");
                setToolTipText(m.brief().isEmpty() ? null : m.brief());
            }
            return this;
        }
    }

    private static String shorten(String params) {
        return params.length() > 48 ? params.substring(0, 46) + "..." : params;
    }
}
