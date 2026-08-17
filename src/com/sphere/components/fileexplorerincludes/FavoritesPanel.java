package com.sphere.components.fileexplorerincludes;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import com.sphere.theme.ThemeManager;
import com.sphere.fonts.FontLoader;

public class FavoritesPanel extends JPanel {

    private final DefaultListModel<File> model;
    private final JList<File> list;

    public FavoritesPanel() {
        
        setLayout(new BorderLayout());

        model = new DefaultListModel<>();
        list = new JList<>(model);
        list.setCellRenderer((jList, value, index, isSelected, cellHasFocus) -> {
            JLabel label = new JLabel(value.getName());
            if (isSelected) {
                label.setOpaque(true);
                label.setBackground(jList.getSelectionBackground());
                label.setForeground(jList.getSelectionForeground());
            }
            label.setToolTipText(value.getAbsolutePath());
            return label;
        });

        add(new JScrollPane(list), BorderLayout.CENTER);
        refresh();
    }

    public void refresh() {
        model.clear();
        for (File f : FavoritesManager.getFavorites()) {
            model.addElement(f);
        }
    }

    public JList<File> getList() {
        return list;
    }
}

