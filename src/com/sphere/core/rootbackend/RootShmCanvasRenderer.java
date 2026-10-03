package com.sphere.core.rootbackend;

import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.swing.JPanel;

public final class RootShmCanvasRenderer extends JPanel {
    /**
     * How long a canvas has to stay still before it is handed to the Plots tab.
     * ROOT repaints a canvas several times while a macro builds it up; only
     * the picture it settles on is worth keeping.
     */
    private static final long SETTLE_MILLIS = 400;

    private final BufferedImage bufferedImage;
    private final byte[] targetPixelArray;
    private final int width;
    private final int height;

    private final ScheduledExecutorService settle = Executors.newSingleThreadScheduledExecutor(r -> {
        final Thread t = new Thread(r, "sphere-root-canvas");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> pending;

    public RootShmCanvasRenderer(int width, int height) {
        this.width = width;
        this.height = height;

        // Use TYPE_4BYTE_ABGR so the backing buffer is a DataBufferByte
        this.bufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR);
        this.targetPixelArray = ((DataBufferByte) bufferedImage.getRaster().getDataBuffer()).getData();
    }

    public int expectedByteCount() {
        return targetPixelArray.length;
    }

    public void updatePixelsFromShm(MemorySegment shmPixelBuffer) {
        final long available = shmPixelBuffer.byteSize();
        if (available < targetPixelArray.length) {
            throw new IllegalArgumentException(
                "frame is " + available + " bytes, expected " + targetPixelArray.length
                + " (" + width + "x" + height + "x4)");
        }
        MemorySegment targetSegment = MemorySegment.ofArray(targetPixelArray);
        // Held while copying so the picture handed to the Plots tab is one frame, not half of two.
        synchronized (targetPixelArray) {
            MemorySegment.copy(shmPixelBuffer, ValueLayout.JAVA_BYTE, 0L, targetSegment, ValueLayout.JAVA_BYTE, 0L, targetPixelArray.length);
        }

        repaint();
        toPlots();
    }

    /**
     * Hands the canvas to the Plots tab once it has settled.
     *
     * The engine renders its canvas into shared memory and this panel was the
     * only thing reading it, and nothing ever put the panel on screen: a canvas
     * drawn in the engine was seen by no one. It now becomes a picture in the
     * Plots tab like any other, under one name, so a canvas redrawn keeps one
     * thumbnail showing its latest state, which the image editor can open.
     */
    private synchronized void toPlots() {
        if (pending != null) {
            pending.cancel(false);
        }
        pending = settle.schedule(() -> {
            final BufferedImage copy = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            final java.awt.Graphics2D g = copy.createGraphics();
            try {
                synchronized (targetPixelArray) {
                    g.drawImage(bufferedImage, 0, 0, null);
                }
            } finally {
                g.dispose();
            }
            com.sphere.components.rootview.RootPlotsPanel.showRendered(copy, "root_canvas");
        }, SETTLE_MILLIS, TimeUnit.MILLISECONDS);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        g.drawImage(bufferedImage, 0, 0, getWidth(), getHeight(), null);
    }
}
