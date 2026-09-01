package com.sphere.core.rootbackend;

import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import javax.swing.JPanel;

public final class RootShmCanvasRenderer extends JPanel {
    private final BufferedImage bufferedImage;
    private final byte[] targetPixelArray;
    private final int width;
    private final int height;

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
        MemorySegment.copy(shmPixelBuffer, ValueLayout.JAVA_BYTE, 0L, targetSegment, ValueLayout.JAVA_BYTE, 0L, targetPixelArray.length);
        
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        g.drawImage(bufferedImage, 0, 0, getWidth(), getHeight(), null);
    }
}