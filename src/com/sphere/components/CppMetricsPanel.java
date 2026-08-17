package com.sphere.components;

import javax.swing.*;
import java.awt.*;
import java.text.DecimalFormat;
import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.fonts.FontLoader;

// Replace this import with the actual package path of your CppBackend/Metrics class
// import com.sphere.core.CppBackend; 

/**
 * A real-time performance dashboard displaying C++ compilation and runtime metrics.
 */
public class CppMetricsPanel extends JPanel {

    // Dummy placeholder backend reference — swap with your actual type
    private final Object cppBackend; 
    
    private final JLabel avgCompileLabel;
    private final JLabel avgRunLabel;
    private final JLabel errorCountLabel;
    private final JLabel successRateLabel;
    
    private final Timer refreshTimer;
    private final DecimalFormat df = new DecimalFormat("0.0");
    private final ThemePalette palette = ThemeManager.getCurrentPalette();

    public CppMetricsPanel(Object cppBackend) {
        this.cppBackend = cppBackend;
        
        setLayout(new BorderLayout());
        setBackground(palette.getBackgroundSurface()); // Matches your Sphere dark theme palette
        setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

        // --- Header Section ---
        JLabel headerLabel = new JLabel("C++ Engine Diagnostics");
        headerLabel.setFont(FontLoader.getGlobalFont(Font.BOLD, 14));
        headerLabel.setForeground(palette.getTextWhite());
        headerLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 15, 0));
        add(headerLabel, BorderLayout.NORTH);

        // --- Grid Data Grid ---
        JPanel statsGrid = new JPanel(new GridLayout(4, 2, 10, 12));
        statsGrid.setBackground(palette.getBackgroundSurface());

        avgCompileLabel = createValueLabel();
        avgRunLabel = createValueLabel();
        errorCountLabel = createValueLabel();
        successRateLabel = createValueLabel();

        addMetricRow(statsGrid, "Avg Compile Time:", avgCompileLabel);
        addMetricRow(statsGrid, "Avg Runtime Exec:", avgRunLabel);
        addMetricRow(statsGrid, "Total Engine Faults:", errorCountLabel);
        addMetricRow(statsGrid, "Build Success Rate:", successRateLabel);

        add(statsGrid, BorderLayout.CENTER);

        // --- Background Auto-Refresh Polling Loop ---
        // Automatically updates the UI values every 1000ms safely on the EDT
        refreshTimer = new Timer(1000, e -> updateMetricsDisplay());
        refreshTimer.start();

        // Initial paint execution
        updateMetricsDisplay();
    }

    private JLabel createValueLabel() {
        JLabel label = new JLabel("0.0");
        label.setFont(FontLoader.getGlobalFont(Font.BOLD, 12));
        label.setForeground(palette.getlockedmode()); // Using your Accent/Amber mapping
        return label;
    }

    private void addMetricRow(JPanel panel, String title, JLabel valueLabel) {
        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        titleLabel.setForeground(palette.getTextLightGray());
        
        panel.add(titleLabel);
        panel.add(valueLabel);
    }

    /**
     * Polls the backend metrics safely and pushes value state updates directly onto the UI thread.
     */
    private void updateMetricsDisplay() {
        if (cppBackend == null) {
            avgCompileLabel.setText("N/A");
            avgRunLabel.setText("N/A");
            errorCountLabel.setText("N/A");
            successRateLabel.setText("N/A");
            return;
        }

        // TODO: Replace this placeholder block with your actual metrics invocation pipeline
        // Example:
        // CppBackendMetrics.MetricsSnapshot stats = cppBackend.getMetricsSnapshot();
        // double avgCompile = stats.getAverageCompileTimeMillis();
        // double avgRun     = stats.getAverageRunTimeMillis();
        // long errors       = stats.getErrorCount();
        // double success    = stats.getSuccessRatePercentage();

        double avgCompile = 124.5; // Mock data
        double avgRun = 45.2;      // Mock data
        long errors = 2;           // Mock data
        double success = 94.1;     // Mock data

        // Thread-safe Swing target mutations
        avgCompileLabel.setText(df.format(avgCompile) + " ms");
        avgRunLabel.setText(df.format(avgRun) + " ms");
        errorCountLabel.setText(String.valueOf(errors));
        successRateLabel.setText(df.format(success) + "%");
        
        // Dynamic alert color shifts for operational failures
        if (errors > 0) {
            errorCountLabel.setForeground(Color.RED); 
        } else {
            errorCountLabel.setForeground(palette.getlockedmode());
        }
    }

    /**
     * Clean up resources when the container component drops out of memory scope.
     */
    @Override
    public void removeNotify() {
        if (refreshTimer != null && refreshTimer.isRunning()) {
            refreshTimer.stop();
        }
        super.removeNotify();
    }
}