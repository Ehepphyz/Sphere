package com.sphere.components.rootview;

import com.sphere.components.imaging.ImagingTheme;
import com.sphere.core.rootbackend.RootBackend;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The macro that draws what is on screen, and a place to run it.
 *
 * Running goes through the ROOT backend when it is up. The engine that ships
 * with Sphere answers ping and the memory commands; until it also answers the
 * interpreter command, the button says so instead of appearing to work.
 */
public final class RootMacroPane extends ViewSurface {

    private final JTextArea source = new JTextArea();
    private final JTextArea output = new JTextArea();
    private final JLabel state = new JLabel(" ");
    private final JButton run = ImagingTheme.textButton("Run", "Send the macro to the ROOT backend");
    private final JButton save = ImagingTheme.textButton("Save as...", "Write the macro to a .C file");
    private final JButton copy = ImagingTheme.textButton("Copy", "Put the macro on the clipboard");

    public RootMacroPane() {
        super(new BorderLayout(), false);

        source.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        source.setBackground(ImagingTheme.panel());
        source.setForeground(ImagingTheme.text());
        source.setCaretColor(ImagingTheme.accent());
        source.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        output.setEditable(false);
        output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        output.setBackground(ImagingTheme.panel());
        output.setForeground(ImagingTheme.subduedText());
        output.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        ImagingTheme.Surface bar = ImagingTheme.strip(true);
        bar.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        bar.add(run);
        bar.add(javax.swing.Box.createHorizontalStrut(6));
        bar.add(save);
        bar.add(javax.swing.Box.createHorizontalStrut(6));
        bar.add(copy);
        bar.add(javax.swing.Box.createHorizontalStrut(12));
        state.setForeground(ImagingTheme.subduedText());
        state.setFont(ImagingTheme.uiFont(Font.PLAIN, 11f));
        bar.add(state);
        bar.add(javax.swing.Box.createHorizontalGlue());

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                                          scroll(source), scroll(output));
        split.setResizeWeight(0.68);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setDividerSize(4);

        add(bar, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        setPreferredSize(new Dimension(400, 260));

        run.addActionListener(e -> execute());
        save.addActionListener(e -> saveAs());
        copy.addActionListener(e ->
            com.sphere.components.ClipboardBridge.write(source.getText()));

        refreshState();
    }

    private static JScrollPane scroll(java.awt.Component view) {
        JScrollPane pane = new JScrollPane(view);
        pane.setBorder(BorderFactory.createEmptyBorder());
        pane.getViewport().setBackground(ImagingTheme.panel());
        return pane;
    }

    public void setMacro(String text) {
        source.setText(text);
        source.setCaretPosition(0);
        refreshState();
    }

    public String getMacro() {
        return source.getText();
    }

    private void refreshState() {
        RootBackend backend = RootBackend.getInstance();
        final boolean up = backend != null && backend.isAvailable();
        run.setEnabled(up);
        state.setText(up ? "backend connected"
                         : "backend not running, the macro can still be saved");
    }

    private void execute() {
        RootBackend backend = RootBackend.getInstance();
        if (backend == null || !backend.isAvailable()) {
            refreshState();
            return;
        }
        final String macro = source.getText();
        run.setEnabled(false);
        output.setText("");
        state.setText("running");

        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                return backend.executeClingAwait(macro, 30_000L);
            }

            @Override
            protected void done() {
                String answer;
                try {
                    answer = get();
                } catch (Exception failed) {
                    answer = null;
                }
                output.setText(answer == null
                    ? "The backend did not answer. Its interpreter command is not "
                      + "handled by the engine that is running."
                    : answer);
                state.setText("done");
                run.setEnabled(true);
            }
        }.execute();
    }

    private void saveAs() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("macro.C"));
        if (chooser.showSaveDialog(SwingUtilities.getWindowAncestor(this))
            != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path target = chooser.getSelectedFile().toPath();
        try {
            Files.writeString(target, source.getText(), StandardCharsets.UTF_8);
            output.setText("written to " + target);
        } catch (Exception failed) {
            output.setText("could not write " + target + ": " + failed.getMessage());
        }
    }
}
