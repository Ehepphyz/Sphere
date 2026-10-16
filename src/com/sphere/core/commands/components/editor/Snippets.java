package com.sphere.components.editor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Templates offered by completion, with fields Tab walks through. A body uses
 * ${1:default} for a field, $1 to repeat it, $0 for where the caret ends and
 * \t for one indentation level. Several are the boilerplate of an analysis:
 * a ROOT histogram, an RDataFrame chain, a tree loop, an uproot read.
 */
public final class Snippets {

    private Snippets() {
    }

    public static final class Snippet {
        public final String trigger;
        public final String description;
        public final String body;

        Snippet(String trigger, String description, String body) {
            this.trigger = trigger;
            this.description = description;
            this.body = body;
        }

        /** The body as it reads once expanded, for the completion preview. */
        public String preview() {
            return expand(body, "", "    ").text;
        }
    }

    /** An expanded body: its text, the fields by number, and where the caret ends. */
    public static final class Expansion {
        public final String text;
        /** Field number → ranges [start, end) relative to the text; field 0 is the end. */
        public final TreeMap<Integer, List<int[]>> fields;
        public final int finalOffset;

        Expansion(String text, TreeMap<Integer, List<int[]>> fields, int finalOffset) {
            this.text = text;
            this.fields = fields;
            this.finalOffset = finalOffset;
        }
    }

    private static final Map<LanguageSpec, List<Snippet>> BY_LANGUAGE = new HashMap<>();

    private static void add(LanguageSpec spec, String trigger, String description, String body) {
        BY_LANGUAGE.computeIfAbsent(spec, k -> new ArrayList<>()).add(new Snippet(trigger, description, body));
    }

    static {
        // ---- C++ and ROOT macros
        LanguageSpec c = LanguageSpec.CPP;
        add(c, "main", "main function", "int main(int argc, char** argv) {\n\t$0\n\treturn 0;\n}");
        add(c, "for", "counted loop", "for (int ${1:i} = 0; $1 < ${2:n}; ++$1) {\n\t$0\n}");
        add(c, "forr", "range loop", "for (const auto& ${1:item} : ${2:container}) {\n\t$0\n}");
        add(c, "if", "if block", "if (${1:condition}) {\n\t$0\n}");
        add(c, "ife", "if / else", "if (${1:condition}) {\n\t$2\n} else {\n\t$0\n}");
        add(c, "while", "while loop", "while (${1:condition}) {\n\t$0\n}");
        add(c, "class", "class with constructor", "class ${1:Name} {\npublic:\n\t$1();\n\t~$1();\n\nprivate:\n\t$0\n};");
        add(c, "struct", "struct", "struct ${1:Name} {\n\t$0\n};");
        add(c, "inc", "#include <...>", "#include <${1:vector}>");
        add(c, "incl", "#include \"...\"", "#include \"${1:header.h}\"");
        add(c, "ns", "namespace", "namespace ${1:name} {\n\n$0\n\n} // namespace $1");
        add(c, "cout", "print a line", "std::cout << ${1:value} << std::endl;");
        add(c, "lambda", "lambda", "[${1:&}](${2:auto x}) {\n\t$0\n}");
        add(c, "try", "try / catch", "try {\n\t$1\n} catch (const std::exception& e) {\n\t${0:std::cerr << e.what() << std::endl;}\n}");
        add(c, "macro", "ROOT macro", "void ${1:macro}() {\n\t$0\n}");
        add(c, "th1", "ROOT 1D histogram",
            "TH1F* ${1:h} = new TH1F(\"$1\", \"${2:title};${3:x};Entries\", ${4:100}, ${5:0.}, ${6:10.});");
        add(c, "th2", "ROOT 2D histogram",
            "TH2F* ${1:h2} = new TH2F(\"$1\", \"${2:title};${3:x};${4:y}\", ${5:100}, ${6:0.}, ${7:10.}, ${8:100}, ${9:0.}, ${10:10.});");
        add(c, "canvas", "ROOT canvas", "TCanvas* ${1:c} = new TCanvas(\"$1\", \"${2:canvas}\", ${3:800}, ${4:600});");
        add(c, "tfile", "open a ROOT file",
            "std::unique_ptr<TFile> ${1:f}(TFile::Open(\"${2:file.root}\", \"${3:READ}\"));\n"
            + "if (!$1 || $1->IsZombie()) {\n\treturn${4};\n}");
        add(c, "treeloop", "loop over a TTree",
            "TTree* ${1:tree} = ${2:f}->Get<TTree>(\"${3:events}\");\n${4:Float_t} ${5:pt};\n"
            + "$1->SetBranchAddress(\"$5\", &$5);\nfor (Long64_t i = 0; i < $1->GetEntries(); ++i) {\n"
            + "\t$1->GetEntry(i);\n\t$0\n}");
        add(c, "reader", "TTreeReader loop",
            "TTreeReader ${1:reader}(\"${2:events}\", ${3:f}.get());\nTTreeReaderValue<${4:float}> ${5:pt}($1, \"$5\");\n"
            + "while ($1.Next()) {\n\t$0\n}");
        add(c, "rdf", "RDataFrame chain",
            "ROOT::RDataFrame ${1:df}(\"${2:events}\", \"${3:file.root}\");\n"
            + "auto ${4:h} = $1.Filter(\"${5:pt > 20}\").Histo1D({\"$4\", \"${6:title}\", ${7:100}, ${8:0.}, ${9:200.}}, \"${10:pt}\");\n$0");
        add(c, "fit", "fit and read back",
            "${1:h}->Fit(\"${2:gaus}\", \"${3:S}\");\nTF1* ${4:fit} = $1->GetFunction(\"$2\");\n"
            + "double ${5:mean} = $4->GetParameter(${6:1});");
        add(c, "legend", "ROOT legend",
            "auto* ${1:leg} = new TLegend(${2:0.65}, ${3:0.70}, ${4:0.88}, ${5:0.88});\n"
            + "$1->AddEntry(${6:h}, \"${7:label}\", \"${8:l}\");\n$1->Draw();");
        add(c, "lv", "four-vector", "TLorentzVector ${1:p4};\n$1.SetPtEtaPhiM(${2:pt}, ${3:eta}, ${4:phi}, ${5:m});");
        add(c, "save", "draw and save", "${1:c}->cd();\n${2:h}->Draw(\"${3:HIST}\");\n$1->SaveAs(\"${4:plot.pdf}\");");

        // ---- Python
        LanguageSpec p = LanguageSpec.PYTHON;
        add(p, "main", "main function", "def main():\n\t$0\n\n\nif __name__ == \"__main__\":\n\tmain()");
        add(p, "ifmain", "if __name__ == \"__main__\"", "if __name__ == \"__main__\":\n\t${0:main()}");
        add(p, "def", "function", "def ${1:name}(${2}):\n\t${0:pass}");
        add(p, "class", "class", "class ${1:Name}:\n\tdef __init__(self${2}):\n\t\t${0:pass}");
        add(p, "for", "loop", "for ${1:item} in ${2:items}:\n\t${0:pass}");
        add(p, "fore", "loop with index", "for ${1:i}, ${2:item} in enumerate(${3:items}):\n\t${0:pass}");
        add(p, "with", "open a file", "with open(${1:\"file.txt\"}, \"${2:r}\") as ${3:f}:\n\t${0:pass}");
        add(p, "try", "try / except", "try:\n\t${1:pass}\nexcept ${2:Exception} as ${3:e}:\n\t${0:raise}");
        add(p, "lc", "list comprehension", "[${1:x} for $1 in ${2:items}]");
        add(p, "imp", "numpy and matplotlib", "import numpy as np\nimport matplotlib.pyplot as plt\n$0");
        add(p, "plt", "figure with axes",
            "fig, ax = plt.subplots(figsize=(${1:8}, ${2:6}))\nax.${3:plot}(${4:x}, ${5:y}, label=\"${6:data}\")\n"
            + "ax.set_xlabel(\"${7:x}\")\nax.set_ylabel(\"${8:y}\")\nax.legend()\nfig.tight_layout()\nplt.show()");
        add(p, "hist", "matplotlib histogram",
            "counts, edges, _ = plt.hist(${1:data}, bins=${2:50}, range=(${3:0}, ${4:10}), histtype=\"step\", label=\"${5:data}\")");
        add(p, "uproot", "read a tree with uproot",
            "import uproot\n\nwith uproot.open(\"${1:file.root}\") as f:\n\ttree = f[\"${2:events}\"]\n"
            + "\tarrays = tree.arrays([${3:\"pt\", \"eta\"}], library=\"${4:np}\")\n\t$0");
        add(p, "pyroot", "loop over a tree with PyROOT",
            "import ROOT\n\nf = ROOT.TFile.Open(\"${1:file.root}\")\ntree = f.Get(\"${2:events}\")\n"
            + "for event in tree:\n\t${0:pass}");
        add(p, "rdf", "RDataFrame chain",
            "df = ROOT.RDataFrame(\"${1:events}\", \"${2:file.root}\")\n"
            + "h = df.Filter(\"${3:pt > 20}\").Histo1D((\"h\", \"${4:title}\", ${5:100}, ${6:0}, ${7:200}), \"${8:pt}\")\n$0");
        add(p, "th1", "ROOT histogram",
            "${1:h} = ROOT.TH1F(\"$1\", \"${2:title};${3:x};Entries\", ${4:100}, ${5:0}, ${6:10})");
        add(p, "argparse", "command line arguments",
            "import argparse\n\nparser = argparse.ArgumentParser(description=\"${1:description}\")\n"
            + "parser.add_argument(\"${2:input}\", help=\"${3:input file}\")\nargs = parser.parse_args()\n$0");
        add(p, "curvefit", "scipy curve_fit",
            "from scipy.optimize import curve_fit\n\n\ndef ${1:model}(x, ${2:a, b}):\n\treturn ${3:a * x + b}\n\n\n"
            + "popt, pcov = curve_fit($1, ${4:x}, ${5:y})\nperr = np.sqrt(np.diag(pcov))");

        // ---- Julia
        LanguageSpec j = LanguageSpec.JULIA;
        add(j, "function", "function", "function ${1:name}(${2})\n\t$0\nend");
        add(j, "for", "loop", "for ${1:i} in ${2:1:n}\n\t$0\nend");
        add(j, "if", "if block", "if ${1:condition}\n\t$0\nend");
        add(j, "struct", "struct", "struct ${1:Name}\n\t${2:field}::${3:Float64}\nend");
        add(j, "module", "module", "module ${1:Name}\n\n$0\n\nend # module");
        add(j, "using", "using", "using ${1:Plots}");

        // ---- CMake
        LanguageSpec m = LanguageSpec.CMAKE;
        add(m, "project", "CMakeLists.txt skeleton",
            "cmake_minimum_required(VERSION ${1:3.16})\nproject(${2:Analysis} LANGUAGES CXX)\n\n"
            + "set(CMAKE_CXX_STANDARD ${3:17})\nset(CMAKE_CXX_STANDARD_REQUIRED ON)\n\n"
            + "add_executable(${4:app} ${5:main.cpp})\n$0");
        add(m, "root", "build against ROOT",
            "find_package(ROOT REQUIRED COMPONENTS ${1:Core Hist Tree RIO Gpad})\n\n"
            + "add_executable(${2:analysis} ${3:analysis.cpp})\n"
            + "target_link_libraries($2 PRIVATE ROOT::Core ROOT::Hist ROOT::Tree ROOT::RIO ROOT::Gpad)\n$0");
        add(m, "exe", "add_executable", "add_executable(${1:target} ${2:main.cpp})");
        add(m, "lib", "add_library", "add_library(${1:target} ${2:STATIC} ${3:source.cpp})");
        add(m, "link", "target_link_libraries", "target_link_libraries(${1:target} ${2:PRIVATE} ${3:library})");
        add(m, "incdir", "target_include_directories",
            "target_include_directories(${1:target} ${2:PUBLIC} \\${CMAKE_CURRENT_SOURCE_DIR}/${3:include})");
        add(m, "if", "if block", "if(${1:condition})\n\t$0\nendif()");
        add(m, "foreach", "foreach loop", "foreach(${1:item} IN LISTS ${2:items})\n\t$0\nendforeach()");
        add(m, "function", "function", "function(${1:name} ${2:arg})\n\t$0\nendfunction()");
        add(m, "option", "option", "option(${1:WITH_FEATURE} \"${2:description}\" ${3:OFF})");
        add(m, "msg", "message", "message(${1:STATUS} \"${2:text}\")");
        add(m, "fetch", "FetchContent dependency",
            "include(FetchContent)\nFetchContent_Declare(\n\t${1:name}\n\tGIT_REPOSITORY ${2:https://github.com/user/repo.git}\n"
            + "\tGIT_TAG ${3:main}\n)\nFetchContent_MakeAvailable($1)");
        add(m, "gtest", "GoogleTest target",
            "enable_testing()\nfind_package(GTest REQUIRED)\nadd_executable(${1:tests} ${2:tests.cpp})\n"
            + "target_link_libraries($1 PRIVATE GTest::gtest_main)\ninclude(GoogleTest)\ngtest_discover_tests($1)");

        // ---- Fortran
        LanguageSpec f = LanguageSpec.FORTRAN;
        add(f, "program", "program", "program ${1:main}\n\timplicit none\n\t$0\nend program $1");
        add(f, "do", "do loop", "do ${1:i} = ${2:1}, ${3:n}\n\t$0\nend do");
        add(f, "if", "if block", "if (${1:condition}) then\n\t$0\nend if");
        add(f, "subroutine", "subroutine", "subroutine ${1:name}(${2:args})\n\timplicit none\n\t$0\nend subroutine $1");
        add(f, "function", "function", "function ${1:name}(${2:x}) result(${3:y})\n\timplicit none\n\t$0\nend function $1");

        // ---- Markdown and LaTeX
        LanguageSpec md = LanguageSpec.MARKDOWN;
        add(md, "code", "code block", "```${1:python}\n$0\n```");
        add(md, "link", "link", "[${1:text}](${2:url})");
        add(md, "img", "image", "![${1:alt}](${2:path})");
        add(md, "table", "table", "| ${1:Column} | ${2:Column} |\n| --- | --- |\n| ${3} | ${4} |");
        add(md, "task", "task item", "- [ ] ${1:task}");

        LanguageSpec tex = LanguageSpec.LATEX;
        add(tex, "begin", "environment", "\\begin{${1:environment}}\n\t$0\n\\end{$1}");
        add(tex, "eq", "numbered equation", "\\begin{equation}\n\t$0\n\t\\label{eq:${1:label}}\n\\end{equation}");
        add(tex, "fig", "figure",
            "\\begin{figure}[${1:htbp}]\n\t\\centering\n\t\\includegraphics[width=${2:0.8}\\textwidth]{${3:file}}\n"
            + "\t\\caption{${4:caption}}\n\t\\label{fig:${5:label}}\n\\end{figure}");
        add(tex, "frac", "fraction", "\\frac{${1:a}}{${2:b}}");
        add(tex, "sec", "section", "\\section{${1:title}}\n\\label{sec:${2:label}}\n$0");
        add(tex, "item", "itemize", "\\begin{itemize}\n\t\\item $0\n\\end{itemize}");

        // ---- Shell
        LanguageSpec sh = LanguageSpec.SHELL;
        add(sh, "shebang", "bash script header", "#!/usr/bin/env bash\nset -euo pipefail\n\n$0");
        add(sh, "if", "if block", "if [[ ${1:condition} ]]; then\n\t$0\nfi");
        add(sh, "for", "loop", "for ${1:f} in ${2:*}; do\n\t$0\ndone");
        add(sh, "func", "function", "${1:name}() {\n\t$0\n}");
    }

    public static List<Snippet> forLanguage(LanguageSpec spec) {
        return BY_LANGUAGE.getOrDefault(spec, List.of());
    }

    /**
     * Expands a body for insertion at a line indented by {@code indent}: later
     * lines get that indent and \t becomes one {@code unit}.
     */
    public static Expansion expand(String body, String indent, String unit) {
        // Defaults first, so "$1" can repeat a field defined later on the line.
        Map<Integer, String> defaults = new HashMap<>();
        for (int i = 0; i < body.length(); i++) {
            if (body.charAt(i) == '$' && i + 1 < body.length() && body.charAt(i + 1) == '{'
                    && (i == 0 || body.charAt(i - 1) != '\\')) {
                int k = i + 2;
                int num = 0;
                boolean digits = false;
                while (k < body.length() && Character.isDigit(body.charAt(k))) {
                    num = num * 10 + (body.charAt(k) - '0');
                    k++;
                    digits = true;
                }
                if (digits && k < body.length() && body.charAt(k) == ':') {
                    int close = body.indexOf('}', k);
                    if (close > k) {
                        defaults.putIfAbsent(num, body.substring(k + 1, close));
                    }
                }
            }
        }

        StringBuilder out = new StringBuilder();
        TreeMap<Integer, List<int[]>> fields = new TreeMap<>();
        int finalOffset = -1;
        int i = 0;
        while (i < body.length()) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length() && body.charAt(i + 1) == '$') {
                out.append('$');
                i += 2;
                continue;
            }
            if (c == '\t') {
                out.append(unit);
                i++;
                continue;
            }
            if (c == '\n') {
                out.append('\n').append(indent);
                i++;
                continue;
            }
            if (c == '$' && i + 1 < body.length()) {
                char next = body.charAt(i + 1);
                if (Character.isDigit(next) || next == '{') {
                    int k = i + 1;
                    boolean braced = next == '{';
                    if (braced) {
                        k++;
                    }
                    int num = 0;
                    int digitsStart = k;
                    while (k < body.length() && Character.isDigit(body.charAt(k))) {
                        num = num * 10 + (body.charAt(k) - '0');
                        k++;
                    }
                    if (k > digitsStart) {
                        String value;
                        if (braced) {
                            int close = body.indexOf('}', k);
                            if (close < 0) {
                                out.append(c);
                                i++;
                                continue;
                            }
                            value = k < close && body.charAt(k) == ':' ? body.substring(k + 1, close) : "";
                            if (value.isEmpty()) {
                                value = defaults.getOrDefault(num, "");
                            }
                            k = close + 1;
                        } else {
                            value = defaults.getOrDefault(num, "");
                        }
                        int start = out.length();
                        out.append(value.replace("\n", "\n" + indent).replace("\t", unit));
                        int end = out.length();
                        if (num == 0) {
                            finalOffset = start;
                            if (end > start) {
                                fields.computeIfAbsent(Integer.MAX_VALUE, x -> new ArrayList<>())
                                      .add(new int[] { start, end });
                            }
                        } else {
                            fields.computeIfAbsent(num, x -> new ArrayList<>()).add(new int[] { start, end });
                        }
                        i = k;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        if (finalOffset < 0) {
            finalOffset = out.length();
        }
        return new Expansion(out.toString(), fields, finalOffset);
    }
}
