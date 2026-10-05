package com.sphere.components.rootview;

import com.sphere.components.rootview.RootPadPainter.AxisRef;
import com.sphere.components.rootview.RootPadPainter.PaletteRef;
import com.sphere.components.rootview.RootPadPainter.StatsRef;
import com.sphere.components.rootview.RootPadPainter.TitleRef;
import com.sphere.components.rootview.RootScene.Item;
import com.sphere.components.rootview.RootScene.Pad;
import com.sphere.core.rootbackend.RootBackend;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A function of a context menu that Sphere does not do itself, done by ROOT:
 * the canvas is rebuilt in the engine from its numbers (RootMacro), the
 * function is called on the object the user pointed at, with the arguments
 * of the dialog, and the canvas comes back as Sphere draws it, with what the
 * function returned in a canvas of its own. Without the engine, the macro
 * that does it in ROOT is shown, to copy or to save.
 */
final class RootEngineCall {

    private static final String CANVAS = "@@SPHERE-CANVAS@@";
    private static final String RESULT = "@@SPHERE-RESULT@@";
    private static final String END = "@@SPHERE-END@@";
    private static final String VALUE = "@@SPHERE-VALUE@@";
    private static final long TIMEOUT_MS = 120_000L;

    private RootEngineCall() {
    }

    static void offer(RootTarget t, RootMethod m, Component parent, RootHost host) {
        Object[] args;
        if (m.params.isEmpty()) {
            args = new Object[0];
        } else if (m.toggle) {
            args = new Object[]{Boolean.TRUE};
        } else {
            final List<RootArgsDialog.Choice> objects = new ArrayList<>();
            for (RootMethod.Param p : m.params) {
                if (p.kind() == RootMethod.Kind.OBJECT) {
                    final String base = p.type().replace("const", "").replace("*", "").replace("&", "").strip();
                    objects.addAll(t.canvas.objectsOf(base));
                    break;
                }
            }
            args = RootArgsDialog.ask(parent, t, m, null, Map.of(), objects, null);
            if (args == null) return;
        }
        final Built built = build(t, m, args, "sphere_" + Long.toHexString(System.nanoTime()));
        final String call = built.call();
        final String target = built.target();
        final StringBuilder code = new StringBuilder(built.code());
        final RootBackend engine = RootBackend.getInstance();
        if (engine == null || !engine.isAvailable() || call == null) {
            showCode(parent, t, m, code.toString(), engine == null || !engine.isAvailable()
                ? "ROOT's engine is not running: this macro does " + m.owner + "::" + m.name + " in ROOT."
                : "Sphere cannot rebuild " + t.title() + " in ROOT's engine.");
            return;
        }
        if (host != null) host.status("ROOT: " + call.replace("((" + m.owner + " *)(" + target + "))->", t.name() + "->") + " ...");
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                final Path header = com.sphere.core.bridge.Bridge.libraries().resolve("sphere_view.hpp");
                engine.executeClingAwait("#include \"" + header.toString().replace('\\', '/') + "\"", 30_000L);
                return engine.executeClingAwait(code.toString(), TIMEOUT_MS);
            }

            @Override
            protected void done() {
                String answer;
                try {
                    answer = get();
                } catch (Exception e) {
                    answer = "ERROR: " + e.getMessage();
                }
                finish(t, m, parent, host, code.toString(), answer);
            }
        }.execute();
    }

    /**
     * A call of a function on an object the engine holds (an object of a
     * file, a canvas of the session), its arrays declared before it, in a
     * block that keeps the session in batch while it runs.
     */
    static String callOn(String object, RootMethod m, Object[] args) {
        final RootMacro.Result none = new RootMacro.Result("", Map.of(), "");
        final StringBuilder decl = new StringBuilder();
        final StringBuilder list = new StringBuilder();
        for (int k = 0; k < m.params.size(); k++) {
            final String a = argument(m.params.get(k), args != null && k < args.length ? args[k] : null, none, decl, k);
            list.append(list.length() == 0 ? "" : ", ").append(a);
        }
        return "{\nBool_t sphere_batch = gROOT->IsBatch();\ngROOT->SetBatch(kTRUE);\n" + decl
            + "if (TObject *sphere_object = " + object + ") ((" + m.owner + " *)sphere_object)->" + m.name + "(" + list + ");\n"
            + "else std::cout << \"Sphere: the object is not found by ROOT\" << std::endl;\n"
            + "gROOT->SetBatch(sphere_batch);\n}\n";
    }

    /** The code sent to the engine, the call it makes, and the C++ expression of the target. */
    record Built(String code, String call, String target) {
    }

    /** Rebuilds the canvas, calls the function on the target with the arguments, and prints the canvas back. */
    static Built build(RootTarget t, RootMethod m, Object[] args, String engineName) {
        final RootMacro.Result macro = RootMacro.canvas(t.scene, t.canvas::view, engineName);
        final String target = target(t, macro);
        final StringBuilder code = new StringBuilder("{\n");
        code.append("Bool_t sphere_batch = gROOT->IsBatch();\ngROOT->SetBatch(kTRUE);\n");
        code.append(macro.code());
        String call = null;
        if (target == null) {
            code.append("/* ").append(t.title()).append(" is not rebuilt by Sphere: call ").append(m.name)
                .append(" on it in your own session */\n");
        } else {
            final StringBuilder argList = new StringBuilder();
            for (int k = 0; k < m.params.size(); k++) {
                final String a = argument(m.params.get(k), args != null && k < args.length ? args[k] : null, macro, code, k);
                if (a == null) break;
                argList.append(argList.length() == 0 ? "" : ", ").append(a);
            }
            call = "((" + m.owner + " *)(" + target + "))->" + m.name + "(" + argList + ")";
            // A generic lambda is a template: only the branch that fits what the function returns is compiled.
            code.append("auto sphere_invoke = [](auto sphere_call) -> TObject * {\n");
            code.append("   using sphere_type = decltype(sphere_call());\n");
            code.append("   if constexpr (std::is_void<sphere_type>::value) { sphere_call(); return nullptr; }\n");
            code.append("   else if constexpr (std::is_pointer<sphere_type>::value && std::is_convertible<sphere_type, TObject *>::value)"
                + " { return sphere_call(); }\n");
            code.append("   else if constexpr (std::is_arithmetic<sphere_type>::value) { auto sphere_value = sphere_call(); std::cout << \"")
                .append(VALUE).append("\" << sphere_value << \"").append(END).append("\" << std::endl; return nullptr; }\n");
            code.append("   else { sphere_call(); return nullptr; }\n};\n");
            code.append("TObject *sphere_result = sphere_invoke([&]() { return ").append(call).append("; });\n");
            code.append("for (auto *sphere_pad : *c_sphere->GetListOfPrimitives()) if (auto *p = dynamic_cast<TPad *>(sphere_pad)) p->Modified();\n");
            code.append("c_sphere->Modified();\nc_sphere->Update();\n");
            code.append("std::cout << \"\\n").append(CANVAS).append("\" << SphereView::Canvas(c_sphere) << \"").append(END)
                .append("\" << std::endl;\n");
            code.append("if (sphere_result != nullptr && (void *)sphere_result != (void *)(").append(target).append(")) std::cout << \"")
                .append(RESULT).append("\" << SphereView::Single(sphere_result, sphere_result->GetOption()) << \"").append(END)
                .append("\" << std::endl;\n");
        }
        code.append("gROOT->SetBatch(sphere_batch);\ndelete c_sphere;\n}\n");
        return new Built(code.toString(), call, target);
    }

    private static void finish(RootTarget t, RootMethod m, Component parent, RootHost host, String code, String answer) {
        final String canvas = between(answer, CANVAS);
        if (canvas == null) {
            showCode(parent, t, m, code, "ROOT did not give the canvas back:\n" + (answer == null ? "(no answer)" : answer.strip()));
            if (host != null) host.status(m.owner + "::" + m.name + " failed in ROOT");
            return;
        }
        final RootScene scene = RootScene.parse(canvas);
        if (scene.error != null) {
            showCode(parent, t, m, code, scene.error);
            return;
        }
        scene.name = t.scene.name;
        scene.title = t.scene.title;
        t.canvas.checkpoint();
        t.canvas.replaceScene(scene);
        final String result = between(answer, RESULT);
        if (result != null && host != null) {
            final RootScene returned = RootScene.parse(result);
            if (returned.error == null && !returned.pad.items.isEmpty()) {
                host.open(returned, returned.pad.items.get(0).name + " (" + m.name + ")");
            }
        }
        final String value = between(answer, VALUE);
        final String printed = answer.replaceAll("(?s)" + CANVAS + ".*?" + END, "").replaceAll("(?s)" + RESULT + ".*?" + END, "")
            .replaceAll("(?s)" + VALUE + ".*?" + END, "").strip();
        if (!printed.isEmpty() && !printed.equals("(int) 0") && host != null) host.show(t.title() + " — " + m.name, printed);
        if (host != null) {
            host.status(m.owner + "::" + m.name + (value == null ? " done by ROOT" : " = " + value.strip() + " (ROOT)"));
        }
    }

    private static String between(String answer, String start) {
        if (answer == null) return null;
        final int a = answer.indexOf(start);
        if (a < 0) return null;
        final int b = answer.indexOf(END, a);
        return b < 0 ? null : answer.substring(a + start.length(), b);
    }

    /** The C++ expression of the object the user pointed at, in the rebuilt canvas; null when it is not rebuilt. */
    static String target(RootTarget t, RootMacro.Result macro) {
        final Map<Object, String> names = macro.names();
        final Object o = t.object;
        if (o instanceof RootScene) return macro.canvas();
        if (o instanceof Pad p) return names.get(p);
        if (o instanceof AxisRef a) {
            final String owner = a.owner() == null ? null : names.get(a.owner());
            return owner == null ? null : owner + "->Get" + "XYZ".charAt(a.axis()) + "axis()";
        }
        if (o instanceof StatsRef s) {
            final String h = names.get(s.hist());
            return h == null ? null : "((TPaveStats *)" + h + "->FindObject(\"stats\"))";
        }
        if (o instanceof PaletteRef p) {
            final String h = names.get(p.hist());
            return h == null ? null : "((TPaletteAxis *)" + h + "->GetListOfFunctions()->FindObject(\"palette\"))";
        }
        if (o instanceof TitleRef r) {
            final String pad = names.get(r.pad());
            return pad == null ? null : "((TPaveText *)" + pad + "->GetPrimitive(\"title\"))";
        }
        if (o instanceof Item i) return names.get(i);
        return null;
    }

    /** An argument as C++: a literal, an array declared before the call, or the variable of an object. */
    private static String argument(RootMethod.Param p, Object value, RootMacro.Result macro, StringBuilder code, int k) {
        final String type = p.type().replace("const", "").strip();
        return switch (p.kind()) {
            case BOOL -> Boolean.TRUE.equals(value) || value instanceof Number n && n.doubleValue() != 0 ? "kTRUE" : "kFALSE";
            case INT -> value instanceof Number n ? Long.toString(n.longValue()) : value == null ? defaultOr(p, "0")
                : String.valueOf(value);
            case REAL -> value instanceof Number n ? RootMacro.n(n.doubleValue()) : value == null ? defaultOr(p, "0")
                : String.valueOf(value);
            case COLOR -> value instanceof Color c ? RootMacro.color(c) : value instanceof Number n ? Long.toString(n.longValue())
                : defaultOr(p, "1");
            case FONT -> value instanceof Number n ? Long.toString(n.longValue()) : defaultOr(p, "42");
            case ARRAY -> {
                if (!(value instanceof double[] a) || a.length == 0) yield "nullptr";
                final String name = "sphere_arg" + k;
                code.append("Double_t ").append(name).append("[] = ").append(RootMacro.array(a)).append(";\n");
                yield name;
            }
            case OBJECT -> {
                final String v = value == null ? null : macro.names().get(value);
                yield v == null ? "nullptr" : "(" + type + ")" + v;
            }
            default -> RootMacro.q(value == null ? p.defaultText() : String.valueOf(value));
        };
    }

    private static String defaultOr(RootMethod.Param p, String fallback) {
        final String d = p.defaultText();
        return d.isEmpty() ? fallback : d.equals("true") ? "kTRUE" : d.equals("false") ? "kFALSE" : d;
    }

    /** The macro, to copy or save, with why it is shown. */
    static void showCode(Component parent, RootTarget t, RootMethod m, String code, String why) {
        final JDialog d = new JDialog(SwingUtilities.getWindowAncestor(parent), t.title() + " — " + m.name,
            java.awt.Dialog.ModalityType.MODELESS);
        final JTextArea area = new JTextArea(code, 28, 96);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setEditable(false);
        area.setCaretPosition(0);
        final JLabel head = new JLabel("<html>" + why.replace("&", "&amp;").replace("<", "&lt;").replace("\n", "<br>") + "</html>");
        head.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        final JButton copy = new JButton("Copy");
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(code), null));
        final JButton save = new JButton("Save as .C...");
        save.addActionListener(e -> {
            final JFileChooser chooser = new JFileChooser();
            chooser.setSelectedFile(new java.io.File(m.name.toLowerCase(Locale.ROOT) + "_" + t.name().replaceAll("\\W", "_") + ".C"));
            if (chooser.showSaveDialog(d) == JFileChooser.APPROVE_OPTION) {
                try {
                    Files.writeString(chooser.getSelectedFile().toPath(), code, StandardCharsets.UTF_8);
                } catch (java.io.IOException failed) {
                    head.setText(failed.getMessage());
                }
            }
        });
        final JButton close = new JButton("Close");
        close.addActionListener(e -> d.dispose());
        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        buttons.add(copy);
        buttons.add(save);
        buttons.add(close);
        d.getContentPane().setLayout(new BorderLayout());
        d.getContentPane().add(head, BorderLayout.NORTH);
        d.getContentPane().add(new JScrollPane(area), BorderLayout.CENTER);
        d.getContentPane().add(buttons, BorderLayout.SOUTH);
        d.setMinimumSize(new Dimension(560, 360));
        d.pack();
        d.setLocationRelativeTo(parent);
        d.setVisible(true);
    }
}
