package com.sphere.components;

import javax.swing.*;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.prefs.Preferences;

/**
 * A JSplitPane that opens where it was left.
 *
 * The position cannot be set in the constructor, because the pane has no width
 * yet and the layout that follows setVisible moves whatever was asked for. So
 * the pane places its own divider at every layout until the user takes hold of
 * it, and what is kept is the width itself, in pixels: a panel asked to be 300
 * wide opens 300 wide, launch after launch.
 */
public class PersistentSplitPane extends JSplitPane {

    /** Below this the pane is still being laid out and its divider means nothing. */
    private static final int MIN_SPAN = 50;

    /** How close to an edge a divider may be placed, in pixels. */
    private static final int EDGE = 20;

    /**
     * Kept apart from what older versions wrote, which held a share of the width.
     * A share reapplied to a width that differs even slightly from the one it was
     * taken at gives back a different number of pixels, and the panel drifted.
     */
    private static final String SUFFIX = ".px";

    /** Works out where the divider goes for a given width. */
    public interface Placement {
        int locationFor(int span);
    }

    private final Preferences prefs;
    private final String key;
    private final int defaultLocation;
    private final double defaultProportion;
    private final Placement placement;

    /** True once the user has taken hold of the divider; nothing else places it after. */
    private boolean userMoved;

    /** Set while this class moves the divider, so its own layout is not taken for a drag. */
    private boolean placing;

    /** How many times the divider had to be put back, for the :layout report. */
    private int corrections;

    /**
     * Told, in pixels, where the user let the divider go. When one is set it takes
     * the place of the preferences, so the width has a single home rather than two
     * that can disagree.
     */
    private java.util.function.IntConsumer onUserMoved;

    /** Opens at a fixed number of pixels the first time it is ever shown. */
    public PersistentSplitPane(int orientation, java.awt.Component left, java.awt.Component right,
                               Preferences prefs, String key, int defaultLocation) {
        this(orientation, left, right, prefs, key, defaultLocation, -1.0, null);
    }

    /** Opens at a share of its own width the first time it is ever shown. */
    public PersistentSplitPane(int orientation, java.awt.Component left, java.awt.Component right,
                               Preferences prefs, String key, double defaultProportion) {
        this(orientation, left, right, prefs, key, -1, defaultProportion, null);
    }

    /** Opens where the given rule says, which may read the other panes. */
    public PersistentSplitPane(int orientation, java.awt.Component left, java.awt.Component right,
                               Preferences prefs, String key, Placement placement) {
        this(orientation, left, right, prefs, key, -1, -1.0, placement);
    }

    private PersistentSplitPane(int orientation, java.awt.Component left, java.awt.Component right,
                                Preferences prefs, String key,
                                int defaultLocation, double defaultProportion,
                                Placement placement) {
        super(orientation, left, right);
        this.prefs = prefs;
        this.key = key;
        this.defaultLocation = defaultLocation;
        this.defaultProportion = defaultProportion;
        this.placement = placement;

        // Only a hand on the divider counts as a choice. Laying out moves it as
        // well, and a position stored from that would drift at every launch.
        if (getUI() instanceof BasicSplitPaneUI ui && ui.getDivider() != null) {
            ui.getDivider().addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent event) {
                    userMoved = true;
                }

                @Override
                public void mouseReleased(MouseEvent event) {
                    userMoved = true;
                    remember();
                }
            });
        }
    }

    /**
     * Puts the divider back where it belongs after every layout.
     *
     * Laying out moves it on its own, according to the resize weight, and it did
     * so again right after the position was set, undoing it. So the position is
     * not set once and trusted: it is checked at each pass and corrected while it
     * is wrong, which costs nothing once it is right. A width of its own is the
     * only thing waited for: on some runtimes every layout happens before the
     * window counts as showing, and a pane that waited for that was never placed.
     */
    @Override
    public void doLayout() {
        super.doLayout();
        if (userMoved || placing) {
            return;
        }
        final int span = span();
        if (span < MIN_SPAN) {
            return;
        }
        final int wanted = placeFor(span);
        // A width that does not fit yet means the pane is still growing. Placing
        // now would put the divider against the edge and move it again a moment
        // later, which is one visible jump for nothing.
        if (wanted < 0 || Math.abs(getDividerLocation() - wanted) <= 1) {
            return;
        }
        placing = true;
        try {
            setDividerLocation(wanted);
            corrections++;
        } finally {
            placing = false;
        }
    }

    /** Hands the width the user chose to whoever wants to write it down. */
    public void setOnUserMoved(java.util.function.IntConsumer listener) {
        this.onUserMoved = listener;
    }

    /** What the pane is holding to, for the report behind :layout. */
    public String state() {
        final int span = span();
        final int kept = prefs == null ? -1 : prefs.getInt(key + SUFFIX, -1);
        return String.format("%-18s span %5d  divider %5d  %s  %-13s  placed %d time(s)",
            key, span, getDividerLocation(),
            span > 0 ? String.format("%3.0f%%", 100.0 * getDividerLocation() / span) : "  ?",
            kept > 0 ? String.format("kept %d px", kept)
                     : (userMoved ? "moved by hand" : "default"), corrections);
    }

    /** The divider position for this width: the one kept, or the one asked for. */
    private int placeFor(int span) {
        final int kept = prefs == null ? -1 : prefs.getInt(key + SUFFIX, -1);
        final int where;
        if (kept >= EDGE && kept <= span - EDGE) {
            where = kept;
        } else if (placement != null) {
            where = placement.locationFor(span);
        } else if (defaultProportion >= 0) {
            where = (int) Math.round(span * defaultProportion);
        } else {
            where = defaultLocation;
        }
        // Negative when it does not fit: the caller waits rather than clamping.
        return where >= EDGE && where <= span - EDGE ? where : -1;
    }

    /** Writes down, in pixels, where the user let the divider go. */
    private void remember() {
        final int span = span();
        final int where = getDividerLocation();
        if (span < MIN_SPAN || where < EDGE || where > span - EDGE) {
            return;
        }
        if (onUserMoved != null) {
            onUserMoved.accept(where);
        } else if (prefs != null) {
            prefs.putInt(key + SUFFIX, where);
        }
    }

    private int span() {
        return getOrientation() == JSplitPane.HORIZONTAL_SPLIT ? getWidth() : getHeight();
    }
}
