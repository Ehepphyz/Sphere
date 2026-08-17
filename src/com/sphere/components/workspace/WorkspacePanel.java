package com.sphere.components.workspace;

import com.sphere.components.WorkspaceManager;
import com.sphere.components.workspace.WorkspaceListener;
import com.sphere.utils.IconManager;
import com.sphere.utils.AppLogger;
import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.fonts.FontLoader;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.List;

/**
 * Modern navigation explorer panel used to manage project directory collections, 
 * run deep structural synchronizations, and configure dynamic environment details.
 */
public class WorkspacePanel extends JPanel implements WorkspaceListener {

    private final WorkspaceManager workspaceManager;
    private static final String WORKSPACE_DIR = "WorkSpace";
    private final ThemePalette palette = ThemeManager.getCurrentPalette();
    
    // UI Elements
    private JList<File> projectList;
    private DefaultListModel<File> listModel;
    
    // Management Controls
    private JButton btnLaunch;
    private JButton btnCreate;
    private JButton btnModify;
    private JButton btnRename;
    private JButton btnImport;
    private JButton btnRemove;

    public WorkspacePanel(WorkspaceManager workspaceManager) {
        this.workspaceManager = workspaceManager;
        
        setLayout(new BorderLayout(0, 12));
        setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        setOpaque(false);
        
        initComponents();
        
        // Connect system listeners and refresh status
        this.workspaceManager.addWorkspaceListener(this);
        this.workspaceManager.scanWorkspace();
    }

    public interface ProjectCreationCallback {
        void onProjectCreated(String projectName);
    }

    private void initComponents() {
        // --- 1. CENTER SECTION: Project Explorer List Window ---
        listModel = new DefaultListModel<>();
        projectList = new JList<>(listModel);
        projectList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        projectList.setFixedCellHeight(28); // Give list entries professional vertical breathing room
        projectList.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        projectList.setBackground(palette.getBackgroundSurface());
        projectList.setForeground(palette.getTextPrimary());

        // Smooth custom cellular item renderer with explicit selection feedback matching the theme
        projectList.setCellRenderer(new DefaultListCellRenderer() {
            private boolean isSelectedCell;

            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, 
                                                          boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                this.isSelectedCell = isSelected;
                
                if (value instanceof File file) {
                    setText(file.getName());
                    setIcon(IconManager.getIcon("wpfolder.png"));
                    setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
                    setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
                    
                    // Turn off standard full-width opacity so the default background doesn't stretch
                    setOpaque(false); 

                    if (isSelected) {
                        setForeground(palette.getTextWhite());
                    } else {
                        setForeground(palette.getTextPrimary());
                    }
                }
                return this;
            }

            @Override
            protected void paintComponent(Graphics g) {
                if (isSelectedCell) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(palette.getAccent());
                    
                    // 1. Locate where the text starts
                    Icon icon = getIcon();
                    int leftBorderPadding = getInsets().left; 
                    int iconWidth = (icon != null) ? icon.getIconWidth() : 0;
                    int iconGap = (icon != null && getText() != null) ? getIconTextGap() : 0;
                    
                    int textStartX = leftBorderPadding + iconWidth + iconGap;

                    // 2. Calculate the exact dimensions of the text string
                    FontMetrics fm = g2.getFontMetrics(getFont());
                    int textWidth = (getText() != null) ? fm.stringWidth(getText()) : 0;
                    int highlightHeight = getHeight() - 2;
                    
                    // Comfortable padding for a slightly rounded tag layout
                    int highlightX = textStartX - 5;
                    int highlightWidth = textWidth + 10;

                    // 3. Draw a modern badge with a subtle 6px corner radius
                    g2.fillRoundRect(highlightX, 1, highlightWidth, highlightHeight, 6, 6);
                    g2.dispose();
                }
                super.paintComponent(g);
            }
        });

        // Track user list selections
        projectList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                workspaceManager.selectProject(projectList.getSelectedValue());
            }
        });

        // --- Contextual Right-Click Overlay System ---
        JPopupMenu contextMenu = new JPopupMenu();
        JMenuItem menuAnalysis = new JMenuItem("Synchronize Project");
        menuAnalysis.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        menuAnalysis.setIcon(IconManager.getIcon("wfoldersync.png"));
        contextMenu.add(menuAnalysis);

        menuAnalysis.addActionListener(e -> {
            File selectedProject = projectList.getSelectedValue();
            if (selectedProject != null) {
                WkProjectAnalysis analyzer = new WkProjectAnalysis();
                analyzer.analyze(selectedProject);
            }
        });

        // Combined mouse listener for both double-clicks and right-click menus
        projectList.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                processPopupTrigger(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                processPopupTrigger(e);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                // Shortcut triggered: Double-clicking an item fires launch action immediately
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    int index = projectList.locationToIndex(e.getPoint());
                    if (index != -1 && projectList.getCellBounds(index, index).contains(e.getPoint())) {
                        File selected = projectList.getSelectedValue();
                        if (selected != null && btnLaunch.isEnabled()) {
                            AppLogger.info("Shortcut triggered: Launching project " + selected.getName());
                            // Fire active workspace system runtime pipeline launch rules
                            workspaceManager.selectProject(selected);
                        }
                    }
                }
            }

            private void processPopupTrigger(MouseEvent e) {
                if (e.isPopupTrigger() || SwingUtilities.isRightMouseButton(e)) {
                    int index = projectList.locationToIndex(e.getPoint());
                    
                    if (index != -1 && projectList.getCellBounds(index, index).contains(e.getPoint())) {
                        projectList.setSelectedIndex(index);
                        contextMenu.show(projectList, e.getX(), e.getY());
                    } else {
                        projectList.clearSelection();
                        workspaceManager.selectProject(null);
                    }
                }
            }
        });

        JScrollPane scrollPane = new JScrollPane(projectList);
        scrollPane.setBorder(null);
        scrollPane.setViewportBorder(null);
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        add(scrollPane, BorderLayout.CENTER);

        // --- 2. SOUTH SECTION: Refactored Utility Toolbar ---
        JPanel toolbarContainer = new JPanel(new BorderLayout());
        toolbarContainer.setOpaque(false);
        
        JPanel buttonGroupPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttonGroupPanel.setOpaque(false);
        
        // Allocate component icons safely from global resources
        btnLaunch = new JButton(IconManager.getIcon("launch.png"));
        btnCreate = new JButton(IconManager.getIcon("create.png"));
        btnModify = new JButton(IconManager.getIcon("modify.png"));
        btnRename = new JButton(IconManager.getIcon("rename.png"));
        btnImport = new JButton(IconManager.getIcon("import.png"));
        btnRemove = new JButton(IconManager.getIcon("delete.png"));

        // Unified layout alignment settings for flat clean icons
        JButton[] controls = {btnLaunch, btnCreate, btnModify, btnRename, btnImport, btnRemove};
        for (JButton button : controls) {
            button.setFocusPainted(false);
            button.setBackground(palette.getButtonHover());
            button.setForeground(palette.getTextPrimary());
            button.setPreferredSize(new Dimension(34, 30));
            buttonGroupPanel.add(button);
        }

        // Detailed Accessibility Context Summaries
        btnLaunch.setToolTipText("Launch Active Project");
        btnCreate.setToolTipText("Create a New Project");
        btnModify.setToolTipText("Configure Environmental Settings");
        btnRename.setToolTipText("Rename Selected Directory Path");
        btnImport.setToolTipText("Import Files or Folders");
        btnRemove.setToolTipText("Permanently Delete Selected Project");

        // Set contextual initial state
        setSelectionContextEnabled(false);

        // --- Wire Control Event Routing Sequences ---

        btnRemove.addActionListener(e -> {
            File selected = projectList.getSelectedValue();
            if (selected != null) {
                int confirm = JOptionPane.showConfirmDialog(
                    this,
                    "Are you absolutely sure you want to permanently delete '" + selected.getName() + "' from disk?",
                    "Confirm Project Deletion",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
                );
                
                if (confirm == JOptionPane.YES_OPTION) {
                    workspaceManager.deleteProject(selected);
                }
            }
        });

        btnCreate.addActionListener(e -> openProjectCreator());

        btnImport.addActionListener(e -> {
            java.io.File currentDirectory = new java.io.File(System.getProperty("user.dir"));
            
            // Pass 'this' to anchor the modal directly to your current UI frame context window
            com.sphere.components.fileexplorerincludes.FlatFileChooser fileChooser = 
                    new com.sphere.components.fileexplorerincludes.FlatFileChooser(
                        javax.swing.SwingUtilities.getWindowAncestor(this), 
                        currentDirectory
                    );
            
            // Blocks execution and displays the custom workspace layout asset viewport
            fileChooser.setVisible(true);
            
            // Extract the multi-selection targets directly from the dialog payload index
            java.util.List<java.io.File> selectedItemsList = fileChooser.getSelectedFiles();
            
            if (selectedItemsList != null && !selectedItemsList.isEmpty()) {
                // Convert the dynamic list payload back to a standard array for your workspace backend mapping
                java.io.File[] selectedItems = selectedItemsList.toArray(new java.io.File[0]);
                workspaceManager.importAssets(selectedItems);
            }
        });

        btnModify.addActionListener(e -> {
            File selected = projectList.getSelectedValue();
            if (selected != null) {
                Window parentWindow = SwingUtilities.getWindowAncestor(this);
                Frame parentFrame = (parentWindow instanceof Frame) ? (Frame) parentWindow : null;
                
                ProjectSettingsFrame settingsWindow = new ProjectSettingsFrame(parentFrame, selected, this.workspaceManager);
                settingsWindow.setSize(1200, 640);
                settingsWindow.setPreferredSize(new Dimension(1200, 640));
                settingsWindow.setLocationRelativeTo(this);
                settingsWindow.setVisible(true);
            }
        });

        btnRename.addActionListener(e -> handleRename());

        toolbarContainer.add(buttonGroupPanel, BorderLayout.WEST);
        add(toolbarContainer, BorderLayout.SOUTH);
    }

    private void openProjectCreator() {
        ProjectCreatorWindow creator = new ProjectCreatorWindow(projectName -> {
            workspaceManager.scanWorkspace();
            
            File newProject = new File(WORKSPACE_DIR + File.separator + projectName);
            workspaceManager.selectProject(newProject);
            
            AppLogger.info("Project successfully created and targeted: " + projectName);
        });
        
        // Force uniform dimensional metrics directly on instantiation initialization loops
        creator.setSize(1200, 640);
        creator.setPreferredSize(new Dimension(1200, 640));
        creator.setLocationRelativeTo(this);
        creator.setVisible(true);
    }

    private void setSelectionContextEnabled(boolean enabled) {
        btnLaunch.setEnabled(enabled);
        btnModify.setEnabled(enabled);
        btnRename.setEnabled(enabled);
        btnRemove.setEnabled(enabled);
    }

    private void handleRename() {
        File activeProject = projectList.getSelectedValue();
        if (activeProject != null) {

            JTextField field = new JTextField(activeProject.getName());
            field.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
            field.setBackground(palette.getBackgroundTrack());
            field.setForeground(palette.getTextPrimary());
            field.setCaretColor(palette.getAccent());
            field.setSelectionColor(palette.getAccent());
            field.setSelectedTextColor(palette.getTextPrimary());
            field.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(palette.getBorder(), 1, true),
                    BorderFactory.createEmptyBorder(4, 6, 4, 6)));
            field.selectAll();

            int result = JOptionPane.showConfirmDialog(
                this,
                field,
                "Rename Project",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
            );

            if (result == JOptionPane.OK_OPTION) {
                String inputName = field.getText().trim();
                if (!inputName.isEmpty()) {
                    if (!workspaceManager.renameProject(activeProject, inputName)) {
                        JOptionPane.showMessageDialog(
                            this,
                            "The project renaming sequence failed.",
                            "IO Error",
                            JOptionPane.ERROR_MESSAGE
                        );
                    }
                }
            }
        }
    }

    // --- WorkspaceListener Subsystem Synchronizers ---

    @Override
    public void onWorkspaceChanged(List<File> projects) {
        SwingUtilities.invokeLater(() -> {
            File cachedSelection = projectList.getSelectedValue();
            listModel.clear();
            
            for (File project : projects) {
                listModel.addElement(project);
            }
            
            if (cachedSelection != null && projects.contains(cachedSelection)) {
                projectList.setSelectedValue(cachedSelection, true);
            }
        });
    }

    @Override
    public void onProjectSelected(File project) {
        SwingUtilities.invokeLater(() -> setSelectionContextEnabled(project != null));
    }

    @Override
    public void onProjectStructureUpdated(File project) {
        SwingUtilities.invokeLater(() -> {
            File activeSelection = projectList.getSelectedValue();
            if (activeSelection != null && activeSelection.equals(project)) {
                setSelectionContextEnabled(true);
                projectList.repaint();
            }
        });
    }
}
