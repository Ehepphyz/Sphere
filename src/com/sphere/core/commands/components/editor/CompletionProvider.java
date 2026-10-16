package com.sphere.components.editor;

import java.util.List;

/**
 * Source of completion candidates. The smart provider answers immediately from
 * the editor's own analysis; a language server answers the same contract
 * asynchronously.
 */
public interface CompletionProvider {

    final class Item {
        public final String insert;
        public final String label;
        public final String detail;
        public final EditorIcons.Kind kind;
        /** Longer description shown under the list, or null. */
        public final String doc;
        /** Characters to step back after inserting, to land inside "()". */
        public final int caretBack;
        /** When set, the item expands into a snippet with fields. */
        public final Snippets.Snippet snippet;
        int score;
        int[] matches;

        public Item(String insert, String label, String detail) {
            this(insert, label, detail, EditorIcons.Kind.WORD, null, 0, null);
        }

        public Item(String insert, String label, String detail, EditorIcons.Kind kind, String doc,
                    int caretBack, Snippets.Snippet snippet) {
            this.insert = insert;
            this.label = label == null ? insert : label;
            this.detail = detail == null ? "" : detail;
            this.kind = kind == null ? EditorIcons.Kind.WORD : kind;
            this.doc = doc;
            this.caretBack = Math.max(0, caretBack);
            this.snippet = snippet;
        }

        public int score() {
            return score;
        }

        public int[] matches() {
            return matches;
        }
    }

    /**
     * @param text   the whole buffer
     * @param offset caret position
     * @param prefix the word being typed
     */
    List<Item> complete(String text, int offset, String prefix);

    /** True when the characters before offset ask for members ("h->", "np.", "std::"). */
    default boolean isMemberContext(String text, int offset) {
        return false;
    }
}
