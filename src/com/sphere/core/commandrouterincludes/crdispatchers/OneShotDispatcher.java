package com.sphere.core.commandrouterincludes.crdispatchers;

import com.sphere.core.commandrouterincludes.CommandContext;
import com.sphere.core.commandrouterincludes.CommandDispatcher;
import com.sphere.core.commandrouterincludes.CommandParser;
import com.sphere.core.snippets.TagInterpreter;
import com.sphere.utils.AppLogger;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles "one-shot" backend execution macro commands using the '::' prefix
 * by integrating with the core routing parser.
 *
 * A line that names a file runs it, the way the bracket form does:
 * "::py -O run.py 3" is "::py -O [@ run.py] 3". The code used to be rebuilt
 * from the parser's macroTokens, which hold neither the file (filed under
 * filepath) nor the flags, so "::py script.py" ran nothing at all and
 * "::py script.py 3" handed "3" alone to the interpreter.
 */
public class OneShotDispatcher implements CommandDispatcher {

    /** The languages the bracket form runs a file in (CommandRouter.executeHybridAsync). */
    private static final Set<String> RUNS_FILES = Set.of(
        "py", "js", "cpp", "root", "jul", "julia", "fort", "fortran", "f90", "fot");

    /** What a source file of one of those languages ends with. */
    private static final Pattern SOURCE_FILE = Pattern.compile(
        "(?i).+\\.(py|js|mjs|cpp|cxx|cc|c|jl|f|f90|f95|f03|f08|for)(\\+{1,2}[a-z]*)?");

    /** One token as the Tokenizer reads it, with its position in the line. */
    private static final Pattern TOKEN = Pattern.compile("\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"|(\\S+)");

    @Override
    public boolean canHandle(String input) {
        return input != null && input.startsWith("::");
    }

    @Override
    public void handle(String input, CommandContext ctx) {
        // 1. Delegate to the unified routing parser engine
        com.sphere.core.commandrouterincludes.ParsedCommand pc = CommandParser.parse(input);
        if (pc == null) {
            AppLogger.error("Failed to parse one-shot macro input framework: " + input);
            return;
        }

        String lang = pc.languageOrApp;
        String rest = CommandParser.remainderAfterLanguage(input);
        if (lang == null || lang.isBlank()) {
            AppLogger.error("No language after '::': write \"::py <file>\".");
            return;
        }
        if (rest.isEmpty()) {
            AppLogger.error("Nothing to run for '::" + lang + "'.");
            return;
        }

        // 2. A file to run, with what precedes it for the interpreter
        if (RUNS_FILES.contains(lang.toLowerCase(Locale.ROOT)) && ctx.router != null
                && runAsSnippet(lang, rest, ctx)) {
            return;
        }

        // 3. Code for the target language backend, exactly as typed
        if (ctx.backends != null && ctx.backends.containsKey(lang)) {
            String resolvedCode = TagInterpreter.resolve(rest, ctx.getActiveProject());
            ctx.executeBackendSafely(lang, resolvedCode);
        } else {
            AppLogger.error("Unknown or unregistered one-shot backend target identifier: " + lang);
        }
    }

    /**
     * Runs "::lang [flags] file [args]" as "::lang [flags] [@ file] [args]" when
     * its first word that is not a flag names a file. False when it names none,
     * so the line is code for the backend instead.
     */
    private static boolean runAsSnippet(String lang, String rest, CommandContext ctx) {
        Matcher m = TOKEN.matcher(rest);
        while (m.find()) {
            String token = m.group(1) != null ? m.group(1) : m.group(2);
            if (token.startsWith("-")) {
                continue;
            }
            if (!namesFile(token, ctx)) {
                return false;
            }
            String flags = rest.substring(0, m.start()).strip();
            String file = rest.substring(m.start(), m.end());
            String args = rest.substring(m.end()).strip();
            String line = "::" + lang + (flags.isEmpty() ? "" : " " + flags)
                        + " [@ " + file + "]" + (args.isEmpty() ? "" : " " + args);
            ctx.router.executeHybridAsync(TagInterpreter.resolve(line, ctx.getActiveProject()));
            return true;
        }
        return false;
    }

    /** A name that can only be a source file, or a file the snippet folders or the current folder hold. */
    private static boolean namesFile(String token, CommandContext ctx) {
        if (SOURCE_FILE.matcher(token).matches()) {
            return true;
        }
        String name = com.sphere.core.rootbackend.RootAclic.split(token)[0];
        String found = com.sphere.core.snippets.SnippetResolver.resolve(name, ctx.getActiveProject());
        try {
            return found != null && !found.equals(name) && Files.isRegularFile(Path.of(found));
        } catch (InvalidPathException notAPath) {
            return false;
        }
    }
}
