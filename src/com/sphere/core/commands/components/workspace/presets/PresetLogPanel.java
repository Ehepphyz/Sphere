package com.sphere.components.workspace.presets;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Operations console logging window layout designed to parse, format,
 * and track telemetry streaming outputs from workspace automation loops.
 */
public class PresetLogPanel extends JPanel {

    private final JTextArea operationalConsoleLogArea;
    private final JButton btnClearLogOutput;
    
    private static final DateTimeFormatter TIME_STAMP_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    public PresetLogPanel() {
        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(8, 8, 8, 8));

        // 1. Core Center - Monospaced Terminal Viewport Layout
        operationalConsoleLogArea = new JTextArea();
        operationalConsoleLogArea.setFont(new Font("Consolas", Font.PLAIN, 13));
        
        // Enforce safe cross-platform programming font fallbacks
        if (!"Consolas".equals(operationalConsoleLogArea.getFont().getName())) {
            operationalConsoleLogArea.setFont(new Font("Monospaced", Font.PLAIN, 13));
        }
        
        operationalConsoleLogArea.setEditable(false);
        operationalConsoleLogArea.setLineWrap(true);
        operationalConsoleLogArea.setWrapStyleWord(true);
        operationalConsoleLogArea.setBackground(new Color(244, 245, 247));
        operationalConsoleLogArea.setForeground(new Color(33, 37, 41));

        JScrollPane logViewportScrollPane = new JScrollPane(operationalConsoleLogArea);
        logViewportScrollPane.setBorder(BorderFactory.createLineBorder(new Color(218, 222, 229)));
        add(logViewportScrollPane, BorderLayout.CENTER);

        // 2. Footer Section - Tool Control Toolbar Row
        JPanel actionControlBarPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 8));
        btnClearLogOutput = new JButton("Clear Console");
        btnClearLogOutput.setFont(btnClearLogOutput.getFont().deriveFont(Font.BOLD, 12f));
        btnClearLogOutput.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        
        btnClearLogOutput.addActionListener(e -> operationalConsoleLogArea.setText(""));
        actionControlBarPanel.add(btnClearLogOutput);
        
        add(actionControlBarPanel, BorderLayout.SOUTH);
    }

    /**
     * Appends a newly formatted tracking message string directly to the log matrix.
     * Engineered to be fully thread-safe for background worker invocation.
     * @param reportingSeverityLevel Descriptive classification status tracker tag (e.g., INFO, WARN, ERROR).
     * @param contextLogMessage      Text data metrics to render on the console workspace.
     */
    public void log(String reportingSeverityLevel, String contextLogMessage) {
        Objects.requireNonNull(reportingSeverityLevel, "Severity tag indicator level cannot be null.");
        Objects.requireNonNull(contextLogMessage, "Target console logging statement text cannot be null.");

        final String activeFormattedTimestamp = LocalTime.now().format(TIME_STAMP_FORMATTER);
        final String formattedPayloadLine = String.format("[%s] [%s] %s\n", 
                activeFormattedTimestamp, reportingSeverityLevel.toUpperCase().trim(), contextLogMessage);

        // Defensively execute updates safely back on the Swing Event Dispatch Thread (EDT)
        SwingUtilities.invokeLater(() -> {
            operationalConsoleLogArea.append(formattedPayloadLine);
            
            // Automatically pin caret indices to track newest additions
            operationalConsoleLogArea.setCaretPosition(operationalConsoleLogArea.getDocument().getLength());
        });
    }
}
