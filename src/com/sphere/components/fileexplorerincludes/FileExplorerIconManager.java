package com.sphere.components.fileexplorerincludes;

import javax.swing.*;
import com.sphere.utils.IconManager;

public class FileExplorerIconManager {

    // Computer / Drives
    public static Icon getComputerIcon()      { return IconManager.getIcon("computer.png"); }
    public static Icon getDriveIcon()         { return IconManager.getIcon("drive.png"); }

    // Folders
    public static Icon getFolderOpenIcon()    { return IconManager.getIcon("folder_open.png"); }
    public static Icon getFolderClosedIcon()  { return IconManager.getIcon("folder_closed.png"); }

    // Generic file
    public static Icon getGenericFileIcon()   { return IconManager.getIcon("file.png"); }

    // Extensions → delegate to IconManager
    public static Icon getIconForExtension(String ext) {
        if (ext == null || ext.isEmpty()) {
            return getGenericFileIcon();
        }

        // Let IconManager handle normalization, ROOT logic, fallback, HiDPI, etc.
        return IconManager.getIconForFile("file." + ext);
    }
}

