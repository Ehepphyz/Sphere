package com.sphere.core.python.jupyterlab;

import com.sphere.components.editor.CodeTextPane;
import com.sphere.components.editor.CompletionPopup;
import com.sphere.components.editor.CompletionProvider;
import com.sphere.components.editor.EditorIcons;
import com.sphere.components.editor.FuzzyMatcher;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Completion in a code cell from the live kernel, which knows what an object
 * holds: a DataFrame's columns, a dict's keys, a function's parameters. Asking
 * it takes some 70 ms (jedi), so the list opens at once with the names the
 * notebook itself defines and is replaced by the kernel's when it answers;
 * further typing filters that answer instead of asking again.
 */
final class KernelCompletion implements CompletionProvider {

    private record Candidate(String text, String type) {
    }

    private final CodeTextPane pane;
    /** Sends a complete request for (code, caret); returns its id, or -1 with no kernel. */
    private final BiFunction<String, Integer, Integer> asker;
    private final Supplier<Set<String>> notebookWords;
    private CompletionPopup popup;

    private int requestId = -1;
    /** The text before the anchor the request was made for: an answer for another place is stale. */
    private String requestHead = "";
    private int requestAnchor = -1;
    private List<Candidate> answer;
    private int answerStart;

    KernelCompletion(CodeTextPane pane, BiFunction<String, Integer, Integer> asker, Supplier<Set<String>> notebookWords) {
        this.pane = pane;
        this.asker = asker;
        this.notebookWords = notebookWords;
    }

    void setPopup(CompletionPopup popup) {
        this.popup = popup;
    }

    @Override
    public boolean isMemberContext(String text, int offset) {
        return offset > 0 && (text.charAt(offset - 1) == '.' || text.charAt(offset - 1) == '[');
    }

    @Override
    public List<Item> complete(String text, int offset, String prefix) {
        int anchor = offset - prefix.length();
        String head = text.substring(0, anchor);
        if (answer != null && anchor == requestAnchor && head.equals(requestHead)) {
            List<Item> items = rank(answer, text, anchor, prefix);
            if (!items.isEmpty()) {
                return items;
            }
        }
        if (anchor != requestAnchor || !head.equals(requestHead)) {
            requestAnchor = anchor;
            requestHead = head;
            answer = null;
            requestId = asker.apply(text, offset);
        }
        return fallback(prefix);
    }

    /** The kernel's answer: shown if the caret is still where it was asked. */
    void answered(int id, int start, List<String> matches, List<String> types) {
        if (id != requestId || id < 0) {
            return;
        }
        List<Candidate> list = new ArrayList<>();
        for (int i = 0; i < matches.size(); i++) {
            list.add(new Candidate(matches.get(i), i < types.size() ? types.get(i) : ""));
        }
        answer = list;
        answerStart = start;
        if (popup != null && pane.isFocusOwner() && !list.isEmpty()) {
            popup.show();
        }
    }

    /**
     * The kernel's candidates as the popup inserts them: from the word under the
     * caret on, whatever the kernel counted from (d[' for a key, np. for a member).
     */
    private List<Item> rank(List<Candidate> candidates, String text, int anchor, String prefix) {
        int start = Math.max(0, Math.min(answerStart, anchor));
        String shared = text.substring(start, anchor);
        List<Item> items = new ArrayList<>();
        List<Integer> scores = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Candidate c : candidates) {
            String m = c.text();
            if (!m.startsWith(shared)) {
                continue;
            }
            String insert = m.substring(shared.length());
            if (insert.endsWith("(") && !insert.equals("(")) {
                insert = insert.substring(0, insert.length() - 1); // rlcompleter's call hint
            }
            if (insert.isEmpty() || !seen.add(insert)) {
                continue;
            }
            int score = prefix.isEmpty() ? 0 : FuzzyMatcher.score(prefix, insert, null);
            if (score == FuzzyMatcher.NO_MATCH) {
                continue;
            }
            items.add(new Item(insert, insert, c.type(), kindOf(c.type()), null, 0, null));
            scores.add(score);
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            order.add(i);
        }
        // the kernel already ranks; a better fuzzy score only moves a candidate up
        order.sort(Comparator.comparingInt((Integer i) -> -scores.get(i)).thenComparingInt(i -> i));
        List<Item> sorted = new ArrayList<>();
        for (int i : order) {
            sorted.add(items.get(i));
        }
        return sorted.size() > 200 ? sorted.subList(0, 200) : sorted;
    }

    /** Before the kernel answers, or without one: the names the notebook's cells define. */
    private List<Item> fallback(String prefix) {
        if (prefix.length() < 2) {
            return List.of();
        }
        List<Item> items = new ArrayList<>();
        for (String w : notebookWords.get()) {
            if (!w.equals(prefix) && FuzzyMatcher.score(prefix, w, null) != FuzzyMatcher.NO_MATCH
                    && w.regionMatches(true, 0, prefix, 0, 1)) {
                items.add(new Item(w, w, "notebook", EditorIcons.Kind.WORD, null, 0, null));
            }
        }
        items.sort(Comparator.comparingInt(i -> -FuzzyMatcher.score(prefix, i.label, null)));
        return items.size() > 50 ? items.subList(0, 50) : items;
    }

    static EditorIcons.Kind kindOf(String type) {
        return switch (type == null ? "" : type) {
            case "function" -> EditorIcons.Kind.FUNCTION;
            case "class" -> EditorIcons.Kind.CLASS;
            case "module" -> EditorIcons.Kind.MODULE;
            case "keyword" -> EditorIcons.Kind.KEYWORD;
            case "param" -> EditorIcons.Kind.PROPERTY;
            case "property" -> EditorIcons.Kind.PROPERTY;
            case "dict key" -> EditorIcons.Kind.FIELD;
            case "path" -> EditorIcons.Kind.FILE;
            case "magic" -> EditorIcons.Kind.COMMAND;
            case "instance", "statement" -> EditorIcons.Kind.VARIABLE;
            default -> EditorIcons.Kind.WORD;
        };
    }
}
