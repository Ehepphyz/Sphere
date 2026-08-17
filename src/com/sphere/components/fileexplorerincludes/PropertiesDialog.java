package com.sphere.components.fileexplorerincludes;

import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.fonts.FontLoader;
import com.sphere.utils.OSValidator;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.*;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;

public class PropertiesDialog extends JDialog {

    private static final DateTimeFormatter US_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss");

    private final ThemePalette palette = ThemeManager.getCurrentPalette();
    private static final DecimalFormat SIZE_FORMATTER =
            new DecimalFormat("#,##0.##");

    public PropertiesDialog(Window owner, File file) {

        super(owner, "Properties - " + file.getName(), ModalityType.APPLICATION_MODAL);
        setLayout(new BorderLayout());
        getContentPane().setBackground(palette.getBackgroundSurface());

        JPanel mainWrapper = new JPanel();
        mainWrapper.setLayout(new BoxLayout(mainWrapper, BoxLayout.Y_AXIS));
        mainWrapper.setBorder(BorderFactory.createEmptyBorder(20, 20, 15, 20));
        mainWrapper.setOpaque(false);

        /*--------------------------------------------------------------
         * GENERAL INFORMATION
         *--------------------------------------------------------------*/
        JPanel generalPanel = createSectionPanel("General File Information");

        addFlexibleRow(generalPanel, "Name:", new JLabel(file.getName()));
        addFlexibleRow(generalPanel, "Path:", createUneditablePathField(file.getAbsolutePath()));
        addFlexibleRow(generalPanel, "Size:", new JLabel(formatFileSize(file.length())));

        try {
            BasicFileAttributes attrs = Files.readAttributes(file.toPath(), BasicFileAttributes.class);
            addFlexibleRow(generalPanel, "Created:", new JLabel(formatToUSDateTime(attrs.creationTime().toInstant())));
            addFlexibleRow(generalPanel, "Modified:", new JLabel(formatToUSDateTime(attrs.lastModifiedTime().toInstant())));
        } catch (Exception ignored) {}

        finalizeSection(generalPanel);
        mainWrapper.add(generalPanel);
        mainWrapper.add(Box.createVerticalStrut(15));

        /*--------------------------------------------------------------
         * SECURITY & PERMISSIONS
         *--------------------------------------------------------------*/
        JPanel securityPanel = createSectionPanel("Security & Access Permissions");

        /*--------------------------------------------------------------
         * OWNER / GROUP / ACL / POSIX
         *--------------------------------------------------------------*/
        JLabel ownerLabel = new JLabel("N/A");
        JLabel groupLabel = new JLabel("N/A");
        JTextArea ntfsArea = null;

        try {
            UserPrincipal owner2 = Files.getOwner(file.toPath());
            ownerLabel = new JLabel(owner2.getName());
        } catch (Exception ignored) {}

        if (OSValidator.isLinux() || OSValidator.isMac()) {
            try {
                PosixFileAttributes posix = Files.readAttributes(file.toPath(), PosixFileAttributes.class);
                groupLabel = new JLabel(posix.group().getName());
            } catch (Exception ignored) {}
        }

        if (OSValidator.isWindows()) {
            try {
                AclFileAttributeView aclView =
                        Files.getFileAttributeView(file.toPath(), AclFileAttributeView.class);

                if (aclView != null) {
                    java.util.List<AclEntry> acl = aclView.getAcl();

                    String groupName = "N/A";
                    StringBuilder rights = new StringBuilder();

                    for (AclEntry entry : acl) {
                        rights.append(entry.principal().getName()).append(": ");
                        rights.append(entry.permissions().toString()).append("\n");

                        if (entry.principal().getName().contains("\\")) {
                            groupName = entry.principal().getName();
                        }
                    }

                    groupLabel = new JLabel(groupName);

                    ntfsArea = new JTextArea(rights.toString());
                    ntfsArea.setOpaque(false);
                    ntfsArea.setEditable(false);
                    ntfsArea.setForeground(palette.getTextPrimary());
                    ntfsArea.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
                }

            } catch (Exception ignored) {}
        }

        addFlexibleRow(securityPanel, "Owner:", ownerLabel);
        addFlexibleRow(securityPanel, "Group:", groupLabel);

        if (ntfsArea != null) {
            addFlexibleRow(securityPanel, "NTFS Rights:", ntfsArea);
        }

        /*--------------------------------------------------------------
         * PERMISSION CHECKBOXES (R/W/X)
         *--------------------------------------------------------------*/
        JCheckBox ownerRead = createPermBox("R");
        JCheckBox ownerWrite = createPermBox("W");
        JCheckBox ownerExec = createPermBox("X");

        JCheckBox groupRead = createPermBox("R");
        JCheckBox groupWrite = createPermBox("W");
        JCheckBox groupExec = createPermBox("X");

        JCheckBox otherRead = createPermBox("R");
        JCheckBox otherWrite = createPermBox("W");
        JCheckBox otherExec = createPermBox("X");

        if (OSValidator.isLinux() || OSValidator.isMac()) {
            try {
                PosixFileAttributes posix = Files.readAttributes(file.toPath(), PosixFileAttributes.class);
                Set<PosixFilePermission> perms = posix.permissions();

                ownerRead.setSelected(perms.contains(PosixFilePermission.OWNER_READ));
                ownerWrite.setSelected(perms.contains(PosixFilePermission.OWNER_WRITE));
                ownerExec.setSelected(perms.contains(PosixFilePermission.OWNER_EXECUTE));

                groupRead.setSelected(perms.contains(PosixFilePermission.GROUP_READ));
                groupWrite.setSelected(perms.contains(PosixFilePermission.GROUP_WRITE));
                groupExec.setSelected(perms.contains(PosixFilePermission.GROUP_EXECUTE));

                otherRead.setSelected(perms.contains(PosixFilePermission.OTHERS_READ));
                otherWrite.setSelected(perms.contains(PosixFilePermission.OTHERS_WRITE));
                otherExec.setSelected(perms.contains(PosixFilePermission.OTHERS_EXECUTE));

            } catch (Exception ignored) {}
        }

        JPanel ownerRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        ownerRow.setOpaque(false);
        ownerRow.add(ownerRead);
        ownerRow.add(ownerWrite);
        ownerRow.add(ownerExec);
        addFlexibleRow(securityPanel, "Owner:", ownerRow);

        JPanel groupRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        groupRow.setOpaque(false);
        groupRow.add(groupRead);
        groupRow.add(groupWrite);
        groupRow.add(groupExec);
        addFlexibleRow(securityPanel, "Group:", groupRow);

        JPanel othersRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        othersRow.setOpaque(false);
        othersRow.add(otherRead);
        othersRow.add(otherWrite);
        othersRow.add(otherExec);
        addFlexibleRow(securityPanel, "Others:", othersRow);

        finalizeSection(securityPanel);
        mainWrapper.add(securityPanel);

        add(mainWrapper, BorderLayout.CENTER);

        /*--------------------------------------------------------------
         * FOOTER BUTTONS
         *--------------------------------------------------------------*/
        JButton activateXBtn = new JButton("Activate +x");
        activateXBtn.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        activateXBtn.setPreferredSize(new Dimension(110, 28));
        activateXBtn.addActionListener(e -> {
            boolean ok = file.setExecutable(true);
            ownerExec.setSelected(true);
            JOptionPane.showMessageDialog(this,
                    ok ? "Executable flag (+x) activated." : "Failed to activate +x.");
        });

        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        cancelBtn.setPreferredSize(new Dimension(90, 28));
        cancelBtn.addActionListener(e -> dispose());

        JButton saveBtn = new JButton("Save");
        saveBtn.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        saveBtn.setPreferredSize(new Dimension(90, 28));
        saveBtn.addActionListener(e -> {
            try {
                if (OSValidator.isLinux() || OSValidator.isMac()) {
                    Set<PosixFilePermission> perms = new HashSet<>();

                    if (ownerRead.isSelected()) perms.add(PosixFilePermission.OWNER_READ);
                    if (ownerWrite.isSelected()) perms.add(PosixFilePermission.OWNER_WRITE);
                    if (ownerExec.isSelected()) perms.add(PosixFilePermission.OWNER_EXECUTE);

                    if (groupRead.isSelected()) perms.add(PosixFilePermission.GROUP_READ);
                    if (groupWrite.isSelected()) perms.add(PosixFilePermission.GROUP_WRITE);
                    if (groupExec.isSelected()) perms.add(PosixFilePermission.GROUP_EXECUTE);

                    if (otherRead.isSelected()) perms.add(PosixFilePermission.OTHERS_READ);
                    if (otherWrite.isSelected()) perms.add(PosixFilePermission.OTHERS_WRITE);
                    if (otherExec.isSelected()) perms.add(PosixFilePermission.OTHERS_EXECUTE);

                    Files.setPosixFilePermissions(file.toPath(), perms);
                }

                JOptionPane.showMessageDialog(this, "Permissions updated.");
                dispose();

            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Failed to update permissions.");
            }
        });

        JPanel footerPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        footerPanel.setBorder(BorderFactory.createEmptyBorder(0, 20, 20, 20));
        footerPanel.setOpaque(false);

        footerPanel.add(activateXBtn);
        footerPanel.add(cancelBtn);
        footerPanel.add(saveBtn);

        add(footerPanel, BorderLayout.SOUTH);

        pack();
        setMinimumSize(new Dimension(400, 450));
        setSize(new Dimension(450, 600));
        setLocationRelativeTo(owner);
    }

    /*--------------------------------------------------------------
     * CLEANED HELPER METHODS (ONLY WHAT IS USED)
     *--------------------------------------------------------------*/
    private JPanel createSectionPanel(String title) {
        JPanel section = new JPanel(new GridBagLayout());
        section.setOpaque(false);

        section.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, palette.getBorder()),
                title,
                javax.swing.border.TitledBorder.LEFT,
                javax.swing.border.TitledBorder.TOP,
                FontLoader.getGlobalFont(Font.PLAIN, 12),
                palette.getTextWhite()
        ));
        return section;
    }

    private void addFlexibleRow(JPanel container, String key, JComponent value) {
        int row = container.getComponentCount() / 2;
        GridBagConstraints gbc = new GridBagConstraints();

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.anchor = GridBagConstraints.LINE_START;
        gbc.insets = new Insets(6, 12, 6, 8);

        JLabel keyLabel = new JLabel(key);
        keyLabel.setFont(FontLoader.getGlobalFont(Font.BOLD, 12));
        keyLabel.setForeground(palette.getTextPrimary());
        container.add(keyLabel, gbc);

        gbc.gridx = 1;
        gbc.insets = new Insets(6, 4, 6, 12);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        value.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        value.setForeground(palette.getTextPrimary());
        container.add(value, gbc);
    }

    private void finalizeSection(JPanel container) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = container.getComponentCount() / 2;
        gbc.weighty = 1.0;
        container.add(Box.createVerticalGlue(), gbc);
    }

    private JTextField createUneditablePathField(String path) {
        JTextField field = new JTextField(path);
        field.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        field.setEditable(false);
        field.setBorder(null);
        field.setOpaque(false);
        field.setForeground(palette.getTextPrimary());
        field.setCaretPosition(0);
        field.setSelectionColor(palette.getAccent());
        field.setSelectedTextColor(palette.getTextWhite());
        return field;
    }

    private JCheckBox createPermBox(String text) {
        JCheckBox box = new JCheckBox(text);
        box.setOpaque(false);
        box.setForeground(palette.getTextPrimary());
        box.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        return box;
    }

    private String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 bytes";

        final String[] units = { "bytes", "KB", "MB", "GB", "TB" };
        int index = (int) (Math.log10(bytes) / Math.log10(1024));
        if (index >= units.length) index = units.length - 1;

        double value = bytes / Math.pow(1024, index);
        return SIZE_FORMATTER.format(value) + " " + units[index] +
                " (" + bytes + " bytes)";
    }

    private String formatToUSDateTime(java.time.Instant instant) {
        if (instant == null) return "";
        LocalDateTime time = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
        return time.format(US_DATE_FORMATTER);
    }

    public static void show(Window owner, File file) {
        PropertiesDialog dlg = new PropertiesDialog(owner, file);
        dlg.setVisible(true);
    }
}

