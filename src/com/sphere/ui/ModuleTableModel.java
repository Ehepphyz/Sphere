package com.sphere.ui;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;
import com.sphere.utils.JsonParser;

/* -------------------------------------------------------------------------
 * Table model for Python modules displayed in PyEnvManagerDialog.
 * Handles installed version, latest version, loading state, and version comparison.
 */
public class ModuleTableModel extends AbstractTableModel {

    private final String[] columnNames = {"Module", "Installed", "Latest", "Actions"};
    private List<JsonParser.ModuleInfo> modules = new ArrayList<>();

    /** True while the "Latest" column is still loading (spinner mode). */
    private boolean latestLoading = false;

    public void setModules(List<JsonParser.ModuleInfo> newModules) {
        this.modules = new ArrayList<>(newModules);
        fireTableDataChanged();
    }

    public JsonParser.ModuleInfo getModuleAt(int rowIndex) {
        return modules.get(rowIndex);
    }

    @Override public int getRowCount() { return modules.size(); }
    @Override public int getColumnCount() { return columnNames.length; }
    @Override public String getColumnName(int column) { return columnNames[column]; }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        if (rowIndex >= modules.size()) return null;

        JsonParser.ModuleInfo m = modules.get(rowIndex);

        switch (columnIndex) {
            case 0: return m.name;
            case 1: return m.version;
            case 2:
                if (latestLoading) return "Loading...";
                return (m.latestVersion == null) ? "N/A" : m.latestVersion;
            case 3: return "Actions";
            default: return null;
        }
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        return columnIndex == 3; // Only the Actions column is editable
    }

    /* ---------------------------------------------------------
     * LATEST VERSION LOADING CONTROL
     */

    public void setLatestLoading(boolean loading) {
        this.latestLoading = loading;

        if (loading) {
            // Reset all latest versions → renderer shows spinner
            for (JsonParser.ModuleInfo m : modules) {
                m.latestVersion = null;
            }
        }

        fireTableDataChanged();
    }

    public boolean isLatestLoading() {
        return latestLoading;
    }

    /* ---------------------------------------------------------
     * Updates the latest version for a specific module.
     * Automatically computes "N/A" when installed == latest.
     */
    public void updateLatestVersion(String moduleName, String latest) {
        for (int i = 0; i < modules.size(); i++) {
            JsonParser.ModuleInfo m = modules.get(i);

            if (m.name.equals(moduleName)) {

                if (m.version != null && latest != null) {
                    int cmp = compareVersions(m.version, latest);

                    if (cmp == 0) {
                        m.latestVersion = "N/A"; // Same version
                    } else {
                        m.latestVersion = latest; // Renderer will color outdated versions
                    }
                } else {
                    m.latestVersion = latest;
                }

                fireTableCellUpdated(i, 2);
                break;
            }
        }
    }

    /* -------------------------------------------------------------------------
     * Compares two semantic version strings.
     * Returns:
     *   < 0 → v1 < v2
     *   = 0 → v1 == v2
     *   > 0 → v1 > v2
     */
    public int compareVersions(String v1, String v2) {
        try {
            String[] a = v1.split("\\.");
            String[] b = v2.split("\\.");

            int len = Math.max(a.length, b.length);
            for (int i = 0; i < len; i++) {
                int x = (i < a.length) ? Integer.parseInt(a[i]) : 0;
                int y = (i < b.length) ? Integer.parseInt(b[i]) : 0;

                if (x != y) return Integer.compare(x, y);
            }
        } catch (Exception ignored) {}

        return 0;
    }
}

