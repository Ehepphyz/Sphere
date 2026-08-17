package com.sphere.components.workspace;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.Map;

/**
 * A multi-step application-modal creation wizard interface that collects project metadata, 
 * validates configuration requirements, and coordinates workspace workspace setups cleanly.
 */
public class ProjectWizardDialog extends JDialog {

    private final CardLayout cardLayout;
    private final JPanel cardsContainer;

    // Sub-sections instantiated as isolated wizard views
    private final SectionOverviewPanel overviewPanel;
    private final SectionCustomTypePanel customTypePanel;
    private final SectionHealthPanel healthPanel;
    
    private final JButton btnNext;
    private final JButton btnBack;
    private final JButton btnCancel;

    private final ProjectManifest manifestBlueprint;
    private boolean completedSuccessfully = false;

    private static final String CARD_OVERVIEW = "OVERVIEW";
    private static final String CARD_CUSTOM_TYPE = "CUSTOM_TYPE";
    private String currentStep = CARD_OVERVIEW;

    public ProjectWizardDialog(Component parent) {
        super(SwingUtilities.getWindowAncestor(parent), "Create New Project Workspace", ModalityType.APPLICATION_MODAL);
        
        manifestBlueprint = new ProjectManifest();
        manifestBlueprint.projectName = "";
        manifestBlueprint.presetVersion = "1.0.0";
        manifestBlueprint.projectType = "Analysis";
        manifestBlueprint.experiment = "ATLAS";

        // Main layout settings
        setLayout(new BorderLayout());
        setMinimumSize(new Dimension(520, 420));
        setSize(550, 460);
        setLocationRelativeTo(parent);

        // Header decorative banner block
        JPanel headerPanel = new JPanel(new GridLayout(2, 1, 0, 4));
        headerPanel.setBackground(new Color(240, 244, 250));
        headerPanel.setBorder(new EmptyBorder(12, 16, 12, 16));
        
        JLabel lblTitle = new JLabel("Project Setup Wizard");
        lblTitle.setFont(lblTitle.getFont().deriveFont(Font.BOLD, 15f));
        lblTitle.setForeground(new Color(35, 50, 75));
        
        JLabel lblDesc = new JLabel("Initialize structured directories and scientific schemas for your active research context.");
        lblDesc.setFont(lblDesc.getFont().deriveFont(Font.PLAIN, 11f));
        lblDesc.setForeground(new Color(90, 100, 115));
        
        headerPanel.add(lblTitle);
        headerPanel.add(lblDesc);
        add(headerPanel, BorderLayout.NORTH);

        // Central Step Container (CardLayout view rotation engine)
        cardLayout = new CardLayout();
        cardsContainer = new JPanel(cardLayout);
        cardsContainer.setOpaque(false);

        // Core view configurations
        overviewPanel = new SectionOverviewPanel(manifestBlueprint, type -> evaluateStepRoutingTransitions());
        customTypePanel = new SectionCustomTypePanel(manifestBlueprint);
        
        cardsContainer.add(overviewPanel, CARD_OVERVIEW);
        cardsContainer.add(customTypePanel, CARD_CUSTOM_TYPE);

        // Lower Diagnostics Status Monitoring center
        healthPanel = new SectionHealthPanel();
        
        JPanel centerWrapper = new JPanel(new BorderLayout());
        centerWrapper.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, new Color(215, 220, 230)));
        centerWrapper.add(cardsContainer, BorderLayout.CENTER);
        centerWrapper.add(healthPanel, BorderLayout.SOUTH);
        add(centerWrapper, BorderLayout.CENTER);

        // Action control buttons initialization
        btnBack = new JButton("< Back");
        btnNext = new JButton("Next >");
        btnCancel = new JButton("Cancel");

        Cursor hand = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
        btnBack.setCursor(hand);
        btnNext.setCursor(hand);
        btnCancel.setCursor(hand);

        btnBack.addActionListener(e -> navigatePreviousStep());
        btnNext.addActionListener(e -> navigateNextStep());
        btnCancel.addActionListener(e -> dispose());

        JPanel footerPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 8));
        footerPanel.add(btnCancel);
        footerPanel.add(Box.createHorizontalStrut(10));
        footerPanel.add(btnBack);
        footerPanel.add(btnNext);
        add(footerPanel, BorderLayout.SOUTH);

        // Bind ongoing state-machine updates to intercept and show typing validations
        overviewPanel.addRealtimeValidation(this::runLiveDiagnosticValidations);
        customTypePanel.addRealtimeValidation(this::runLiveDiagnosticValidations);

        evaluateStepRoutingTransitions();
        runLiveDiagnosticValidations();
    }

    private void runLiveDiagnosticValidations() {
        // Hydrate intermediate metrics out of UI layouts into our working validation blueprint
        overviewPanel.apply(manifestBlueprint);
        if ("Custom".equals(manifestBlueprint.projectType)) {
            customTypePanel.apply(manifestBlueprint);
        }

        Map<String, String> errors = Validator.validate(manifestBlueprint);
        healthPanel.update(errors);

        // Block completion tracks if state exceptions persist
        if (CARD_OVERVIEW.equals(currentStep) && isCustomTypeSelected()) {
            btnNext.setText("Next >");
            btnNext.setEnabled(true);
        } else {
            btnNext.setText("Create Project");
            btnNext.setEnabled(errors.isEmpty());
        }
    }

    private void evaluateStepRoutingTransitions() {
        btnBack.setVisible(CARD_CUSTOM_TYPE.equals(currentStep));
        runLiveDiagnosticValidations();
    }

    private void navigateNextStep() {
        if (CARD_OVERVIEW.equals(currentStep) && isCustomTypeSelected()) {
            currentStep = CARD_CUSTOM_TYPE;
            cardLayout.show(cardsContainer, CARD_CUSTOM_TYPE);
            evaluateStepRoutingTransitions();
        } else {
            // Confirm creation sequences 
            overviewPanel.apply(manifestBlueprint);
            if (isCustomTypeSelected()) {
                customTypePanel.apply(manifestBlueprint);
            }
            
            completedSuccessfully = true;
            dispose();
        }
    }

    private void navigatePreviousStep() {
        if (CARD_CUSTOM_TYPE.equals(currentStep)) {
            currentStep = CARD_OVERVIEW;
            cardLayout.show(cardsContainer, CARD_OVERVIEW);
            evaluateStepRoutingTransitions();
        }
    }

    private boolean isCustomTypeSelected() {
        return "Custom".equals(overviewPanel.getSelectedProjectType());
    }

    /**
     * Checks whether the user completed the wizard steps and clicked the "Create Project" button.
     * @return true if manifest variables populated successfully without cancellation steps.
     */
    public boolean isCompleted() {
        return completedSuccessfully;
    }

    /**
     * Extracts the fully populated and validated project manifest configuration payload.
     * @return the ready-to-persist project manifest model context block.
     */
    public ProjectManifest getGeneratedManifest() {
        return completedSuccessfully ? manifestBlueprint : null;
    }
}
