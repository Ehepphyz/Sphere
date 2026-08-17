package com.sphere.components;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.List;
import com.sphere.utils.SessionManager;
import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;

public class SessionSelectionDialog extends JDialog {
    
    private final JList<String> sessionList;
    private final DefaultListModel<String> listModel;
    private String selectedSessionName = null;
    private final ThemePalette palette = ThemeManager.getCurrentPalette();

    public SessionSelectionDialog(Frame parent) {
        super(parent, "Select Session to Edit", true);
        setLayout(new BorderLayout(10, 10));

        // 1. Initialize List Model using our smart SessionManager
        listModel = new DefaultListModel<>();
        refreshListModel();

        sessionList = new JList<>(listModel);
        sessionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // Double-click shortcut to edit a session instantly
        sessionList.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                if (evt.getClickCount() == 2 && sessionList.getSelectedValue() != null) {
                    confirmSelection();
                }
            }
        });

        JScrollPane scrollPane = new JScrollPane(sessionList);
        scrollPane.setPreferredSize(new Dimension(450, 300));
        add(scrollPane, BorderLayout.CENTER);

        // 2. Buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton editBtn = new JButton("Edit");
        JButton removeOldBtn = new JButton("Clean Old Sessions");
        JButton cancelBtn = new JButton("Cancel");

        editBtn.addActionListener(e -> confirmSelection());
        removeOldBtn.addActionListener(e -> handleCleanup());
        cancelBtn.addActionListener(e -> dispose());

        buttonPanel.add(removeOldBtn);
        buttonPanel.add(editBtn);
        buttonPanel.add(cancelBtn);
        add(buttonPanel, BorderLayout.SOUTH);

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        pack();
        setLocationRelativeTo(parent);
    }

    private void confirmSelection() {
        if (sessionList.getSelectedValue() != null) {
            selectedSessionName = sessionList.getSelectedValue();
            dispose();
        } else {
            JOptionPane.showMessageDialog(this, "Please select a session from the list.", "No Selection", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void handleCleanup() {
        if (listModel.size() <= 2) {
            JOptionPane.showMessageDialog(this, "No old sessions to remove.", "Information", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(this, 
            "Are you sure you want to delete all sessions except the 2 most recent?", 
            "Confirm Cleanup", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);

        if (confirm == JOptionPane.YES_OPTION) {
            // Let SessionManager handle the physical deletion logic
            SessionManager.purgeOldSessions(2);
            // Simply UI update
            refreshListModel();
        }
    }

    private void refreshListModel() {
        listModel.clear();
        List<Path> availableSessions = SessionManager.getAllSessions();
        for (Path path : availableSessions) {
            listModel.addElement(path.getFileName().toString());
        }
    }

    public String getSelectedSession() {
        return selectedSessionName;
    }
}
