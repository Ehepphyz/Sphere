package com.sphere.core.python.jupyterlab;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import com.sphere.utils.AppLogger;
import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.fonts.FontLoader;


public final class JupylabXeditor extends JFrame {

    private static final long serialVersionUID = 1L;
    private final ThemePalette palette = ThemeManager.getCurrentPalette();

    static class Notebook {
        int nbformat = 4;
        int nbformat_minor = 5;
        Map<String, Object> metadata = new LinkedHashMap<>();
        List<Cell> cells = new ArrayList<>();
        // Keys the format may gain, kept so a save does not strip them.
        Map<String, Object> extra = new LinkedHashMap<>();
    }

    static class Cell {
        String cell_type;
        // Required from nbformat 4.5 on, and dropped by the previous reader.
        String id;
        Map<String, Object> metadata = new LinkedHashMap<>();
        Object source;
        Integer execution_count;
        List<Output> outputs = new ArrayList<>();
        Map<String, Object> extra = new LinkedHashMap<>();
    }

    static class Output {
        String output_type;
        String name;
        Object text;
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> metadata = new LinkedHashMap<>();
        String ename;
        String evalue;
        List<String> traceback = new ArrayList<>();
        Integer execution_count;
    }

    public enum KernelMode { DISABLED, LOCAL_PYTHON, CONNECTING }

    private transient KernelMode kernelMode = KernelMode.LOCAL_PYTHON;
    private transient Notebook notebook;
    private transient Path currentPath;
    private transient JPanel cellsContainer;
    private transient JComboBox<String> kernelSwitcher;
    private transient JLabel kernelIndicator;
    private transient JScrollPane cellsScrollPane;
    private boolean dirty = false;

    // Kernel side: one live interpreter, its inspector and its completion popup.
    private final transient com.sphere.utils.SettingsManager settings =
        new com.sphere.utils.SettingsManager();
    private transient NotebookExecutor executor;
    private transient JLabel kernelStatusLabel;
    private transient JTable variableTable;
    private transient javax.swing.table.DefaultTableModel variableModel;
    private transient JSplitPane bodySplit;
    private transient JPopupMenu completionPopup;
    private transient javax.swing.text.JTextComponent completionTarget;
    private transient int completionAnchor = -1;

    public JupylabXeditor(Path ipynbPath) throws Exception {
        super("JupylabXeditor - " + (ipynbPath == null ? "untitled" : ipynbPath.getFileName()));
        this.currentPath = ipynbPath;
        if (ipynbPath != null && Files.exists(ipynbPath)) {
            this.notebook = loadNotebook(ipynbPath);
        } else {
            this.notebook = new Notebook();
        }

        initUI();

        // Declare the global listener as a local variable so it can be unregistered later
        AWTEventListener mouseHoverListener = new AWTEventListener() {
            @Override
            public void eventDispatched(AWTEvent event) {
                if (event instanceof MouseEvent me) {
                    if (me.getID() == MouseEvent.MOUSE_MOVED || me.getID() == MouseEvent.MOUSE_ENTERED) {
                        Component src = me.getComponent();
                        if (src == null || cellsContainer == null) return;

                        CellPanel activeTarget = null;
                        for (Component comp : cellsContainer.getComponents()) {
                            if (comp instanceof CellPanel cp) {
                                Point p = SwingUtilities.convertPoint(src, me.getPoint(), cp);
                                if (cp.getBounds().contains(p.x + cp.getX(), p.y + cp.getY())) {
                                    activeTarget = cp;
                                    break;
                                }
                            }
                        }

                        for (Component comp : cellsContainer.getComponents()) {
                            if (comp instanceof CellPanel cp) {
                                cp.setCellHoverActive(cp == activeTarget);
                            }
                        }
                    }
                }
            }
        };

        // Register the global AWT listener
        Toolkit.getDefaultToolkit().addAWTEventListener(mouseHoverListener, 
                AWTEvent.MOUSE_MOTION_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK);

        // Handle window events to clean up the global listener and prevent memory leaks
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                handleQuit();
            }
            @Override
            public void windowClosed(WindowEvent e) {
                // Remove the global listener when the window is completely closed/disposed
                Toolkit.getDefaultToolkit().removeAWTEventListener(mouseHoverListener);
                // A kernel outliving its notebook would hold an interpreter per
                // window that was ever opened.
                if (executor != null) {
                    executor.shutdown();
                }
            }
        });

        if (cellsScrollPane != null) {
            SwingUtilities.invokeLater(() -> {
                JScrollBar verticalBar = cellsScrollPane.getVerticalScrollBar();
                if (verticalBar != null) {
                    verticalBar.setValue(0);
                }
            });
        }
    }

    private Notebook loadNotebook(Path path) throws Exception {
        return NotebookIO.read(path);
    }

    private void saveNotebook() throws Exception {
        if (currentPath == null) throw new IOException("No file path specified. Use Save As.");
        NotebookIO.write(currentPath, notebook);
        dirty = false;
    }

    // -----------------------------
    // UI Layout Initialization
    // -----------------------------
    private final void initUI() {
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setLayout(new BorderLayout());

        JMenuBar menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("File");
        fileMenu.setForeground(palette.getTextPrimary());
        fileMenu.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));

        JMenuItem newItem = new JMenuItem("New");
        newItem.addActionListener(e -> {
            if (!confirmDiscardIfDirty()) return;
            notebook = new Notebook();
            currentPath = null;
            dirty = false;
            rebuildCellsUI();
        });
        fileMenu.add(newItem);

        JMenuItem saveItem = new JMenuItem("Save");
        saveItem.addActionListener(e -> {
            try {
                syncUIToModel();
                if (currentPath == null) {
                    JFileChooser fc = new JFileChooser();
                    if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                        currentPath = fc.getSelectedFile().toPath();
                    } else return;
                }
                saveNotebook();
                JOptionPane.showMessageDialog(this, "Saved successfully.");
            } catch (Exception ex) {
                ex.printStackTrace();
                JOptionPane.showMessageDialog(this, "Save failed: " + ex.getMessage());
            }
        });
        fileMenu.add(saveItem);

        JMenuItem saveAsItem = new JMenuItem("Save As...");
        saveAsItem.addActionListener(e -> {
            try {
                syncUIToModel();
                JFileChooser fc = new JFileChooser();
                if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                    currentPath = fc.getSelectedFile().toPath();
                    saveNotebook();
                    JOptionPane.showMessageDialog(this, "Saved as " + currentPath);
                }
            } catch (Exception ex) {
                ex.printStackTrace();
                JOptionPane.showMessageDialog(this, "Save As failed: " + ex.getMessage());
            }
        });
        fileMenu.add(saveAsItem);

        fileMenu.addSeparator();
        JMenuItem quitItem = new JMenuItem("Quit");
        quitItem.addActionListener(e -> handleQuit());
        fileMenu.add(quitItem);

        menuBar.add(fileMenu);
        setJMenuBar(menuBar);

        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        JButton addCodeBtn = new JButton("Add Code");
        addCodeBtn.addActionListener(e -> addNewCell("code"));
        toolbar.add(addCodeBtn);

        JButton addTextBtn = new JButton("Add Text");
        addTextBtn.addActionListener(e -> addNewCell("markdown"));
        toolbar.add(addTextBtn);
        toolbar.addSeparator();

        // Running the notebook is now a first-class action: the kernel keeps its
        // state, so running the cells in order actually means something.
        JButton runAllBtn = new JButton("Run All");
        runAllBtn.setToolTipText("Run every code cell, in order");
        runAllBtn.addActionListener(e -> runAllCells(0, notebook.cells.size()));
        toolbar.add(runAllBtn);

        JButton stopBtn = new JButton("Stop");
        stopBtn.setToolTipText("Interrupt the running cell");
        stopBtn.addActionListener(e -> executor().interrupt());
        toolbar.add(stopBtn);

        JButton restartBtn = new JButton("Restart");
        restartBtn.setToolTipText("Restart the interpreter and forget every variable");
        restartBtn.addActionListener(e -> restartKernel(false));
        toolbar.add(restartBtn);

        JButton restartRunBtn = new JButton("Restart & Run All");
        restartRunBtn.addActionListener(e -> restartKernel(true));
        toolbar.add(restartRunBtn);

        toolbar.addSeparator();
        JToggleButton varsBtn = new JToggleButton("Variables");
        varsBtn.setToolTipText("Show the kernel's live namespace");
        varsBtn.addActionListener(e -> {
            setVariablePanelVisible(varsBtn.isSelected());
            if (varsBtn.isSelected()) {
                executor().requestVariables();
            }
        });
        toolbar.add(varsBtn);
        toolbar.add(Box.createHorizontalGlue());

        kernelStatusLabel = new JLabel("idle");
        kernelStatusLabel.setForeground(palette.getTextSecondary());
        kernelStatusLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));

        kernelSwitcher = new JComboBox<>(new String[] {"Disabled", "Local Python"});
        kernelSwitcher.setSelectedIndex(kernelMode == KernelMode.LOCAL_PYTHON ? 1 : 0);
        kernelSwitcher.setPreferredSize(new Dimension(120, 24));
        kernelSwitcher.addActionListener(e -> {
            if (kernelSwitcher.getSelectedIndex() == 1) {
                setKernelMode(KernelMode.CONNECTING);
                offEdt(() -> {
                    try {
                        executor().ensureStarted();
                    } catch (IOException ex) {
                        SwingUtilities.invokeLater(() -> setKernelMode(KernelMode.DISABLED));
                        AppLogger.error("JupyLab kernel: " + ex.getMessage());
                    }
                });
            } else {
                offEdt(() -> executor().shutdown());
                setKernelMode(KernelMode.DISABLED);
            }
        });

        kernelIndicator = new JLabel(" ");
        kernelIndicator.setOpaque(true);
        kernelIndicator.setBorder(BorderFactory.createLineBorder(palette.getBackgroundSurface()));
        kernelIndicator.setPreferredSize(new Dimension(18, 18));
        kernelIndicator.setToolTipText("Kernel status");

        JPanel kernelPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        kernelPanel.setOpaque(false);
        kernelPanel.add(kernelStatusLabel);
        kernelPanel.add(kernelSwitcher);
        kernelPanel.add(kernelIndicator);

        toolbar.add(kernelPanel);
        add(toolbar, BorderLayout.NORTH);

        cellsContainer = new JPanel() {
            private static final long serialVersionUID = 1L;
            @Override
            public void scrollRectToVisible(Rectangle aRect) {
                // Disable intrusive internal requests from moving the view window unexpectedly on sub-component focus
            }
        };
        cellsContainer.setLayout(new BoxLayout(cellsContainer, BoxLayout.Y_AXIS));
        cellsContainer.setBorder(new EmptyBorder(10, 1, 10, 1));
        cellsContainer.setAlignmentX(Component.LEFT_ALIGNMENT);

        cellsScrollPane = new JScrollPane(cellsContainer,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        bodySplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, cellsScrollPane,
                                   createVariablePanel());
        bodySplit.setResizeWeight(1.0);
        bodySplit.setBorder(null);
        bodySplit.setDividerSize(4);
        setVariablePanelVisible(false);
        add(bodySplit, BorderLayout.CENTER);

        rebuildCellsUI();

        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int w = Math.max(900, Math.min(1200, screen.width - 200));
        int h = Math.max(600, Math.min(900, screen.height - 200));
        setSize(w, h);
        setLocationRelativeTo(null);

        addComponentListener(new ComponentAdapter() {
            private int lastWidth = 0;

            @Override
            public void componentResized(ComponentEvent e) {
                SwingUtilities.invokeLater(() -> {
                    int w = cellsScrollPane.getViewport().getWidth();
                    if (w <= 0) return;

                    if (w != lastWidth) {
                        lastWidth = w;
                        for (Component comp : cellsContainer.getComponents()) {
                            if (comp instanceof CellPanel cp) {
                                cp.updateViewportWidth(w);
                            }
                        }
                        cellsContainer.revalidate();
                        cellsContainer.repaint();
                    }
                });
            }
        });
        updateKernelIndicator();
    }

    // -----------------------------
    // Cell Management Engine
    // -----------------------------
    private void rebuildCellsUI() {
        if (cellsContainer == null) return;
        cellsContainer.removeAll();
        int idx = 0;
        CellPanel previousPanel = null;

        for (Cell c : notebook.cells) {
            CellPanel cp = new CellPanel(c, idx++);
            cp.setAlignmentX(Component.LEFT_ALIGNMENT);

            int viewportWidth = cellsScrollPane.getViewport().getWidth();
            if (viewportWidth <= 0) viewportWidth = getWidth();
            cp.updateViewportWidth(viewportWidth);
            cp.putClientProperty("cellIndex", cp.index);

            DraggableCellSupport.makeDraggable(cp, cellsContainer, notebook.cells, () -> {
                dirty = true;
                rebuildCellsUI();
            });

            if (previousPanel != null) {
                if ("markdown".equals(previousPanel.cell.cell_type) && "markdown".equals(c.cell_type)) {
                    cellsContainer.add(Box.createVerticalStrut(4));
                } else {
                    cellsContainer.add(Box.createVerticalStrut(10));
                }
            }

            cellsContainer.add(cp);
            previousPanel = cp;
        }
        cellsContainer.revalidate();
        cellsContainer.repaint();
    }

    private void addNewCell(String type) {
        Cell c = new Cell();
        c.cell_type = type;
        c.source = Arrays.asList("");
        notebook.cells.add(c);
        dirty = true;
        rebuildCellsUI();
        SwingUtilities.invokeLater(() -> scrollToCell(notebook.cells.size() - 1));
    }

    private void insertCellAt(int pos, String type) {
        Cell c = new Cell();
        c.cell_type = type;
        c.source = Arrays.asList("");
        notebook.cells.add(pos, c);
        dirty = true;
        rebuildCellsUI();
        SwingUtilities.invokeLater(() -> scrollToCell(pos));
    }

    private void removeCellAt(int pos) {
        if (pos >= 0 && pos < notebook.cells.size()) {
            notebook.cells.remove(pos);
            dirty = true;
            rebuildCellsUI();
        }
    }

    private void syncUIToModel() {
        List<Cell> newCells = new ArrayList<>();
        for (Component comp : cellsContainer.getComponents()) {
            if (comp instanceof CellPanel cp) {
                newCells.add(cp.toCell());
            }
        }
        notebook.cells = newCells;
        dirty = true;
    }

    private void scrollToCell(int cellIndex) {
        if (cellIndex < 0) return;
        int cellCount = 0;
        Component[] comps = cellsContainer.getComponents();
        for (Component comp : comps) {
            if (comp instanceof CellPanel) {
                if (cellCount == cellIndex) {
                    Rectangle r = comp.getBounds();
                    cellsContainer.scrollRectToVisible(r);
                    CellPanel cp = (CellPanel) comp;
                    if (cp.codeEditor != null) cp.codeEditor.requestFocusInWindow();
                    else if (cp.markdownEditor != null) cp.markdownEditor.requestFocusInWindow();
                    return;
                }
                cellCount++;
            }
        }
    }

    private boolean confirmDiscardIfDirty() {
        if (!dirty) return true;
        int res = JOptionPane.showConfirmDialog(this,
                "You have unsaved changes. Discard changes?",
                "Unsaved Changes",
                JOptionPane.YES_NO_OPTION);
        return res == JOptionPane.YES_OPTION;
    }

    private void handleQuit() {
        if (!dirty) {
            dispose(); // Replace System.exit(0);
            return;
        }
        int res = JOptionPane.showOptionDialog(this,
                "Save changes before quitting?",
                "Save Changes?",
                JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                new String[] {"Yes", "No", "Cancel"},
                "Yes");
        if (res == JOptionPane.CLOSED_OPTION || res == 2) {
            return;
        }
        if (res == 1) {
            dispose(); // Replace System.exit(0);
            return;
        }
        try {
            syncUIToModel();
            if (currentPath == null) {
                JFileChooser fc = new JFileChooser();
                if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                    currentPath = fc.getSelectedFile().toPath();
                } else {
                    return;
                }
            }
            saveNotebook();
            dispose(); // Replace System.exit(0);
        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(this, "Save failed: " + ex.getMessage());
        }
    }

    // -----------------------------
    // Cell Component Wrapper Class
    // -----------------------------
    class CellPanel extends JPanel {
        private static final long serialVersionUID = 1L;
        transient Cell cell;
        int index;
        JTextPane codeEditor;
        JTextArea markdownEditor;
        JPanel bodyPanel;
        JPanel outputsPanel;
        JPanel headerPanel;
        JLabel countLbl;
        JPanel codeContainer;
        JButton runOverlayBtn;

        private boolean isEditing = false;
        private int currentViewportWidth = 800;

        private final int SIDEBAR_WIDTH = 45;
        private final transient Border normalBorder = BorderFactory.createLineBorder(palette.getJupyLabXedNBorder(), 1);
        private final transient Border activeBorder = BorderFactory.createMatteBorder(0, 3, 0, 0, palette.getJupyLabXedActive());
        private boolean isCurrentlyHovered = false;
        private boolean isRunning = false;
        private long lastElapsedMs = -1;

        private JEditorPane previewPaneReference;

        CellPanel(Cell cell, int index) {
            this.cell = cell;
            this.index = index;

            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(false);

            if (cellsScrollPane != null && cellsScrollPane.getViewport().getWidth() > 0) {
                this.currentViewportWidth = cellsScrollPane.getViewport().getWidth();
            }

            headerPanel = (JPanel) createHeader();
            headerPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
            headerPanel.setOpaque(false);

            bodyPanel = new JPanel(new BorderLayout());
            bodyPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
            bodyPanel.setOpaque(false);
            bodyPanel.add(createBody(), BorderLayout.CENTER);

            codeContainer = new JPanel();
            codeContainer.setLayout(new BoxLayout(codeContainer, BoxLayout.Y_AXIS));
            codeContainer.setOpaque(true);
            codeContainer.setBackground(palette.getAccent());
            codeContainer.add(headerPanel);
            codeContainer.add(bodyPanel);

            if ("code".equals(cell.cell_type) && !cell.outputs.isEmpty()) {
                outputsPanel = (JPanel) createOutputsPanel();
                outputsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
            }

            if ("code".equals(cell.cell_type)) {
                String countStr = cell.execution_count == null ? "[]" : "[" + cell.execution_count + "]";
                countLbl = new JLabel(countStr);
                countLbl.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
                countLbl.setForeground(palette.getJupyCellCounter());
                countLbl.setHorizontalAlignment(SwingConstants.RIGHT);
                countLbl.setBorder(BorderFactory.createEmptyBorder(0, 2, 2, 2));

                runOverlayBtn = new JButton("▶");
                runOverlayBtn.setFont(FontLoader.getGlobalFont(Font.PLAIN, 14));
                runOverlayBtn.setForeground(palette.getJupyLabXedLbutton());
                runOverlayBtn.setContentAreaFilled(false);
                runOverlayBtn.setBorderPainted(false);
                runOverlayBtn.setFocusPainted(false);
                runOverlayBtn.setOpaque(false);
                runOverlayBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                runOverlayBtn.setVisible(false);
                runOverlayBtn.setMargin(new Insets(0, 0, 0, 0));
                runOverlayBtn.setBorder(BorderFactory.createEmptyBorder(2, 2, 0, 2));
                runOverlayBtn.addActionListener(e -> runPythonAsync());
            }

            rebuildCellStructure();
            attachHoverToHeaderAndCode();

            if ("markdown".equals(cell.cell_type)) {
                codeContainer.setBorder(null);
                codeContainer.setOpaque(false);
                headerPanel.setVisible(false);

                SwingUtilities.invokeLater(() -> {
                    this.isEditing = true;
                    switchToPreviewMode();
                });
            } else {
                codeContainer.setBorder(normalBorder);
                headerPanel.setVisible(false);
                setupFocusIndicator();
            }
        }

        // Explicit control for revealing editing toolbar actions upon inside click or cell focus
        private void setHeaderVisible(boolean visible) {
            if (headerPanel != null) {
                headerPanel.setVisible(visible);
                revalidate();
                repaint();
            }
        }

        public void setCellHoverActive(boolean active) {
            if (this.isCurrentlyHovered == active) return;
            this.isCurrentlyHovered = active;

            if (active) {
                if ("code".equals(cell.cell_type)) {
                    if (codeContainer != null) codeContainer.setBorder(activeBorder);
                    // Only show the execution triangle icon outside the cell on hover
                    if (runOverlayBtn != null) runOverlayBtn.setVisible(true);
                }
            } else {
                boolean insideEditWindow = "markdown".equals(cell.cell_type) && isEditing;
                if (!insideEditWindow) {
                    if (codeContainer != null) {
                        codeContainer.setBorder("code".equals(cell.cell_type) ? normalBorder : null);
                    }
                    // Hide the execution triangle icon when mouse leaves the cell area
                    if (runOverlayBtn != null) runOverlayBtn.setVisible(false);
                }
            }
            revalidate();
            repaint();
        }

        /**
         * Repaints this cell's outputs only. Rebuilding the whole notebook on every
         * chunk of streamed text would make a printing loop unusable.
         */
        void refreshOutputs() {
            if (!"code".equals(cell.cell_type)) {
                return;
            }
            outputsPanel = cell.outputs.isEmpty() ? null : (JPanel) createOutputsPanel();
            if (outputsPanel != null) {
                outputsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
            }
            updateCounterLabel();
            rebuildCellStructure();
            revalidate();
            repaint();
            if (cellsScrollPane != null) {
                cellsScrollPane.revalidate();
                cellsScrollPane.repaint();
            }
        }

        /** The counter shows [*] while the cell holds the kernel, as Jupyter does. */
        void setRunning(boolean running) {
            this.isRunning = running;
            if (running) {
                this.lastElapsedMs = -1;
            }
            updateCounterLabel();
        }

        void setElapsed(long millis) {
            this.lastElapsedMs = millis;
            updateCounterLabel();
        }

        private void updateCounterLabel() {
            if (countLbl == null) {
                return;
            }
            countLbl.setText(isRunning ? "[*]"
                : cell.execution_count == null ? "[ ]" : "[" + cell.execution_count + "]");
            countLbl.setForeground(isRunning ? palette.getJupyLabXedActive()
                                             : palette.getJupyCellCounter());
            countLbl.setToolTipText(lastElapsedMs < 0 ? null
                : "Last run: " + formatElapsed(lastElapsedMs));
        }

        private void rebuildCellStructure() {
            removeAll();
            if ("code".equals(cell.cell_type)) {
                JPanel codeRowWrapper = new JPanel(new BorderLayout());
                codeRowWrapper.setOpaque(false);
                codeRowWrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
                codeRowWrapper.add(codeContainer, BorderLayout.CENTER);

                JPanel leftSidebar = new JPanel(new BorderLayout());
                leftSidebar.setOpaque(false);
                leftSidebar.setPreferredSize(new Dimension(SIDEBAR_WIDTH, 0));
                leftSidebar.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
                if (runOverlayBtn != null) leftSidebar.add(runOverlayBtn, BorderLayout.NORTH);
                if (countLbl != null) leftSidebar.add(countLbl, BorderLayout.SOUTH);

                codeRowWrapper.add(leftSidebar, BorderLayout.WEST);
                add(codeRowWrapper);
                if (outputsPanel != null) {
                    JPanel outputRowWrapper = new JPanel(new BorderLayout());
                    outputRowWrapper.setOpaque(false);
                    outputRowWrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
                    outputRowWrapper.add(outputsPanel, BorderLayout.CENTER);

                    JPanel outputSpacer = new JPanel();
                    outputSpacer.setOpaque(false);
                    outputSpacer.setPreferredSize(new Dimension(SIDEBAR_WIDTH, 0));
                    outputSpacer.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
                    outputRowWrapper.add(outputSpacer, BorderLayout.WEST);

                    add(outputRowWrapper);
                }
            } else {
                add(codeContainer);
            }
        }

        private void attachHoverToHeaderAndCode() {
        }

        private void switchToEditMode() {
            if (!"markdown".equals(cell.cell_type) || isEditing) return;
            isEditing = true;
            codeContainer.setOpaque(true);
            codeContainer.setBorder(activeBorder);
            
            // Display the toolbar header when cell entering active edit mode
            setHeaderVisible(true);

            if (bodyPanel.getComponent(0) instanceof JPanel cardPanel) {
                CardLayout cl = (CardLayout) cardPanel.getLayout();
                cl.show(cardPanel, "EDITOR");
                if (markdownEditor != null) {
                    markdownEditor.requestFocusInWindow();
                }
            }
            revalidate();
            repaint();
        }

        private void switchToPreviewMode() {
            if (!"markdown".equals(cell.cell_type)) return;
            isEditing = false;
            codeContainer.setOpaque(false);
            codeContainer.setBorder(null);
            
            // Hide the editing toolbar header when rendering text preview mode
            setHeaderVisible(false);

            if (bodyPanel.getComponent(0) instanceof JPanel cardPanel) {
                CardLayout cl = (CardLayout) cardPanel.getLayout();
                if (markdownEditor != null && previewPaneReference != null) {
                    previewPaneReference.setText(JupyLabMarkdown.toHtml(markdownEditor.getText()));
                    previewPaneReference.setCaretPosition(0);
                }
                cl.show(cardPanel, "PREVIEW");
            }

            bodyPanel.revalidate();
            bodyPanel.repaint();
            if (cellsContainer != null) {
                cellsContainer.revalidate();
                cellsContainer.repaint();
            }
        }

        public void updateViewportWidth(int width) {
            if (width > 0) {
                this.currentViewportWidth = width;
            }
        }

        @Override
        public Dimension getPreferredSize() {
            // Calculate a safe width bounding constraint
            int targetWidth = (currentViewportWidth > 100) ? currentViewportWidth - 20 : 800;
            
            // Cumulate the combined height of all internal active components
            int totalHeight = 0;
            if (headerPanel != null && headerPanel.isVisible()) {
                totalHeight += headerPanel.getPreferredSize().height;
            }
            if (bodyPanel != null) {
                int bodyHeight = bodyPanel.getPreferredSize().height;
                
                // Enforce a minimum typing height bounds for short cells when focused or active
                if ("markdown".equals(cell.cell_type) && isEditing) {
                    bodyHeight = Math.max(bodyHeight, 65);
                } else if ("code".equals(cell.cell_type)) {
                    bodyHeight = Math.max(bodyHeight, 50);
                }
                totalHeight += bodyHeight;
            }
            if (outputsPanel != null && outputsPanel.isVisible()) {
                totalHeight += outputsPanel.getPreferredSize().height;
            }
            
            // Add a small safety padding margin for layout aesthetics
            totalHeight += 6;
            return new Dimension(targetWidth, totalHeight);
        }

        @Override
        public Dimension getMaximumSize() {
            // Let the layout manager expand infinitely horizontally, but lock the height to preferred requirements
            Dimension pref = getPreferredSize();
            return new Dimension(Integer.MAX_VALUE, pref.height);
        }

        @Override
        public Dimension getMinimumSize() {
            // Re-use dynamic layout dimensions to keep components accessible and scroll-stable
            Dimension pref = getPreferredSize();
            int minWidth = (currentViewportWidth > 100) ? currentViewportWidth - 20 : 200;
            return new Dimension(minWidth, pref.height);
        }

        private void setupFocusIndicator() {
            FocusListener highlightListener = new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent e) { 
                    codeContainer.setBorder(activeBorder);
                    // Reveal modification toolbar only when cell gains active focus
                    setHeaderVisible(true);
                }
                @Override
                public void focusLost(FocusEvent e) {
                    if (!isCurrentlyHovered) {
                        codeContainer.setBorder(normalBorder);
                    }
                    // Hide modification toolbar when cell loses active focus
                    setHeaderVisible(false);
                }
            };
            if (codeEditor != null) codeEditor.addFocusListener(highlightListener);
        }

        private JComponent createHeader() {
            JPanel p = new JPanel(new BorderLayout());
            String typeName;
            
            // Use clean, standardized neutral background tones for a unified layout look
            if (cell.cell_type == null || "code".equals(cell.cell_type)) {
                typeName = "CODE";
                p.setBackground(palette.getJupyUniLayoutCode());
            } else if ("markdown".equals(cell.cell_type)) {
                typeName = "TEXT";
                p.setBackground(palette.getJupyUniLayoutMark());
            } else {
                typeName = cell.cell_type.toUpperCase();
                p.setBackground(palette.getJupyUnilayoutDefo());
            }

            // Matte bottom separator to separate the toolbar from text spaces cleanly
            p.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, palette.getJupyMatteSep()));

            JLabel type = new JLabel(typeName);
            type.setFont(FontLoader.getGlobalFont(Font.BOLD, 10));
            type.setForeground(palette.getTextSecondary());

            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 4));
            left.setOpaque(false);
            left.add(type);

            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 2));
            right.setOpaque(false);

            // Universal consumer framework utility to polish native Swing buttons into flat web-like aesthetics
            java.util.function.Consumer<JButton> applyFlatAesthetic = (btn) -> {
                btn.setContentAreaFilled(false);
                btn.setBorderPainted(false);
                btn.setFocusPainted(false);
                btn.setOpaque(false);
                btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                btn.setMargin(new Insets(4, 6, 4, 6));
                
                btn.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseEntered(MouseEvent e) {
                        btn.setContentAreaFilled(true);
                        btn.setBackground(palette.getJupyDarkTranslus()); // 6% dark translucent opacity highlight veil
                    }
                    @Override
                    public void mouseExited(MouseEvent e) {
                        btn.setContentAreaFilled(false);
                    }
                });
            };

            // 1. Execute/Preview Action
            JButton execBtn = new JButton(com.sphere.utils.IconManager.getIcon("execution.png"));
            applyFlatAesthetic.accept(execBtn);
            if ("code".equals(cell.cell_type)) {
                execBtn.setToolTipText("Execute code");
                execBtn.addActionListener(e -> runPythonAsync());
            } else {
                execBtn.setToolTipText("Render Preview");
                execBtn.addActionListener(e -> switchToPreviewMode());
            }
            right.add(execBtn);

            // 2. Edit Configuration Action
            JButton editBtn = new JButton(com.sphere.utils.IconManager.getIcon("edit_cell.png"));
            applyFlatAesthetic.accept(editBtn);
            editBtn.setToolTipText("Edit Cell");
            editBtn.addActionListener(e -> switchToEditMode());
            right.add(editBtn);

            // 3. Structural Insertion Next Action
            JButton addNextBtn = new JButton(com.sphere.utils.IconManager.getIcon("add_down.png"));
            applyFlatAesthetic.accept(addNextBtn);
            addNextBtn.setToolTipText("Add next cell");
            addNextBtn.addActionListener(e -> {
                int pos = findMyIndex();
                insertCellAt(pos + 1, "code");
            });
            right.add(addNextBtn);

            // 4. Structural Insertion Previous Action
            JButton addPrevBtn = new JButton(com.sphere.utils.IconManager.getIcon("add_up.png"));
            applyFlatAesthetic.accept(addPrevBtn);
            addPrevBtn.setToolTipText("Add previous cell");
            addPrevBtn.addActionListener(e -> {
                int pos = findMyIndex();
                insertCellAt(Math.max(0, pos), "code");
            });
            right.add(addPrevBtn);

            // 5. Context Conversion Action
            JButton modifyBtn = new JButton(com.sphere.utils.IconManager.getIcon("switch_text.png"));
            applyFlatAesthetic.accept(modifyBtn);
            modifyBtn.setToolTipText("Switch cell type");
            modifyBtn.addActionListener(e -> {
                String[] opts = {"code", "markdown", "raw"};
                String cur = cell.cell_type == null ? "code" : cell.cell_type;
                String sel = (String) JOptionPane.showInputDialog(this, "Select cell type:", "Modify Cell", JOptionPane.PLAIN_MESSAGE, null, opts, cur);
                if (sel != null) {
                    cell.cell_type = sel;
                    dirty = true;
                    rebuildCellsUI();
                }
            });
            right.add(modifyBtn);

            // 6. Destruction Action
            JButton removeBtn = new JButton(com.sphere.utils.IconManager.getIcon("trash.png"));
            applyFlatAesthetic.accept(removeBtn);
            removeBtn.setToolTipText("Remove cell");
            removeBtn.addActionListener(e -> {
                int pos = findMyIndex();
                removeCellAt(pos);
            });
            right.add(removeBtn);

            p.add(left, BorderLayout.WEST);
            p.add(right, BorderLayout.EAST);
            return p;
        }

        private int findMyIndex() {
            Component[] comps = cellsContainer.getComponents();
            int cellCount = 0;
            for (Component comp : comps) {
                if (comp instanceof CellPanel) {
                    if (comp == this) return cellCount;
                    cellCount++;
                }
            }
            return -1;
        }

        private JComponent createBody() {
            String src = normalize(cell.source);

            if ("markdown".equals(cell.cell_type)) {
                JPanel cardPanel = new JPanel(new CardLayout()) {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public Dimension getPreferredSize() {
                        for (Component comp : getComponents()) {
                            if (comp.isVisible()) {
                                return comp.getPreferredSize();
                            }
                        }
                        return new Dimension(0, 0);
                    }
                    @Override
                    public Dimension getMaximumSize() {
                        for (Component comp : getComponents()) {
                            if (comp.isVisible()) {
                                return new Dimension(Integer.MAX_VALUE, comp.getPreferredSize().height);
                            }
                        }
                        return new Dimension(Integer.MAX_VALUE, 0);
                    }
                };
                cardPanel.setOpaque(false);
                CardLayout cl = (CardLayout) cardPanel.getLayout();
                
                markdownEditor = new JTextArea(src);
                markdownEditor.setLineWrap(true);
                markdownEditor.setWrapStyleWord(true);
                markdownEditor.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
                
                // Allow the caret to track and move normally during typing
                if (markdownEditor.getCaret() instanceof javax.swing.text.DefaultCaret caret) {
                    caret.setUpdatePolicy(javax.swing.text.DefaultCaret.ALWAYS_UPDATE);
                }

                // Reference array wrapper to cache original text snapshot when editing initiates
                final String[] initialTextBeforeEdit = new String[]{ src };

                markdownEditor.addFocusListener(new FocusAdapter() {
                    @Override
                    public void focusGained(FocusEvent e) {
                        // Capture the base content state before any key modifications happen
                        initialTextBeforeEdit[0] = markdownEditor.getText();
                    }

                    @Override
                    public void focusLost(FocusEvent e) {
                        Component opposite = e.getOppositeComponent();
                        if (opposite == null) return;
                        Component cellAncestor = SwingUtilities.getAncestorOfClass(CellPanel.class, opposite);
                        if (cellAncestor != null && cellAncestor != CellPanel.this) {
                            if (markdownEditor != null) {
                                previewPaneReference.setText(JupyLabMarkdown.toHtml(markdownEditor.getText()));
                            }
                            switchToPreviewMode();
                            if (cellsContainer != null) {
                                cellsContainer.revalidate();
                                cellsContainer.repaint();
                            }
                        }
                    }
                });

                // Key layout bindings including ESC cancellation and smart auto-closures
                markdownEditor.addKeyListener(new KeyAdapter() {
                    @Override
                    public void keyPressed(KeyEvent e) {
                        // Revert any ongoing textual alterations and release editor layout when ESC is invoked
                        if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                            markdownEditor.setText(initialTextBeforeEdit[0]);
                            cell.source = initialTextBeforeEdit[0];
                            switchToPreviewMode();
                            if (cellsContainer != null) {
                                cellsContainer.requestFocusInWindow();
                                cellsContainer.revalidate();
                                cellsContainer.repaint();
                            }
                            e.consume();
                        }
                    }

                    @Override
                    public void keyTyped(KeyEvent e) {
                        char ch = e.getKeyChar();
                        int caretPos = markdownEditor.getCaretPosition();
                        String text = markdownEditor.getText();

                        // 1. Docstrings handling for triple quotes (""" or ''')
                        if (ch == '"' || ch == '\'') {
                            if (caretPos >= 2 && text.charAt(caretPos - 1) == ch && text.charAt(caretPos - 2) == ch) {
                                markdownEditor.insert(String.valueOf(ch) + ch + ch, caretPos);
                                markdownEditor.setCaretPosition(caretPos + 1);
                                return;
                            }
                        }

                        // 2. Auto-closing brackets/quotes pairs matching logic
                        char closingChar = 0;
                        switch (ch) {
                            case '(' -> closingChar = ')';
                            case '[' -> closingChar = ']';
                            case '{' -> closingChar = '}';
                            case '"' -> closingChar = '"';
                            case '\'' -> closingChar = '\'';
                        }

                        if (closingChar != 0) {
                            if ((ch == '"' || ch == '\'') && caretPos < text.length() && text.charAt(caretPos) == ch) {
                                markdownEditor.setCaretPosition(caretPos + 1);
                                e.consume();
                                return;
                            }
                            markdownEditor.insert(String.valueOf(closingChar), caretPos);
                            markdownEditor.setCaretPosition(caretPos);
                            return;
                        }

                        if (ch == ')' || ch == ']' || ch == '}') {
                            if (caretPos < text.length() && text.charAt(caretPos) == ch) {
                                markdownEditor.setCaretPosition(caretPos + 1);
                                e.consume();
                            }
                        }
                    }
                });

                // Listen for text changes to update the model and automatically resize the container
                markdownEditor.getDocument().addDocumentListener(SimpleDocumentListener.of(() -> {
                    cell.source = markdownEditor.getText();
                    dirty = true;
                    if (cellsContainer != null) {
                        cellsContainer.revalidate();
                        cellsContainer.repaint();
                    }
                }));
                
                JScrollPane editorScrollPane = new JScrollPane(markdownEditor);
                editorScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
                editorScrollPane.setPreferredSize(new Dimension(0, 150));
                editorScrollPane.setMaximumSize(new Dimension(Integer.MAX_VALUE, 150));
                editorScrollPane.setBorder(null);
                editorScrollPane.setViewportBorder(null);
                
                // Route viewport mouse wheel streaming upwards seamlessly to the top master scroll component
                editorScrollPane.addMouseWheelListener(e -> {
                    JScrollPane mainScrollPane = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, cellsContainer);
                    if (mainScrollPane != null) {
                        mainScrollPane.dispatchEvent(SwingUtilities.convertMouseEvent(editorScrollPane, e, mainScrollPane));
                    }
                });

                previewPaneReference = new JEditorPane();
                previewPaneReference.setContentType("text/html");
                previewPaneReference.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
                previewPaneReference.setEditable(false);
                previewPaneReference.setOpaque(false);
                previewPaneReference.setBorder(null);
                previewPaneReference.putClientProperty("html.disableCSS", Boolean.TRUE);
                previewPaneReference.setMargin(new Insets(0, 0, 0, 0));
                previewPaneReference.setText(JupyLabMarkdown.toHtml(src));
                
                JScrollPane previewScrollPane = new JScrollPane(previewPaneReference);
                previewScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
                previewScrollPane.setBorder(null);
                previewScrollPane.setViewportBorder(null);
                
                // Route mouse wheel tracking from preview pane structures up to parent sheets
                previewScrollPane.addMouseWheelListener(e -> {
                    JScrollPane mainScrollPane = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, cellsContainer);
                    if (mainScrollPane != null) {
                        mainScrollPane.dispatchEvent(SwingUtilities.convertMouseEvent(previewScrollPane, e, mainScrollPane));
                    }
                });

                cardPanel.add(previewScrollPane, "PREVIEW");
                cardPanel.add(editorScrollPane, "EDITOR");
                cl.show(cardPanel, "PREVIEW");

                previewPaneReference.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mousePressed(MouseEvent e) {
                        if (!SwingUtilities.isLeftMouseButton(e)) return;
                        
                        JScrollPane mainScrollPane = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, cellsContainer);
                        final int scrollValue = (mainScrollPane != null) ? mainScrollPane.getVerticalScrollBar().getValue() : -1;

                        switchToEditMode();
                        
                        if (cellsContainer != null) {
                            cellsContainer.revalidate();
                            cellsContainer.repaint();
                        }
                        
                        markdownEditor.requestFocusInWindow();
                        
                        int pos = markdownEditor.viewToModel2D(SwingUtilities.convertPoint(previewPaneReference, e.getPoint(), markdownEditor));
                        if (pos >= 0) {
                            markdownEditor.setCaretPosition(pos);
                        }

                        if (mainScrollPane != null && scrollValue != -1) {
                            SwingUtilities.invokeLater(() -> mainScrollPane.getVerticalScrollBar().setValue(scrollValue));
                        }
                    }
                });

                this.putClientProperty("triggerPreview", (Runnable) () -> {
                    if (markdownEditor != null) {
                        previewPaneReference.setText(JupyLabMarkdown.toHtml(markdownEditor.getText()));
                    }
                    switchToPreviewMode();
                });
                this.putClientProperty("triggerEdit", (Runnable) () -> switchToEditMode());

                return cardPanel;
            }

            if ("code".equals(cell.cell_type)) {
                codeEditor = new JTextPane();
                codeEditor.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
                codeEditor.setText(src);

                // Allow the caret to track and move normally during typing
                if (codeEditor.getCaret() instanceof javax.swing.text.DefaultCaret caret) {
                    caret.setUpdatePolicy(javax.swing.text.DefaultCaret.ALWAYS_UPDATE);
                }

                // Reference array wrapper to cache original text snapshot when editing initiates
                final String[] initialTextBeforeEdit = new String[]{ src };

                codeEditor.addFocusListener(new FocusAdapter() {
                    @Override
                    public void focusGained(FocusEvent e) {
                        // Capture snapshot baseline text state when code workspace becomes active
                        initialTextBeforeEdit[0] = codeEditor.getText();
                    }
                });

                // Handle keys including ESC cancellation, auto-closing pairs, and docstrings
                codeEditor.addKeyListener(new KeyAdapter() {
                    @Override
                    public void keyPressed(KeyEvent e) {
                        // Revert active code string changes and escape operational focus when ESC clicks
                        if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                            codeEditor.setText(initialTextBeforeEdit[0]);
                            cell.source = initialTextBeforeEdit[0];
                            if (cellsContainer != null) {
                                cellsContainer.requestFocusInWindow();
                            }
                            e.consume();
                            return;
                        }
                        // The shortcuts a notebook is expected to answer to.
                        if (e.getKeyCode() == KeyEvent.VK_ENTER
                                && (e.isShiftDown() || e.isControlDown())) {
                            runPythonAsyncForCell(CellPanel.this);
                            e.consume();
                            return;
                        }
                        if (e.getKeyCode() == KeyEvent.VK_SPACE && e.isControlDown()) {
                            requestCompletion(codeEditor);
                            e.consume();
                        }
                    }

                    @Override
                    public void keyTyped(KeyEvent e) {
                        char ch = e.getKeyChar();
                        int caretPos = codeEditor.getCaretPosition();
                        String text = codeEditor.getText();

                        // 1. Docstrings handling for triple quotes (""" or ''')
                        if (ch == '"' || ch == '\'') {
                            if (caretPos >= 2 && text.charAt(caretPos - 1) == ch && text.charAt(caretPos - 2) == ch) {
                                try {
                                    codeEditor.getDocument().insertString(caretPos, String.valueOf(ch) + ch + ch, null);
                                    codeEditor.setCaretPosition(caretPos + 1);
                                } catch (javax.swing.text.BadLocationException ex) {
                                    // Drop insertion errors silently
                                }
                                return;
                            }
                        }

                        // 2. Auto-closing brackets/quotes pairs matching logic
                        char closingChar = 0;
                        switch (ch) {
                            case '(' -> closingChar = ')';
                            case '[' -> closingChar = ']';
                            case '{' -> closingChar = '}';
                            case '"' -> closingChar = '"';
                            case '\'' -> closingChar = '\'';
                        }

                        if (closingChar != 0) {
                            if ((ch == '"' || ch == '\'') && caretPos < text.length() && text.charAt(caretPos) == ch) {
                                codeEditor.setCaretPosition(caretPos + 1);
                                e.consume();
                                return;
                            }
                            try {
                                codeEditor.getDocument().insertString(caretPos, String.valueOf(closingChar), null);
                                codeEditor.setCaretPosition(caretPos);
                            } catch (javax.swing.text.BadLocationException ex) {
                                // Drop insertion errors silently
                            }
                            return;
                        }

                        if (ch == ')' || ch == ']' || ch == '}') {
                            if (caretPos < text.length() && text.charAt(caretPos) == ch) {
                                codeEditor.setCaretPosition(caretPos + 1);
                                e.consume();
                            }
                        }
                    }
                });

                // Save typed text into the cell model and immediately trigger a container layout refresh
                codeEditor.getDocument().addDocumentListener(SimpleDocumentListener.of(() -> {
                    cell.source = codeEditor.getText();
                    dirty = true;
                    if (cellsContainer != null) {
                        cellsContainer.revalidate();
                        cellsContainer.repaint();
                    }
                }));

                PythonSyntaxHighlighter.attachTo(codeEditor, palette);

                JScrollPane sp = new JScrollPane(codeEditor);
                sp.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
                
                // Route viewport mouse wheel streaming upwards seamlessly to the top master scroll component
                sp.addMouseWheelListener(e -> {
                    JScrollPane mainScrollPane = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, cellsContainer);
                    if (mainScrollPane != null) {
                        mainScrollPane.dispatchEvent(SwingUtilities.convertMouseEvent(sp, e, mainScrollPane));
                    }
                });
                
                return sp;
            }

            JTextArea raw = new JTextArea(src);
            raw.setLineWrap(true);
            raw.setWrapStyleWord(true);
            
            // Allow the caret to track and move normally during typing
            if (raw.getCaret() instanceof javax.swing.text.DefaultCaret caret) {
                caret.setUpdatePolicy(javax.swing.text.DefaultCaret.ALWAYS_UPDATE);
            }

            // Reference array wrapper to cache original text snapshot when editing initiates
            final String[] initialTextBeforeEdit = new String[]{ src };

            raw.addFocusListener(new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent e) {
                    // Capture original snapshot fallback point when raw field receives operational focus
                    initialTextBeforeEdit[0] = raw.getText();
                }
            });

            // Handle keys including ESC cancellation, auto-closing pairs, and docstrings
            raw.addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    // Revert textual updates made inside the cell layout block if ESC is fired
                    if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                        raw.setText(initialTextBeforeEdit[0]);
                        cell.source = initialTextBeforeEdit[0];
                        if (cellsContainer != null) {
                            cellsContainer.requestFocusInWindow();
                        }
                        e.consume();
                    }
                }

                @Override
                public void keyTyped(KeyEvent e) {
                    char ch = e.getKeyChar();
                    int caretPos = raw.getCaretPosition();
                    String text = raw.getText();

                    // 1. Docstrings handling for triple quotes (""" or ''')
                    if (ch == '"' || ch == '\'') {
                        if (caretPos >= 2 && text.charAt(caretPos - 1) == ch && text.charAt(caretPos - 2) == ch) {
                            raw.insert(String.valueOf(ch) + ch + ch, caretPos);
                            raw.setCaretPosition(caretPos + 1);
                            return;
                        }
                    }

                    // 2. Auto-closing pairs checking
                    char closingChar = 0;
                    switch (ch) {
                        case '(' -> closingChar = ')';
                        case '[' -> closingChar = ']';
                        case '{' -> closingChar = '}';
                        case '"' -> closingChar = '"';
                        case '\'' -> closingChar = '\'';
                    }

                    if (closingChar != 0) {
                        if ((ch == '"' || ch == '\'') && caretPos < text.length() && text.charAt(caretPos) == ch) {
                            raw.setCaretPosition(caretPos + 1);
                            e.consume();
                            return;
                        }
                        raw.insert(String.valueOf(closingChar), caretPos);
                        raw.setCaretPosition(caretPos);
                        return;
                    }

                    if (ch == ')' || ch == ']' || ch == '}') {
                        if (caretPos < text.length() && text.charAt(caretPos) == ch) {
                            raw.setCaretPosition(caretPos + 1);
                            e.consume();
                        }
                    }
                }
            });

            // Listen for raw text changes and handle layout recalculation
            raw.getDocument().addDocumentListener(SimpleDocumentListener.of(() -> {
                cell.source = raw.getText();
                dirty = true;
                if (cellsContainer != null) {
                    cellsContainer.revalidate();
                    cellsContainer.repaint();
                }
            }));
            
            JScrollPane sp = new JScrollPane(raw);
            sp.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            
            // Route viewport mouse wheel streaming upwards seamlessly to the top master scroll component
            sp.addMouseWheelListener(e -> {
                JScrollPane mainScrollPane = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, cellsContainer);
                if (mainScrollPane != null) {
                    mainScrollPane.dispatchEvent(SwingUtilities.convertMouseEvent(sp, e, mainScrollPane));
                }
            });
            
            return sp;
        }

        private JComponent createOutputsPanel() {
            if (cell.outputs == null || cell.outputs.isEmpty()) {
                return new JPanel();
            }

            JPanel wrapper = new JPanel();
            wrapper.setLayout(new BoxLayout(wrapper, BoxLayout.Y_AXIS));
            wrapper.setBackground(palette.getTerminalBackground());
            wrapper.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, palette.getTerminalForeground()),
                BorderFactory.createEmptyBorder(12, 16, 12, 16)
            ));

            // Use the dedicated monospace terminal font directly from your FontLoader subsystem
            Font terminalFont = FontLoader.getTerminalFont(Font.PLAIN, 12);

            for (Output out : cell.outputs) {
                if ("stream".equals(out.output_type)) {
                    String rawText = out.text instanceof String ? (String) out.text : "";
                    if (out.text instanceof List) {
                        StringBuilder sb = new StringBuilder();
                        for (Object line : (List<?>) out.text) {
                            sb.append(line);
                        }
                        rawText = sb.toString();
                    }

                    JTextArea ta = new JTextArea(normalize(rawText));
                    ta.setEditable(false);
                    ta.setFont(terminalFont);

                    if ("stderr".equals(out.name)) {
                        ta.setForeground(palette.getError());
                    } else {
                        ta.setForeground(palette.getTerminalForeground());
                    }
                    ta.setBackground(palette.getTerminalBackground());
                    ta.setLineWrap(true);
                    ta.setWrapStyleWord(true);
                    ta.setBorder(null);
                    wrapper.add(ta);
                } 
                else if ("execute_result".equals(out.output_type) || "display_data".equals(out.output_type)) {
                    if (out.data != null) {
                        // 1. Image formats handler (PNG / JPEG)
                        if (out.data.containsKey("image/png") || out.data.containsKey("image/jpeg") || out.data.containsKey("image/jpg")) {
                            String key = out.data.containsKey("image/png") ? "image/png" : 
                                         (out.data.containsKey("image/jpeg") ? "image/jpeg" : "image/jpg");
                            Object rawData = out.data.get(key);
                            String base64Str = "";

                            if (rawData instanceof String) {
                                base64Str = ((String) rawData).trim().replaceAll("\\s", "");
                            } else if (rawData instanceof List) {
                                StringBuilder sb = new StringBuilder();
                                for (Object line : (List<?>) rawData) {
                                    sb.append(line.toString().trim());
                                }
                                base64Str = sb.toString().replaceAll("\\s", "");
                            }

                            if (!base64Str.isEmpty()) {
                                try {
                                    byte[] bytes = Base64.getDecoder().decode(base64Str);
                                    ImageIcon icon = new ImageIcon(bytes);
                                    Image rawImage = icon.getImage();

                                    JPanel imgPanel = new JPanel() {
                                        private static final long serialVersionUID = 1L;
                                        @Override
                                        protected void paintComponent(Graphics g) {
                                            super.paintComponent(g);
                                            Graphics2D g2 = (Graphics2D) g.create();
                                            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                                            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                                            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                                            
                                            g2.drawImage(rawImage, 0, 0, getWidth(), getHeight(), this);
                                            g2.dispose();
                                        }
                                    };

                                    imgPanel.setPreferredSize(new Dimension(icon.getIconWidth(), icon.getIconHeight()));
                                    imgPanel.setMinimumSize(new Dimension(icon.getIconWidth(), icon.getIconHeight()));
                                    imgPanel.setMaximumSize(new Dimension(icon.getIconWidth(), icon.getIconHeight()));

                                    imgPanel.setOpaque(true);
                                    imgPanel.setBackground(palette.getJupyPyImageBg());
                                    imgPanel.setBorder(BorderFactory.createLineBorder(palette.getJupyLabXedNBorder(), 1));

                                    JPanel imgContainer = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 6));
                                    imgContainer.setOpaque(false);
                                    imgContainer.add(imgPanel);
                                    wrapper.add(imgContainer);

                                } catch (Exception ex) {
                                    AppLogger.error("Failed to render output image: " + ex.getMessage());
                                }
                            }
                        }
                        // 2. Rich text first: a DataFrame carries a table, a sympy
                        // expression carries LaTeX, and both used to fall back to
                        // the plain repr because only text and images were read.
                        else if (out.data.containsKey("text/html")
                                 || out.data.containsKey("text/latex")) {
                            boolean html = out.data.containsKey("text/html");
                            String body = joinMime(out.data.get(html ? "text/html" : "text/latex"));
                            JEditorPane pane = new JEditorPane("text/html",
                                html ? styledHtml(stripStyleBlocks(body))
                                     : styledHtml("<pre>" + escapeHtml(body) + "</pre>"));
                            pane.setEditable(false);
                            pane.setOpaque(true);
                            pane.setBackground(palette.getTerminalBackground());
                            pane.setBorder(null);
                            pane.setAlignmentX(Component.LEFT_ALIGNMENT);
                            wrapper.add(pane);
                        }
                        else if (out.data.containsKey("text/plain")) {
                            Object val = out.data.get("text/plain");
                            String txt = "";
                            if (val instanceof String) txt = (String) val;
                            else if (val instanceof List) {
                                StringBuilder sb = new StringBuilder();
                                for (Object o : (List<?>) val) sb.append(o);
                                txt = sb.toString();
                            }
                            JTextArea ta = new JTextArea(normalize(txt));
                            ta.setEditable(false);
                            ta.setFont(terminalFont);
                            ta.setForeground(palette.getTerminalForeground());
                            ta.setBackground(palette.getTerminalBackground());
                            ta.setLineWrap(true);
                            ta.setWrapStyleWord(true);
                            ta.setBorder(null);
                            wrapper.add(ta);
                        }
                    }
                } 
                else if ("error".equals(out.output_type)) {
                    StringBuilder sb = new StringBuilder();
                    if (out.ename != null) sb.append(out.ename).append(": ").append(out.evalue).append("\n");
                    if (out.traceback != null) {
                        for (String line : out.traceback) {
                            sb.append(line).append("\n");
                        }
                    }
                    JTextArea ta = new JTextArea(sb.toString());
                    ta.setEditable(false);
                    ta.setFont(terminalFont);
                    ta.setForeground(palette.getError());
                    ta.setBackground(palette.getTerminalBackground());
                    ta.setBorder(null);
                    wrapper.add(ta);
                }
            }
            return wrapper;
        }

        private String normalize(Object o) {
            if (o == null) return "";
            if (o instanceof String) return (String) o;
            if (o instanceof List<?>) {
                StringBuilder sb = new StringBuilder();
                for (Object x : (List<?>) o) sb.append(x.toString());
                return sb.toString();
            }
            return o.toString();
        }

        private String joinMime(Object value) {
            return NotebookIO.joinLines(value);
        }

        /** Wraps a rich fragment so it reads on the terminal background. */
        private String styledHtml(String body) {
            Color fg = palette.getTerminalForeground();
            Color bg = palette.getTerminalBackground();
            return "<html><body style=\"margin:0;color:rgb(" + fg.getRed() + "," + fg.getGreen()
                 + "," + fg.getBlue() + ");background:rgb(" + bg.getRed() + "," + bg.getGreen()
                 + "," + bg.getBlue() + ");font-family:sans-serif;font-size:11px\">"
                 + body + "</body></html>";
        }

        /**
         * Swing renders HTML 3.2 and shows a style element as plain text, which is
         * how a pandas table arrived with its own stylesheet printed above it.
         */
        private String stripStyleBlocks(String html) {
            return html.replaceAll("(?is)<style[^>]*>.*?</style>", "")
                       .replaceAll("(?is)<script[^>]*>.*?</script>", "");
        }

        private String escapeHtml(String text) {
            return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }

        public Cell toCell() {
            return this.cell;
        }

        private void runPythonAsync() {
            JupylabXeditor.this.runPythonAsyncForCell(this);
        }
    }

    // -----------------------------
    // Variable inspector, completion, kernel controls
    // -----------------------------

    private JComponent createVariablePanel() {
        variableModel = new javax.swing.table.DefaultTableModel(
                new Object[] {"Name", "Type", "Value"}, 0) {
            private static final long serialVersionUID = 1L;
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        variableTable = new JTable(variableModel);
        variableTable.setFillsViewportHeight(true);
        variableTable.setRowHeight(20);
        variableTable.setShowGrid(false);
        variableTable.setFont(FontLoader.getTerminalFont(Font.PLAIN, 12));
        variableTable.setBackground(palette.getBackgroundSurface());
        variableTable.setForeground(palette.getTextPrimary());
        variableTable.getTableHeader().setReorderingAllowed(false);

        JScrollPane scroll = new JScrollPane(variableTable);
        scroll.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, palette.getBorder()));
        scroll.setPreferredSize(new Dimension(300, 100));
        return scroll;
    }

    private void setVariablePanelVisible(boolean visible) {
        if (bodySplit == null) {
            return;
        }
        Component right = bodySplit.getRightComponent();
        if (right != null) {
            right.setVisible(visible);
        }
        bodySplit.setDividerSize(visible ? 4 : 0);
        bodySplit.setDividerLocation(visible ? Math.max(200, getWidth() - 320) : Integer.MAX_VALUE);
        bodySplit.revalidate();
    }

    private void updateVariablePanel(List<Map<String, Object>> items) {
        if (variableModel == null) {
            return;
        }
        variableModel.setRowCount(0);
        for (Map<String, Object> item : items) {
            variableModel.addRow(new Object[] {
                item.get("name"), item.get("type"), item.get("info")});
        }
    }

    /**
     * Asks the interpreter what the object really holds. A static word list cannot
     * know the columns of a DataFrame that was loaded a second ago.
     */
    private void requestCompletion(javax.swing.text.JTextComponent editor) {
        if (editor == null) {
            return;
        }
        completionTarget = editor;
        executor().requestCompletion(editor.getText(), editor.getCaretPosition());
    }

    private void showCompletion(int start, List<String> matches) {
        if (completionPopup != null) {
            completionPopup.setVisible(false);
        }
        javax.swing.text.JTextComponent editor = completionTarget;
        if (editor == null || matches == null || matches.isEmpty()) {
            return;
        }
        completionAnchor = start;
        DefaultListModel<String> model = new DefaultListModel<>();
        for (String match : matches) {
            model.addElement(match);
        }
        JList<String> list = new JList<>(model);
        list.setSelectedIndex(0);
        list.setFont(FontLoader.getTerminalFont(Font.PLAIN, 12));
        list.setBackground(palette.getPopupBackground());
        list.setForeground(palette.getTextPrimary());
        list.setVisibleRowCount(Math.min(10, model.size()));

        Runnable accept = () -> {
            String chosen = list.getSelectedValue();
            if (chosen != null) {
                applyCompletion(editor, chosen);
            }
            completionPopup.setVisible(false);
        };
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) { accept.run(); }
            }
        });
        list.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) { accept.run(); e.consume(); }
                else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) { completionPopup.setVisible(false); }
            }
        });

        completionPopup = new JPopupMenu();
        completionPopup.setBorder(BorderFactory.createLineBorder(palette.getPopupBorder()));
        completionPopup.add(new JScrollPane(list));
        try {
            Rectangle caret = editor.modelToView2D(editor.getCaretPosition()).getBounds();
            completionPopup.show(editor, caret.x, caret.y + caret.height);
            list.requestFocusInWindow();
        } catch (Exception ignored) {
            // caret briefly out of the document
        }
    }

    private void applyCompletion(javax.swing.text.JTextComponent editor, String chosen) {
        try {
            int caret = editor.getCaretPosition();
            int from = Math.max(0, Math.min(completionAnchor, caret));
            editor.getDocument().remove(from, caret - from);
            String text = chosen.endsWith("(") ? chosen : chosen;
            editor.getDocument().insertString(from, text, null);
        } catch (javax.swing.text.BadLocationException ignored) {
            // the buffer changed under the popup; the next request will be correct
        }
    }

    private void restartKernel(boolean thenRunAll) {
        offEdt(() -> {
            try {
                executor().restart();
            } catch (IOException ex) {
                AppLogger.error("JupyLab kernel restart failed: " + ex.getMessage());
                return;
            }
            SwingUtilities.invokeLater(() -> {
                for (Cell cell : notebook.cells) {
                    cell.execution_count = null;
                }
                rebuildCellsUI();
                if (thenRunAll) {
                    runAllCells(0, notebook.cells.size());
                }
            });
        });
    }

    private static String formatElapsed(long millis) {
        if (millis < 1000) {
            return millis + " ms";
        }
        return String.format(java.util.Locale.ROOT, "%.2f s", millis / 1000.0);
    }

    // -----------------------------
    // Kernel-backed execution
    // -----------------------------

    /** Created on first use so opening a notebook does not launch an interpreter. */
    private NotebookExecutor executor() {
        if (executor == null) {
            executor = new NotebookExecutor(settings, new EditorView());
        }
        return executor;
    }

    /** Kernel work never runs on the event thread: starting a process is I/O. */
    private void offEdt(Runnable task) {
        Thread worker = new Thread(task, "jupylab-control");
        worker.setDaemon(true);
        worker.start();
    }

    public void runPythonAsyncForCell(CellPanel cp) {
        if (cp == null || cp.codeEditor == null) {
            return;
        }
        cp.cell.source = cp.codeEditor.getText();
        dirty = true;
        offEdt(() -> executor().run(cp.cell));
    }

    /** Runs every code cell in order, each waiting for the one before it. */
    private void runAllCells(int from, int to) {
        syncUIToModel();
        List<Cell> slice = new ArrayList<>();
        for (int i = Math.max(0, from); i < Math.min(to, notebook.cells.size()); i++) {
            slice.add(notebook.cells.get(i));
        }
        dirty = true;
        offEdt(() -> executor().runAll(slice));
    }

    private CellPanel panelFor(Cell cell) {
        if (cellsContainer == null) {
            return null;
        }
        for (Component c : cellsContainer.getComponents()) {
            if (c instanceof CellPanel cp && cp.cell == cell) {
                return cp;
            }
        }
        return null;
    }

    /** Everything the kernel makes the editor do, in one place. */
    private final class EditorView implements NotebookExecutor.View {

        @Override
        public void cellStarted(Cell cell) {
            CellPanel cp = panelFor(cell);
            if (cp != null) {
                cp.setRunning(true);
                cp.refreshOutputs();
            }
        }

        @Override
        public void cellOutputsChanged(Cell cell) {
            CellPanel cp = panelFor(cell);
            if (cp != null) {
                cp.refreshOutputs();
            }
        }

        @Override
        public void cellFinished(Cell cell, Integer count, long millis) {
            CellPanel cp = panelFor(cell);
            if (cp != null) {
                cp.setRunning(false);
                cp.setElapsed(millis);
                cp.refreshOutputs();
            }
        }

        @Override
        public void kernelState(String label, boolean alive) {
            setKernelMode(alive ? KernelMode.LOCAL_PYTHON
                                : KernelMode.DISABLED);
            if (kernelIndicator != null) {
                kernelIndicator.setToolTipText("Kernel: " + label);
            }
            if (kernelStatusLabel != null) {
                kernelStatusLabel.setText(label);
            }
        }

        @Override
        public void inputRequested(Cell cell, String prompt) {
            String answer = JOptionPane.showInputDialog(JupylabXeditor.this,
                prompt.isEmpty() ? "Input:" : prompt, "Cell input",
                JOptionPane.QUESTION_MESSAGE);
            executor().answerInput(answer == null ? "" : answer);
        }

        @Override
        public void completionReady(int start, List<String> matches) {
            showCompletion(start, matches);
        }

        @Override
        public void variablesReady(List<Map<String, Object>> items) {
            updateVariablePanel(items);
        }
    }

    private void setKernelMode(KernelMode mode) {
        this.kernelMode = mode;
        updateKernelIndicator();
    }

    private void updateKernelIndicator() {
        if (kernelIndicator == null) return;

        switch (kernelMode) {
            case DISABLED -> kernelIndicator.setBackground(palette.getLogPromptPrefix());
            case CONNECTING -> kernelIndicator.setBackground(palette.getLogWarnPrefix());
            case LOCAL_PYTHON -> kernelIndicator.setBackground(palette.getLogSuccessPrefix());
        }
    }

    @FunctionalInterface
    interface SimpleDocumentListener extends DocumentListener {
        void update();

        @Override default void insertUpdate(javax.swing.event.DocumentEvent e) { update(); }
        @Override default void removeUpdate(javax.swing.event.DocumentEvent e) { update(); }
        @Override default void changedUpdate(javax.swing.event.DocumentEvent e) { update(); }

        static SimpleDocumentListener of(Runnable r) {
            return () -> r.run();
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                Path p = (args.length > 0) ? Paths.get(args[0]) : null;
                JupylabXeditor editor = new JupylabXeditor(p);
                editor.setVisible(true);
            } catch (Exception e) {
                e.printStackTrace();
                JOptionPane.showMessageDialog(null, "Failed to start: " + e.getMessage());
            }
        });
    }
}
