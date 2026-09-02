package com.sphere.components.editor;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * What the tokenizer and the indenter need to know about one language. Adding a
 * language means adding a constant here, nothing else.
 */
public final class LanguageSpec {

    public final String name;
    public final Set<String> keywords;
    public final Set<String> types;
    public final String lineComment;
    public final String blockCommentOpen;
    public final String blockCommentClose;
    public final boolean hasPreprocessor;
    public final boolean hasDecorators;
    /** True where 'x' is a string, false where it is a character literal. */
    public final boolean singleQuoteIsString;
    /** Trailing characters that open a deeper indentation level. */
    public final char[] indentAfter;
    /** Leading characters that close one. */
    public final char[] dedentOn;
    private final Set<String> extensions;

    private LanguageSpec(String name, String[] keywords, String[] types,
                         String lineComment, String blockOpen, String blockClose,
                         boolean hasPreprocessor, boolean hasDecorators,
                         boolean singleQuoteIsString,
                         char[] indentAfter, char[] dedentOn, String[] extensions) {
        this.name = name;
        this.keywords = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(keywords)));
        this.types = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(types)));
        this.lineComment = lineComment;
        this.blockCommentOpen = blockOpen;
        this.blockCommentClose = blockClose;
        this.hasPreprocessor = hasPreprocessor;
        this.hasDecorators = hasDecorators;
        this.singleQuoteIsString = singleQuoteIsString;
        this.indentAfter = indentAfter;
        this.dedentOn = dedentOn;
        this.extensions = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(extensions)));
    }

    public boolean isPlain() {
        return this == NONE;
    }

    // -----------------------------------------------------------------------

    public static final LanguageSpec NONE = new LanguageSpec(
            "Plain Text",
            new String[0], new String[0],
            null, null, null, false, false, false,
            new char[0], new char[0], new String[0]);

    public static final LanguageSpec CPP = new LanguageSpec(
            "C++",
            new String[] {
                "alignas","alignof","and","asm","break","case","catch","class","concept",
                "const_cast","constexpr","consteval","constinit","continue","co_await",
                "co_return","co_yield","decltype","default","delete","do","dynamic_cast",
                "else","enum","explicit","export","extern","false","for","friend","goto",
                "if","inline","namespace","new","noexcept","not","nullptr","operator","or",
                "private","protected","public","register","reinterpret_cast","requires",
                "return","sizeof","static_assert","static_cast","struct","switch","template",
                "this","thread_local","throw","true","try","typedef","typeid","typename",
                "union","using","virtual","while","xor"
            },
            new String[] {
                "auto","bool","char","char8_t","char16_t","char32_t","const","double","float",
                "int","long","mutable","short","signed","static","unsigned","void","volatile",
                "wchar_t","size_t","ptrdiff_t","nullptr_t","int8_t","int16_t","int32_t",
                "int64_t","uint8_t","uint16_t","uint32_t","uint64_t",
                "std","string","vector","map","set","array","span","optional","variant",
                "Double_t","Float_t","Int_t","UInt_t","Long64_t","ULong64_t","Bool_t",
                "TTree","TFile","TH1F","TH1D","TH2F","TCanvas","TChain","TBranch","TLeaf"
            },
            "//", "/*", "*/", true, false, false,
            new char[] { '{', '(', '[' }, new char[] { '}', ')', ']' },
            new String[] { ".cpp", ".cc", ".cxx", ".c++", ".c", ".h", ".hpp", ".hh", ".hxx", ".C" });

    public static final LanguageSpec PYTHON = new LanguageSpec(
            "Python",
            new String[] {
                "and","as","assert","async","await","break","class","continue","def","del",
                "elif","else","except","finally","for","from","global","if","import","in",
                "is","lambda","nonlocal","not","or","pass","raise","return","try","while",
                "with","yield","match","case"
            },
            new String[] {
                "None","True","False","self","cls","int","float","str","bool","bytes","list",
                "dict","set","tuple","frozenset","complex","object","type","range",
                "print","len","open","enumerate","zip","map","filter","sum","min","max","abs"
            },
            "#", null, null, false, true, true,
            new char[] { ':', '{', '(', '[' }, new char[] { '}', ')', ']' },
            new String[] { ".py", ".pyw", ".pyi" });

    public static final LanguageSpec JULIA = new LanguageSpec(
            "Julia",
            new String[] {
                "baremodule","begin","break","catch","const","continue","do","else","elseif",
                "end","export","false","finally","for","function","global","if","import",
                "let","local","macro","module","quote","return","struct","true","try","using",
                "while","mutable","abstract","primitive","where","in","isa"
            },
            new String[] {
                "Int","Int8","Int16","Int32","Int64","UInt","UInt8","UInt16","UInt32","UInt64",
                "Float16","Float32","Float64","Bool","Char","String","Symbol","Array","Vector",
                "Matrix","Dict","Set","Tuple","Nothing","Missing","Any","Number","Real",
                "println","print","length","push!","pop!","sum","maximum","minimum","zeros","ones"
            },
            "#", "#=", "=#", false, true, false,
            new char[] { '{', '(', '[' }, new char[] { '}', ')', ']' },
            new String[] { ".jl" });

    private static final LanguageSpec[] ALL = { CPP, PYTHON, JULIA };

    /**
     * Picks a language from a file name. Unknown extensions fall back to plain
     * text rather than guessing.
     */
    public static LanguageSpec forFile(File file) {
        if (file == null) {
            return NONE;
        }
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return NONE;
        }
        String ext = name.substring(dot);
        for (LanguageSpec spec : ALL) {
            // ".C" is a ROOT macro and ".c" is C, so extensions stay case sensitive.
            if (spec.extensions.contains(ext)) {
                return spec;
            }
        }
        String lower = ext.toLowerCase();
        for (LanguageSpec spec : ALL) {
            if (spec.extensions.contains(lower)) {
                return spec;
            }
        }
        return NONE;
    }
}
