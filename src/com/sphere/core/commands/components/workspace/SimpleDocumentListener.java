package com.sphere.components.workspace;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.util.Objects;

/**
 * A simplified functional wrapper for the Swing DocumentListener interface, 
 * capturing all textual modifications on standard text components safely.
 */
public class SimpleDocumentListener implements DocumentListener {

    private final Runnable onChangeCallback;

    /**
     * Constructs a unified document modification proxy listener.
     * @param onChangeCallback the logic task routine to execute when text contents mutate.
     */
    public SimpleDocumentListener(Runnable onChangeCallback) {
        this.onChangeCallback = Objects.requireNonNull(onChangeCallback, "Change callback routine cannot be null.");
    }

    /**
     * Functional convenience factory used to quickly register tracking lambdas onto text documents.
     */
    public static SimpleDocumentListener onUpdate(Runnable callback) {
        return new SimpleDocumentListener(callback);
    }

    private void handleUpdate() {
        // Safe asynchronous dispatch ensuring background calculations don't bottleneck character input rendering
        SwingUtilities.invokeLater(onChangeCallback);
    }

    @Override
    public void insertUpdate(DocumentEvent e) {
        handleUpdate();
    }

    @Override
    public void removeUpdate(DocumentEvent e) {
        handleUpdate();
    }

    @Override
    public void changedUpdate(DocumentEvent e) {
        handleUpdate();
    }
}
