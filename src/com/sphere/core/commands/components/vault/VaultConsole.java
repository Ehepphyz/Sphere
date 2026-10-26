package com.sphere.components.vault;

import com.sphere.components.vault.VaultQuery.Hit;
import com.sphere.utils.AppLogger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * The vault from the console: the same index, search and runner as the tab,
 * so ":vault run gen" runs what the tab's Run would, with the same last values.
 */
public final class VaultConsole {

    private VaultConsole() {
    }

    /** The index, read at least once, for a console that may run before the tab exists. */
    private static VaultIndex ready() {
        final VaultIndex index = VaultIndex.shared();
        index.start();
        try {
            index.rescan().get(10, TimeUnit.SECONDS);
        } catch (Exception slow) {
            AppLogger.warn("The vault is still reading its folders; the list may be short.");
        }
        return index;
    }

    public static void help() {
        AppLogger.raw("  Code Vault: snippets/ and rootbackend/user_scripts/, global and project.");
        AppLogger.raw("    :vault open [query]        the Code Vault window, on the best match");
        AppLogger.raw("    :vault find <query>        search: words, #tag, lang:py, uses:TH1F, fn:x, is:twin|issue|macro");
        AppLogger.raw("    :vault run <query> [-- a]  run the best match, with its last values or with a");
        AppLogger.raw("    :vault info <query>        what the vault read in it, and the line it would send");
        AppLogger.raw("    :vault twins               files whose content another one repeats");
        AppLogger.raw("    :vault check               warnings and errors, with the fix offered");
        AppLogger.raw("    :vault stats               how many of what, and what runs most");
        AppLogger.raw("    :vault reindex             read the folders again");
        AppLogger.raw("  The Code Vault tab lists them; its window has a form for the arguments.");
    }

    /** Opens the Code Vault window, on the best match when a query is given. */
    public static void open(String text) {
        VaultItem item = null;
        if (text != null && !text.isBlank()) {
            item = best(ready(), text);
            if (item == null) return;
        }
        CodeVaultPanel.openDocked(item);
    }

    public static void find(String text) {
        final VaultIndex index = ready();
        final List<Hit> hits = VaultQuery.parse(text).run(index.items(), VaultQuery.Lens.ALL, null,
            VaultQuery.Sort.SMART, index);
        if (hits.isEmpty()) {
            AppLogger.result("Nothing in the vault matches " + text.strip() + ".");
            return;
        }
        for (Hit hit : hits.subList(0, Math.min(hits.size(), 40))) {
            AppLogger.raw(line(hit.item(), index));
        }
        if (hits.size() > 40) AppLogger.raw("  ... and " + (hits.size() - 40) + " more.");
    }

    private static String line(VaultItem item, VaultIndex index) {
        final VaultAnalysis.Severity worst = item.info.worst();
        final String marks = (index.state().pinned(item.key()) ? "*" : "")
            + (index.twinsOf(item).isEmpty() ? "" : " twin")
            + (worst == VaultAnalysis.Severity.ERROR ? " error" : worst == VaultAnalysis.Severity.WARNING ? " warning" : "");
        final String what = item.info.title.isEmpty() ? String.join(", ", item.info.uses) : item.info.title;
        return String.format("  %-5s %-34s %s%s", VaultQuery.family(item),
            (item.kind == VaultItem.Kind.MACRO ? "macro:" : "") + item.relative,
            what.length() > 60 ? what.substring(0, 57) + "..." : what,
            marks.isEmpty() ? "" : "   [" + marks.strip() + "]");
    }

    private static VaultItem best(VaultIndex index, String text) {
        final List<Hit> hits = VaultQuery.parse(text).run(index.items(), VaultQuery.Lens.ALL, null,
            VaultQuery.Sort.SMART, index);
        if (hits.isEmpty()) {
            AppLogger.error("Nothing in the vault matches " + text.strip() + ". :vault find lists what is there.");
            return null;
        }
        return hits.get(0).item();
    }

    /**
     * Runs the best match. What follows "--" goes inside the bracket as it is,
     * in place of the remembered values.
     */
    public static void run(String text) {
        final int dashes = text.indexOf(" -- ");
        final String wanted = dashes >= 0 ? text.substring(0, dashes) : text.endsWith(" --") ? text.substring(0, text.length() - 3) : text;
        final String given = dashes >= 0 ? text.substring(dashes + 4).strip() : null;
        if (wanted.isBlank()) {
            AppLogger.error("Usage: :vault run <query> [-- arguments]");
            return;
        }
        final VaultIndex index = ready();
        final VaultItem item = best(index, wanted);
        if (item == null) return;
        if (!VaultRunner.runnable(item)) {
            AppLogger.error(item.name() + " is not something Sphere runs.");
            return;
        }
        final VaultRunner.Request request = VaultDetail.saved(item, index.state());
        if (given != null) {
            for (int i = 0; i < request.values.size(); i++) request.values.set(i, "");
            request.extra = given;
        }
        final String why = VaultRunner.problem(item, request);
        if (why != null) {
            AppLogger.error(item.name() + ": " + why + ". Give it after --, or fill the form in the Code Vault tab.");
            return;
        }
        final String command = VaultRunner.command(item, request, index.project());
        final com.sphere.core.CommandRouter router = com.sphere.core.CommandRouter.active();
        if (router == null) {
            AppLogger.error("No command router is running.");
            return;
        }
        AppLogger.info(command);
        index.state().used(item.key());
        router.processInput(command);
    }

    public static void info(String text) {
        final VaultIndex index = ready();
        final VaultItem item = best(index, text);
        if (item == null) return;
        final VaultAnalysis info = item.info;
        AppLogger.raw("  " + item.name() + "   " + VaultQuery.family(item) + "   " + item.path);
        if (!info.description.isEmpty()) AppLogger.raw("  " + info.description);
        if (!info.tags.isEmpty()) AppLogger.raw("  tags      #" + String.join("  #", info.tags));
        if (!info.uses.isEmpty()) AppLogger.raw("  uses      " + String.join(", ", info.uses));
        if (!info.functions.isEmpty()) AppLogger.raw("  defines   " + String.join(", ", info.functions));
        for (VaultParam p : info.params) {
            AppLogger.raw(String.format("  %-9s %-18s %-8s %s%s", "param", p.label(),
                p.type().name().toLowerCase(java.util.Locale.ROOT),
                p.defaultValue().isEmpty() ? (p.required() ? "required" : "") : "= " + p.defaultValue(),
                p.help().isEmpty() ? "" : "   " + p.help()));
        }
        for (VaultAnalysis.Issue issue : info.issues) {
            AppLogger.raw("  " + issue.severity().name().toLowerCase(java.util.Locale.ROOT) + "   " + issue.message()
                + (issue.fix() == null ? "" : "   (fix in the tab: " + issue.fix().label() + ")"));
        }
        final List<VaultItem> twins = index.twinsOf(item);
        for (VaultItem twin : twins) AppLogger.raw("  twin      " + twin.kind.folder + "/" + twin.relative);
        final String command = VaultRunner.command(item, VaultDetail.saved(item, index.state()), index.project());
        if (command != null) AppLogger.raw("  runs as   " + command);
    }

    public static void twins() {
        final VaultIndex index = ready();
        final Map<Long, List<VaultItem>> groups = index.twinGroups();
        if (groups.isEmpty()) {
            AppLogger.result("No two files of the vault hold the same content.");
            return;
        }
        for (List<VaultItem> group : groups.values()) {
            AppLogger.raw("  " + group.size() + " files, one content (" + group.get(0).info.lines + " lines):");
            for (VaultItem item : group) AppLogger.raw("      " + item.kind.folder + "/" + item.relative);
        }
    }

    public static void check() {
        final VaultIndex index = ready();
        int shown = 0;
        for (VaultItem item : index.items()) {
            if (item.info.issues.isEmpty()) continue;
            boolean header = false;
            for (VaultAnalysis.Issue issue : item.info.issues) {
                if (issue.severity() == VaultAnalysis.Severity.INFO && issue.fix() == null) continue;
                if (!header) {
                    AppLogger.raw("  " + item.kind.folder + "/" + item.relative);
                    header = true;
                    shown++;
                }
                AppLogger.raw("      " + issue.severity().name().toLowerCase(java.util.Locale.ROOT) + "  "
                    + issue.message() + (issue.fix() == null ? "" : "   -> " + issue.fix().label()));
            }
        }
        if (shown == 0) AppLogger.result("Nothing to report in the vault.");
    }

    public static void stats() {
        final VaultIndex index = ready();
        final VaultIndex.Stats s = index.stats();
        AppLogger.raw("  " + s.snippets() + " snippets, " + s.macros() + " macros, " + s.twins() + " twins, "
            + s.problems() + " with an issue, read in " + s.millis() + " ms");
        final Map<String, Integer> families = new TreeMap<>();
        for (VaultItem item : index.items()) families.merge(VaultQuery.family(item), 1, Integer::sum);
        final StringBuilder by = new StringBuilder("  ");
        families.forEach((f, n) -> by.append(f).append(' ').append(n).append("   "));
        AppLogger.raw(by.toString());
        final List<VaultItem> used = new ArrayList<>();
        for (VaultItem item : index.items()) if (index.state().runs(item.key()) > 0) used.add(item);
        used.sort(Comparator.comparingInt((VaultItem i) -> -index.state().runs(i.key())));
        for (VaultItem item : used.subList(0, Math.min(5, used.size()))) {
            AppLogger.raw(String.format("  %4d runs  %s", index.state().runs(item.key()), item.relative));
        }
        for (VaultIndex.Root root : index.roots()) {
            AppLogger.raw("  " + (root.layer() == VaultItem.Layer.PROJECT ? "project " : "global  ") + root.dir());
        }
    }

    public static void reindex() {
        final long start = System.nanoTime();
        final VaultIndex index = ready();
        AppLogger.result("Vault read again: " + index.stats().items() + " files in "
            + (System.nanoTime() - start) / 1_000_000 + " ms.");
    }
}
