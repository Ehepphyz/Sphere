package com.sphere.components.variables;

import com.sphere.components.variables.VariableStore.Variable;
import com.sphere.core.rootbackend.RootBackend;

import java.util.ArrayList;
import java.util.List;

/**
 * What the ROOT interpreter is holding.
 *
 * Cling keeps every global the session has declared, its own among them, so the
 * listing is filtered down to what was declared after Sphere started. The
 * interpreter answers with a text block rather than writing a file, since it is
 * the one language Sphere can talk to directly.
 */
public final class RootVariables implements VariableSources.Source {

    public static final String SOURCE = "root";

    /** How long the interpreter is given to answer. */
    private static final long TIMEOUT_MS = 8000;

    /** A cap, so a session that has declared thousands stays readable. */
    private static final int MAX_ROWS = 2000;

    /**
     * Builds the listing inside the interpreter and hands it back as one string.
     *
     * Only the scalar types are given a value: reading anything else would mean
     * knowing its layout, and the name and type are what a listing is for.
     */
    private static final String GLOBALS = """
        []{ TString out; TIter next(gROOT->GetListOfGlobals(kFALSE)); TGlobal *g;
            while ((g = (TGlobal *) next())) {
              if (!g->IsValid()) continue;
              TString type = g->GetTypeName(); TString value; void *p = g->GetAddress();
              if (p) {
                if (type == "int") value = TString::Format("%d", *(int *) p);
                else if (type == "unsigned int") value = TString::Format("%u", *(unsigned int *) p);
                else if (type == "long") value = TString::Format("%ld", *(long *) p);
                else if (type == "unsigned long") value = TString::Format("%lu", *(unsigned long *) p);
                else if (type == "short") value = TString::Format("%d", (int) *(short *) p);
                else if (type == "float") value = TString::Format("%g", (double) *(float *) p);
                else if (type == "double") value = TString::Format("%g", *(double *) p);
                else if (type == "bool") value = (*(bool *) p) ? "true" : "false";
                else if (type == "char") value = TString::Format("%c", *(char *) p);
              }
              out += TString::Format("%s\\t%s\\t%s\\n", g->GetName(), type.Data(), value.Data());
            }
            return std::string(out.Data()); }()
        """;

    /**
     * Everything the session is holding besides its globals.
     *
     * A histogram, a tree or an open file is not a global: ROOT keeps them in
     * lists of its own, and they are what a physics session actually works with.
     * Their class stands in for the type, and where they live for the value.
     */
    private static final String OBJECTS = """
        []{ TString out; TObject *o;
            TIter files(gROOT->GetListOfFiles());
            while ((o = files())) out += TString::Format("%s\\t%s\\topen file\\n", o->GetName(), o->ClassName());
            TIter canvases(gROOT->GetListOfCanvases());
            while ((o = canvases())) out += TString::Format("%s\\t%s\\tcanvas\\n", o->GetName(), o->ClassName());
            TIter specials(gROOT->GetListOfSpecials());
            while ((o = specials())) out += TString::Format("%s\\t%s\\tobject\\n", o->GetName(), o->ClassName());
            TIter functions(gROOT->GetListOfFunctions());
            while ((o = functions())) out += TString::Format("%s\\t%s\\tfunction\\n", o->GetName(), o->ClassName());
            if (gDirectory) { TIter here(gDirectory->GetList());
              while ((o = here())) out += TString::Format("%s\\t%s\\tin %s\\n", o->GetName(), o->ClassName(), gDirectory->GetName()); }
            return std::string(out.Data()); }()
        """;

    @Override
    public String name() {
        return SOURCE;
    }

    @Override
    public void refresh() {
        RootBackend backend = RootBackend.getInstance();
        if (backend == null) {
            VariableStore.publish(SOURCE, List.of());
            return;
        }
        List<Variable> held = new ArrayList<>();
        held.addAll(ask(backend, GLOBALS));
        held.addAll(ask(backend, OBJECTS));
        if (!held.isEmpty() || !VariableStore.sources().contains(SOURCE)) {
            VariableStore.publish(SOURCE, held);
        }
    }

    private static List<Variable> ask(RootBackend backend, String listing) {
        final String answer = backend.executeClingAwait(listing.replace("\n", " "),
                                                        TIMEOUT_MS);
        return answer == null ? List.of() : parse(answer);
    }

    /**
     * Turns the interpreter's answer into variables.
     *
     * Cling prints a value with its type in front, and the string escaped, so
     * what comes back is unwrapped before being read line by line.
     */
    static List<Variable> parse(String answer) {
        String text = answer.strip();
        final int quote = text.indexOf('"');
        if (text.startsWith("(") && quote > 0 && text.endsWith("\"")) {
            text = text.substring(quote + 1, text.length() - 1);
        }
        text = text.replace("\\t", "\t").replace("\\n", "\n").replace("\\\"", "\"");

        List<Variable> variables = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (line.isBlank() || variables.size() >= MAX_ROWS) {
                continue;
            }
            final String[] fields = line.split("\t", -1);
            if (fields[0].isBlank()) {
                continue;
            }
            variables.add(new Variable(SOURCE, fields[0].strip(),
                                       fields.length > 1 ? fields[1].strip() : "",
                                       fields.length > 2 ? fields[2].strip() : ""));
        }
        return variables;
    }
}
