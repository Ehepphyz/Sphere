package com.sphere.core.exec;

import com.sphere.components.variables.VariableSources;
import com.sphere.components.variables.VariableStore;
import com.sphere.components.variables.VariablesPanel;
import com.sphere.core.python.jupyterlab.kernel.PythonKernelProcess;
import com.sphere.utils.AppLogger;
import com.sphere.utils.SettingsManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A Python interpreter kept alive for the console's permanent mode.
 *
 * It is the same kernel the notebook uses, driven from the console instead of a
 * cell editor. Holding it open is what makes a variable defined in one :exec
 * still there at the next one, and what lets the Variables tab ask for the
 * namespace rather than read a file left behind.
 */
public final class ConsolePython implements PythonKernelProcess.Listener,
                                            VariableSources.Source {

    public static final String SOURCE = "python";

    private static ConsolePython shared;

    private final PythonKernelProcess kernel;

    private ConsolePython(SettingsManager settings) {
        this.kernel = new PythonKernelProcess(settings, this);
    }

    public static synchronized ConsolePython instance(SettingsManager settings) {
        if (shared == null) {
            shared = new ConsolePython(settings);
        }
        return shared;
    }

    public boolean isRunning() {
        return kernel.isRunning();
    }

    @Override
    public String name() {
        return SOURCE;
    }

    @Override
    public void refresh() {
        if (kernel.isRunning()) {
            kernel.variables();
        }
    }

    // ---- the session --------------------------------------------------------

    public synchronized void start() throws IOException {
        if (kernel.isRunning()) {
            return;
        }
        kernel.start();
        VariableSources.register(this);
    }

    public synchronized void shutdown() {
        if (!kernel.isRunning()) {
            return;
        }
        kernel.shutdown();
        VariableSources.unregister(SOURCE);
        VariableStore.clear(SOURCE);
    }

    /** Wipes the namespace without losing the interpreter. */
    public void reset() {
        if (kernel.isRunning()) {
            kernel.reset();
            VariableStore.clear(SOURCE);
            VariablesPanel.instance().reread();
        }
    }

    public void run(String code) throws IOException {
        if (code == null || code.isBlank()) {
            return;
        }
        start();
        kernel.execute(code);
    }

    // ---- what the kernel says -----------------------------------------------

    @Override
    public void onReady(String pythonVersion, String executable) {
        // Starting is not news; only a failure to start is.
    }

    @Override
    public void onStream(int execId, boolean stderr, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        final String line = text.endsWith("\n")
                          ? text.substring(0, text.length() - 1) : text;
        if (stderr) {
            AppLogger.error(line);
        } else {
            AppLogger.stream(line);
        }
    }

    @Override
    public void onDisplay(int execId, Map<String, Object> bundle) {
        show(execId, bundle);
    }

    @Override
    public void onResult(int execId, int count, Map<String, Object> bundle) {
        show(execId, bundle);
    }

    /** Pictures of this session so far, so each figure gets a name of its own. */
    private int figures;

    /**
     * What the kernel produced: a picture goes to the Plots tab, the text to
     * the console. The kernel sends every matplotlib figure as a PNG once a
     * block has run, and only the text side used to be read here, so a figure
     * drawn in console mode was lost.
     */
    private void show(int execId, Map<String, Object> bundle) {
        if (bundle == null) {
            return;
        }
        final Object png = bundle.get("image/png");
        if (png != null) {
            try {
                final byte[] bytes = java.util.Base64.getMimeDecoder().decode(String.valueOf(png));
                final java.io.File kept = com.sphere.components.rootview.RootPlotsPanel.showEncoded(
                    bytes, "python_fig" + (++figures));
                if (kept != null) {
                    AppLogger.info("Figure shown in the Plots tab (" + kept.getName() + ").");
                }
            } catch (IllegalArgumentException undecodable) {
                AppLogger.error("A figure came back unreadable: " + undecodable.getMessage());
            }
            return;
        }
        Object plain = bundle.get("text/plain");
        if (plain != null && !String.valueOf(plain).isBlank()) {
            AppLogger.stream(String.valueOf(plain).stripTrailing());
        }
    }

    @Override
    public void onError(int execId, String ename, String evalue, List<String> traceback) {
        if (traceback != null && !traceback.isEmpty()) {
            for (String line : traceback) {
                AppLogger.error(line.stripTrailing());
            }
        } else {
            AppLogger.error(ename + ": " + evalue);
        }
    }

    @Override
    public void onDone(int execId, Integer count, long millis) {
        // The block is over, so the namespace is settled and worth reading.
        if (kernel.isRunning()) {
            kernel.variables();
        }
    }

    @Override
    public void onInputRequest(int execId, String prompt) {
        // The console has no place to answer input(), so the call is unblocked
        // rather than left hanging until the kernel is killed.
        AppLogger.error("input() is not available in console mode; it returned empty.");
        kernel.answerInput("");
    }

    @Override
    public void onCompletion(int execId, int start, List<String> matches) {
        // Completion belongs to the editor, not to a run.
    }

    @Override
    public void onVariables(int execId, List<Map<String, Object>> items) {
        List<VariableStore.Variable> variables = new ArrayList<>();
        for (Map<String, Object> item : items) {
            variables.add(new VariableStore.Variable(
                SOURCE, text(item.get("name")), text(item.get("type")),
                text(item.get("info"))));
        }
        VariableStore.publish(SOURCE, variables);
        VariablesPanel.instance().reread();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @Override
    public void onExit(String reason) {
        VariableSources.unregister(SOURCE);
        VariableStore.clear(SOURCE);
    }
}
