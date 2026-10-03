package com.sphere.core.commands;

import com.sphere.core.rootbackend.RootAclic;
import com.sphere.utils.AppLogger;
import com.sphere.utils.WebLinks;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The ROOT prompt's own commands, typed in ROOT mode or after ':root':
 * .x, .L, .ls, .pwd, .help, .demo, .q and the others root.exe and cling answer.
 *
 * Most go to the engine as they are, where TApplication runs them as root.exe
 * would. A few mean something else inside Sphere and are answered here:
 * .q leaves ROOT mode, since the engine and every object it holds stay;
 * .demo opens ROOT's demos, whose pictures land in the Plots tab;
 * .help on a class, .gh and .forum open the page in the browser;
 * a macro named from the console's folder is found there, where the engine,
 * running in a folder of its own, would not look.
 */
final class RootPrompt {

    /** A macro may compile for minutes (ACLiC) or run for longer; root.exe waits too. */
    private static final long RUN_TIMEOUT_MS = 30L * 60 * 1000;

    /** Anything else answers at once or is stuck. */
    private static final long COMMAND_TIMEOUT_MS = 60_000L;

    private static final Pattern URL = Pattern.compile("https?://[^\\s\"']+");

    private RootPrompt() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /** A line for the prompt itself rather than C++: ".x hsimple.C" is, ".5 * 2" is not. */
    static boolean isPromptCommand(String line) {
        final String t = line == null ? "" : line.strip();
        return t.length() > 1 && t.charAt(0) == '.' && !Character.isDigit(t.charAt(1));
    }

    static void run(String line, CommandExecutionContext c) {
        String text = line.strip();
        while (text.endsWith(";")) text = text.substring(0, text.length() - 1).strip();
        final int space = text.indexOf(' ');
        final String word = space < 0 ? text : text.substring(0, space);
        final String rest = space < 0 ? "" : text.substring(space + 1).strip();
        final String lower = word.toLowerCase(Locale.ROOT);

        if (lower.startsWith(".q") || lower.startsWith(".exi")) {
            quit(c);
            return;
        }
        if (lower.equals(".demo")) {
            // ".demo" alone is root.exe's; ".demo hsimple" and ".demo all" are ':root demo''s.
            RootDemoCommands.demo(":root demo " + rest, c);
            return;
        }
        if (word.equals(".R")) {
            AppLogger.error("Remote ROOT sessions (.R) are not available from Sphere's engine; "
                + "a remote ROOT runs through '::root' in a terminal on that host.");
            return;
        }
        if ((word.equals(".help") || word.equals(".?")) && rest.isEmpty()) {
            help(c);
            return;
        }
        if (word.equals(".x") || word.equals(".X") || word.equals(".xk") || word.equals(".Xk")
                || word.equals(".L") || word.equals(".U") || word.equals(".which")) {
            engine(word + " " + local(rest), c, word.equalsIgnoreCase(".which") ? COMMAND_TIMEOUT_MS : RUN_TIMEOUT_MS);
            return;
        }
        engine(text, c, COMMAND_TIMEOUT_MS);
    }

    /** ROOT's .q: here it leaves ROOT mode, and the engine keeps what it holds. */
    private static void quit(CommandExecutionContext c) {
        if (c != null && c.ctx != null && "root".equals(c.ctx.currentMode)) {
            Handlers.switchMode(c, null, "");
            AppLogger.result("The engine keeps running with every object it holds; ':root mode' comes back to it.");
            return;
        }
        AppLogger.result("Nothing to quit: ROOT runs inside Sphere and keeps its objects. "
            + "In ROOT mode, .q leaves the mode.");
    }

    /** The engine's own .help, then what differs in Sphere. */
    private static void help(CommandExecutionContext c) {
        final String answer = ask(".help", c, COMMAND_TIMEOUT_MS);
        if (answer != null) show(".help", answer);
        AppLogger.raw("");
        AppLogger.raw(" In Sphere");
        AppLogger.raw(" ==============================================================================");
        AppLogger.raw("   .q (.quit, .exit)   : leave ROOT mode; the engine and its objects stay");
        AppLogger.raw("   .demo               : ROOT's demos, their canvases in the Plots tab ('.demo all': every one)");
        AppLogger.raw("   new TBrowser        : Sphere's TBrowser: the demos' canvases live in 3D, pictures, .root files,");
        AppLogger.raw("                         fits and analysis (also ':root browser [files]')");
        AppLogger.raw("   .x / .L <macro>     : a macro is looked for in the console's folder first");
        AppLogger.raw("   canvases            : each one drawn or modified lands in the Plots tab,");
        AppLogger.raw("                         as png, svg, jpg or tiff (':root canvas formats')");
        AppLogger.raw("   .help Class::member : the reference guide opens in your browser");
        AppLogger.raw("   .R                  : not available (no remote session from the engine)");
    }

    /** Sends the line and prints what came back; a page ROOT names is opened. */
    private static void engine(String text, CommandExecutionContext c, long timeout) {
        final String answer = ask(text, c, timeout);
        if (answer != null) show(text, answer);
    }

    private static String ask(String text, CommandExecutionContext c, long timeout) {
        final com.sphere.core.rootbackend.RootBackend b = Handlers.backend(c);
        if (b == null) {
            return null;
        }
        final String answer = b.executeClingAwait(text, timeout);
        if (answer == null) {
            AppLogger.error("No answer from the engine for " + text + " within " + timeout / 1000 + " s.");
        }
        return answer;
    }

    private static void show(String text, String answer) {
        if (answer.startsWith("ERROR")) {
            AppLogger.error(answer.startsWith("ERROR: ") ? answer.substring(7) : answer.substring(5).strip());
            return;
        }
        final Matcher page = URL.matcher(answer);
        final boolean opensPage = text.startsWith(".help ") || text.startsWith(".? ")
            || text.startsWith(".gh") || text.startsWith(".forum");
        if (opensPage && page.find()) {
            final String url = page.group();
            AppLogger.result(url);
            if (!WebLinks.open(url)) {
                AppLogger.warn("No browser could be opened from here; the address is above.");
            }
            return;
        }
        if (!"OK".equals(answer)) {
            AppLogger.result(answer);
        } else if (text.startsWith(".L ")) {
            AppLogger.result(text.substring(3).strip() + " loaded.");
        } else if (text.startsWith(".U ")) {
            AppLogger.result(text.substring(3).strip() + " unloaded.");
        }
    }

    /**
     * A macro named relative to the console's folder, made absolute when it is
     * there: the engine runs in a folder of its own and would not find it.
     * The ACLiC flags (macro.C+, ++g) and the arguments (macro.C(3, "a")) are
     * kept as they were. A name found nowhere here is left to ROOT's macro path.
     */
    static String local(String rest) {
        if (rest.isEmpty()) return rest;
        final int open = rest.indexOf('(');
        final String head = open < 0 ? rest : rest.substring(0, open).strip();
        final String arguments = open < 0 ? "" : rest.substring(open);
        final String[] parts = RootAclic.split(head);
        final java.io.File file = Handlers.resolve(parts[0]);
        if (new java.io.File(parts[0]).isAbsolute() || !file.isFile()) return rest;
        return file.getAbsolutePath().replace('\\', '/') + parts[1] + arguments;
    }
}
