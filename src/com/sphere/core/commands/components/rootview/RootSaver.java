package com.sphere.components.rootview;

import com.sphere.components.rootview.RootScene.Graph;
import com.sphere.components.rootview.RootScene.Graph2D;
import com.sphere.components.rootview.RootScene.Hist;
import com.sphere.components.rootview.RootScene.Item;
import com.sphere.components.rootview.RootScene.Pad;
import com.sphere.core.rootbackend.RootBackend;

import javax.imageio.ImageIO;
import javax.swing.JFileChooser;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;

/**
 * TObject::SaveAs, TPad::SaveAs, TH1::SaveAs and TGraph::SaveAs: a canvas,
 * a pad or one object written by the extension of the file, as ROOT does.
 * Pictures (png, jpg, gif, bmp) and Sphere's scene (.sphere.json) are written
 * by Sphere; a macro (.C, .cxx) is written by RootMacro; a histogram's or a
 * graph's numbers go to .csv, .tsv or .txt as ROOT lays them out; what only
 * ROOT writes (.root, .pdf, .eps, .ps, .svg, .tex, .xml) is written by the
 * engine from the rebuilt canvas.
 */
final class RootSaver {

    private RootSaver() {
    }

    static String save(RootTarget t, String filename, String option) throws IOException {
        String name = filename == null ? "" : filename.strip();
        if (name.isEmpty() || name.equals("hist") || name.equals("graph")) {
            final JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Save " + t.title() + " as");
            chooser.addChoosableFileFilter(new FileNameExtensionFilter("Picture (png, jpg, gif)", "png", "jpg", "jpeg", "gif", "bmp"));
            chooser.addChoosableFileFilter(new FileNameExtensionFilter("ROOT macro (.C)", "C", "cxx"));
            chooser.addChoosableFileFilter(new FileNameExtensionFilter("Numbers (csv, tsv, txt)", "csv", "tsv", "txt"));
            chooser.addChoosableFileFilter(new FileNameExtensionFilter("ROOT (root, pdf, eps, svg, tex)", "root", "pdf", "eps",
                "ps", "svg", "tex", "xml"));
            chooser.setSelectedFile(new java.io.File((name.isEmpty() ? t.name() : name) + ".png"));
            if (chooser.showSaveDialog(t.canvas) != JFileChooser.APPROVE_OPTION) return "not saved";
            name = chooser.getSelectedFile().getPath();
        }
        Path path = Path.of(name);
        if (!path.isAbsolute()) path = Path.of(System.getProperty("user.dir")).resolve(path);
        String ext = extension(path);
        if (ext.isEmpty()) {
            path = path.resolveSibling(path.getFileName() + ".root");
            ext = "root";
        }
        final RootScene scene = sceneOf(t);
        switch (ext) {
            case "png", "jpg", "jpeg", "gif", "bmp" -> {
                ImageIO.write(picture(t, scene), ext.equals("jpg") ? "jpeg" : ext, path.toFile());
                return "saved " + path;
            }
            case "json" -> {
                Files.writeString(path, RootSceneJson.write(scene), StandardCharsets.UTF_8);
                return "saved " + path;
            }
            case "c", "cxx", "cc", "cpp" -> {
                final String fn = path.getFileName().toString().replaceAll("\\.[^.]*$", "").replaceAll("\\W", "_");
                Files.writeString(path, RootMacro.file(scene, scene == t.scene ? t.canvas::view : p -> null,
                    fn.isEmpty() || Character.isDigit(fn.charAt(0)) ? "sphere_" + fn : fn), StandardCharsets.UTF_8);
                return "macro written: " + path;
            }
            case "csv", "tsv", "txt" -> {
                final Item item = t.item();
                if (item == null) throw new IllegalArgumentException("numbers are saved from a histogram or a graph");
                Files.writeString(path, numbers(item, ext), StandardCharsets.UTF_8);
                return "numbers of " + item.name + " written: " + path;
            }
            case "svg", "root", "pdf", "eps", "ps", "tex", "xml" -> {
                return engine(t, scene, path, ext);
            }
            default -> throw new IllegalArgumentException("Sphere does not write ." + ext + " files");
        }
    }

    static String extension(Path p) {
        final String n = p.getFileName().toString();
        final int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** What is saved: the canvas, a pad as a canvas, or one object alone. */
    static RootScene sceneOf(RootTarget t) {
        if (t.object instanceof RootScene s) return s;
        if (t.object instanceof Pad p && p == t.scene.pad) return t.scene;
        return RootActions.clone(t, "");
    }

    static BufferedImage picture(RootTarget t, RootScene scene) {
        final int w = Math.max(100, scene.width);
        final int h = Math.max(80, scene.height);
        if (scene == t.scene) return t.canvas.snapshot(w, h);
        final RootCanvasView view = new RootCanvasView();
        view.setScene(scene);
        return view.snapshot(w, h);
    }

    /** As TH1::SaveAs and TGraph::SaveAs write .csv (commas), .tsv (tabs) and .txt (spaces). */
    static String numbers(Item item, String ext) {
        final String sep = ext.equals("csv") ? "," : ext.equals("tsv") ? "\t" : " ";
        final StringBuilder b = new StringBuilder();
        if (item instanceof Hist h && h.dim == 1) {
            b.append("# BinLowEdge").append(sep).append("BinUpEdge").append(sep).append("BinContent").append(sep).append("ey\n");
            for (int i = 0; i < h.nx(); i++) {
                b.append(num(h.x.edge(i))).append(sep).append(num(h.x.edge(i + 1))).append(sep).append(num(h.at(i, 0)))
                    .append(sep).append(num(h.error(i))).append('\n');
            }
        } else if (item instanceof Hist h && h.dim == 2) {
            b.append("# x").append(sep).append("y").append(sep).append("BinContent").append(sep).append("ey\n");
            for (int iy = 0; iy < h.ny(); iy++) {
                for (int ix = 0; ix < h.nx(); ix++) {
                    final int k = iy * h.nx() + ix;
                    b.append(num(h.x.center(ix))).append(sep).append(num(h.y.center(iy))).append(sep).append(num(h.v[k]))
                        .append(sep).append(num(RootDataActions.err(h, k))).append('\n');
                }
            }
        } else if (item instanceof Graph g) {
            final boolean asym = g.exl != null && g.exh != null && !java.util.Arrays.equals(g.exl, g.exh)
                || g.eyl != null && g.eyh != null && !java.util.Arrays.equals(g.eyl, g.eyh);
            b.append("# fX").append(sep).append("fY");
            if (asym) b.append(sep).append("fEXlow").append(sep).append("fEXhigh").append(sep).append("fEYlow").append(sep).append("fEYhigh");
            else if (g.exl != null || g.eyl != null) b.append(sep).append("fEX").append(sep).append("fEY");
            b.append('\n');
            for (int i = 0; i < g.x.length; i++) {
                b.append(num(g.x[i])).append(sep).append(num(g.y[i]));
                if (asym) {
                    b.append(sep).append(num(g.exl == null ? 0 : g.exl[i])).append(sep).append(num(g.exh == null ? 0 : g.exh[i]))
                        .append(sep).append(num(g.eyl == null ? 0 : g.eyl[i])).append(sep).append(num(g.eyh == null ? 0 : g.eyh[i]));
                } else if (g.exl != null || g.eyl != null) {
                    b.append(sep).append(num(g.exl == null ? 0 : g.exl[i])).append(sep).append(num(g.eyl == null ? 0 : g.eyl[i]));
                }
                b.append('\n');
            }
        } else if (item instanceof Graph2D g) {
            b.append("# fX").append(sep).append("fY").append(sep).append("fZ\n");
            for (int i = 0; i < g.x.length; i++) b.append(num(g.x[i])).append(sep).append(num(g.y[i])).append(sep).append(num(g.z[i])).append('\n');
        } else {
            throw new IllegalArgumentException(item.className + " has no numbers to save");
        }
        return b.toString();
    }

    private static String num(double v) {
        return String.format(Locale.ROOT, "%.10g", v).replaceAll("\\.?0+(e|$)", "$1");
    }

    /** What ROOT itself writes, by the engine; without it, an SVG holds the picture. */
    static String engine(RootTarget t, RootScene scene, Path path, String ext) throws IOException {
        final RootBackend engine = RootBackend.getInstance();
        if (engine == null || !engine.isAvailable()) {
            if (ext.equals("svg")) {
                final ByteArrayOutputStream png = new ByteArrayOutputStream();
                final BufferedImage img = picture(t, scene);
                ImageIO.write(img, "png", png);
                Files.writeString(path, "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" width=\""
                    + img.getWidth() + "\" height=\"" + img.getHeight() + "\">\n<image width=\"" + img.getWidth() + "\" height=\""
                    + img.getHeight() + "\" xlink:href=\"data:image/png;base64," + Base64.getEncoder().encodeToString(png.toByteArray())
                    + "\"/>\n</svg>\n", StandardCharsets.UTF_8);
                return "saved " + path + " (a picture inside an SVG: ROOT's engine writes vector SVG)";
            }
            throw new IllegalStateException("." + ext + " is written by ROOT's engine, which is not running");
        }
        final RootMacro.Result macro = RootMacro.canvas(scene, scene == t.scene ? t.canvas::view : p -> null,
            "sphere_save_" + Long.toHexString(System.nanoTime()));
        final String file = path.toString().replace('\\', '/');
        final boolean object = scene != t.scene && ext.equals("root") && t.item() != null;
        final String saver = object ? macro.names().get(scene.pad.items.get(0)) : macro.canvas();
        final String code = "{\nBool_t sphere_batch = gROOT->IsBatch();\ngROOT->SetBatch(kTRUE);\n" + macro.code()
            + (saver == null ? macro.canvas() : saver) + "->SaveAs(" + RootMacro.q(file) + ");\n"
            + "gROOT->SetBatch(sphere_batch);\ndelete c_sphere;\n}\n";
        final RootHost host = t.canvas.host();
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                return engine.executeClingAwait(code, 120_000L);
            }

            @Override
            protected void done() {
                String answer;
                try {
                    answer = get();
                } catch (Exception e) {
                    answer = e.getMessage();
                }
                host.status(Files.exists(path) ? "ROOT wrote " + path : "ROOT did not write " + path
                    + (answer == null ? "" : ": " + answer.strip()));
            }
        }.execute();
        return "ROOT is writing " + path;
    }
}
