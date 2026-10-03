package com.sphere.core.python.jupyterlab;

import com.sphere.core.python.jupyterlab.JupylabXeditor.Cell;
import com.sphere.core.python.jupyterlab.JupylabXeditor.Output;
import com.sphere.core.python.jupyterlab.kernel.PythonKernelProcess;
import com.sphere.utils.SettingsManager;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns kernel messages into notebook outputs, and notebook cells into kernel
 * requests. Sits between the editor and the process so neither has to know about
 * the other's vocabulary.
 */
public final class NotebookExecutor implements PythonKernelProcess.Listener {

    /** The name the kernel's variables appear under in the Variables tab. */
    private static final String SOURCE = "python";

    /** What the editor has to do when the kernel says something. */
    public interface View {
        void cellStarted(Cell cell);
        void cellOutputsChanged(Cell cell);
        void cellFinished(Cell cell, Integer count, long millis);
        void kernelState(String label, boolean alive);
        void inputRequested(Cell cell, String prompt);
        void completionReady(int start, List<String> matches);
        void variablesReady(List<Map<String, Object>> items);
    }

    private final View view;
    private final PythonKernelProcess kernel;
    private final Map<Integer, Cell> pending = new ConcurrentHashMap<>();
    private final Deque<Cell> queue = new ArrayDeque<>();

    private volatile Cell running;

    public NotebookExecutor(SettingsManager settings, View view) {
        this.view = view;
        this.kernel = new PythonKernelProcess(settings, this);
    }

    public boolean isAlive() {
        return kernel.isRunning();
    }

    public boolean isBusy() {
        return running != null;
    }

    public Cell runningCell() {
        return running;
    }

    /** Starts the interpreter if it is not up yet. Safe to call before every run. */
    public void ensureStarted() throws IOException {
        if (!kernel.isRunning()) {
            view.kernelState("starting", false);
            kernel.start();
            // The namespace is alive from here on, so the Variables tab can ask
            // for it rather than waiting for the notebook to show it.
            com.sphere.components.variables.VariableSources.register(
                new com.sphere.components.variables.VariableSources.Source() {
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
                });
        }
    }

    public void run(Cell cell) {
        if (cell == null || !"code".equals(cell.cell_type)) {
            return;
        }
        try {
            ensureStarted();
        } catch (IOException ex) {
            reportStartFailure(cell, ex);
            return;
        }
        synchronized (queue) {
            if (running != null) {
                // One cell at a time, like any notebook: a queued cell waits its turn
                // instead of racing the one already using the namespace.
                queue.addLast(cell);
                return;
            }
        }
        dispatch(cell);
    }

    /** Runs the given cells in order, each waiting for the previous one. */
    public void runAll(List<Cell> cells) {
        List<Cell> code = new ArrayList<>();
        for (Cell cell : cells) {
            if ("code".equals(cell.cell_type)) {
                code.add(cell);
            }
        }
        if (code.isEmpty()) {
            return;
        }
        try {
            ensureStarted();
        } catch (IOException ex) {
            reportStartFailure(code.get(0), ex);
            return;
        }
        synchronized (queue) {
            queue.addAll(code);
        }
        pumpQueue();
    }

    private void dispatch(Cell cell) {
        running = cell;
        cell.outputs.clear();
        // The next cell of a Run All is dispatched from the reader thread, so this
        // has to reach Swing the same way every other notification does.
        onEdt(() -> view.cellStarted(cell));
        int id = kernel.execute(sourceOf(cell));
        pending.put(id, cell);
    }

    private void pumpQueue() {
        Cell next;
        synchronized (queue) {
            if (running != null) {
                return;
            }
            next = queue.pollFirst();
        }
        if (next != null) {
            dispatch(next);
        }
    }

    /** Empties the queue and asks the running cell to stop. */
    public void interrupt() {
        synchronized (queue) {
            queue.clear();
        }
        kernel.interrupt();
    }

    public void restart() throws IOException {
        synchronized (queue) {
            queue.clear();
        }
        pending.clear();
        running = null;
        kernel.restart();
    }

    public void shutdown() {
        synchronized (queue) {
            queue.clear();
        }
        pending.clear();
        running = null;
        kernel.shutdown();
    }

    public void requestCompletion(String code, int cursor) {
        if (kernel.isRunning()) {
            kernel.complete(code, cursor);
        }
    }

    public void requestVariables() {
        if (kernel.isRunning()) {
            kernel.variables();
        }
    }

    public void answerInput(String text) {
        kernel.answerInput(text);
    }

    private void reportStartFailure(Cell cell, IOException ex) {
        cell.outputs.clear();
        Output error = new Output();
        error.output_type = "error";
        error.ename = "KernelError";
        error.evalue = ex.getMessage();
        cell.outputs.add(error);
        onEdt(() -> {
            view.cellOutputsChanged(cell);
            view.kernelState("unavailable", false);
        });
    }

    static String sourceOf(Cell cell) {
        return NotebookIO.joinLines(cell.source);
    }

    // ---- Kernel messages ---------------------------------------------------

    @Override
    public void onReady(String pythonVersion, String executable) {
        onEdt(() -> view.kernelState("Python " + pythonVersion, true));
    }

    @Override
    public void onStream(int execId, boolean stderr, String text) {
        Cell cell = pending.get(execId);
        if (cell == null) {
            return;
        }
        String channel = stderr ? "stderr" : "stdout";
        // Consecutive writes on the same channel belong to one output, the way
        // Jupyter stores them; one output per chunk would bloat the file.
        Output last = cell.outputs.isEmpty() ? null : cell.outputs.get(cell.outputs.size() - 1);
        if (last != null && "stream".equals(last.output_type) && channel.equals(last.name)) {
            last.text = NotebookIO.joinLines(last.text) + text;
        } else {
            Output out = new Output();
            out.output_type = "stream";
            out.name = channel;
            out.text = text;
            cell.outputs.add(out);
        }
        onEdt(() -> view.cellOutputsChanged(cell));
    }

    @Override
    public void onDisplay(int execId, Map<String, Object> bundle) {
        addBundle(execId, "display_data", bundle, null);
    }

    @Override
    public void onResult(int execId, int count, Map<String, Object> bundle) {
        addBundle(execId, "execute_result", bundle, count);
    }

    private void addBundle(int execId, String type, Map<String, Object> bundle, Integer count) {
        Cell cell = pending.get(execId);
        if (cell == null) {
            return;
        }
        Output out = new Output();
        out.output_type = type;
        out.data = new LinkedHashMap<>(bundle);
        out.execution_count = count;
        cell.outputs.add(out);
        onEdt(() -> view.cellOutputsChanged(cell));
        toPlots(execId, bundle);
    }

    /**
     * A figure also goes to the Plots tab, beside the cell that drew it: the
     * tab is where every picture Sphere produces is kept and opened in the
     * image editor, and a notebook's figure should be no exception. Named after
     * the run, so running the cell again replaces rather than adds.
     */
    private void toPlots(int execId, Map<String, Object> bundle) {
        final Object png = bundle.get("image/png");
        if (png == null) {
            return;
        }
        try {
            final byte[] bytes = java.util.Base64.getMimeDecoder().decode(String.valueOf(png));
            final int figure = figuresOf.merge(execId, 1, Integer::sum);
            com.sphere.components.rootview.RootPlotsPanel.showEncoded(bytes,
                "notebook_run" + execId + "_fig" + figure);
        } catch (IllegalArgumentException undecodable) {
            // The cell shows what it can; the tab just goes without.
        }
    }

    /** Figures sent so far, per run, to name each one. */
    private final Map<Integer, Integer> figuresOf = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void onError(int execId, String ename, String evalue, List<String> traceback) {
        Cell cell = pending.get(execId);
        if (cell == null) {
            return;
        }
        Output out = new Output();
        out.output_type = "error";
        out.ename = ename;
        out.evalue = evalue;
        out.traceback = new ArrayList<>(traceback);
        cell.outputs.add(out);
        onEdt(() -> view.cellOutputsChanged(cell));
    }

    @Override
    public void onDone(int execId, Integer count, long millis) {
        Cell cell = pending.remove(execId);
        if (cell == null) {
            return;
        }
        cell.execution_count = count;
        running = null;
        onEdt(() -> view.cellFinished(cell, count, millis));
        // The inspector follows every run, so it always shows the namespace as it
        // stands rather than as it was when the panel was last opened.
        requestVariables();
        pumpQueue();
    }

    @Override
    public void onInputRequest(int execId, String prompt) {
        Cell cell = pending.get(execId);
        onEdt(() -> view.inputRequested(cell, prompt));
    }

    @Override
    public void onCompletion(int execId, int start, List<String> matches) {
        onEdt(() -> view.completionReady(start, matches));
    }

    @Override
    public void onVariables(int execId, List<Map<String, Object>> items) {
        publish(items);
        onEdt(() -> view.variablesReady(items));
    }

    /** Hands the namespace to the Variables tab as well as to the notebook. */
    private void publish(List<Map<String, Object>> items) {
        List<com.sphere.components.variables.VariableStore.Variable> variables =
            new ArrayList<>();
        for (Map<String, Object> item : items) {
            variables.add(new com.sphere.components.variables.VariableStore.Variable(
                SOURCE, text(item.get("name")), text(item.get("type")),
                text(item.get("info"))));
        }
        com.sphere.components.variables.VariableStore.publish(SOURCE, variables);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @Override
    public void onExit(String reason) {
        running = null;
        pending.clear();
        synchronized (queue) {
            queue.clear();
        }
        // The namespace went with the process; leaving its variables on show
        // would be showing values that no longer exist anywhere.
        com.sphere.components.variables.VariableSources.unregister(SOURCE);
        com.sphere.components.variables.VariableStore.clear(SOURCE);
        onEdt(() -> view.kernelState(reason == null ? "stopped" : reason, false));
    }

    private static void onEdt(Runnable task) {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }
}
