package com.sphere.core.python.jupyterlab;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.Collections;
import java.util.List;

/**
 * DraggableCellSupport
 *
 * Simple drag-to-reorder support using standard generic JComponents.
 */
public class DraggableCellSupport {

    public static void makeDraggable(JComponent comp, JPanel container, List<?> cellsList, Runnable onReorder) {

        MouseAdapter ma = new MouseAdapter() {
            private Point start;
            private boolean isDraggingCell = false;

            @Override
            public void mousePressed(MouseEvent e) {
                // Check if the click landed inside an active text editing component
                Component deepest = SwingUtilities.getDeepestComponentAt(comp, e.getX(), e.getY());
                if (deepest instanceof javax.swing.text.JTextComponent) {
                    start = null;
                    isDraggingCell = false;
                    return; // Ignore completely, pass full control to the text editor
                }
                
                start = e.getPoint();
                isDraggingCell = true;
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                // Guard clause: Only proceed if we are explicitly dragging the cell layout, 
                // and NOT text inside an editor
                if (!isDraggingCell || start == null) return;
                
                int dy = e.getY() - start.y;
                
                // Retrieve the cell index stored inside the component properties
                Integer cellIdxObj = (Integer) comp.getClientProperty("cellIndex");
                if (cellIdxObj == null) return;
                int cellIdx = cellIdxObj;

                // Check threshold to move up
                if (dy < -30 && cellIdx > 0) {
                    Collections.swap(cellsList, cellIdx, cellIdx - 1);
                    reset();
                    onReorder.run(); 
                } 
                // Check threshold to move down
                else if (dy > 30 && cellIdx < cellsList.size() - 1) {
                    Collections.swap(cellsList, cellIdx, cellIdx + 1);
                    reset();
                    onReorder.run(); 
                }
            }
            
            @Override
            public void mouseReleased(MouseEvent e) {
                reset();
            }

            private void reset() {
                start = null;
                isDraggingCell = false;
            }
        };

        comp.addMouseListener(ma);
        comp.addMouseMotionListener(ma);
    }
}