package com.sphere.components.g4editor;

import com.sphere.core.geant4.BeamPhysics;
import com.sphere.core.geant4.Geometry;
import com.sphere.core.geant4.GeometryException;
import com.sphere.core.geant4.GoalSeek;
import com.sphere.core.geant4.Templates;
import com.sphere.core.geant4.Units;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The template gallery: every detector template at a glance, each drawn in 3D with the beam it is made for and the
 * energy that beam leaves along it. A search reads what is asked in words ("a calorimeter for 100 GeV pions") into
 * the template and its beam. The template chosen is shown large with its parameters: as they change, it is built
 * again on a copy, drawn, and its physics worked out (Physics Live's), and a goal finds the parameter value that meets
 * a physics target. Created, it is the console's template command, logged and undone like any edit.
 */
final class TemplateGallery extends JDialog {

    private static final long serialVersionUID = 1L;

    /**
     * What was chosen: the template, the parameters changed from their defaults, the beam as a console command (null:
     * none) and as the gun's settings (empty: none), for G4code's C++.
     */
    record Choice(String kind, Map<String, String> params, String beamCommand, Map<String, String> gun) {
        /** The console command that builds it. */
        String command() {
            final StringBuilder b = new StringBuilder("template " + kind);
            params.forEach((k, v) -> b.append(' ').append(k).append('=').append(v.replace(" ", "")));
            return b.toString();
        }
    }

    /** A template built on a copy, drawn, and judged for its beam. */
    record Preview(BufferedImage image, BeamPhysics.Report report, Geometry geometry, String error) {
    }

    private static final String[] PARTICLES = {"e-", "e+", "gamma", "mu-", "mu+", "pi-", "pi+", "proton", "neutron", "kaon-"};
    private static final int THUMB_W = 196, THUMB_H = 118, PREVIEW_W = 420, PREVIEW_H = 236;
    private static final Pattern ENERGY = Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)\\s*(tev|gev|mev|kev|ev)\\b");
    private static final Set<String> STOP = Set.of("a", "an", "the", "for", "of", "with", "to", "and", "in", "on", "at",
        "my", "i", "want", "need", "some", "that", "this", "from", "by", "detector", "design", "make", "build", "template");

    private final boolean dark;
    private final SphereCreo.Palette pal;
    private final ExecutorService painter = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "template-gallery");
        t.setDaemon(true);
        return t;
    });
    final Map<String, BufferedImage> thumbs = new HashMap<>();
    private final List<Card> cards = new ArrayList<>();
    final JTextField search = new JTextField();
    final JLabel heard = new JLabel(" ");
    private final DefaultListModel<String> fields = new DefaultListModel<>();
    private final JList<String> fieldList = new JList<>(fields);
    private final JPanel grid = new JPanel(new GridLayout(0, 3, 8, 8));
    private final JLabel preview = new JLabel("", SwingUtilities.CENTER);
    private final JLabel title = new JLabel(" ");
    private final JTextArea about = new JTextArea(3, 30);
    final JComboBox<String> particle = new JComboBox<>(PARTICLES);
    final JTextField energy = new JTextField(8);
    private final JCheckBox setBeam = new JCheckBox("Make it the design's beam", true);
    private final List<String> keys = new ArrayList<>();
    final DefaultTableModel params = new DefaultTableModel(new Object[] {"Parameter", "Value"}, 0) {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 1;
        }
    };
    private final JTable table = new JTable(params);
    private final JEditorPane physics = new JEditorPane("text/html", "");
    private final JComboBox<GoalSeek.Metric> metric = new JComboBox<>(new GoalSeek.Metric[] {GoalSeek.Metric.CONTAINMENT,
        GoalSeek.Metric.LONGITUDINAL, GoalSeek.Metric.LATERAL, GoalSeek.Metric.DEPTH_X0, GoalSeek.Metric.DEPTH_LAMBDA,
        GoalSeek.Metric.MASS, GoalSeek.Metric.SAMPLING, GoalSeek.Metric.RESOLUTION, GoalSeek.Metric.MIP_SIGNAL});
    private final JComboBox<String> op = new JComboBox<>(new String[] {"≥", "≤", "="});
    final JTextField target = new JTextField("95", 6);
    final JLabel goalSaid = new JLabel(" ");
    private final JButton seek = new JButton("Seek");
    private final AtomicLong generation = new AtomicLong();
    private final Timer redraw = new Timer(300, e -> renderPreview());
    Templates.Template current;
    Preview last;
    private Choice chosen;
    private boolean filling;

    /** Shows the gallery and waits: the template chosen, or null. */
    static Choice choose(Window owner, String action, boolean dark) {
        final TemplateGallery d = new TemplateGallery(owner, action, dark);
        d.setVisible(true);
        return d.chosen;
    }

    TemplateGallery(Window owner, String action, boolean dark) {
        super(owner, "Template gallery", ModalityType.APPLICATION_MODAL);
        this.dark = dark;
        this.pal = dark ? SphereCreo.DARK : SphereCreo.LIGHT;
        redraw.setRepeats(false);
        setSize(1220, 760);
        setLocationRelativeTo(owner);
        final JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        root.setBackground(pal.window());
        setContentPane(root);

        // the search: words read into a template and its beam
        final JPanel top = new JPanel(new BorderLayout(8, 2));
        top.setOpaque(false);
        final JLabel s = new JLabel("Search");
        s.setFont(SphereCreo.UI_BOLD);
        s.setForeground(pal.text());
        top.add(s, BorderLayout.WEST);
        search.putClientProperty("JTextField.placeholderText", "Describe what you need: \"a calorimeter for 100 GeV pions\","
            + " \"gamma spectroscopy\", \"proton therapy\"...");
        search.setToolTipText("Words about the detector, its physics, its field; a particle and an energy set the beam");
        top.add(search, BorderLayout.CENTER);
        heard.setForeground(pal.textDim());
        heard.setFont(SphereCreo.SMALL);
        top.add(heard, BorderLayout.SOUTH);
        root.add(top, BorderLayout.NORTH);

        // the fields, the cards
        final Map<String, Integer> counts = new LinkedHashMap<>();
        for (Templates.Template t : Templates.catalog()) {
            counts.merge(t.field(), 1, Integer::sum);
        }
        fields.addElement("All templates (" + Templates.catalog().size() + ")");
        counts.forEach((f, n) -> fields.addElement(f + " (" + n + ")"));
        fieldList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fieldList.setSelectedIndex(0);
        fieldList.setFont(SphereCreo.UI);
        fieldList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                refill(false);
            }
        });
        final JScrollPane fs = new JScrollPane(fieldList);
        fs.setPreferredSize(new Dimension(200, 10));
        root.add(fs, BorderLayout.WEST);
        for (Templates.Template t : Templates.catalog()) {
            cards.add(new Card(t));
        }
        grid.setOpaque(false);
        final JPanel gridHolder = new JPanel(new BorderLayout());
        gridHolder.setOpaque(false);
        gridHolder.add(grid, BorderLayout.NORTH);
        final JScrollPane gs = new JScrollPane(gridHolder);
        gs.getVerticalScrollBar().setUnitIncrement(24);
        gs.getViewport().setBackground(pal.window());
        root.add(gs, BorderLayout.CENTER);

        root.add(details(), BorderLayout.EAST);

        // the buttons
        final JPanel buttons = new JPanel(new BorderLayout());
        buttons.setOpaque(false);
        final JButton copy = new JButton("Copy command");
        copy.setToolTipText("The console command that builds it (and sets its beam), to the clipboard");
        copy.addActionListener(e -> {
            final Choice c = choice();
            if (c != null) {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(c.command()
                    + (c.beamCommand() == null ? "" : "; " + c.beamCommand())), null);
                goalSaid.setText("Copied: " + c.command());
            }
        });
        final JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        final JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        final JButton create = new JButton(action);
        create.addActionListener(e -> create());
        right.add(cancel);
        right.add(create);
        buttons.add(copy, BorderLayout.WEST);
        buttons.add(right, BorderLayout.EAST);
        root.add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(create);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW);

        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                refill(true);
            }

            public void removeUpdate(DocumentEvent e) {
                refill(true);
            }

            public void changedUpdate(DocumentEvent e) {
                refill(true);
            }
        });
        refill(false);
        select(Templates.catalog().get(0));
        // the thumbnails, one after the other, off the event thread
        for (Templates.Template t : Templates.catalog()) {
            painter.submit(() -> {
                final Preview p = render(t, Map.of(), null, 0, THUMB_W, THUMB_H, dark);
                SwingUtilities.invokeLater(() -> {
                    thumbs.put(t.kind(), p.image());
                    grid.repaint();
                });
            });
        }
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                painter.shutdownNow();
                redraw.stop();
            }
        });
    }

    /** The chosen template, large: its preview, what it is, its beam, its parameters, its physics, a goal. */
    private JComponent details() {
        final JPanel d = new JPanel(new BorderLayout(4, 6));
        d.setPreferredSize(new Dimension(PREVIEW_W + 20, 10));
        d.setBackground(pal.navBg());
        d.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        final JPanel head = new JPanel(new BorderLayout(4, 4));
        head.setOpaque(false);
        preview.setPreferredSize(new Dimension(PREVIEW_W, PREVIEW_H));
        preview.setForeground(pal.textDim());
        head.add(preview, BorderLayout.NORTH);
        title.setFont(SphereCreo.BIG);
        title.setForeground(pal.text());
        head.add(title, BorderLayout.CENTER);
        about.setLineWrap(true);
        about.setWrapStyleWord(true);
        about.setEditable(false);
        about.setOpaque(false);
        about.setFont(SphereCreo.UI);
        about.setForeground(pal.textDim());
        head.add(about, BorderLayout.SOUTH);
        d.add(head, BorderLayout.NORTH);

        final JPanel middle = new JPanel(new BorderLayout(4, 6));
        middle.setOpaque(false);
        final JPanel beam = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        beam.setOpaque(false);
        final JLabel bl = new JLabel("Beam");
        bl.setForeground(pal.text());
        beam.add(bl);
        beam.add(particle);
        beam.add(energy);
        setBeam.setOpaque(false);
        setBeam.setForeground(pal.text());
        setBeam.setToolTipText("The template's beam becomes the design's gun: the one Physics Live judges, a run fires and"
            + " the C++ export uses");
        beam.add(setBeam);
        particle.addActionListener(e -> {
            if (!filling) {
                redraw.restart();
            }
        });
        energy.addActionListener(e -> redraw.restart());
        energy.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                edited();
            }

            public void removeUpdate(DocumentEvent e) {
                edited();
            }

            public void changedUpdate(DocumentEvent e) {
                edited();
            }
        });
        middle.add(beam, BorderLayout.NORTH);
        table.setRowHeight(22);
        table.getColumnModel().getColumn(0).setPreferredWidth(190);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        params.addTableModelListener(e -> edited());
        final JScrollPane ts = new JScrollPane(table);
        ts.setPreferredSize(new Dimension(PREVIEW_W, 150));
        middle.add(ts, BorderLayout.CENTER);
        physics.setEditable(false);
        physics.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        physics.setFont(SphereCreo.UI);
        final JScrollPane ps = new JScrollPane(physics);
        ps.setPreferredSize(new Dimension(PREVIEW_W, 118));
        middle.add(ps, BorderLayout.SOUTH);
        d.add(middle, BorderLayout.CENTER);

        // a goal: the parameter value that meets a physics target, found on copies
        final JPanel goal = new JPanel(new BorderLayout(4, 2));
        goal.setOpaque(false);
        final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row.setOpaque(false);
        final JLabel gl = new JLabel("Goal");
        gl.setForeground(pal.text());
        row.add(gl);
        metric.setRenderer(new javax.swing.DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel,
                                                                   boolean focus) {
                final GoalSeek.Metric m = (GoalSeek.Metric) value;
                return super.getListCellRendererComponent(list, m == null ? "" : m.label + " (" + m.unit + ")", index, sel,
                    focus);
            }
        });
        row.add(metric);
        row.add(op);
        row.add(target);
        seek.setToolTipText("Finds the value of this template's parameter that meets the goal, each tried on a copy: the"
            + " parameter that moves it most is turned");
        seek.addActionListener(e -> seek());
        row.add(seek);
        goal.add(row, BorderLayout.NORTH);
        goalSaid.setForeground(pal.textDim());
        goalSaid.setFont(SphereCreo.SMALL);
        goal.add(goalSaid, BorderLayout.SOUTH);
        d.add(goal, BorderLayout.SOUTH);
        return d;
    }

    private void edited() {
        if (!filling) {
            redraw.restart();
        }
    }

    // ------------------------------------------------------------------
    // Search and cards
    // ------------------------------------------------------------------

    /** How well a template answers words: its name counts most, its keywords next, its description and field last. */
    static int score(Templates.Template t, String query) {
        final String name = t.title().toLowerCase(Locale.ROOT) + " " + t.kind();
        final String keys = Templates.keywords(t.kind()).toLowerCase(Locale.ROOT);
        final String text = (t.description() + " " + t.field()).toLowerCase(Locale.ROOT);
        int score = 0;
        for (String w : query.toLowerCase(Locale.ROOT).split("[^a-z0-9\\-/]+")) {
            if (w.length() < 2 || STOP.contains(w) || w.matches("\\d+(\\.\\d+)?")) {
                continue;
            }
            final String stem = w.length() > 3 && w.endsWith("s") ? w.substring(0, w.length() - 1) : w;
            if (name.contains(stem)) {
                score += 3;
            } else if (keys.contains(stem)) {
                score += 2;
            } else if (text.contains(stem)) {
                score += 1;
            }
        }
        return score;
    }

    /** A particle and an energy said in words ("100 GeV pions"), or null. */
    static Object[] beamIn(String query) {
        final Matcher e = ENERGY.matcher(query);
        String energyText = null;
        if (e.find()) {
            final String u = e.group(2).toLowerCase(Locale.ROOT);
            energyText = e.group(1) + " " + switch (u) {
                case "tev" -> "TeV";
                case "gev" -> "GeV";
                case "mev" -> "MeV";
                case "kev" -> "keV";
                default -> "eV";
            };
        }
        BeamPhysics.Particle p = null;
        for (String w : query.split("[^A-Za-z0-9+\\-]+")) {
            final BeamPhysics.Particle q = w.length() < 2 ? null : BeamPhysics.Particle.of(w);
            if (q != null && q != BeamPhysics.Particle.GEANTINO) {
                p = q;
                break;
            }
        }
        return p == null && energyText == null ? null : new Object[] {p, energyText};
    }

    /** The cards of the field chosen, the best answers to the search first. */
    private void refill(boolean fromSearch) {
        final String q = search.getText().strip();
        final int fi = fieldList.getSelectedIndex();
        final String field = fi <= 0 ? null : Templates.catalog().stream().map(Templates.Template::field).distinct()
            .toList().get(fi - 1);
        final List<Card> shown = new ArrayList<>();
        for (Card c : cards) {
            if (field != null && !c.t.field().equals(field)) {
                continue;
            }
            c.score = q.isEmpty() ? 0 : score(c.t, q);
            if (q.isEmpty() || c.score > 0) {
                shown.add(c);
            }
        }
        if (!q.isEmpty()) {
            shown.sort(Comparator.comparingInt((Card c) -> -c.score));
        }
        grid.removeAll();
        for (Card c : shown) {
            grid.add(c);
        }
        grid.revalidate();
        grid.repaint();
        if (fromSearch) {
            final Object[] b = beamIn(q);
            final StringBuilder said = new StringBuilder();
            if (!shown.isEmpty() && !q.isEmpty()) {
                select(shown.get(0).t);
                said.append("Best match: ").append(shown.get(0).t.title());
            } else if (!q.isEmpty()) {
                said.append("No template answers these words: try a field, a particle, a detector's name");
            }
            if (b != null) {
                filling = true;
                try {
                    if (b[0] != null) {
                        particle.setSelectedItem(((BeamPhysics.Particle) b[0]).g4Name);
                    }
                    if (b[1] != null) {
                        energy.setText((String) b[1]);
                    }
                } finally {
                    filling = false;
                }
                said.append(said.length() > 0 ? "; " : "").append("beam ").append(particle.getSelectedItem()).append(' ')
                    .append(energy.getText());
                redraw.restart();
            }
            heard.setText(said.length() == 0 ? " " : said.toString());
        }
    }

    /** The template shown large, its parameters at their defaults and its own beam. */
    private void select(Templates.Template t) {
        if (t == current) {
            return;
        }
        current = t;
        filling = true;
        try {
            title.setText(t.title() + "   ·   " + t.field());
            about.setText(t.description() + "\nMade for: " + t.beam() + ".");
            about.setCaretPosition(0);
            params.setRowCount(0);
            keys.clear();
            for (Templates.Param p : t.params()) {
                keys.add(p.key());
                params.addRow(new Object[] {p.label() + (Templates.variableOf(t.kind(), p.key()) == null ? "" : "  ●"),
                    p.value()});
            }
            table.setToolTipText("●: a variable of the design once built (on the model, in Variables, by a goal)");
            final String[] w = t.beam().split("\\s+");
            particle.setSelectedItem(w[0]);
            energy.setText(w.length > 2 ? w[1] + " " + w[2] : "1 GeV");
        } finally {
            filling = false;
        }
        for (Card c : cards) {
            c.repaint();
        }
        goalSaid.setText(" ");
        preview.setIcon(null);
        preview.setText("drawing " + t.title() + "...");
        renderPreview();
    }

    /** A template on the grid: its 3D picture, its name, its field, its beam. */
    private final class Card extends JComponent {
        private static final long serialVersionUID = 1L;
        final Templates.Template t;
        int score;

        Card(Templates.Template t) {
            this.t = t;
            setPreferredSize(new Dimension(THUMB_W + 12, THUMB_H + 62));
            setToolTipText("<html><body style='width:280px'>" + t.description() + "<br><i>" + t.beam() + "</i></body></html>");
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    select(t);
                    if (e.getClickCount() == 2) {
                        create();
                    }
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g0) {
            final Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            final boolean on = t == current;
            g.setColor(pal.navBg());
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
            g.setColor(on ? pal.accent() : pal.line());
            g.setStroke(new java.awt.BasicStroke(on ? 2f : 1f));
            g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 10, 10);
            final BufferedImage img = thumbs.get(t.kind());
            if (img != null) {
                g.drawImage(img, 6, 6, null);
            } else {
                g.setColor(new Color(pal.graphicsBottom()));
                g.fillRect(6, 6, THUMB_W, THUMB_H);
                g.setColor(pal.textDim());
                g.setFont(SphereCreo.SMALL);
                g.drawString("drawing...", 14, 24);
            }
            int y = THUMB_H + 6 + 16;
            g.setFont(SphereCreo.UI_BOLD);
            g.setColor(pal.text());
            g.drawString(fit(g, t.title(), THUMB_W), 8, y);
            y += 15;
            g.setFont(SphereCreo.SMALL);
            g.setColor(pal.textDim());
            g.drawString(fit(g, t.field(), THUMB_W), 8, y);
            y += 14;
            g.setColor(new Color(0x38C8F0));
            g.drawString(fit(g, t.beam(), THUMB_W), 8, y);
            g.dispose();
        }
    }

    private static String fit(Graphics2D g, String s, int w) {
        final FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(s) <= w) {
            return s;
        }
        String t = s;
        while (t.length() > 1 && fm.stringWidth(t + "…") > w) {
            t = t.substring(0, t.length() - 1);
        }
        return t + "…";
    }

    // ------------------------------------------------------------------
    // Previews: built on a copy, drawn, judged
    // ------------------------------------------------------------------

    /** The parameters as the table holds them now, those that differ from the defaults. */
    private Map<String, String> changed() {
        final Map<String, String> out = new LinkedHashMap<>();
        if (current == null) {
            return out;
        }
        for (int i = 0; i < keys.size(); i++) {
            final String v = String.valueOf(params.getValueAt(i, 1)).strip();
            if (!v.isEmpty() && !v.equals(current.params().get(i).value())) {
                out.put(keys.get(i), v.replace(" ", ""));
            }
        }
        return out;
    }

    private BeamPhysics.Particle chosenParticle() {
        return BeamPhysics.Particle.of(String.valueOf(particle.getSelectedItem()));
    }

    private double chosenEnergy() {
        try {
            return com.sphere.core.geant4.Expr.eval(com.sphere.core.geant4.Inputs.normalize(energy.getText(),
                Units.Category.ENERGY, "mm"), com.sphere.core.geant4.Expr.NO_VARIABLES);
        } catch (GeometryException | RuntimeException e) {
            return 0;
        }
    }

    private void renderPreview() {
        if (current == null) {
            return;
        }
        final Templates.Template t = current;
        final Map<String, String> p = changed();
        final BeamPhysics.Particle part = chosenParticle();
        final double e = chosenEnergy();
        final long gen = generation.incrementAndGet();
        painter.submit(() -> {
            if (gen != generation.get()) {
                return;
            }
            final Preview pr = render(t, p, part, e, PREVIEW_W, PREVIEW_H, dark);
            SwingUtilities.invokeLater(() -> {
                if (gen == generation.get()) {
                    shown(pr);
                }
            });
        });
    }

    private void shown(Preview p) {
        last = p;
        if (p.image() != null) {
            preview.setIcon(new ImageIcon(p.image()));
            preview.setText(null);
        } else {
            preview.setIcon(null);
            preview.setText(p.error());
        }
        physics.setText(html(p));
        physics.setCaretPosition(0);
    }

    /** What the beam does in the template as it stands: Physics Live's lines, the mass, the notes. */
    static String html(Preview p) {
        if (p.report() == null) {
            return "<html><body style='font-family:sans-serif;font-size:10px;color:#e05050'>" + esc(String.valueOf(p.error()))
                + "</body></html>";
        }
        final StringBuilder b = new StringBuilder("<html><body style='font-family:sans-serif;font-size:10px'>");
        final BeamPhysics.Report r = p.report();
        final List<String> lines = r.lines();
        for (int i = 0; i < lines.size(); i++) {
            final String l = lines.get(i);
            final boolean note = i > 0 && r.notes().contains(l);
            b.append(note ? "<span style='color:#f0b030'>" : "").append(esc(l)).append(note ? "</span>" : "").append("<br>");
        }
        if (p.geometry() != null) {
            b.append("<span style='color:gray'>Mass: ").append(esc(Units.best(GoalSeek.detectorMass(p.geometry()),
                Units.Category.MASS))).append(String.format(Locale.ROOT, "; %d volumes, %d placements</span>",
                p.geometry().volumes().size(), p.geometry().placements().size()));
        }
        return b.append("</body></html>").toString();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** A template built on a copy with these parameters and this beam, drawn with its beam and shower, and judged. */
    static Preview render(Templates.Template t, Map<String, String> params, BeamPhysics.Particle particle, double energy,
                          int w, int h, boolean dark) {
        try {
            final Geometry g = build(t, params, particle, energy);
            final EditorModel m = new EditorModel(g);
            final Viewport vp = new Viewport(m);
            vp.darkTheme = dark;
            vp.bare = true;
            vp.showDatums = false;
            vp.showSpin = false;
            vp.showGrid = false;
            vp.setSize(w, h);
            vp.camera.width = w;
            vp.camera.height = h;
            vp.setView("iso", false);
            vp.fitAll(false);
            final SphereLive live = new SphereLive(m, vp);
            live.showCard = false;
            vp.live = live;
            live.setOn(true);
            final BufferedImage img = vp.snapshot(w, h);
            return new Preview(img, live.report(), g, null);
        } catch (Exception e) {
            return new Preview(null, null, null, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /** A template built alone in a blank design, its beam set (the template's, the particle and energy given). */
    static Geometry build(Templates.Template t, Map<String, String> params, BeamPhysics.Particle particle, double energy)
        throws GeometryException {
        final Geometry g = Geometry.blank();
        Templates.build(g, t.kind(), params, "mm");
        if (particle != null) {
            g.settings().put("gun.particle", particle.g4Name);
        }
        if (energy > 0) {
            g.settings().put("gun.energy", Units.num(energy) + "*MeV");
        }
        g.rebuild();
        return g;
    }

    // ------------------------------------------------------------------
    // A goal on the template's parameters
    // ------------------------------------------------------------------

    void seek() {
        if (current == null) {
            return;
        }
        final Templates.Template t = current;
        final Map<String, String> p = changed();
        final BeamPhysics.Particle part = chosenParticle();
        final double e = chosenEnergy();
        final GoalSeek.Metric m = (GoalSeek.Metric) metric.getSelectedItem();
        final GoalSeek.Op o = switch (op.getSelectedIndex()) {
            case 1 -> GoalSeek.Op.AT_MOST;
            case 2 -> GoalSeek.Op.EQUAL;
            default -> GoalSeek.Op.AT_LEAST;
        };
        final double goalValue;
        try {
            goalValue = m.parse(target.getText(), com.sphere.core.geant4.Expr.NO_VARIABLES);
        } catch (GeometryException ex) {
            goalSaid.setText("Target: " + ex.getMessage());
            return;
        }
        // the variables the parameters become: what the goal may turn
        final Map<String, String> keyOf = new HashMap<>();
        for (Templates.Param q : t.params()) {
            final String v = Templates.variableOf(t.kind(), q.key());
            if (v != null) {
                keyOf.put(v, q.key());
            }
        }
        seek.setEnabled(false);
        goalSaid.setText("Seeking " + m.label + " " + op.getSelectedItem() + " " + target.getText() + " ...");
        painter.submit(() -> {
            String said;
            String key = null, value = null;
            try {
                final Geometry g = build(t, p, part, e);
                final BeamPhysics.Beam beam = BeamPhysics.Beam.of(g);
                final GoalSeek.Goal goal = new GoalSeek.Goal(m, o, goalValue);
                final List<GoalSeek.Lever> levers = new ArrayList<>(GoalSeek.levers(g, beam, m));
                levers.removeIf(l -> !keyOf.containsKey(l.variable()));
                if (levers.isEmpty()) {
                    said = "No parameter of this template moves the " + m.label + " for " + beam.describe();
                } else {
                    final GoalSeek.Result r = GoalSeek.solve(g, beam, goal, levers.get(0).variable());
                    key = keyOf.get(r.variable());
                    value = r.after();
                    said = r.message().replace(r.variable(), key) + (r.reached() ? "" : " (not met)");
                }
            } catch (Exception ex) {
                said = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            }
            final String s = said, k = key, v = value;
            SwingUtilities.invokeLater(() -> {
                seek.setEnabled(true);
                goalSaid.setText(s);
                if (k != null && t == current) {
                    final int row = keys.indexOf(k);
                    if (row >= 0) {
                        params.setValueAt(v, row, 1);
                    }
                }
            });
        });
    }

    // ------------------------------------------------------------------
    // Creating
    // ------------------------------------------------------------------

    /** The choice as the dialog stands: the parameters changed, and the beam command when the beam is to be set. */
    Choice choice() {
        if (current == null) {
            return null;
        }
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        final Map<String, String> p = changed();
        String beam = null;
        final Map<String, String> gun = new LinkedHashMap<>();
        if (setBeam.isSelected()) {
            try {
                final Geometry g = build(current, p, chosenParticle(), chosenEnergy());
                beam = beamCommand(g.settings());
                g.settings().forEach((k, v) -> {
                    if (k.startsWith("gun.")) {
                        gun.put(k, v);
                    }
                });
            } catch (GeometryException e) {
                goalSaid.setText(e.getMessage());
                return null;
            }
        }
        return new Choice(current.kind(), p, beam, gun);
    }

    /** The console's beam command for a gun: its particle, its energy, where it stands (when set) and where it goes. */
    static String beamCommand(Map<String, String> gun) {
        final StringBuilder b = new StringBuilder("beam " + gun.getOrDefault("gun.particle", "e-") + " "
            + gun.getOrDefault("gun.energy", "1*GeV").replace(" ", ""));
        final String pos = gun.get("gun.position");
        if (pos != null && !pos.isBlank()) {
            b.append(" at ").append(pos.strip());
        }
        b.append(" toward ").append(gun.getOrDefault("gun.direction", "0 0 1").strip());
        return b.toString();
    }

    private void create() {
        final Choice c = choice();
        if (c != null) {
            chosen = c;
            dispose();
        }
    }
}
