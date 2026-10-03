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
    public static final int VERSION = 4;

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
        # SPHERE_JULIA_VERSION = 4
        #
        # Sphere's Julia session. It reads one command per line on its standard
        # input and answers with a single control line, so that everything else it
        # prints is the program's own output.
        #
        # Everything lives in a module of its own, which keeps Main holding only
        # what the user puts there.

        # The bridge to Sphere's other engines: SphereSPX reads the PDF sets,
        # events and jets Sphere exports, and publishes results back to it. It is
        # a module, so the Variables tab does not list it.
        let lib = get(ENV, "SPHERE_BRIDGE_LIB", "")
            file = joinpath(lib, "SphereSPX.jl")
            if !isempty(lib) && isfile(file)
                try
                    Base.include(Main, file)
                catch failure
                    println(stderr, "SphereSPX could not be loaded: ", failure)
                end
            end
        end

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

        # ---- pictures -------------------------------------------------------
        #
        # What Julia would show as a picture goes to Sphere's Plots tab: a plot
        # passed to display(), or one a run ends on, as the REPL would show it.
        # Written as a PNG into SPHERE_PLOTS, the tab picks it up and the image
        # editor can open it. Without this a plot in the session either opened
        # a GR window of its own or went nowhere.

        struct SphereDisplay <: AbstractDisplay end

        const FIGURES = Ref(0)

        plots_folder() = get(ENV, "SPHERE_PLOTS", joinpath(pwd(), "plots"))

        function publish_picture(x)
            showable(MIME("image/png"), x) || return false
            FIGURES[] += 1
            folder = plots_folder()
            mkpath(folder)
            path = joinpath(folder, "julia_fig$(FIGURES[]).png")
            open(path, "w") do io
                show(io, MIME("image/png"), x)
            end
            println("[plots] ", basename(path))
            return true
        end

        Base.display(::SphereDisplay, x) =
            publish_picture(x) ? nothing : throw(MethodError(display, (SphereDisplay(), x)))
        Base.display(::SphereDisplay, ::MIME"image/png", x) = (publish_picture(x); nothing)

        # On top of the display stack, before every run: Plots.jl and the others
        # push a display of their own when loaded, and theirs opens a window.
        function display_on_top()
            stack = Base.Multimedia.displays
            if isempty(stack) || !(last(stack) isa SphereDisplay)
                filter!(d -> !(d isa SphereDisplay), stack)
                pushdisplay(SphereDisplay())
            end
        end

        # What a run ended on, shown if it is a picture. A trailing semicolon
        # keeps it quiet, as it does in the REPL.
        function show_result(value, code = "")
            (value === nothing || endswith(rstrip(code), ';')) && return
            try
                publish_picture(value)
            catch failure
                println(stderr, "The result could not be drawn: ", sprint(showerror, failure))
            end
        end

        \"\"\"Runs code in Main, reporting a failure the way Julia would.\"\"\"
        function run_code(code::AbstractString)
            display_on_top()
            try
                # invokelatest: this loop started before the user's packages
                # were loaded, and from here their show and showable methods
                # (Plots', Makie's) would not be seen at all.
                value = include_string(Main, code, "sphere")
                Base.invokelatest(show_result, value, code)
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
            display_on_top()
            try
                value = Base.include(Main, path)
                Base.invokelatest(show_result, value)
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
                    # The same reason: a value of a package loaded since the
                    # session started is shown with that package's own show.
                    Base.invokelatest(dump_variables)
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
