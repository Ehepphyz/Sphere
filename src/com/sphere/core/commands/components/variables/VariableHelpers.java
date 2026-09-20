package com.sphere.components.variables;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The few lines each language needs in order to be seen in the Variables tab.
 *
 * A program that has ended cannot be asked anything, so it has to say what it
 * held before it goes. These are written into the project on request rather than
 * carried inside Sphere, because they belong to the user's own build: they are
 * compiled with his Fortran, imported by his Python, and he is free to change
 * them.
 */
public final class VariableHelpers {

    /** Language name to the file it is written as. */
    private static final Map<String, String> FILES = new LinkedHashMap<>();

    static {
        FILES.put("python", "sphere_vars.py");
        FILES.put("julia", "sphere_vars.jl");
        FILES.put("cpp", "sphere_vars.h");
        FILES.put("fortran", "sphere_vars.f90");
    }

    private VariableHelpers() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    public static java.util.Set<String> languages() {
        return FILES.keySet();
    }

    /** Writes one language's helper beside the project, and says where it went. */
    public static Path write(Path folder, String language) throws IOException {
        final String key = language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
        final String fileName = FILES.get(key);
        if (fileName == null) {
            throw new IOException("No helper for " + language + ". There is one for "
                                  + String.join(", ", FILES.keySet()) + ".");
        }
        Files.createDirectories(folder);
        Path target = folder.resolve(fileName);
        Files.writeString(target, sourceOf(key), StandardCharsets.UTF_8);
        return target;
    }

    public static String sourceOf(String language) {
        return switch (language) {
            case "python" -> PYTHON;
            case "julia" -> JULIA;
            case "cpp" -> CPP;
            case "fortran" -> FORTRAN;
            default -> "";
        };
    }

    // ---- one per language ---------------------------------------------------

    private static final String PYTHON = """
        \"""Writes the variables Sphere reads: name, type and value, tab separated.\"""

        import os

        FOLDER = "variables"


        def _escape(text):
            return (str(text).replace("\\\\", "\\\\\\\\").replace("\\t", "\\\\t")
                    .replace("\\r", "").replace("\\n", "\\\\n"))


        def dump(namespace=None, source="python", folder=None):
            \"""Writes a namespace, globals() by default, into <folder>/<source>.vars.\"""
            items = globals() if namespace is None else namespace
            target = folder or os.environ.get("SPHERE_VARS") or FOLDER
            os.makedirs(target, exist_ok=True)

            lines = ["# name\\ttype\\tvalue"]
            for name in sorted(items):
                if name.startswith("_"):
                    continue
                value = items[name]
                if callable(value) or type(value).__name__ == "module":
                    continue
                try:
                    text = repr(value)
                except Exception:
                    text = "<unreadable>"
                if len(text) > 200:
                    text = text[:197] + "..."
                lines.append("%s\\t%s\\t%s" % (_escape(name),
                                              _escape(type(value).__name__),
                                              _escape(text)))

            path = os.path.join(target, source + ".vars")
            with open(path, "w", encoding="utf-8") as out:
                out.write("\\n".join(lines) + "\\n")
            return path
        """;

    private static final String JULIA = """
        # Writes the variables Sphere reads: name, type and value, tab separated.

        module SphereVars

        export dump_vars

        escape(text) = replace(string(text), "\\\\" => "\\\\\\\\", "\\t" => "\\\\t",
                               "\\r" => "", "\\n" => "\\\\n")

        \"""
            dump_vars(mod = Main; source = "julia", folder = nothing)

        Writes the module's variables into <folder>/<source>.vars.
        \"""
        function dump_vars(mod::Module = Main; source::AbstractString = "julia",
                           folder = nothing)
            target = folder === nothing ?
                get(ENV, "SPHERE_VARS", "variables") : folder
            mkpath(target)

            lines = ["# name\\ttype\\tvalue"]
            for name in sort(names(mod; all = true))
                text = string(name)
                startswith(text, "#") && continue
                startswith(text, "_") && continue
                isdefined(mod, name) || continue
                value = getfield(mod, name)
                (value isa Module || value isa Function) && continue
                shown = try
                    repr(value)
                catch
                    "<unreadable>"
                end
                length(shown) > 200 && (shown = shown[1:197] * "...")
                push!(lines, string(escape(text), "\\t", escape(typeof(value)), "\\t",
                                    escape(shown)))
            end

            path = joinpath(target, source * ".vars")
            open(path, "w") do out
                write(out, join(lines, "\\n"), "\\n")
            end
            return path
        end

        end # module
        """;

    private static final String CPP = """
        // Writes the variables Sphere reads: name, type and value, tab separated.
        //
        // Header only, so it costs an include and nothing else:
        //   sphere::Vars v; v.add("energy", "double", energy); v.write();

        #ifndef SPHERE_VARS_H
        #define SPHERE_VARS_H

        #include <cstdlib>
        #include <fstream>
        #include <sstream>
        #include <string>
        #include <vector>

        namespace sphere {

        class Vars {
        public:
          explicit Vars(std::string source = "cpp") : source_(std::move(source)) {}

          template <typename T>
          void add(const std::string &name, const std::string &type, const T &value) {
            std::ostringstream text;
            text << value;
            rows_.push_back(escape(name) + "\\t" + escape(type) + "\\t"
                            + escape(text.str()));
          }

          /// Writes them where Sphere reads, and says whether it could.
          bool write(const std::string &folder = "") const {
            const std::string target = folder.empty() ? where() : folder;
            std::ofstream out(target + "/" + source_ + ".vars");
            if (!out) {
              return false;
            }
            out << "# name\\ttype\\tvalue\\n";
            for (const std::string &row : rows_) {
              out << row << "\\n";
            }
            return out.good();
          }

        private:
          static std::string where() {
            const char *set = std::getenv("SPHERE_VARS");
            return (set != nullptr && *set != '\\0') ? set : "variables";
          }

          static std::string escape(const std::string &field) {
            std::string clean;
            clean.reserve(field.size());
            for (char c : field) {
              if (c == '\\\\') {
                clean += "\\\\\\\\";
              } else if (c == '\\t') {
                clean += "\\\\t";
              } else if (c == '\\n') {
                clean += "\\\\n";
              } else if (c != '\\r') {
                clean += c;
              }
            }
            return clean;
          }

          std::string source_;
          std::vector<std::string> rows_;
        };

        } // namespace sphere

        #endif // SPHERE_VARS_H
        """;

    private static final String FORTRAN = """
        ! Writes the variables Sphere reads: name, type and value, tab separated.
        !
        !   call sphere_vars_open('fortran')
        !   call sphere_vars_add('energy', 'real', energy)
        !   call sphere_vars_close()

        module sphere_vars
          implicit none
          private
          public :: sphere_vars_open, sphere_vars_add, sphere_vars_close

          integer, parameter :: unit_number = 77
          character, parameter :: tab = achar(9)

        contains

          subroutine sphere_vars_open(source, folder)
            character(len=*), intent(in) :: source
            character(len=*), intent(in), optional :: folder
            character(len=512) :: target, path
            integer :: length, status

            if (present(folder)) then
              target = folder
            else
              call get_environment_variable('SPHERE_VARS', target, length, status)
              if (status /= 0 .or. len_trim(target) == 0) target = 'variables'
            end if

            call execute_command_line('mkdir -p ' // trim(target), wait=.true.)
            path = trim(target) // '/' // trim(source) // '.vars'
            open(unit=unit_number, file=trim(path), status='replace', action='write')
            write(unit_number, '(a)') '# name' // tab // 'type' // tab // 'value'
          end subroutine sphere_vars_open

          subroutine sphere_vars_add(name, kind_name, value)
            character(len=*), intent(in) :: name, kind_name
            class(*), intent(in) :: value
            character(len=64) :: text

            select type (value)
            type is (integer)
              write(text, '(i0)') value
            type is (real)
              write(text, '(g0)') value
            type is (double precision)
              write(text, '(g0)') value
            type is (logical)
              text = merge('true ', 'false', value)
            type is (character(len=*))
              text = value
            class default
              text = '<unreadable>'
            end select

            write(unit_number, '(a)') trim(name) // tab // trim(kind_name) // tab &
                                      // trim(adjustl(text))
          end subroutine sphere_vars_add

          subroutine sphere_vars_close()
            close(unit_number)
          end subroutine sphere_vars_close

        end module sphere_vars
        """;
}
