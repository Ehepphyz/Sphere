package com.sphere.core.julia;

import com.sphere.utils.AppLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The Julia side of the session. Carried here so it cannot be lost, and written
 * out to config/ so it stays readable and can be adapted.
 *
 * It uses nothing but the standard library: a physics workstation has Julia,
 * it does not necessarily have any given package installed.
 */
public final class JuliaScript {

    /** Bumped whenever the source below changes, so a stale copy is rewritten. */
    public static final int VERSION = 2;

    public static final String FILE_NAME = "sphere_julia.jl";

    /** Marks a line the session must read rather than show. */
    public static final char CONTROL = '';

    private JuliaScript() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * Path of the driver on disk, written or refreshed as needed. The version
     * marker on the first lines is what tells a stale copy from a current one.
     */
    public static Path materialize() throws IOException {
        Path directory = Path.of("config");
        Files.createDirectories(directory);
        Path script = directory.resolve(FILE_NAME);
        if (Files.isReadable(script)) {
            String existing = Files.readString(script, StandardCharsets.UTF_8);
            if (existing.contains("SPHERE_JULIA_VERSION = " + VERSION)) {
                return script;
            }
            AppLogger.info("Julia driver refreshed to version " + VERSION + ".");
        }
        Files.writeString(script, SOURCE, StandardCharsets.UTF_8);
        return script;
    }

    private static final String SOURCE = """
        # SPHERE_JULIA_VERSION = 2
        #
        # Sphere's Julia session. It reads one command per line on its standard
        # input and answers with a single control line, so that everything else it
        # prints is the program's own output.
        #
        # Everything lives in a module of its own, which keeps Main holding only
        # what the user puts there.

        module SphereJulia

        using Base64

        const CONTROL = '\\x01'

        escape_field(text) = replace(string(text), "\\\\" => "\\\\\\\\", "\\t" => "\\\\t",
                                     "\\r" => "", "\\n" => "\\\\n")

        \"\"\"
        Writes what Main is holding where Sphere reads it: one line per variable,
        name, type and value separated by tabs.
        \"\"\"
        function dump_variables()
            folder = get(ENV, "SPHERE_VARS", "variables")
            mkpath(folder)

            lines = ["# name\\ttype\\tvalue"]
            for symbol in sort(names(Main; all = true))
                name = string(symbol)
                (startswith(name, "#") || startswith(name, "_")) && continue
                isdefined(Main, symbol) || continue
                value = getfield(Main, symbol)
                (value isa Module || value isa Function) && continue
                shown = try
                    # Asking for a limited view keeps a million element array from
                    # being rendered in full only to be cut afterwards.
                    sprint(show, value; context = :limit => true)
                catch
                    "<unreadable>"
                end
                if length(shown) > 200
                    # first() counts characters; indexing a String counts bytes and
                    # would split one in two.
                    shown = first(shown, 197) * "..."
                end
                push!(lines, string(escape_field(name), "\\t",
                                    escape_field(typeof(value)), "\\t",
                                    escape_field(shown)))
            end

            open(joinpath(folder, "julia.vars"), "w") do out
                write(out, join(lines, "\\n"), "\\n")
            end
        end

        \"\"\"Runs code in Main, reporting a failure the way Julia would.\"\"\"
        function run_code(code::AbstractString)
            try
                include_string(Main, code, "sphere")
            catch failure
                showerror(stderr, failure, catch_backtrace())
                println(stderr)
            end
        end

        # Runs a file, with the arguments that came with it. The payload is the
        # path on its first line and one argument per line after it. ARGS is what
        # a Julia program reads its arguments from, and a session outlives a run,
        # so it is set for this one and emptied afterwards.
        function run_file(payload::AbstractString)
            pieces = split(payload, '\\n')
            path = String(pieces[1])
            empty!(ARGS)
            append!(ARGS, String.(pieces[2:end]))
            try
                Base.include(Main, path)
            catch failure
                showerror(stderr, failure, catch_backtrace())
                println(stderr)
            finally
                empty!(ARGS)
            end
        end

        function main()
            while !eof(stdin)
                line = readline(stdin)
                isempty(line) && continue

                parts = split(line, ' '; limit = 2)
                operation = parts[1]
                payload = length(parts) > 1 ? String(base64decode(parts[2])) : ""

                if operation == "RUN"
                    run_code(payload)
                elseif operation == "FILE"
                    run_file(payload)
                elseif operation == "QUIT"
                    break
                end

                try
                    dump_variables()
                catch
                end

                print(stdout, CONTROL, "DONE")
                println(stdout)
                flush(stdout)
                flush(stderr)
            end
        end

        end # module

        SphereJulia.main()
        """;
}
