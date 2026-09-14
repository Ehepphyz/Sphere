package com.sphere.components.workspace;

import com.sphere.components.WorkspaceManager;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.sphere.utils.IconManager;
import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.fonts.FontLoader;

/**
 * Main dashboard orchestration layout binding individual metadata subsections,
 * hosting real-time validation engines, and coordinating localized configuration updates.
 */
public class ProjectDashboardPanel extends JPanel {

    private final SectionSummaryHeader summaryHeader;
    private final SectionOverviewPanel overviewSection;
    private final SectionDescriptionPanel descriptionSection;
    private final SectionTagsPanel tagsSection;
    private final SectionNotesPanel notesSection;
    private final SectionHealthPanel healthSection;
    private final SectionPresetDetailsPanel presetDetailsSection;
    private final SectionCustomTypePanel customTypeSection;
    private final ProblemsPanel problemsSection;
    private final SectionActionsPanel actionsSection;

    private final JPanel rightCustomLayoutContainer;
    private final ProjectManifest manifestContext;
    private final WorkspaceManager workspaceManager;

    public interface DashboardListener {
        void onApplyChanges();
        void onCancel();
    }

    public ProjectDashboardPanel(ProjectManifest manifest, WorkspaceManager workspaceManager, DashboardListener listener, File projectDirectory) {

        this.manifestContext = Objects.requireNonNull(manifest);
        this.workspaceManager = workspaceManager;

        ThemePalette palette = ThemeManager.getCurrentPalette();

        this.problemsSection = new ProblemsPanel();
        this.problemsSection.setOpaque(true);
        this.problemsSection.setBackground(palette.getBackgroundSurface());

        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(4, 4, 4, 4));

        if (palette != null) {
            setOpaque(true);
            setBackground(palette.getBackgroundSurface());
        }

        summaryHeader = new SectionSummaryHeader(manifestContext);
        add(summaryHeader, BorderLayout.NORTH);

        // --- LEFT COLUMN STACK ---
        JPanel leftColumnStack = new JPanel() {
            @Override
            public Dimension getPreferredSize() {
                Dimension d = super.getPreferredSize();
                d.width = Math.max(d.width, 300);
                return d;
            }
        };
        leftColumnStack.setLayout(new BoxLayout(leftColumnStack, BoxLayout.Y_AXIS));
        leftColumnStack.setBorder(new EmptyBorder(8, 8, 8, 8));
        leftColumnStack.setOpaque(true);
        leftColumnStack.setBackground(palette.getBackgroundSurface());

        overviewSection = new SectionOverviewPanel(manifestContext, this::onProjectTypeSelectionChanged);
        overviewSection.setOpaque(false);

        descriptionSection = new SectionDescriptionPanel(manifestContext);
        descriptionSection.setOpaque(false);

        tagsSection = new SectionTagsPanel(manifestContext);
        tagsSection.setOpaque(true);
        tagsSection.setBackground(palette.getBackgroundSurface());

        notesSection = new SectionNotesPanel(manifestContext);
        notesSection.setOpaque(false);

        healthSection = new SectionHealthPanel();
        healthSection.setOpaque(false);

        presetDetailsSection = new SectionPresetDetailsPanel(manifestContext, workspaceManager, projectDirectory);
        customTypeSection = new SectionCustomTypePanel(manifestContext);
        customTypeSection.setOpaque(false);

        leftColumnStack.add(new CollapsibleSectionPanel("Project Overview", overviewSection));
        leftColumnStack.add(new CollapsibleSectionPanel("Description Summary", descriptionSection));
        leftColumnStack.add(new CollapsibleSectionPanel("Categorization Tags", tagsSection));
        leftColumnStack.add(new CollapsibleSectionPanel("User Research Notes", notesSection));
        leftColumnStack.add(new CollapsibleSectionPanel("Realtime Diagnostics Health", healthSection));

        for (Component comp : leftColumnStack.getComponents()) {
            if (comp instanceof JComponent) {
                ((JComponent) comp).setOpaque(false);
            }
        }

        JScrollPane scrollPane = new JScrollPane(leftColumnStack);
        
        scrollPane.setBorder(null);
        scrollPane.setOpaque(true);
        scrollPane.setBackground(palette.getBackgroundSurface());
        scrollPane.getViewport().setOpaque(true);
        scrollPane.getViewport().setBackground(palette.getBackgroundSurface());
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);

        // --- RIGHT SIDE CONTAINER ---
        rightCustomLayoutContainer = new JPanel(new BorderLayout());
        rightCustomLayoutContainer.setOpaque(true);
        rightCustomLayoutContainer.setBackground(palette.getBackgroundSurface());

        JPanel rightHeader = new JPanel(new BorderLayout());
        rightHeader.setBorder(new EmptyBorder(5, 8, 5, 5));
        rightHeader.setOpaque(false);

        JLabel title = new JLabel("Custom Profile Settings");
        title.setFont(FontLoader.getGlobalFont(Font.BOLD, 12));
        title.setForeground(palette.getTextPrimary());

        JButton btnCloseRight = new JButton(IconManager.getIcon("close.png"));
        btnCloseRight.setBorderPainted(false);
        btnCloseRight.setContentAreaFilled(false);
        btnCloseRight.setFocusPainted(false);
        btnCloseRight.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnCloseRight.addActionListener(e -> {
            overviewSection.setProjectType("Analysis");
            updateCustomPanelLayoutVisibility("Analysis");
        });

        rightHeader.add(title, BorderLayout.WEST);
        rightHeader.add(btnCloseRight, BorderLayout.EAST);
        rightCustomLayoutContainer.add(rightHeader, BorderLayout.NORTH);
        
        CollapsibleSectionPanel rightCollapsible = new CollapsibleSectionPanel("Custom Profile Specifications", customTypeSection);
        rightCollapsible.setOpaque(false);
        rightCustomLayoutContainer.add(rightCollapsible, BorderLayout.CENTER);

        JSplitPane configurationSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scrollPane, rightCustomLayoutContainer);
        configurationSplitPane.setResizeWeight(0.65);
        configurationSplitPane.setContinuousLayout(true);
        configurationSplitPane.setBorder(null);
        configurationSplitPane.setOpaque(true);
        configurationSplitPane.setBackground(palette.getBackgroundSurface());

        JSplitPane mainDashboardSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, configurationSplitPane, this.problemsSection);
        this.problemsSection.setMinimumSize(new Dimension(150, 0));
        mainDashboardSplitPane.setResizeWeight(0.90);
        mainDashboardSplitPane.setContinuousLayout(true);
        mainDashboardSplitPane.setOpaque(true);
        mainDashboardSplitPane.setBackground(palette.getBackgroundSurface());
        mainDashboardSplitPane.setBorder(null);

        configurationSplitPane.setDividerSize(5);
        mainDashboardSplitPane.setDividerSize(5);

        add(mainDashboardSplitPane, BorderLayout.CENTER);

        // --- ACTIONS SECTION ---
        actionsSection = new SectionActionsPanel();
        actionsSection.setOpaque(true);
        actionsSection.setBackground(palette.getBackgroundSurface());

        JButton btnPresetDetails = new JButton("View Preset Parameters");
        btnPresetDetails.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        btnPresetDetails.setFocusPainted(false);
        
        btnPresetDetails.setBackground(palette.getButtonHover());
        btnPresetDetails.setForeground(palette.getTextPrimary());
        btnPresetDetails.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnPresetDetails.addActionListener(e -> presetDetailsSection.showPopup());
        actionsSection.add(btnPresetDetails, 0);

        actionsSection.onApply(e -> {
            apply(manifestContext);
            summaryHeader.update(manifestContext);
            validateAllFormMetrics();
            listener.onApplyChanges();
        });

        actionsSection.onCancel(e -> listener.onCancel());
        add(actionsSection, BorderLayout.SOUTH);

        updateCustomPanelLayoutVisibility(manifestContext.getProjectType());
        enableRealtimeTelemetryValidation();

        SwingUtilities.invokeLater(() -> {
            applyIntelligentFormScaffolding(manifestContext.getProjectType());
            validateAllFormMetrics();
        });
    }

    public void apply(ProjectManifest manifest) {
        if (manifest == null) return;

        overviewSection.apply(manifest);
        descriptionSection.apply(manifest);
        tagsSection.apply(manifest);
        notesSection.apply(manifest);

        if ("Custom".equals(manifest.getProjectType())) {
            customTypeSection.apply(manifest);
        }
    }

    private void enableRealtimeTelemetryValidation() {
        overviewSection.addRealtimeValidation(this::validateAllFormMetrics);
        descriptionSection.addRealtimeValidation(this::validateAllFormMetrics);
        tagsSection.addRealtimeValidation(this::validateAllFormMetrics);
        notesSection.addRealtimeValidation(this::validateAllFormMetrics);
        customTypeSection.addRealtimeValidation(this::validateAllFormMetrics);
    }

    private void validateAllFormMetrics() {
        ProjectManifest temporaryWorkingCopy = new ProjectManifest();
        apply(temporaryWorkingCopy);

        Map<String, String> aggregateIssues = new LinkedHashMap<>(Validator.validate(temporaryWorkingCopy));
        aggregateIssues.putAll(evaluatePresetFrameworkConstraints(temporaryWorkingCopy));

        healthSection.update(aggregateIssues);
        problemsSection.update(aggregateIssues);
    }

    private Map<String, String> evaluatePresetFrameworkConstraints(ProjectManifest manifest) {
        Map<String, String> presetIssues = new LinkedHashMap<>();
        String experiment = manifest.getExperiment();
        String version = manifest.getPresetVersion();

        if (experiment.isBlank()) {
            presetIssues.put("experiment", "Target research experiment selection is required.");
            return presetIssues;
        }

        if (version.isBlank()) {
            presetIssues.put("preset", "Preset version string is missing.");
            return presetIssues;
        }

        Map<String, WorkspaceManager.PresetRule> rules = (workspaceManager != null) ? workspaceManager.getPresetRules() : null;

        if (rules != null && !rules.isEmpty()) {
            WorkspaceManager.PresetRule standardRule = rules.get(experiment);
            if (standardRule != null && standardRule.allowedPrefixes != null && !standardRule.allowedPrefixes.isEmpty()) {
                boolean isPrefixMatched = standardRule.allowedPrefixes.stream().anyMatch(version::startsWith);
                if (!isPrefixMatched) {
                    presetIssues.put("preset", String.format("%s versions must match prefixes: %s", experiment, standardRule.allowedPrefixes));
                }
                return presetIssues;
            }
        }

        // No rule came from the file, so fall back to what the framework is known by.
        java.util.List<String> known = WorkspaceManager.DEFAULT_PREFIXES.get(experiment);
        if (known != null && known.stream().noneMatch(version::startsWith)) {
            presetIssues.put("preset", experiment + " versions normally start with "
                + String.join(" or ", known) + ".");
        }
        return presetIssues;
    }

    private void updateCustomPanelLayoutVisibility(String projectType) {
        boolean isCustom = "Custom".equals(projectType);
        rightCustomLayoutContainer.setVisible(isCustom);

        SwingUtilities.invokeLater(() -> {
            this.revalidate();
            this.repaint();

            Container parent = rightCustomLayoutContainer.getParent();
            if (parent instanceof JSplitPane) {
                ((JSplitPane) parent).resetToPreferredSizes();
            }
        });
    }

    private void onProjectTypeSelectionChanged(String targetType) {
        updateCustomPanelLayoutVisibility(targetType);
        applyIntelligentFormScaffolding(targetType);
        validateAllFormMetrics();
    }

    /**
     * Fills the blanks for a project type, and only the blanks.
     *
     * This ran on every open and wrote its canned text over whatever the
     * project already said, so opening the window and pressing Apply replaced
     * the user's own description and tags.
     */
    private void applyIntelligentFormScaffolding(String projectType) {
        switch (projectType) {
            case "Custom" -> {
                if (customTypeSection.getCustomNameInput().isEmpty()) {
                    ProjectManifest temporaryScaffold = new ProjectManifest();
                    temporaryScaffold.setCustomTypeName("UserDefinedProfile");
                    temporaryScaffold.setCustomIcon("custom_gear.png");
                    temporaryScaffold.setCustomWorkflowTemplate("templates/custom_baseline.json");
                    temporaryScaffold.setCustomDefaultStructure("src/, data/raw/, scripts/");
                    customTypeSection.apply(temporaryScaffold);
                }
                suggestTags(java.util.Arrays.asList("custom", "user-defined"));
                suggestDescription("Custom user-defined profile template configuration environment.");
            }
            case "Analysis" -> {
                suggestTags(java.util.Arrays.asList("analysis", "root", "ntuples"));
                suggestDescription("Physics analysis project using the ROOT framework.");
            }
            case "Detector Simulation" -> {
                suggestTags(java.util.Arrays.asList("simulation", "geant4", "detector"));
                suggestDescription("Detector simulation pipeline using Geant4 infrastructure.");
            }
            case "Event Generation" -> {
                suggestTags(java.util.Arrays.asList("mg5", "lhe", "generator"));
                suggestDescription("High-energy physics event generation workflows driven by MadGraph5.");
            }
            case "Reconstruction" -> {
                suggestTags(java.util.Arrays.asList("tracking", "calorimetry", "reco"));
                suggestDescription("Event data reconstruction pipelines and tracking metrics.");
            }
            case "ML/AI Pipeline" -> {
                suggestTags(java.util.Arrays.asList("ml", "ai", "training", "dataset"));
                suggestDescription("Machine Learning model training, dataset conditioning, and inference layers.");
            }
            case "Visualization" -> {
                suggestTags(java.util.Arrays.asList("visualization", "plots", "graphics"));
                suggestDescription("Data visualization frameworks, plotting tools, and graphic reporting.");
            }
            case "Documentation" -> {
                suggestTags(java.util.Arrays.asList("docs", "markdown", "guide"));
                suggestDescription("Project documentation containing technical descriptions and workflow guides.");
            }
        }
        revalidate();
        repaint();
    }

    private void suggestTags(java.util.List<String> tags) {
        if (!tagsSection.hasTags()) {
            tagsSection.setTags(tags);
        }
    }

    private void suggestDescription(String text) {
        if (descriptionSection.getDescriptionInput().isEmpty()) {
            descriptionSection.setText(text);
        }
    }

    public void assignDefaultWindowAction() {
        if (actionsSection != null) {
            actionsSection.assignDefaultWindowAction();
        }
    }
}
