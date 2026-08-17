package com.sphere.components.fileexplorerincludes;

import com.sphere.components.FileExplorer;
import com.sphere.ui.QuickCodeEditorFrame;
import com.sphere.theme.ThemeManager;
import com.sphere.fonts.FontLoader;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;

/**
 * Workbench sidebar wrapper panel combining the active FileExplorer tree 
 * with a dynamic top-level text search filter interface.
 */
public class FileExplorerPanel extends JPanel {

    private final FileExplorer explorer;
    private final JTextField searchField;
    private final JButton clearButton;

    /**
     * Constructs the sidebar explorer panel wrapping the tree workspace hierarchy.
     * @param editorFrame The persistent workbench frame context used for text file routing.
     */
    public FileExplorerPanel(QuickCodeEditorFrame editorFrame) {
        super(new BorderLayout());

        // Route The Shared Window Frame Instance Down To The Nested Explorer Tree
        explorer = new FileExplorer(editorFrame);
        searchField = new JTextField();
        clearButton = new JButton("Clear");

        JScrollPane scrollPane = new JScrollPane(explorer);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());

        JPanel topBar = new JPanel(new BorderLayout(6, 0));
        topBar.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        topBar.add(new JLabel("Search:"), BorderLayout.WEST);
        topBar.add(searchField, BorderLayout.CENTER);
        topBar.add(clearButton, BorderLayout.EAST);

        add(topBar, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);

        installSearchBehavior();
    }

    /**
     * Configures document interceptors and button handlers to trigger dynamic filtering.
     */
    private void installSearchBehavior() {
        // Live filtering (real-time search)
        searchField.getDocument().addDocumentListener(new DocumentListener() {

            @Override
            public void insertUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applyFilter();
            }

            private void applyFilter() {
                String query = searchField.getText();
                explorer.applyFilter(query);
            }
        });

        clearButton.addActionListener(e -> {
            searchField.setText("");
            explorer.clearFilter();
        });
    }

    public FileExplorer getExplorer() {
        return explorer;
    }
}
