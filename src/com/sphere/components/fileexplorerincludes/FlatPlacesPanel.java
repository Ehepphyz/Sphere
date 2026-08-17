package com.sphere.components.fileexplorerincludes;

import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.utils.IconManager;
import com.sphere.utils.OSValidator;

import javax.swing.*;
import java.awt.*;
import java.io.File;

/**
 * Modern sidebar containing partition management roots and compact horizontal shortcut action rows.
 */
public class FlatPlacesPanel extends JPanel {

    private final FlatFileChooserPanel chooser;
    private final JComboBox<File> driveSelector;
    private final JPanel iconRowPanel;

    public FlatPlacesPanel(FlatFileChooserPanel chooser) {
        this.chooser = chooser;

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(10, 8, 10, 8));

        // ---------------------------------------------------------
        // PARTITION / HDD SYSTEM DRIVES MANAGEMENT CONTROL
        // ---------------------------------------------------------
        JLabel storageLabel = new JLabel("Storage");
        storageLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        storageLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        add(storageLabel);

        File[] roots = File.listRoots();
        driveSelector = new JComboBox<>(roots);
        driveSelector.setMaximumSize(new Dimension(140, 26));
        driveSelector.setAlignmentX(Component.CENTER_ALIGNMENT);
        
        driveSelector.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof File) {
                    setText(((File) value).getPath());
                }
                return this;
            }
        });

        // RESTRUCTURING 1: Auto-synchronize selector with active user.dir workspace root path
        String currentWorkingPath = System.getProperty("user.dir");
        if (currentWorkingPath != null) {
            File workingFile = new File(currentWorkingPath);
            for (File root : roots) {
                if (workingFile.getAbsolutePath().startsWith(root.getAbsolutePath())) {
                    driveSelector.setSelectedItem(root);
                    break;
                }
            }
        }

        driveSelector.addActionListener(e -> {
            File selectedRoot = (File) driveSelector.getSelectedItem();
            if (selectedRoot != null && selectedRoot.exists()) {
                chooser.setDirectory(selectedRoot);
            }
        });
        add(driveSelector);

        add(Box.createVerticalStrut(15));

        JLabel placesLabel = new JLabel("Places");
        placesLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        placesLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        add(placesLabel);

        // ---------------------------------------------------------
        // HORIZONTAL ICON ROW CONTAINER PANEL
        // ---------------------------------------------------------
        iconRowPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
        iconRowPanel.setMaximumSize(new Dimension(140, 40));
        iconRowPanel.setOpaque(false);

        String userHomePath = System.getProperty("user.home");
        File homeDir = new File(userHomePath);
        File desktopDir = new File(userHomePath, "Desktop");
        File documentsDir = new File(userHomePath, "Documents");

        if (OSValidator.isLinux() && !desktopDir.exists()) {
            File localDesktop = new File(homeDir, "desktop");
            if (localDesktop.exists()) desktopDir = localDesktop;
        }

        addPlace(IconManager.getIcon("home.png"), homeDir, "Home");
        if (desktopDir.exists()) {
            addPlace(IconManager.getIcon("desktop.png"), desktopDir, "Desktop");
        }
        if (documentsDir.exists()) {
            addPlace(IconManager.getIcon("documents.png"), documentsDir, "Documents");
        }

        add(iconRowPanel);
        add(Box.createVerticalGlue());

        updateUI();
    }

    private void addPlace(Icon icon, File dir, String tooltipText) {
        JButton button = new JButton(icon);
        button.setToolTipText(tooltipText);
        button.setFocusPainted(false);
        
        button.setMinimumSize(new Dimension(34, 34));
        button.setMaximumSize(new Dimension(34, 34));
        button.setPreferredSize(new Dimension(34, 34));
        button.setMargin(new Insets(2, 2, 2, 2));
        
        button.setBorder(BorderFactory.createEmptyBorder());
        button.setContentAreaFilled(false);
        button.setOpaque(false);

        button.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                ThemePalette palette = ThemeManager.getCurrentPalette();
                if (palette != null) {
                    button.setContentAreaFilled(true);
                    button.setBackground(palette.getButtonPressed());
                }
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                button.setContentAreaFilled(false);
            }
        });

        button.addActionListener(e -> {
            if (dir != null && dir.exists() && dir.isDirectory()) {
                chooser.setDirectory(dir);
            }
        });
        
        iconRowPanel.add(button);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        ThemePalette palette = ThemeManager.getCurrentPalette();
        if (palette != null) {
            setBackground(palette.getBackgroundSurface());
            
            for (int i = 0; i < getComponentCount(); i++) {
                Component comp = getComponent(i);
                if (comp instanceof JLabel) {
                    comp.setFont(com.sphere.fonts.FontLoader.getGlobalFont(Font.BOLD, 11));
                    comp.setForeground(palette.getTextPrimary());
                }
            }
        }
    }
}
