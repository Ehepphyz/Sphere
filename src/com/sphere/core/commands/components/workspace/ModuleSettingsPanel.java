package com.sphere.components.workspace;

import com.sphere.fonts.FontLoader;
import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * One framework's place in a project that already exists.
 *
 * The creation window settles this once; here it is changed afterwards, read
 * from and written back to the project's own .workflow file.
 */
public final class ModuleSettingsPanel extends JPanel {

    private final String module;
    private final Path projectDirectory;

    private final JCheckBox useModule;
    private final JTextField folderField;
    private final JTextField fileField;
    private final JTextArea contentArea;

    /** What the file held when the tab opened, so an untouched file is left alone. */
    private String loadedContent;

    private final ThemePalette palette = ThemeManager.getCurrentPalette();

    public ModuleSettingsPanel(String module, String caption, File projectDirectory,
                               String defaultFolder, String defaultFile,
                               String defaultContent) {
        this.module = module;
        this.projectDirectory = projectDirectory.toPath();

        Map<String, Object> workflow = WorkflowIO.load(this.projectDirectory);
        final boolean used = WorkflowIO.uses(workflow, module);
        final String folder = WorkflowIO.entry(workflow, module, "folder", defaultFolder);
        final String file = WorkflowIO.entry(workflow, module, "file", defaultFile);

        setLayout(new BorderLayout(0, 12));
        setBackground(palette.getBackgroundSurface());
        setBorder(new EmptyBorder(16, 16, 16, 16));

        useModule = new JCheckBox("Use " + module + " in this project");
        useModule.setOpaque(false);
        useModule.setForeground(palette.getTextPrimary());
        useModule.setFont(FontLoader.getGlobalFont(Font.BOLD, 12));
        useModule.setSelected(used);

        folderField = field(folder);
        fileField = field(file);

        contentArea = new JTextArea();
        contentArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        contentArea.setBackground(palette.getBackgroundTrack());
        contentArea.setForeground(palette.getTextPrimary());
        contentArea.setCaretColor(palette.getAccent());
        contentArea.setBorder(new EmptyBorder(4, 6, 4, 6));
        loadedContent = readStarterFile(file, defaultContent);
        contentArea.setText(loadedContent);

        add(buildForm(caption), BorderLayout.NORTH);
        add(buildEditor(caption, defaultContent), BorderLayout.CENTER);
    }

    /** Whether the project is to use this framework. */
    public boolean included() {
        return useModule.isSelected();
    }

    /** Folds this tab's answers into the workflow document. */
    public void apply(Map<String, Object> workflow) {
        WorkflowIO.record(workflow, module, useModule.isSelected(),
                          folderField.getText().strip(), fileField.getText().strip());
    }

    /**
     * Creates the folder and the starter file when the module is in use.
     *
     * A file whose text was not touched is left as it is on disk, so opening
     * the tab and pressing Apply never rewrites someone's work.
     */
    public void writeFiles() throws IOException {
        if (!useModule.isSelected()) {
            return;
        }
        final String folder = folderField.getText().strip();
        if (!folder.isEmpty()) {
            Path target = inside(folder);
            if (target != null) {
                Files.createDirectories(target);
            }
        }

        final String file = fileField.getText().strip();
        if (file.isEmpty()) {
            return;
        }
        Path target = inside(file);
        if (target == null) {
            return;
        }
        final String text = contentArea.getText();
        if (Files.exists(target) && text.equals(loadedContent)) {
            return;
        }
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Files.writeString(target, text, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        loadedContent = text;
    }

    // ---- the layout ---------------------------------------------------------

    private JPanel buildForm(String caption) {
        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(6, 6, 6, 6);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;

        gc.gridx = 0; gc.gridy = 0; gc.gridwidth = 4; gc.weightx = 1.0;
        form.add(useModule, gc);

        gc.gridwidth = 1;
        gc.gridx = 0; gc.gridy = 1; gc.weightx = 0.0;
        form.add(label("Folder:"), gc);
        gc.gridx = 1; gc.weightx = 1.0;
        form.add(folderField, gc);
        gc.gridx = 2; gc.weightx = 0.0;
        form.add(label("Starter file:"), gc);
        gc.gridx = 3; gc.weightx = 1.0;
        form.add(fileField, gc);
        return form;
    }

    private JPanel buildEditor(String caption, String defaults) {
        JLabel note = new JLabel(caption);
        note.setFont(FontLoader.getGlobalFont(Font.PLAIN, 11));
        note.setForeground(palette.getTextSecondary());

        JButton load = new JButton("Load defaults");
        load.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        load.addActionListener(e -> contentArea.setText(defaults));

        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setOpaque(false);
        header.add(note, BorderLayout.CENTER);
        header.add(load, BorderLayout.EAST);

        JScrollPane scroll = new JScrollPane(contentArea);
        scroll.setPreferredSize(new Dimension(400, 220));
        scroll.setBorder(BorderFactory.createLineBorder(palette.getBorder()));

        JPanel editor = new JPanel(new BorderLayout(0, 8));
        editor.setOpaque(false);
        editor.add(header, BorderLayout.NORTH);
        editor.add(scroll, BorderLayout.CENTER);
        return editor;
    }

    private JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setFont(FontLoader.getGlobalFont(Font.BOLD, 12));
        label.setForeground(palette.getTextPrimary());
        return label;
    }

    private JTextField field(String value) {
        JTextField input = new JTextField(value, 24);
        input.setBackground(palette.getBackgroundTrack());
        input.setForeground(palette.getTextPrimary());
        input.setCaretColor(palette.getAccent());
        input.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        input.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(palette.getBorder(), 1, true),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        return input;
    }

    private String readStarterFile(String relative, String fallback) {
        Path target = inside(relative);
        if (target == null || !Files.isRegularFile(target)) {
            return fallback;
        }
        try {
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return fallback;
        }
    }

    /** Resolves a typed path, or null when it would land outside the project. */
    private Path inside(String relative) {
        if (relative == null || relative.isBlank()) {
            return null;
        }
        Path candidate = projectDirectory.resolve(relative).normalize();
        return candidate.startsWith(projectDirectory.normalize()) ? candidate : null;
    }

    // ---- the files each framework starts from -------------------------------

    public static String rootMacro() {
        return """
        void macro() {
            TFile file("../../data/output/results.root", "RECREATE");
            TH1F spectrum("spectrum", "spectrum;value;entries", 100, 0, 100);
            spectrum.Write();
            file.Close();
        }
        """;
    }

    public static String geant4Macro() {
        return """
        /run/initialize
        /run/verbose 1
        /event/verbose 0
        /gun/particle mu-
        /gun/energy 10 GeV
        /run/beamOn 1000
        """;
    }

    public static String madGraphCard() {
        return """
        import model sm
        generate p p > t t~
        output proc_tt
        """;
    }

    public static String herwigInput() {
        return """
        read snippets/PPCollider.in
        set EventGenerator:NumberOfEvents 1000
        set EventGenerator:EventHandler:LuminosityFunction:Energy 13600.0
        saverun setup EventGenerator
        """;
    }
}
