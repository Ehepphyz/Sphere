package com.sphere.components;

import com.sphere.components.terminal.ShellInfo;
import com.sphere.components.terminal.ShellSelector;
import com.sphere.components.terminal.TerminalPanel;
import com.sphere.utils.IconManager;
import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.fonts.FontLoader;

import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.*;
import java.awt.event.*;
import java.util.List;

/* ---------------------------
 * Manages terminal lifecycle, tab organization, and context interactions.
 * Delivered with ultra-thin 1px metrics and fitted text bounds.
 * ---------------------------
 */
public class TerminalManager {

    private final JTabbedPane tabbedPane;
    private final JPanel rootPanel;
    private final ThemePalette palette = ThemeManager.getCurrentPalette();
    /** Counts openings, not tabs: numbering by tab count repeated a name after a close. */
    private int opened;

    public TerminalManager() {
        tabbedPane = new JTabbedPane();
        tabbedPane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabbedPane.setFocusable(false);

        // Global context menu for the tab area
        tabbedPane.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    showGlobalContextMenu(e);
                }
            }
        });

        rootPanel = new JPanel(new BorderLayout());
        rootPanel.add(tabbedPane, BorderLayout.CENTER);
    }

    public JComponent getTabbedPane() {
        return rootPanel;
    }

    public TerminalPanel newTerminal(String shellCommand) {
        return newTerminal(new ShellInfo(shellCommand, shellCommand,
            com.sphere.components.terminal.ShellSelector.interactiveArguments(shellCommand), null));
    }

    public TerminalPanel newTerminal(ShellInfo shell) {
        return newTerminal(shell, new java.io.File(System.getProperty("user.dir")));
    }

    /** Opens a shell in a folder: the project root, or the folder being worked in. */
    public TerminalPanel newTerminal(ShellInfo shell, java.io.File workingDirectory) {
        TerminalPanel panel = new TerminalPanel(shell, workingDirectory);
        // The name says which shell it is, so three tabs are not three "Terminal".
        String title = shell.name + " " + (++opened);

        tabbedPane.addTab(title, panel);
        int index = tabbedPane.indexOfComponent(panel);
        tabbedPane.setTabComponentAt(index, createTabHeader(title, panel));
        tabbedPane.setSelectedComponent(panel);

        // Ensure the terminal view receives keyboard focus immediately
        SwingUtilities.invokeLater(panel::requestFocusInWindow);

        return panel;
    }

    /* ---------------------------
     * Shuts down all active terminal engines to prevent zombie processes.
     * ---------------------------
     */
    public void shutdownAllTerminals() {
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            Component c = tabbedPane.getComponentAt(i);
            if (c instanceof TerminalPanel) {
                ((TerminalPanel) c).disposeTerminal();
            }
        }
    }

    /* ---------------------------
     * GLOBAL CONTEXT MENU
     * ---------------------------
     */
    private void showGlobalContextMenu(MouseEvent e) {
        JPopupMenu menu = createModernPopupMenu();
        
        List<ShellInfo> shells = ShellSelector.detectShells();
        
        if (shells.isEmpty()) {
            JMenuItem emptyItem = createModernMenuItem("No configured shells detected");
            emptyItem.setEnabled(false);
            menu.add(emptyItem);
        } else {
            JMenu newMenu = createModernMenu("New Terminal");
            
            for (ShellInfo shell : shells) {
                JMenuItem item = createModernMenuItem(shell.name, ev -> newTerminal(shell));
                item.setToolTipText(shell.command);
                newMenu.add(item);
            }
            menu.add(newMenu);
        }

        attachPopupMenuWidthResizer(menu);
        menu.show(e.getComponent(), e.getX(), e.getY());
    }

    /* ---------------------------
     * DETACH LOGIC
     * ---------------------------
     */
    private void detachTerminal(TerminalPanel panel) {
        tabbedPane.remove(panel);

        JFrame frame = new JFrame("Detached Terminal");
        frame.setSize(900, 600);
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.add(panel, BorderLayout.CENTER);

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                panel.disposeTerminal();
            }
        });
        frame.setVisible(true);
    }

    /* ---------------------------
     * TAB-SPECIFIC CONTEXT MENU
     * ---------------------------
     */
    private void showTerminalContextMenu(MouseEvent e, TerminalPanel panel) {
        JPopupMenu menu = createModernPopupMenu();

         // Thin 1px separator line matching UI style guidelines
        JSeparator sep = new JSeparator() {
            @Override
            public void paint(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(palette.getPopupBorder());
                g2.drawLine(6, 0, getWidth() - 6, 0);
                g2.dispose();
            }
        };

        menu.add(createModernMenuItem("Detach Terminal", ev -> detachTerminal(panel)));
        menu.add(createModernMenuItem("Close Terminal", ev -> {
            panel.disposeTerminal();
            tabbedPane.remove(panel);
        }));
        menu.add(createModernMenuItem("Rename Terminal", ev -> renameTerminal(panel)));
        menu.add(createModernMenuItem("Duplicate Terminal",
            ev -> newTerminal(panel.getEngine().getShell(), panel.getEngine().currentDirectory())));
        menu.add(sep);
        // Stops the command without taking the shell with it, as Ctrl+C does.
        menu.add(createModernMenuItem("Interrupt Command", ev -> panel.getEngine().interrupt()));
        menu.add(createModernMenuItem("Clear Output", ev -> panel.getView().clear()));
        // Shows the palette on request, instead of at every opening as before.
        menu.add(createModernMenuItem("Test Colours", ev -> panel.getView().printColourTest()));

        attachPopupMenuWidthResizer(menu);
        menu.show(e.getComponent(), e.getX(), e.getY());
    }

    /*--------------------------------------------------------------
     * Helper to build fully custom flat menu items with manually
     * drawn background selections and aligned fonts.
     *--------------------------------------------------------------*/
    private JMenuItem createModernMenuItem(String text) {
        return createModernMenuItem(text, null);
    }

    private JMenuItem createModernMenuItem(String text, java.awt.event.ActionListener action) {
        JMenuItem item = new JMenuItem(text) {
            @Override
            protected void paintComponent(Graphics g) {
                ButtonModel model = getModel();
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                
                ThemePalette palette = ThemeManager.getCurrentPalette();
                
                // Unified hover state selection fill
                if (model.isArmed() || model.isSelected()) {
                    g2.setColor(palette != null ? palette.getButtonPressed() : palette.getPopupHoverFallback());
                    g2.fillRoundRect(4, 2, getWidth() - 8, getHeight() - 4, 6, 6);
                    g2.setColor(palette.getTextWhite());
                } else {
                    g2.setColor(palette != null ? palette.getTextPrimary() : palette.getTextLightGray());
                }
                
                g2.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
                FontMetrics fm = g2.getFontMetrics();
                int textY = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
                
                g2.drawString(getText(), 12, textY);
                g2.dispose();
            }
        };
        
        item.setOpaque(false);
        item.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        if (action != null) {
            item.addActionListener(action);
        }
        return item;
    }

    /*--------------------------------------------------------------
     * Helper to build fully custom nested sub-menus with manually
     * drawn background selections, aligned fonts and a white indicator triangle.
     *--------------------------------------------------------------*/
    private JMenu createModernMenu(String text) {
        JMenu item = new JMenu(text) {
            @Override
            protected void paintComponent(Graphics g) {
                ButtonModel model = getModel();
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                
                ThemePalette palette = ThemeManager.getCurrentPalette();
                
                // Unified hover state selection fill
                if (model.isArmed() || model.isSelected()) {
                    g2.setColor(palette != null ? palette.getButtonPressed() : palette.getPopupHoverFallback());
                    g2.fillRoundRect(4, 2, getWidth() - 8, getHeight() - 4, 6, 6);
                    g2.setColor(palette.getTextWhite());
                } else {
                    g2.setColor(palette != null ? palette.getTextPrimary() : palette.getTextLightGray());
                }
                
                g2.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
                FontMetrics fm = g2.getFontMetrics();
                int textY = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
                g2.drawString(getText(), 12, textY);

                // --- Draw the white sub-menu indicator triangle ---
                if (model.isArmed() || model.isSelected()) {
                    g2.setColor(palette.getTextWhite());
                } else {
                    g2.setColor(palette != null ? palette.getTextPrimary() : palette.getTextLightGray());
                }
                
                int arrowSize = 4;
                int arrowX = getWidth() - 16;
                int arrowY = getHeight() / 2;
                
                int[] xPoints = { arrowX, arrowX, arrowX + arrowSize };
                int[] yPoints = { arrowY - arrowSize, arrowY + arrowSize, arrowY };
                g2.fillPolygon(xPoints, yPoints, 3);
                // --------------------------------------------------

                g2.dispose();
            }
        };
        
        item.setOpaque(false);
        item.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        
        // Custom look for the popup containing child components
        JPopupMenu popup = item.getPopupMenu();
        popup.setOpaque(false);
        popup.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        
        return item;
    }

    /*--------------------------------------------------------------
     * Creates a custom JPopupMenu base component with thin borders.
     *--------------------------------------------------------------*/
    private JPopupMenu createModernPopupMenu() {
        JPopupMenu menu = new JPopupMenu() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                ThemePalette palette = ThemeManager.getCurrentPalette();
                if (palette != null) {
                    g2.setColor(palette.getBackgroundSurface().brighter());
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                    
                    // Razor-thin line-weight bounding frame border (1px)
                    g2.setColor(palette.getPopupBorder());
                    g2.setStroke(new BasicStroke(1.0f));
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                }
                g2.dispose();
            }
        };
        menu.setOpaque(false);
        menu.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        return menu;
    }

    /*--------------------------------------------------------------
     * Dynamic text metric layout sizing adaptation listener
     *--------------------------------------------------------------*/
    private void attachPopupMenuWidthResizer(JPopupMenu menu) {
        menu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                SwingUtilities.invokeLater(() -> {
                    int maxTextWidth = 100;
                    Font font = FontLoader.getGlobalFont(Font.PLAIN, 12);
                    FontMetrics fm = menu.getFontMetrics(font);
                    
                    for (Component comp : menu.getComponents()) {
                        if (comp instanceof JMenuItem && comp.isVisible()) {
                            int textWidth = fm.stringWidth(((JMenuItem) comp).getText());
                            if (textWidth > maxTextWidth) {
                                maxTextWidth = textWidth;
                            }
                        }
                    }

                    // On ajoute un peu plus d'espace à droite (44 au lieu de 32) pour accueillir le triangle sans collision
                    int targetWidth = maxTextWidth + 44;
                    menu.setPreferredSize(new Dimension(targetWidth, menu.getPreferredSize().height));
                    menu.revalidate();
                });
            }

            @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}
            @Override public void popupMenuCanceled(PopupMenuEvent e) {}
        });
    }

    private void renameTerminal(TerminalPanel panel) {
        int index = tabbedPane.indexOfComponent(panel);
        if (index == -1) return;

        String newName = JOptionPane.showInputDialog(rootPanel, "New terminal name:");
        if (newName != null && !newName.isBlank()) {
            tabbedPane.setTitleAt(index, newName);
            tabbedPane.setTabComponentAt(index, createTabHeader(newName, panel));
        }
    }

    /* ---------------------------
     * TAB HEADER UI
     * ---------------------------
     */
    private Component createTabHeader(String title, TerminalPanel panel) {
        JPanel tab = new JPanel(new GridBagLayout());
        tab.setOpaque(false);

        // Constraints for the Label
        GridBagConstraints gbcLabel = new GridBagConstraints();
        gbcLabel.gridx = 0;
        gbcLabel.gridy = 0;
        gbcLabel.weightx = 1.0; // Label takes up available horizontal space
        gbcLabel.anchor = GridBagConstraints.WEST;
        
        JLabel label = new JLabel(title);
        tab.add(label, gbcLabel);

        // Constraints for the Button Container
        GridBagConstraints gbcButtons = new GridBagConstraints();
        gbcButtons.gridx = 1;
        gbcButtons.gridy = 0;
        // THIS IS THE FIX: Left inset forces 20 pixels of space between label and buttons
        gbcButtons.insets = new Insets(0, 20, 0, 0); 
        gbcButtons.anchor = GridBagConstraints.EAST;

        JPanel buttonContainer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        buttonContainer.setOpaque(false);

        JButton detachBtn = new JButton(IconManager.getIcon("tdetach.png"));
        styleTabButton(detachBtn, "Detach Terminal");
        detachBtn.addActionListener(e -> detachTerminal(panel));

        JButton closeBtn = new JButton(IconManager.getIcon("close.png"));
        styleTabButton(closeBtn, "Close Terminal");
        closeBtn.addActionListener(e -> {
            panel.disposeTerminal();
            tabbedPane.remove(panel);
        });

        buttonContainer.add(detachBtn);
        buttonContainer.add(closeBtn);

        tab.add(buttonContainer, gbcButtons);

        // Keep your mouse listener
        tab.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    showTerminalContextMenu(e, panel);
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    tabbedPane.setSelectedComponent(panel);
                }
            }
        });

        return tab;
    }

    private void styleTabButton(JButton btn, String toolTip) {
        btn.setBorderPainted(false);
        btn.setContentAreaFilled(false);
        btn.setFocusable(false);
        btn.setToolTipText(toolTip);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
        btn.setMargin(new Insets(0, 2, 0, 2));
    }
    
}
