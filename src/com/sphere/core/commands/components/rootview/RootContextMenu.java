package com.sphere.components.rootview;

import com.sphere.components.rootview.RootPadPainter.Mode;
import com.sphere.components.rootview.RootScene.Geometry;
import com.sphere.components.rootview.RootScene.Item;

import javax.swing.BorderFactory;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingConstants;
import java.awt.Font;
import java.util.List;

/**
 * ROOT's context menu of an object, as TRootContextMenu builds it: the
 * title "Class::name", then the functions of the class and of every class
 * it derives from, those of one class together, a toggle with its state;
 * for a pad holding a 3D view the functions of its TView3D and TAxis3D, for
 * a geometry those of TGeoManager. A function Sphere does itself is called
 * at once; one it leaves to ROOT is marked, and goes to the engine.
 */
final class RootContextMenu {

    private RootContextMenu() {
    }

    static JPopupMenu build(RootTarget t, RootHost host, JMenu extras) {
        final JPopupMenu menu = new JPopupMenu();
        final JLabel title = new JLabel(t.title(), SwingConstants.CENTER);
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        title.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        menu.add(title);
        menu.addSeparator();
        functions(menu, t, host, RootMethod.menuOf(t.className));

        final RootPadPainter.View v = t.canvas.view(t.pad);
        if (v.mode == Mode.THREE_D && t.pad.main() != null) {
            menu.addSeparator();
            final JMenu view3D = new JMenu("TView3D");
            final RootTarget tv = new RootTarget("TView3D", t.pad, t.pad, t.scene, t.canvas, t.at, t.x, t.y);
            for (RootMethod m : RootMethod.declared("TView3D")) view3D.add(item(tv, m, host));
            menu.add(view3D);
            final JMenu axis3D = new JMenu("TAxis3D");
            final RootTarget ta = new RootTarget("TAxis3D", t.pad, t.pad, t.scene, t.canvas, t.at, t.x, t.y);
            for (RootMethod m : RootMethod.declared("TAxis3D")) axis3D.add(item(ta, m, host));
            menu.add(axis3D);
        }
        Geometry geometry = null;
        for (Item i : t.pad.items) if (i instanceof Geometry g) geometry = g;
        if (geometry != null && !"TGeoManager".equals(t.className)) {
            menu.addSeparator();
            final JMenu geo = new JMenu("TGeoManager");
            final RootTarget tg = new RootTarget("TGeoManager", geometry, t.pad, t.scene, t.canvas, t.at, t.x, t.y);
            functions(geo.getPopupMenu(), tg, host, RootMethod.declared("TGeoManager"));
            menu.add(geo);
            if (!RootMethod.inherits(t.className, "TGeoVolume")) {
                final JMenu vol = new JMenu("TGeoVolume");
                final RootTarget tv = new RootTarget("TGeoVolume", geometry, t.pad, t.scene, t.canvas, t.at, t.x, t.y);
                functions(vol.getPopupMenu(), tv, host, RootMethod.menuOf("TGeoVolume"));
                menu.add(vol);
            }
        }
        if (extras != null) {
            menu.addSeparator();
            menu.add(extras);
        }
        return menu;
    }

    private static void functions(JPopupMenu menu, RootTarget t, RootHost host, List<RootMethod> methods) {
        String last = null;
        for (RootMethod m : methods) {
            if (last != null && !last.equals(m.owner)) menu.addSeparator();
            last = m.owner;
            menu.add(item(t, m, host));
        }
    }

    static JMenuItem item(RootTarget t, RootMethod m, RootHost host) {
        final boolean own = RootActions.implemented(t, m);
        final String label = own ? m.label() : "<html>" + m.label() + " <font color=\"#8a8f98\">· ROOT</font></html>";
        final JMenuItem item;
        if (m.toggle) {
            Boolean on = null;
            try {
                on = RootActions.state(t, m);
            } catch (RuntimeException unknown) {
                // The state cannot be read on this object: shown off.
            }
            item = new JCheckBoxMenuItem(label, on != null && on);
        } else {
            item = new JMenuItem(label);
        }
        item.setToolTipText(m.owner + "::" + m.signature() + (own ? "" : " — done by ROOT's engine"));
        item.addActionListener(e -> RootActions.invoke(t, m, t.canvas, host));
        return item;
    }
}
