package com.sphere.core.commands;

import com.sphere.components.vault.VaultConsole;

/**
 * :vault, the Code Vault from the console: snippets/ and user_scripts/ searched,
 * checked and run with the same index the tab keeps.
 */
public final class VaultCommands {

    private VaultCommands() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    static void register() {
        CommandDefinitions.register(":vault", "The Code Vault: snippets and ROOT macros searched and run. Usage: :vault [open|find|run|info|twins|check|stats|reindex]",
            (i, c) -> VaultConsole.help());
        CommandDefinitions.register(":vault help", "What :vault does", (i, c) -> VaultConsole.help());
        CommandDefinitions.register(":vault open", "Open the Code Vault window, on the best match. Usage: :vault open [query]",
            (i, c) -> VaultConsole.open(Handlers.args(i, ":vault open")));
        CommandDefinitions.register(":vault find", "Search snippets/ and user_scripts/. Usage: :vault find <words #tag lang:py uses:TH1F is:twin>",
            (i, c) -> VaultConsole.find(Handlers.args(i, ":vault find")));
        CommandDefinitions.register(":vault run", "Run the best match with its last values. Usage: :vault run <query> [-- arguments]",
            (i, c) -> VaultConsole.run(Handlers.args(i, ":vault run")));
        CommandDefinitions.register(":vault info", "What the vault read in a snippet or a macro. Usage: :vault info <query>",
            (i, c) -> VaultConsole.info(Handlers.args(i, ":vault info")));
        CommandDefinitions.register(":vault twins", "Files of the vault whose content another one repeats",
            (i, c) -> VaultConsole.twins());
        CommandDefinitions.register(":vault check", "Warnings and errors found in the snippets and macros",
            (i, c) -> VaultConsole.check());
        CommandDefinitions.register(":vault stats", "How many of what, and what is run most",
            (i, c) -> VaultConsole.stats());
        CommandDefinitions.register(":vault reindex", "Read snippets/ and user_scripts/ again",
            (i, c) -> VaultConsole.reindex());
    }
}
