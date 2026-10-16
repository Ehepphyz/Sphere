package com.sphere.components.editor;

import java.awt.Color;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What the tokenizer, the indenter, the linter and the tabs need to know about
 * one language. Adding a language means adding a constant here, nothing else.
 */
public final class LanguageSpec {

    /** Which lexer reads the text. Most languages are CODE with a few switches. */
    public enum Dialect { PLAIN, CODE, MARKDOWN, LATEX, CONFIG, MARKUP }

    public final String id;
    public final String name;
    /** Short text of the badge drawn on the tab. */
    public final String tag;
    /** Identity color, stated for a dark surface (EditorTheme rebalances it). */
    public final Color color;
    public final Dialect dialect;

    public final Set<String> keywords;
    public final Set<String> types;
    public final Set<String> constants;
    /** Commands and functions the language ships with: colored as calls, offered first. */
    public final Set<String> builtins;

    /** Primary line comment, used by Toggle Comment. */
    public final String lineComment;
    public final String[] extraLineComments;
    public final String blockCommentOpen;
    public final String blockCommentClose;

    public final boolean hasPreprocessor;
    public final boolean hasDecorators;
    /** True where 'x' is a string, false where it is a character literal. */
    public final boolean singleQuoteIsString;
    public final boolean caseInsensitive;
    /** ${VAR}, $VAR, $ENV{VAR}, $<genex> */
    public final boolean dollarVariables;
    /** %VAR% and !VAR! of cmd.exe */
    public final boolean percentVariables;
    /** "REM" opens a comment (cmd.exe) */
    public final boolean remComments;
    public final boolean tripleQuotes;
    public final boolean rawStrings;
    public final boolean backtickStrings;
    /** Strings may run over several lines (CMake, shells, Julia). */
    public final boolean multilineStrings;
    /** A string followed by ':' is a key (JSON). */
    public final boolean jsonKeys;
    /** Whether an unmatched bracket is worth reporting in this language. */
    public final boolean bracketLint;

    /** Trailing characters that open a deeper indentation level. */
    public final char[] indentAfter;
    /** Leading characters that close one. */
    public final char[] dedentOn;
    /** A line matching this opens a block (beyond indentAfter). May be null. */
    public final Pattern increaseIndent;
    /** A line matching this belongs one level up (else, end, endif()). May be null. */
    public final Pattern decreaseIndent;
    /** The line after one matching this goes one level up (return, pass). May be null. */
    public final Pattern dedentAfter;
    /** Pairs closed as they are typed, two characters per pair. */
    public final String autoClosePairs;

    private final Set<String> extensions;
    private final Set<String> fileNames;

    private LanguageSpec(Builder b) {
        this.id = b.id;
        this.name = b.name;
        this.tag = b.tag;
        this.color = b.color;
        this.dialect = b.dialect;
        this.caseInsensitive = b.caseInsensitive;
        this.keywords = words(b.keywords, b.caseInsensitive);
        this.types = words(b.types, false);
        this.constants = words(b.constants, false);
        this.builtins = words(b.builtins, b.caseInsensitive);
        this.lineComment = b.lineComment;
        this.extraLineComments = b.extraLineComments;
        this.blockCommentOpen = b.blockOpen;
        this.blockCommentClose = b.blockClose;
        this.hasPreprocessor = b.preprocessor;
        this.hasDecorators = b.decorators;
        this.singleQuoteIsString = b.singleQuoteIsString;
        this.dollarVariables = b.dollarVariables;
        this.percentVariables = b.percentVariables;
        this.remComments = b.remComments;
        this.tripleQuotes = b.tripleQuotes;
        this.rawStrings = b.rawStrings;
        this.backtickStrings = b.backtickStrings;
        this.multilineStrings = b.multilineStrings;
        this.jsonKeys = b.jsonKeys;
        this.bracketLint = b.bracketLint;
        this.indentAfter = b.indentAfter;
        this.dedentOn = b.dedentOn;
        int flags = b.caseInsensitive ? Pattern.CASE_INSENSITIVE : 0;
        this.increaseIndent = b.increaseIndent == null ? null : Pattern.compile(b.increaseIndent, flags);
        this.decreaseIndent = b.decreaseIndent == null ? null : Pattern.compile(b.decreaseIndent, flags);
        this.dedentAfter = b.dedentAfter == null ? null : Pattern.compile(b.dedentAfter, flags);
        this.autoClosePairs = b.pairs;
        this.extensions = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(b.extensions)));
        Set<String> names = new HashSet<>();
        for (String n : b.fileNames) {
            names.add(n.toLowerCase(Locale.ROOT));
        }
        this.fileNames = Collections.unmodifiableSet(names);
    }

    private static Set<String> words(String[] list, boolean lower) {
        Set<String> out = new HashSet<>();
        for (String w : list) {
            out.add(lower ? w.toLowerCase(Locale.ROOT) : w);
        }
        return Collections.unmodifiableSet(out);
    }

    public boolean isPlain() {
        return this == NONE;
    }

    /** True for the languages written as programs, where symbols and calls mean something. */
    public boolean isCode() {
        return dialect == Dialect.CODE;
    }

    public boolean isKeyword(String word) {
        return keywords.contains(caseInsensitive ? word.toLowerCase(Locale.ROOT) : word);
    }

    public boolean isBuiltin(String word) {
        return builtins.contains(caseInsensitive ? word.toLowerCase(Locale.ROOT) : word);
    }

    /** Every line-comment opener, the primary one first. */
    public List<String> lineComments() {
        List<String> out = new ArrayList<>();
        if (lineComment != null) {
            out.add(lineComment);
        }
        out.addAll(Arrays.asList(extraLineComments));
        return out;
    }

    /** The closing partner of an auto-closed opener, or 0. */
    public char closerFor(char opener) {
        for (int i = 0; i + 1 < autoClosePairs.length(); i += 2) {
            if (autoClosePairs.charAt(i) == opener) {
                return autoClosePairs.charAt(i + 1);
            }
        }
        return 0;
    }

    @Override
    public String toString() {
        return name;
    }

    // -----------------------------------------------------------------------
    // Builder
    // -----------------------------------------------------------------------

    private static final String[] NONE_WORDS = new String[0];

    private static final class Builder {
        final String id;
        final String name;
        final String tag;
        final Color color;
        Dialect dialect = Dialect.CODE;
        String[] keywords = NONE_WORDS;
        String[] types = NONE_WORDS;
        String[] constants = NONE_WORDS;
        String[] builtins = NONE_WORDS;
        String lineComment;
        String[] extraLineComments = NONE_WORDS;
        String blockOpen;
        String blockClose;
        boolean preprocessor;
        boolean decorators;
        boolean singleQuoteIsString;
        boolean caseInsensitive;
        boolean dollarVariables;
        boolean percentVariables;
        boolean remComments;
        boolean tripleQuotes;
        boolean rawStrings;
        boolean backtickStrings;
        boolean multilineStrings;
        boolean jsonKeys;
        boolean bracketLint = true;
        char[] indentAfter = { '{', '(', '[' };
        char[] dedentOn = { '}', ')', ']' };
        String increaseIndent;
        String decreaseIndent;
        String dedentAfter;
        String pairs = "()[]{}\"\"''";
        String[] extensions = NONE_WORDS;
        String[] fileNames = NONE_WORDS;

        Builder(String id, String name, String tag, Color color) {
            this.id = id;
            this.name = name;
            this.tag = tag;
            this.color = color;
        }

        Builder dialect(Dialect d) { this.dialect = d; return this; }
        Builder keywords(String... w) { this.keywords = w; return this; }
        Builder types(String... w) { this.types = w; return this; }
        Builder constants(String... w) { this.constants = w; return this; }
        Builder builtins(String... w) { this.builtins = w; return this; }
        Builder comments(String line, String open, String close) {
            this.lineComment = line;
            this.blockOpen = open;
            this.blockClose = close;
            return this;
        }
        Builder extraComments(String... c) { this.extraLineComments = c; return this; }
        Builder preprocessor() { this.preprocessor = true; return this; }
        Builder decorators() { this.decorators = true; return this; }
        Builder singleQuoteStrings() { this.singleQuoteIsString = true; return this; }
        Builder caseInsensitive() { this.caseInsensitive = true; return this; }
        Builder dollarVariables() { this.dollarVariables = true; return this; }
        Builder percentVariables() { this.percentVariables = true; return this; }
        Builder remComments() { this.remComments = true; return this; }
        Builder tripleQuotes() { this.tripleQuotes = true; return this; }
        Builder rawStrings() { this.rawStrings = true; return this; }
        Builder backticks() { this.backtickStrings = true; return this; }
        Builder multilineStrings() { this.multilineStrings = true; return this; }
        Builder jsonKeys() { this.jsonKeys = true; return this; }
        Builder noBracketLint() { this.bracketLint = false; return this; }
        Builder indent(char[] after, char[] on) { this.indentAfter = after; this.dedentOn = on; return this; }
        Builder increase(String regex) { this.increaseIndent = regex; return this; }
        Builder decrease(String regex) { this.decreaseIndent = regex; return this; }
        Builder dedentAfter(String regex) { this.dedentAfter = regex; return this; }
        Builder pairs(String p) { this.pairs = p; return this; }
        Builder ext(String... e) { this.extensions = e; return this; }
        Builder names(String... n) { this.fileNames = n; return this; }
        LanguageSpec build() { return new LanguageSpec(this); }
    }

    private static Builder lang(String id, String name, String tag, Color color) {
        return new Builder(id, name, tag, color);
    }

    // -----------------------------------------------------------------------
    // Languages
    // -----------------------------------------------------------------------

    public static final LanguageSpec NONE = lang("text", "Plain Text", "TXT", new Color(150, 156, 166))
            .dialect(Dialect.PLAIN)
            .pairs("()[]{}")
            .noBracketLint()
            .indent(new char[0], new char[0])
            .build();

    public static final LanguageSpec CPP = lang("cpp", "C++", "C++", new Color(243, 75, 125))
            .keywords(
                "alignas","alignof","and","asm","break","case","catch","class","concept",
                "const_cast","constexpr","consteval","constinit","continue","co_await",
                "co_return","co_yield","decltype","default","delete","do","dynamic_cast",
                "else","enum","explicit","export","extern","final","for","friend","goto",
                "if","inline","namespace","new","noexcept","not","operator","or","override",
                "private","protected","public","register","reinterpret_cast","requires",
                "return","sizeof","static_assert","static_cast","struct","switch","template",
                "this","thread_local","throw","try","typedef","typeid","typename",
                "union","using","virtual","while","xor")
            .types(
                "auto","bool","char","char8_t","char16_t","char32_t","const","double","float",
                "int","long","mutable","short","signed","static","unsigned","void","volatile",
                "wchar_t","size_t","ptrdiff_t","nullptr_t","int8_t","int16_t","int32_t",
                "int64_t","uint8_t","uint16_t","uint32_t","uint64_t",
                "std","string","string_view","vector","map","set","array","span","optional","variant",
                "unique_ptr","shared_ptr","weak_ptr","pair","tuple","unordered_map","unordered_set",
                "deque","list","function","thread","mutex","atomic","ostream","istream","fstream",
                "ifstream","ofstream","stringstream","complex",
                "Double_t","Float_t","Int_t","UInt_t","Long64_t","ULong64_t","Bool_t","Char_t",
                "Short_t","Size_t","Option_t","Text_t",
                "TTree","TFile","TH1","TH1F","TH1D","TH1I","TH2","TH2F","TH2D","TH3F","TH3D",
                "TCanvas","TChain","TBranch","TLeaf","TF1","TF2","TGraph","TGraphErrors",
                "TGraphAsymmErrors","TMultiGraph","THStack","TLegend","TLorentzVector","TVector3",
                "TRandom","TRandom3","TMath","TString","TStyle","TROOT","TPad","TLine","TLatex",
                "TProfile","TObject","TNamed","TDirectory","TKey","TNtuple","TEfficiency",
                "TFitResultPtr","TMatrixD","TVectorD","TTreeReader","TTreeReaderValue",
                "TTreeReaderArray","RDataFrame","ROOT","RooRealVar","RooDataSet","RooPlot",
                "RooGaussian","RooAddPdf","RooArgList","RooArgSet")
            .constants(
                "true","false","nullptr","NULL","kTRUE","kFALSE",
                "kRed","kBlue","kGreen","kBlack","kWhite","kOrange","kMagenta","kCyan",
                "kYellow","kGray","kViolet","kAzure","kTeal","kSpring","kPink",
                "gROOT","gStyle","gPad","gRandom","gSystem","gDirectory","gFile")
            .comments("//", "/*", "*/")
            .preprocessor()
            .rawStrings()
            .ext(".cpp", ".cc", ".cxx", ".c++", ".c", ".h", ".hpp", ".hh", ".hxx", ".ipp", ".tpp", ".C", ".cu")
            .build();

    public static final LanguageSpec PYTHON = lang("python", "Python", "PY", new Color(79, 145, 214))
            .keywords(
                "and","as","assert","async","await","break","class","continue","def","del",
                "elif","else","except","finally","for","from","global","if","import","in",
                "is","lambda","nonlocal","not","or","pass","raise","return","try","while",
                "with","yield","match","case")
            .types(
                "self","cls","int","float","str","bool","bytes","bytearray","list",
                "dict","set","tuple","frozenset","complex","object","type","range",
                "print","len","open","enumerate","zip","map","filter","sum","min","max","abs",
                "sorted","reversed","isinstance","issubclass","hasattr","getattr","setattr",
                "super","property","staticmethod","classmethod","iter","next","round","pow",
                "divmod","input","format","repr","hash","id","dir","vars","any","all","chr",
                "ord","bin","hex","oct","callable","exec","eval","compile",
                "Exception","ValueError","TypeError","KeyError","IndexError","RuntimeError",
                "AttributeError","ImportError","StopIteration","FileNotFoundError",
                "NotImplementedError","ZeroDivisionError","OSError","IOError")
            .constants("True","False","None","NotImplemented","Ellipsis","__name__","__file__",
                       "__doc__","__all__")
            .comments("#", null, null)
            .decorators()
            .singleQuoteStrings()
            .tripleQuotes()
            .indent(new char[] { ':', '{', '(', '[' }, new char[] { '}', ')', ']' })
            .decrease("^\\s*(elif\\b.*|else|except\\b.*|finally)\\s*:\\s*(#.*)?$")
            .dedentAfter("^\\s*(return\\b.*|pass|break|continue|raise\\b.*)\\s*(#.*)?$")
            .ext(".py", ".pyw", ".pyi")
            .names("SConstruct", "SConscript")
            .build();

    public static final LanguageSpec JULIA = lang("julia", "Julia", "JL", new Color(170, 121, 193))
            .keywords(
                "baremodule","begin","break","catch","const","continue","do","else","elseif",
                "end","export","finally","for","function","global","if","import",
                "let","local","macro","module","quote","return","struct","try","using",
                "while","mutable","abstract","primitive","where","in","isa","type")
            .types(
                "Int","Int8","Int16","Int32","Int64","UInt","UInt8","UInt16","UInt32","UInt64",
                "Float16","Float32","Float64","Bool","Char","String","Symbol","Array","Vector",
                "Matrix","Dict","Set","Tuple","NamedTuple","Nothing","Missing","Any","Number",
                "Real","Integer","AbstractFloat","AbstractArray","AbstractVector","AbstractMatrix",
                "println","print","length","push!","pop!","sum","maximum","minimum","zeros","ones",
                "collect","map","filter","reduce","size","eltype","typeof","rand","randn")
            .constants("true","false","nothing","missing","NaN","Inf","pi","π","im","ℯ")
            .comments("#", "#=", "=#")
            .decorators()
            .tripleQuotes()
            .multilineStrings()
            .increase("^\\s*(?:function|macro|module|baremodule|(?:mutable\\s+)?struct|abstract\\s+type"
                      + "|primitive\\s+type|quote|begin|let|if|elseif|else|for|while|try|catch|finally)\\b"
                      + "(?!.*\\bend\\b\\s*(#.*)?$).*$"
                      + "|.*\\bdo\\b(?:\\s+[\\w,\\s()]+)?\\s*$")
            .decrease("^\\s*(end|else|elseif\\b.*|catch\\b.*|finally)\\b.*$")
            .pairs("()[]{}\"\"")
            .ext(".jl")
            .build();

    public static final LanguageSpec CMAKE = lang("cmake", "CMake", "CM", new Color(38, 176, 160))
            .keywords(
                "if","elseif","else","endif","foreach","endforeach","while","endwhile",
                "function","endfunction","macro","endmacro","return","break","continue",
                "block","endblock")
            .builtins(
                "cmake_minimum_required","project","add_executable","add_library","add_subdirectory",
                "add_custom_command","add_custom_target","add_compile_options","add_compile_definitions",
                "add_definitions","add_dependencies","add_link_options","add_test","enable_testing",
                "find_package","find_library","find_path","find_program","find_file","include",
                "include_directories","link_directories","link_libraries","target_link_libraries",
                "target_include_directories","target_compile_definitions","target_compile_options",
                "target_compile_features","target_sources","target_link_directories",
                "target_link_options","target_precompile_headers","set","unset","option","message",
                "list","string","file","get_filename_component","set_target_properties",
                "set_property","get_property","get_target_property","get_directory_property",
                "set_directory_properties","configure_file","install","export","execute_process",
                "math","cmake_parse_arguments","cmake_path","include_guard","mark_as_advanced",
                "separate_arguments","source_group","try_compile","try_run","enable_language",
                "cmake_policy","set_source_files_properties","get_source_file_property",
                "aux_source_directory","fetchcontent_declare","fetchcontent_makeavailable",
                "fetchcontent_getproperties","fetchcontent_populate","pkg_check_modules",
                "check_cxx_compiler_flag","check_include_file","check_cxx_source_compiles",
                "check_symbol_exists","define_property","site_name","variable_watch",
                "add_custom_command","create_test_sourcelist","ctest_build","ctest_test",
                "root_generate_dictionary","root_standard_library_package","root_add_test",
                "gtest_discover_tests","qt_add_executable","pybind11_add_module")
            .constants(
                "PUBLIC","PRIVATE","INTERFACE","REQUIRED","COMPONENTS","OPTIONAL_COMPONENTS","CONFIG",
                "MODULE","NO_MODULE","QUIET","EXACT","VERSION","LANGUAGES","DESCRIPTION","HOMEPAGE_URL",
                "CACHE","STRING","BOOL","PATH","FILEPATH","INTERNAL","FORCE","PARENT_SCOPE","TARGET",
                "TARGETS","DESTINATION","FILES","DIRECTORY","PROGRAMS","RUNTIME","LIBRARY","ARCHIVE",
                "INCLUDES","EXPORT","NAMESPACE","NOT","AND","OR","STREQUAL","STRLESS","STRGREATER",
                "MATCHES","EQUAL","LESS","GREATER","LESS_EQUAL","GREATER_EQUAL","VERSION_LESS",
                "VERSION_GREATER","VERSION_EQUAL","VERSION_LESS_EQUAL","VERSION_GREATER_EQUAL",
                "DEFINED","EXISTS","IS_DIRECTORY","IS_ABSOLUTE","IS_NEWER_THAN","COMMAND","POLICY",
                "TEST","IN_LIST","IN","LISTS","ITEMS","RANGE","ZIP_LISTS","ON","OFF","TRUE","FALSE",
                "YES","NO","STATUS","WARNING","AUTHOR_WARNING","SEND_ERROR","FATAL_ERROR",
                "DEPRECATION","NOTICE","VERBOSE","DEBUG","TRACE","CHECK_START","CHECK_PASS",
                "CHECK_FAIL","STATIC","SHARED","OBJECT","IMPORTED","ALIAS","GLOBAL",
                "EXCLUDE_FROM_ALL","WIN32","MACOSX_BUNDLE","APPEND","PREPEND","REMOVE_ITEM",
                "REMOVE_DUPLICATES","SORT","REVERSE","LENGTH","GET","FIND","JOIN","SUBLIST",
                "TRANSFORM","REPLACE","REGEX","TOLOWER","TOUPPER","STRIP","GLOB","GLOB_RECURSE",
                "CONFIGURE_DEPENDS","READ","WRITE","COPY","RELATIVE_PATH","TO_CMAKE_PATH",
                "TO_NATIVE_PATH","WORKING_DIRECTORY","OUTPUT","DEPENDS","COMMENT","VERBATIM",
                "BYPRODUCTS","SOURCES","PROPERTIES","PROPERTY","OUTPUT_VARIABLE","RESULT_VARIABLE",
                "ERROR_VARIABLE","OUTPUT_STRIP_TRAILING_WHITESPACE","NAME","BEFORE","AFTER","SYSTEM",
                "GIT_REPOSITORY","GIT_TAG","GIT_SHALLOW","URL","URL_HASH","SOURCE_DIR","BINARY_DIR",
                "CXX","C","Fortran","CUDA","UNIX","APPLE","MSVC","MINGW","CYGWIN","ANDROID",
                "COPYONLY","ESCAPE_QUOTES","@ONLY","NEWLINE_STYLE","OPTIONAL","PATTERN","EXCLUDE",
                "PERMISSIONS","USE_SOURCE_PERMISSIONS","FILES_MATCHING","LINKER_LANGUAGE",
                "CXX_STANDARD","CXX_STANDARD_REQUIRED","CXX_EXTENSIONS","POSITION_INDEPENDENT_CODE",
                "OUTPUT_NAME","RUNTIME_OUTPUT_DIRECTORY","LIBRARY_OUTPUT_DIRECTORY")
            .comments("#", "#[[", "]]")
            .caseInsensitive()
            .dollarVariables()
            .multilineStrings()
            .indent(new char[] { '(' }, new char[] { ')' })
            .increase("^\\s*(if|elseif|else|foreach|while|function|macro|block)\\s*\\(.*$")
            .decrease("^\\s*(endif|endforeach|endwhile|endfunction|endmacro|endblock|else|elseif)\\s*\\(.*$")
            .pairs("()[]{}\"\"")
            .ext(".cmake")
            .names("CMakeLists.txt", "CMakePresets.json.in")
            .build();

    public static final LanguageSpec FORTRAN = lang("fortran", "Fortran", "F90", new Color(123, 111, 224))
            .keywords(
                "program","end","subroutine","function","module","contains","use","implicit","none",
                "intent","in","out","inout","if","then","else","elseif","endif","do","enddo","while",
                "call","return","stop","exit","cycle","select","case","default","where","elsewhere",
                "forall","interface","type","class","public","private","allocate","deallocate",
                "allocatable","pointer","target","optional","save","data","parameter","dimension",
                "result","recursive","pure","elemental","print","write","read","open","close",
                "format","go","to","goto","continue","include","block","associate","concurrent",
                "abstract","extends","procedure","generic","import","only","operator","assignment",
                "common","equivalence","external","intrinsic","namelist","sequence","value","bind")
            .types("integer","real","double","precision","complex","character","logical","byte")
            .constants("true","false","TRUE","FALSE","True","False")
            .builtins(
                "size","shape","sum","product","maxval","minval","abs","sqrt","exp","log","log10",
                "sin","cos","tan","asin","acos","atan","atan2","sinh","cosh","tanh","mod","modulo",
                "real","int","nint","floor","ceiling","allocated","present","len","len_trim","trim",
                "adjustl","adjustr","index","matmul","transpose","dot_product","reshape","huge",
                "tiny","epsilon","kind","selected_real_kind","selected_int_kind","max","min",
                "random_number","random_seed","cpu_time","system_clock","count","any","all",
                "lbound","ubound","merge","pack","spread","cshift","eoshift","associated","null")
            .comments("!", null, null)
            .singleQuoteStrings()
            .caseInsensitive()
            .increase("^\\s*(program|module|(?:recursive\\s+|pure\\s+|elemental\\s+)*subroutine"
                      + "|(?:recursive\\s+|pure\\s+|elemental\\s+)*(?:\\w+(?:\\s*\\([^)]*\\))?\\s+)?function"
                      + "|interface|do\\b|select\\s+case|type\\s*(,.*)?::?\\s*\\w|type\\s+\\w|block\\b"
                      + "|associate|forall)\\b.*$"
                      + "|^\\s*if\\s*\\(.*\\)\\s*then\\s*(!.*)?$|^\\s*else(\\s*if.*then)?\\s*(!.*)?$")
            .decrease("^\\s*(end\\b|else\\b|elseif\\b|contains\\b).*$")
            .indent(new char[] { '(', '[' }, new char[] { ')', ']' })
            .ext(".f90", ".f95", ".f03", ".f08", ".f", ".for", ".ftn", ".F90", ".F")
            .build();

    public static final LanguageSpec MARKDOWN = lang("markdown", "Markdown", "MD", new Color(225, 110, 0))
            .dialect(Dialect.MARKDOWN)
            .pairs("()[]{}``")
            .noBracketLint()
            .indent(new char[0], new char[0])
            .ext(".md", ".markdown", ".mdown", ".mkd", ".mdx")
            .build();

    public static final LanguageSpec LATEX = lang("latex", "LaTeX", "TEX", new Color(60, 160, 70))
            .dialect(Dialect.LATEX)
            .comments("%", null, null)
            .pairs("()[]{}$$")
            .indent(new char[] { '{' }, new char[] { '}' })
            .increase("^\\s*\\\\begin\\{.*$")
            .decrease("^\\s*\\\\end\\{.*$")
            .ext(".tex", ".sty", ".cls", ".ltx", ".bib")
            .build();

    public static final LanguageSpec SHELL = lang("shell", "Shell", "SH", new Color(137, 224, 81))
            .keywords(
                "if","then","else","elif","fi","for","while","until","do","done","case","esac",
                "function","select","in","return","break","continue","local","export","readonly",
                "declare","unset","shift","source","exit","time")
            .types(
                "echo","printf","cd","pwd","read","test","eval","exec","set","trap","wait","kill",
                "alias","unalias","type","command","let","getopts","mkdir","rm","cp","mv","ls",
                "cat","grep","sed","awk","find","chmod","chown","sudo","tar","curl","wget","make",
                "cmake","git","python","python3","pip","root","g++","gcc")
            .constants("true","false")
            .comments("#", null, null)
            .singleQuoteStrings()
            .dollarVariables()
            .multilineStrings()
            .backticks()
            .noBracketLint()
            .increase("^\\s*(if|elif|else|for|while|until|case|select|function)\\b.*$|.*\\b(then|do)\\s*$")
            .decrease("^\\s*(fi|done|esac|else|elif)\\b.*$")
            .pairs("()[]{}\"\"''``")
            .ext(".sh", ".bash", ".zsh", ".ksh", ".csh", ".tcsh")
            .names(".bashrc", ".bash_profile", ".zshrc", ".profile", "thisroot.sh")
            .build();

    public static final LanguageSpec POWERSHELL = lang("powershell", "PowerShell", "PS", new Color(86, 132, 240))
            .keywords(
                "if","elseif","else","switch","foreach","for","while","do","until","function",
                "param","begin","process","end","return","break","continue","try","catch","finally",
                "throw","trap","filter","in","exit","class","enum","using")
            .constants("$true","$false","$null")
            .comments("#", "<#", "#>")
            .singleQuoteStrings()
            .caseInsensitive()
            .dollarVariables()
            .multilineStrings()
            .ext(".ps1", ".psm1", ".psd1")
            .build();

    public static final LanguageSpec BATCH = lang("batch", "Batch", "BAT", new Color(193, 241, 46))
            .keywords(
                "if","else","for","in","do","goto","call","exit","set","setlocal","endlocal","echo",
                "off","on","not","exist","defined","errorlevel","equ","neq","lss","leq","gtr","geq",
                "pause","shift","start","cd","pushd","popd","title","cls","color","choice",
                "timeout","nul","enabledelayedexpansion","del","copy","move","mkdir","rmdir","type")
            .comments("::", null, null)
            .remComments()
            .caseInsensitive()
            .percentVariables()
            .singleQuoteStrings()
            .noBracketLint()
            .indent(new char[] { '(' }, new char[] { ')' })
            .pairs("()\"\"")
            .ext(".bat", ".cmd")
            .build();

    public static final LanguageSpec JSON = lang("json", "JSON", "{}", new Color(232, 163, 61))
            .constants("true", "false", "null")
            .comments("//", "/*", "*/")
            .jsonKeys()
            .pairs("[]{}\"\"")
            .indent(new char[] { '{', '[' }, new char[] { '}', ']' })
            .ext(".json", ".jsonc", ".geojson", ".ipynb", ".code-workspace")
            .build();

    public static final LanguageSpec CONFIG = lang("config", "Config", "CFG", new Color(138, 155, 168))
            .dialect(Dialect.CONFIG)
            .constants("true", "false", "yes", "no", "on", "off", "True", "False", "TRUE", "FALSE",
                       "Yes", "No", "ON", "OFF", "null", "none", "None")
            .comments("#", null, null)
            .extraComments(";")
            .noBracketLint()
            .indent(new char[0], new char[0])
            .ext(".conf", ".ini", ".cfg", ".properties", ".toml", ".yaml", ".yml", ".env",
                 ".editorconfig", ".desktop", ".service", ".src")
            .names(".gitignore", ".gitattributes", ".env", ".clang-format", ".clang-tidy",
                   "Pipfile", "requirements.txt", "setup.cfg", "pyproject.toml")
            .build();

    public static final LanguageSpec JAVA = lang("java", "Java", "JV", new Color(205, 135, 40))
            .keywords(
                "abstract","assert","break","case","catch","class","continue","default","do","else",
                "enum","extends","final","finally","for","goto","if","implements","import",
                "instanceof","interface","native","new","package","private","protected","public",
                "return","static","strictfp","super","switch","synchronized","this","throw","throws",
                "transient","try","volatile","while","var","record","sealed","permits","yield")
            .types(
                "boolean","byte","char","double","float","int","long","short","void","String",
                "Object","Integer","Long","Double","Float","Boolean","Character","List","Map","Set",
                "ArrayList","HashMap","HashSet","Optional","Stream","System","Math","Thread",
                "Runnable","Exception","RuntimeException","Override","Deprecated","FunctionalInterface")
            .constants("true", "false", "null")
            .comments("//", "/*", "*/")
            .decorators()
            .ext(".java")
            .build();

    public static final LanguageSpec JAVASCRIPT = lang("javascript", "JavaScript", "JS", new Color(241, 224, 90))
            .keywords(
                "break","case","catch","class","const","continue","debugger","default","delete","do",
                "else","export","extends","finally","for","function","if","import","in","instanceof",
                "let","new","return","super","switch","this","throw","try","typeof","var","void",
                "while","with","yield","async","await","of","static","get","set","from","as",
                "interface","type","enum","implements","declare","namespace","readonly","abstract")
            .types(
                "Array","Object","String","Number","Boolean","Map","Set","Promise","JSON","Math",
                "Date","RegExp","Error","console","window","document","Symbol","BigInt","number",
                "string","boolean","any","unknown","never")
            .constants("true", "false", "null", "undefined", "NaN", "Infinity")
            .comments("//", "/*", "*/")
            .singleQuoteStrings()
            .backticks()
            .decorators()
            .pairs("()[]{}\"\"''``")
            .ext(".js", ".mjs", ".cjs", ".jsx", ".ts", ".tsx")
            .build();

    public static final LanguageSpec MARKUP = lang("xml", "XML / HTML", "<>", new Color(227, 76, 38))
            .dialect(Dialect.MARKUP)
            .comments(null, "<!--", "-->")
            .noBracketLint()
            .indent(new char[0], new char[0])
            .pairs("\"\"''")
            .ext(".xml", ".html", ".htm", ".xhtml", ".svg", ".xsd", ".xsl", ".plist", ".pom", ".gdml")
            .build();

    private static final LanguageSpec[] ALL = {
        NONE, CPP, PYTHON, JULIA, CMAKE, FORTRAN, MARKDOWN, LATEX,
        SHELL, POWERSHELL, BATCH, JSON, CONFIG, JAVA, JAVASCRIPT, MARKUP
    };

    /** Every language, Plain Text first, for the language picker. */
    public static List<LanguageSpec> all() {
        return Collections.unmodifiableList(Arrays.asList(ALL));
    }

    public static LanguageSpec byId(String id) {
        for (LanguageSpec spec : ALL) {
            if (spec.id.equalsIgnoreCase(id) || spec.name.equalsIgnoreCase(id)) {
                return spec;
            }
        }
        return NONE;
    }

    /**
     * Picks a language from a file name. Whole names come first, so that
     * CMakeLists.txt is CMake and not text. Unknown extensions fall back to
     * plain text rather than guessing.
     */
    public static LanguageSpec forFile(File file) {
        if (file == null) {
            return NONE;
        }
        String name = file.getName();
        String lowerName = name.toLowerCase(Locale.ROOT);
        for (LanguageSpec spec : ALL) {
            if (spec.fileNames.contains(lowerName)) {
                return spec;
            }
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            if (lowerName.equals("makefile") || lowerName.equals("dockerfile")) {
                return SHELL;
            }
            return NONE;
        }
        String ext = name.substring(dot);
        for (LanguageSpec spec : ALL) {
            // ".C" is a ROOT macro and ".c" is C, so extensions stay case sensitive first.
            if (spec.extensions.contains(ext)) {
                return spec;
            }
        }
        String lower = ext.toLowerCase(Locale.ROOT);
        for (LanguageSpec spec : ALL) {
            if (spec.extensions.contains(lower)) {
                return spec;
            }
        }
        return NONE;
    }

    /** The badge text for a file: a header reads H, a C source C. */
    public static String badgeFor(LanguageSpec spec, File file) {
        if (spec == CPP && file != null) {
            String n = file.getName();
            String lower = n.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".h") || lower.endsWith(".hpp") || lower.endsWith(".hh")
                    || lower.endsWith(".hxx")) {
                return "H";
            }
            if (n.endsWith(".c")) {
                return "C";
            }
            if (n.endsWith(".cu")) {
                return "CU";
            }
        }
        return spec.tag;
    }

    /**
     * The Sphere console command that runs a file, the way the user would type
     * it, or null when the file is not something the console runs.
     */
    public static String runCommandFor(LanguageSpec spec, File file) {
        if (file == null) {
            return null;
        }
        String n = file.getName();
        String lower = n.toLowerCase(Locale.ROOT);
        if (spec == PYTHON) {
            return "::py";
        }
        if (spec == JULIA) {
            return "::jul";
        }
        if (spec == FORTRAN) {
            return "::fort";
        }
        if (spec == CPP) {
            if (n.endsWith(".C")) {
                return "::root";
            }
            if (lower.endsWith(".cpp") || lower.endsWith(".cc") || lower.endsWith(".cxx")
                    || lower.endsWith(".c++") || n.endsWith(".c")) {
                return "::cpp";
            }
        }
        return null;
    }
}
