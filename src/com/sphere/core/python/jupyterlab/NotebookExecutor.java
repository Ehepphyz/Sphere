package com.sphere.core.python.jupyterlab;

import com.sphere.core.python.jupyterlab.JupylabXeditor.Cell;
import com.sphere.core.python.jupyterlab.JupylabXeditor.Output;
import com.sphere.core.python.jupyterlab.kernel.JupyterKernelProcess;
import com.sphere.core.python.jupyterlab.kernel.KernelClient;
import com.sphere.core.python.jupyterlab.kernel.KernelSpecs;
import com.sphere.core.python.jupyterlab.kernel.PythonKernelProcess;
import com.sphere.utils.SettingsManager;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Turns kernel messages into notebook outputs, and notebook cells into kernel
 * requests. Sits between the editor and the kernel, whichever kind it is (the
 * stdio driver or a Jupyter kernel), so neither has to know the other's vocabulary.
 *
 * Every change to a cell's outputs happens on the event thread. The former
 * executor appended to a cell's output list from the kernel's reader thread
 * while the event thread was iterating over the same list to draw it.
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

        /** Completion with the kernel's own bounds and the kind of each candidate. */
        default void completionReady(int requestId, int start, int end, List<String> matches, List<String> types) {
            completionReady(start, matches);
        }

        default void inspectionReady(int requestId, boolean found, Map<String, Object> bundle) {
        }

        default void analysisReady(int requestId, List<Map<String, Object>> cells) {
        }

        default void kernelBusy(boolean busy) {
        }

        /** What a value looks like, asked by requestPeek. */
        default void peekReady(int requestId, Map<String, Object> peek) {
        }

        /** The kernel's memory and CPU, asked by requestUsage. */
        default void kernelUsage(Map<String, Object> usage) {
        }

        /** The error a cell stopped on, to point at its line. */
        default void cellFailed(Cell cell, String ename, String evalue, List<String> traceback) {
        }
    }

    private final SettingsManager settings;
    private final View view;
    private final Supplier<Path> workingDirectory;
    private final Map<Integer, Cell> pending = new ConcurrentHashMap<>();
    /** Cells whose run ended, for what a background thread prints afterwards. */
    private final Map<Integer, Cell> recent = java.util.Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, false) {
        private static final long serialVersionUID = 1L;
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Cell> eldest) {
            return size() > 64;
        }
    });
    private final Deque<Cell> queue = new ArrayDeque<>();
    /** Event thread only: clear_output(wait=True) clears when the next output comes. */
    private final Set<Cell> clearOnNext = new HashSet<>();
    private final Map<String, List<Output>> displays = new HashMap<>();
    private final Map<String, Set<Cell>> displayCells = new HashMap<>();
    private final Map<Integer, Integer> figuresOf = new ConcurrentHashMap<>();

    private volatile List<KernelSpecs.Spec> specs;
    private volatile KernelSpecs.Spec spec;
    private volatile KernelClient kernel;
    private volatile Cell running;
    private volatile boolean runAll;

    public NotebookExecutor(SettingsManager settings, View view, Supplier<Path> workingDirectory) {
        this.settings = settings;
        this.view = view;
        this.workingDirectory = workingDirectory;
    }

    public NotebookExecutor(SettingsManager settings, View view) {
        this(settings, view, () -> null);
    }

    // ---- Kernel choice -----------------------------------------------------

    /** The kernels installed on this machine, found once (it reads a few folders). */
    public List<KernelSpecs.Spec> specs() {
        List<KernelSpecs.Spec> s = specs;
        if (s == null) {
            s = KernelSpecs.discover(settings);
            specs = s;
        }
        return s;
    }

    public KernelSpecs.Spec spec() {
        return spec;
    }

    /** Switches kernels; the running one stops, the new one starts on the next run. */
    public synchronized void setSpec(KernelSpecs.Spec next) {
        if (next == null || next.equals(spec)) {
            return;
        }
        KernelClient old = kernel;
        kernel = null;
        spec = next;
        if (old != null) {
            clearRuns();
            old.shutdown();
        }
    }

    private synchronized KernelClient kernel() {
        KernelClient k = kernel;
        if (k == null) {
            if (spec == null) {
                spec = KernelSpecs.choose(specs(), null);
            }
            if (spec == null || spec.isSphereDriver()) {
                PythonKernelProcess driver = new PythonKernelProcess(settings, this);
                driver.setWorkingDirectory(workingDirectory.get());
                k = driver;
            } else {
                k = new JupyterKernelProcess(spec, this, workingDirectory.get());
            }
            kernel = k;
        }
        return k;
    }

    public boolean isAlive() {
        KernelClient k = kernel;
        return k != null && k.isRunning();
    }

    public boolean isBusy() {
        return running != null;
    }

    public Cell runningCell() {
        return running;
    }

    public String describe() {
        KernelClient k = kernel;
        return k == null ? (spec == null ? "" : spec.displayName()) : k.describe();
    }

    /** Starts the interpreter if it is not up yet. Safe to call before every run. */
    public void ensureStarted() throws IOException {
        KernelClient k = kernel();
        if (!k.isRunning()) {
            onEdt(() -> view.kernelState("starting " + (spec == null ? "" : spec.displayName()), false));
            k.start();
            registerVariables();
        }
    }

    /**
     * The namespace is alive from here on, so the Variables tab can ask for it
     * rather than waiting for the notebook to show it.
     */
    private void registerVariables() {
        com.sphere.components.variables.VariableSources.register(
            new com.sphere.components.variables.VariableSources.Source() {
                @Override
                public String name() {
                    return SOURCE;
                }

                @Override
                public void refresh() {
                    requestVariables();
                }
            });
    }

    // ---- Runs --------------------------------------------------------------

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

    /** Runs the given cells in order, each waiting for the previous one; stops at the first error. */
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
            runAll = true;
        }
        pumpQueue();
    }

    private void dispatch(Cell cell) {
        running = cell;
        // The next cell of a Run All is dispatched from the reader thread, so this
        // has to reach Swing the same way every other notification does.
        onEdt(() -> {
            clearOutputs(cell);
            view.cellStarted(cell);
        });
        KernelClient k = kernel();
        // Sent and filed as one step: a quick cell's reply and idle status arrive on two threads, and
        // one could end the run before the cell was filed under its id -- the run never ended then,
        // the cell stayed at [*] and every later run waited behind it.
        synchronized (filing) {
            int id = k.execute(sourceOf(cell));
            pending.put(id, cell);
        }
    }

    /** Held from sending a cell until it is filed under the run's id (dispatch). */
    private final Object filing = new Object();

    private void pumpQueue() {
        Cell next;
        synchronized (queue) {
            if (running != null) {
                return;
            }
            next = queue.pollFirst();
            if (next == null) {
                runAll = false;
            }
        }
        if (next != null) {
            dispatch(next);
        }
    }

    /** Empties the queue and asks the running cell to stop. */
    public void interrupt() {
        synchronized (queue) {
            queue.clear();
            runAll = false;
        }
        KernelClient k = kernel;
        if (k != null) {
            k.interrupt();
        }
    }

    public void restart() throws IOException {
        clearRuns();
        forgetNamespace();
        kernel().restart();
        registerVariables();
    }

    public void shutdown() {
        clearRuns();
        KernelClient k = kernel;
        if (k != null) {
            k.shutdown();
        }
        forgetNamespace();
    }

    /** The variables went with the interpreter; the Variables tab must not keep showing them. */
    private void forgetNamespace() {
        com.sphere.components.variables.VariableSources.unregister(SOURCE);
        com.sphere.components.variables.VariableStore.clear(SOURCE);
    }

    private void clearRuns() {
        synchronized (queue) {
            queue.clear();
            runAll = false;
        }
        pending.clear();
        running = null;
    }

    public int requestCompletion(String code, int cursor) {
        KernelClient k = kernel;
        return k != null && k.isRunning() ? k.complete(code, cursor) : -1;
    }

    public int requestInspection(String code, int cursor) {
        KernelClient k = kernel;
        return k != null && k.isRunning() ? k.inspect(code, cursor) : -1;
    }

    /** Undefined names, order dependencies and live kinds for every code cell, in notebook order. */
    public int requestAnalysis(List<String> cells) {
        KernelClient k = kernel;
        return k != null && k.isRunning() ? k.analyze(cells) : -1;
    }

    public void requestVariables() {
        KernelClient k = kernel;
        if (k != null && k.isRunning()) {
            k.variables();
        }
    }

    /** What a live value looks like; -1 while a cell runs (the kernel answers in turn) or when it cannot say. */
    public int requestPeek(String name) {
        KernelClient k = kernel;
        return k != null && k.isRunning() && running == null ? k.peek(name) : -1;
    }

    /** The kernel's memory and CPU (answered on the control channel, even while a cell runs). */
    public void requestUsage() {
        KernelClient k = kernel;
        if (k != null && k.isRunning()) {
            k.usage();
        }
    }

    /**
     * Takes names out of the namespace, as no cell: a reactive notebook leaves no
     * variable behind that no cell defines any more. False when the kernel cannot.
     */
    public boolean forget(java.util.Collection<String> names) {
        KernelClient k = kernel;
        if (k == null || !k.isRunning() || names == null) {
            return false;
        }
        StringBuilder tuple = new StringBuilder();
        for (String n : names) {
            if (n != null && n.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                tuple.append('\'').append(n).append("', ");
            }
        }
        return tuple.length() > 0
               && k.effect("[globals().pop(_sphere_n, None) for _sphere_n in (" + tuple + ")]") >= 0;
    }

    /** Moves a value to its new name, as a notebook-wide rename does to the code: nothing needs to run again. */
    public boolean renameVariable(String from, String to) {
        KernelClient k = kernel;
        String id = "[A-Za-z_][A-Za-z0-9_]*";
        if (k == null || !k.isRunning() || from == null || to == null || !from.matches(id) || !to.matches(id)) {
            return false;
        }
        return k.effect("if '" + from + "' in globals(): globals()['" + to + "'] = globals().pop('" + from + "')") >= 0;
    }

    public void answerInput(String text) {
        KernelClient k = kernel;
        if (k != null) {
            k.answerInput(text);
        }
    }

    private void reportStartFailure(Cell cell, IOException ex) {
        onEdt(() -> {
            cell.outputs.clear();
            Output error = new Output();
            error.output_type = "error";
            error.ename = "KernelError";
            error.evalue = ex.getMessage();
            cell.outputs.add(error);
            view.cellOutputsChanged(cell);
            view.kernelState("unavailable", false);
        });
    }

    static String sourceOf(Cell cell) {
        return NotebookIO.joinLines(cell.source);
    }

    // ---- Outputs (event thread) --------------------------------------------

    private Cell cellOf(int execId) {
        synchronized (filing) {
            Cell c = pending.get(execId);
            return c != null ? c : recent.get(execId);
        }
    }

    /** Applies a waiting clear_output(wait=True), now that something replaces it. */
    private void beforeOutput(Cell cell) {
        if (clearOnNext.remove(cell)) {
            clearOutputs(cell);
        }
    }

    private void clearOutputs(Cell cell) {
        for (Output o : cell.outputs) {
            if (o.displayId != null) {
                List<Output> list = displays.get(o.displayId);
                if (list != null) {
                    list.remove(o);
                }
            }
        }
        cell.outputs.clear();
    }

    @Override
    public void onReady(String version, String description) {
        onEdt(() -> view.kernelState(describe(), true));
    }

    @Override
    public void onStream(int execId, boolean stderr, String text) {
        onEdt(() -> {
            Cell cell = cellOf(execId);
            if (cell == null) {
                return;
            }
            beforeOutput(cell);
            String channel = stderr ? "stderr" : "stdout";
            // Consecutive writes on the same channel belong to one output, the way
            // Jupyter stores them; a builder keeps a long run linear.
            Output last = cell.outputs.isEmpty() ? null : cell.outputs.get(cell.outputs.size() - 1);
            if (last != null && "stream".equals(last.output_type) && channel.equals(last.name)) {
                if (!(last.text instanceof StringBuilder)) {
                    last.text = new StringBuilder(NotebookIO.joinLines(last.text));
                }
                ((StringBuilder) last.text).append(text);
            } else {
                Output out = new Output();
                out.output_type = "stream";
                out.name = channel;
                out.text = new StringBuilder(text);
                cell.outputs.add(out);
            }
            view.cellOutputsChanged(cell);
        });
    }

    @Override
    public void onDisplay(int execId, Map<String, Object> bundle) {
        onDisplay(execId, bundle, null);
    }

    @Override
    public void onDisplay(int execId, Map<String, Object> bundle, String displayId) {
        addBundle(execId, "display_data", bundle, null, displayId);
    }

    @Override
    public void onUpdateDisplay(String displayId, Map<String, Object> bundle) {
        onEdt(() -> {
            List<Output> targets = displays.get(displayId);
            if (targets == null) {
                return;
            }
            for (Output o : targets) {
                o.data = new LinkedHashMap<>(bundle);
                o.version++;
            }
            for (Cell cell : displayCells.getOrDefault(displayId, Set.of())) {
                view.cellOutputsChanged(cell);
            }
        });
    }

    @Override
    public void onResult(int execId, int count, Map<String, Object> bundle) {
        addBundle(execId, "execute_result", bundle, count, null);
    }

    private void addBundle(int execId, String type, Map<String, Object> bundle, Integer count, String displayId) {
        onEdt(() -> {
            Cell cell = cellOf(execId);
            if (cell == null) {
                return;
            }
            beforeOutput(cell);
            Output out = new Output();
            out.output_type = type;
            out.data = new LinkedHashMap<>(bundle);
            out.execution_count = count;
            out.displayId = displayId;
            if (displayId != null) {
                displays.computeIfAbsent(displayId, k -> new ArrayList<>()).add(out);
                displayCells.computeIfAbsent(displayId, k -> new HashSet<>()).add(cell);
            }
            cell.outputs.add(out);
            view.cellOutputsChanged(cell);
        });
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
            final byte[] bytes = java.util.Base64.getMimeDecoder().decode(NotebookIO.joinLines(png));
            final int figure = figuresOf.merge(execId, 1, Integer::sum);
            com.sphere.components.rootview.RootPlotsPanel.showEncoded(bytes,
                "notebook_run" + execId + "_fig" + figure);
        } catch (IllegalArgumentException undecodable) {
            // The cell shows what it can; the tab just goes without.
        }
    }

    @Override
    public void onError(int execId, String ename, String evalue, List<String> traceback) {
        onEdt(() -> {
            Cell cell = cellOf(execId);
            if (cell == null) {
                return;
            }
            beforeOutput(cell);
            Output out = new Output();
            out.output_type = "error";
            out.ename = ename;
            out.evalue = evalue;
            out.traceback = new ArrayList<>(traceback);
            cell.outputs.add(out);
            view.cellOutputsChanged(cell);
            view.cellFailed(cell, ename, evalue, traceback);
        });
        // Run All stops at the first error, as JupyterLab does: what follows
        // would only fail on what this cell did not define.
        synchronized (queue) {
            if (runAll) {
                queue.clear();
                runAll = false;
            }
        }
    }

    @Override
    public void onClearOutput(int execId, boolean wait) {
        onEdt(() -> {
            Cell cell = cellOf(execId);
            if (cell == null) {
                return;
            }
            if (wait) {
                clearOnNext.add(cell);
            } else {
                clearOutputs(cell);
                view.cellOutputsChanged(cell);
            }
        });
    }

    @Override
    public void onDone(int execId, Integer count, long millis) {
        Cell cell;
        synchronized (filing) {
            cell = pending.remove(execId);
        }
        if (cell == null) {
            return;
        }
        recent.put(execId, cell);
        running = null;
        onEdt(() -> {
            clearOnNext.remove(cell);
            cell.execution_count = count;
            view.cellFinished(cell, count, millis);
        });
        // The inspector follows every run, so it always shows the namespace as it
        // stands rather than as it was when the panel was last opened.
        requestVariables();
        pumpQueue();
    }

    @Override
    public void onInputRequest(int execId, String prompt) {
        Cell cell = cellOf(execId);
        onEdt(() -> view.inputRequested(cell, prompt));
    }

    @Override
    public void onCompletion(int execId, int start, List<String> matches) {
        onEdt(() -> view.completionReady(execId, start, -1, matches, List.of()));
    }

    @Override
    public void onCompletion(int execId, int start, int end, List<String> matches, List<String> types) {
        onEdt(() -> view.completionReady(execId, start, end, matches, types));
    }

    @Override
    public void onInspect(int execId, boolean found, Map<String, Object> bundle) {
        onEdt(() -> view.inspectionReady(execId, found, bundle));
    }

    @Override
    public void onAnalysis(int execId, List<Map<String, Object>> cells) {
        onEdt(() -> view.analysisReady(execId, cells));
    }

    @Override
    public void onPeek(int execId, List<Map<String, Object>> items) {
        Map<String, Object> peek = items.isEmpty() ? Map.of("kind", "error", "text", "no answer") : items.get(0);
        onEdt(() -> view.peekReady(execId, peek));
    }

    @Override
    public void onUsage(int execId, Map<String, Object> usage) {
        onEdt(() -> view.kernelUsage(usage));
    }

    @Override
    public void onStatus(String state) {
        boolean busy = "busy".equals(state);
        onEdt(() -> view.kernelBusy(busy));
    }

    @Override
    public void onVariables(int execId, List<Map<String, Object>> items) {
        publish(items);
        onEdt(() -> view.variablesReady(items));
    }

    /** Hands the namespace to the Variables tab as well as to the notebook. */
    private void publish(List<Map<String, Object>> items) {
        List<com.sphere.components.variables.VariableStore.Variable> variables = new ArrayList<>();
        for (Map<String, Object> item : items) {
            variables.add(new com.sphere.components.variables.VariableStore.Variable(
                SOURCE, text(item.get("name")), text(item.get("type")), text(item.get("info"))));
        }
        com.sphere.components.variables.VariableStore.publish(SOURCE, variables);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @Override
    public void onExit(String reason) {
        clearRuns();
        // The namespace went with the process; leaving its variables on show
        // would be showing values that no longer exist anywhere.
        forgetNamespace();
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
