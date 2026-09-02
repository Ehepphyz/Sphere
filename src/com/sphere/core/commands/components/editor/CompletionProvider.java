package com.sphere.components.editor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Source of completion candidates. The buffer implementation below answers
 * immediately; a language server answers the same contract asynchronously.
 */
public interface CompletionProvider {

    final class Item {
        public final String insert;
        public final String label;
        public final String detail;

        public Item(String insert, String label, String detail) {
            this.insert = insert;
            this.label = label == null ? insert : label;
            this.detail = detail == null ? "" : detail;
        }
    }

    /**
     * @param text   the whole buffer
     * @param offset caret position
     * @param prefix the word being typed
     */
    List<Item> complete(String text, int offset, String prefix);

    /**
     * Keywords, types and every identifier already present in the buffer. No
     * process, no protocol, useful the moment it is installed.
     */
    final class Buffer implements CompletionProvider {

        private static final int MAX_ITEMS = 200;
        private final LanguageSpec spec;

        public Buffer(LanguageSpec spec) {
            this.spec = spec == null ? LanguageSpec.NONE : spec;
        }

        @Override
        public List<Item> complete(String text, int offset, String prefix) {
            if (prefix == null || prefix.length() < 2) {
                return List.of();
            }
            Set<String> words = new LinkedHashSet<>();

            for (String kw : spec.keywords) {
                if (kw.startsWith(prefix)) {
                    words.add(kw);
                }
            }
            for (String ty : spec.types) {
                if (ty.startsWith(prefix)) {
                    words.add(ty);
                }
            }
            collectIdentifiers(text, offset, prefix, words);

            List<Item> items = new ArrayList<>(words.size());
            for (String w : words) {
                if (w.equals(prefix)) {
                    continue;
                }
                String kind = spec.keywords.contains(w) ? "keyword"
                            : spec.types.contains(w) ? "type" : "buffer";
                items.add(new Item(w, w, kind));
            }
            items.sort(Comparator.comparing((Item i) -> i.label.length())
                                 .thenComparing(i -> i.label));
            return items.size() > MAX_ITEMS ? items.subList(0, MAX_ITEMS) : items;
        }

        private static void collectIdentifiers(String text, int offset, String prefix,
                                               Set<String> out) {
            final int n = text.length();
            int i = 0;
            while (i < n && out.size() < MAX_ITEMS) {
                char c = text.charAt(i);
                if (!Character.isJavaIdentifierStart(c)) {
                    i++;
                    continue;
                }
                int j = i;
                while (j < n && Character.isJavaIdentifierPart(text.charAt(j))) {
                    j++;
                }
                // Skip the word the caret sits in, it is what is being typed.
                boolean atCaret = offset >= i && offset <= j;
                if (!atCaret && j - i > prefix.length()) {
                    String word = text.substring(i, j);
                    if (word.startsWith(prefix)) {
                        out.add(word);
                    }
                }
                i = j;
            }
        }
    }
}
