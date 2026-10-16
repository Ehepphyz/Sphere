package com.sphere.components.editor;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.Document;
import javax.swing.text.Element;
import javax.swing.text.JTextComponent;
import javax.swing.text.Position;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What typing does beyond inserting characters. Enter keeps the level, adds one
 * after an opener and splits a pair of braces around an empty line; a closer
 * typed alone pulls its line back. Brackets and quotes close themselves and are
 * typed over; Backspace between an empty pair deletes both; Tab indents a
 * selection, Shift+Tab takes it back, Home goes to the first character first.
 */
public final class AutoIndenter {

    private static final Pattern MD_LIST = Pattern.compile("^(\\s*)([-*+]|\\d{1,9}[.)])(\\s+)(\\[[ xX]\\]\\s+)?(.*)$");

    private final JTextComponent component;
    private LanguageSpec spec;
    private String unit;
    private int tabSize;
    private boolean useTabs;
    private boolean autoClose = true;
    /** Closers this class inserted, so typing them steps over instead of doubling. */
    private final List<Position> autoClosed = new ArrayList<>();
    private BooleanSupplier tabInterceptor;
    private BooleanSupplier shiftTabInterceptor;

    public AutoIndenter(JTextComponent component, LanguageSpec spec, int spacesPerLevel) {
        this.component = component;
        this.spec = spec == null ? LanguageSpec.NONE : spec;
        this.tabSize = Math.max(1, spacesPerLevel);
        this.unit = " ".repeat(tabSize);
        install();
        component.addCaretListener(e -> forgetStaleClosers(e.getDot()));
    }

    public void setLanguage(LanguageSpec spec) {
        this.spec = spec == null ? LanguageSpec.NONE : spec;
        reinstallPairs();
    }

    /** Spaces or a tab per level, as the file itself is indented. */
    public void setIndentation(boolean tabs, int size) {
        this.useTabs = tabs;
        this.tabSize = Math.max(1, size);
        this.unit = tabs ? "\t" : " ".repeat(this.tabSize);
    }

    public boolean usesTabs() {
        return useTabs;
    }

    public int indentSize() {
        return tabSize;
    }

    public String unit() {
        return unit;
    }

    public void setAutoClose(boolean value) {
        this.autoClose = value;
    }

    /** Asked first when Tab is pressed (completion, snippets); true consumes the key. */
    public void setTabInterceptor(BooleanSupplier tab, BooleanSupplier shiftTab) {
        this.tabInterceptor = tab;
        this.shiftTabInterceptor = shiftTab;
    }

    // ---- Installation ------------------------------------------------------

    private final List<String> pairKeys = new ArrayList<>();

    private void install() {
        InputMap inputMap = component.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap actionMap = component.getActionMap();

        Object enterKey = inputMap.get(KeyStroke.getKeyStroke("ENTER"));
        final Action defaultEnter = enterKey == null ? null : actionMap.get(enterKey);
        final Object enterName = enterKey != null ? enterKey : "sphere-enter";
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), enterName);
        actionMap.put(enterName, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!component.isEditable()) {
                    if (defaultEnter != null) {
                        defaultEnter.actionPerformed(e);
                    }
                    return;
                }
                onEnter();
            }
        });

        bind(inputMap, actionMap, KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "sphere-tab", () -> onTab(false));
        bind(inputMap, actionMap, KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK),
             "sphere-shift-tab", () -> onTab(true));

        final Action backspace = actionMap.get(DefaultEditorKit.deletePrevCharAction);
        bind(inputMap, actionMap, KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "sphere-backspace", () -> {
            if (!onBackspace() && backspace != null) {
                backspace.actionPerformed(new ActionEvent(component, ActionEvent.ACTION_PERFORMED, ""));
            }
        });

        bind(inputMap, actionMap, KeyStroke.getKeyStroke(KeyEvent.VK_HOME, 0), "sphere-home", () -> smartHome(false));
        bind(inputMap, actionMap, KeyStroke.getKeyStroke(KeyEvent.VK_HOME, InputEvent.SHIFT_DOWN_MASK),
             "sphere-home-select", () -> smartHome(true));

        // Closers realign their line as they are typed, which is what makes a
        // block visibly close in C++ and Julia, and step over what was auto-closed.
        for (char closer : new char[] { '}', ')', ']' }) {
            final char ch = closer;
            bind(inputMap, actionMap, KeyStroke.getKeyStroke(closer), "sphere-closer-" + closer, () -> onCloser(ch));
        }
        reinstallPairs();
    }

    private void reinstallPairs() {
        InputMap inputMap = component.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap actionMap = component.getActionMap();
        for (String key : pairKeys) {
            char c = key.charAt(key.length() - 1);
            inputMap.remove(KeyStroke.getKeyStroke(c));
            actionMap.remove(key);
        }
        pairKeys.clear();
        String pairs = spec.autoClosePairs == null ? "" : spec.autoClosePairs;
        for (int i = 0; i + 1 < pairs.length(); i += 2) {
            final char open = pairs.charAt(i);
            final char close = pairs.charAt(i + 1);
            if (open == close) {
                String key = "sphere-quote-" + open;
                bind(inputMap, actionMap, KeyStroke.getKeyStroke(open), key, () -> onQuote(open));
                pairKeys.add(key);
            } else if (open != '}' && open != ')' && open != ']') {
                String key = "sphere-open-" + open;
                bind(inputMap, actionMap, KeyStroke.getKeyStroke(open), key, () -> onOpener(open, close));
                pairKeys.add(key);
            }
        }
    }

    private void bind(InputMap in, ActionMap am, KeyStroke ks, String name, Runnable r) {
        in.put(ks, name);
        am.put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (component.isEditable() && component.isEnabled()) {
                    r.run();
                }
            }
        });
    }

    private void atomic(Runnable r) {
        if (component instanceof CodeTextPane p) {
            p.atomic(r);
        } else {
            r.run();
        }
    }

    // ---- Enter -------------------------------------------------------------

    private void onEnter() {
        atomic(() -> {
            try {
                if (component.getSelectionStart() != component.getSelectionEnd()) {
                    component.replaceSelection("");
                }
                Document doc = component.getDocument();
                int caret = component.getCaretPosition();
                Element root = doc.getDefaultRootElement();
                int lineIndex = root.getElementIndex(caret);
                Element lineEl = root.getElement(lineIndex);
                int lineStart = lineEl.getStartOffset();
                int lineEnd = Math.min(lineEl.getEndOffset() - 1, doc.getLength());
                String line = doc.getText(lineStart, lineEnd - lineStart);
                int col = caret - lineStart;
                String before = line.substring(0, Math.min(col, line.length()));
                String after = line.substring(Math.min(col, line.length()));
                String indent = leadingWhitespace(line);
                if (indent.length() > before.length()) {
                    indent = before;
                }

                if (spec == LanguageSpec.MARKDOWN && markdownList(line, lineStart, caret, before, after)) {
                    return;
                }
                if (docCommentContinuation(before, indent, caret)) {
                    return;
                }

                // A line that closes a block (else:, end, endif()) goes one level up first.
                if (spec.decreaseIndent != null && after.isBlank()
                        && spec.decreaseIndent.matcher(line).matches()) {
                    String prevIndent = previousIndent(lineIndex);
                    if (columns(indent) >= columns(prevIndent) && columns(indent) >= columns(unit)) {
                        int remove = removableUnit(indent);
                        if (remove > 0) {
                            doc.remove(lineStart, remove);
                            caret -= remove;
                            indent = indent.substring(remove);
                            before = before.substring(Math.min(remove, before.length()));
                        }
                    }
                }

                String newIndent = indent;
                String code = stripTrailingComment(before, lineStart);
                if (!code.isBlank() && opensBlock(code, line)) {
                    newIndent = indent + unit;
                } else if (spec.dedentAfter != null && spec.dedentAfter.matcher(line).matches()) {
                    int remove = removableUnit(indent);
                    newIndent = indent.substring(Math.min(remove, indent.length()));
                }

                char prev = lastNonSpace(before);
                char next = after.isEmpty() ? 0 : after.charAt(0);
                boolean betweenPair = prev != 0 && next != 0 && spec.indentAfter.length > 0
                        && SyntaxHighlighter.matches(prev, next)
                        && before.stripTrailing().endsWith(String.valueOf(prev));

                // A blank line keeps no trailing whitespace once left.
                if (before.isBlank() && !before.isEmpty() && after.isBlank()) {
                    doc.remove(lineStart, before.length());
                    caret = lineStart;
                }

                if (betweenPair) {
                    String inner = "\n" + indent + unit;
                    String outer = "\n" + indent;
                    // Drop the spaces between the caret and the closer.
                    int spaces = 0;
                    while (spaces < after.length() && after.charAt(spaces) == ' ') {
                        spaces++;
                    }
                    if (spaces > 0) {
                        doc.remove(caret, spaces);
                    }
                    doc.insertString(caret, inner + outer, null);
                    component.setCaretPosition(caret + inner.length());
                } else {
                    // Spaces left after the caret would start the new line: drop them.
                    int spaces = 0;
                    while (spaces < after.length() && (after.charAt(spaces) == ' ' || after.charAt(spaces) == '\t')) {
                        spaces++;
                    }
                    if (spaces > 0 && spaces < after.length()) {
                        doc.remove(caret, spaces);
                    }
                    doc.insertString(caret, "\n" + newIndent, null);
                    component.setCaretPosition(caret + 1 + newIndent.length());
                }
            } catch (BadLocationException ignored) {
                // caret moved during the edit; leaving the line unindented is harmless
            }
        });
    }

    /** Markdown: Enter after "- item" starts "- ", after "3. item" starts "4. ", on an empty item ends the list. */
    private boolean markdownList(String line, int lineStart, int caret, String before, String after)
            throws BadLocationException {
        if (!after.isBlank()) {
            return false;
        }
        Matcher m = MD_LIST.matcher(line);
        if (!m.matches()) {
            return false;
        }
        Document doc = component.getDocument();
        if (m.group(5).isBlank()) {
            // An empty item: end the list instead of continuing it.
            doc.remove(lineStart, line.length());
            doc.insertString(lineStart, m.group(1), null);
            component.setCaretPosition(lineStart + m.group(1).length());
            return true;
        }
        String marker = m.group(2);
        if (Character.isDigit(marker.charAt(0))) {
            char sep = marker.charAt(marker.length() - 1);
            long n = Long.parseLong(marker.substring(0, marker.length() - 1));
            marker = (n + 1) + String.valueOf(sep);
        }
        String task = m.group(4) != null ? "[ ] " : "";
        String insert = "\n" + m.group(1) + marker + m.group(3) + task;
        doc.insertString(caret, insert, null);
        component.setCaretPosition(caret + insert.length());
        return true;
    }

    /** Enter inside a /** comment starts the next line with " * ". */
    private boolean docCommentContinuation(String before, String indent, int caret) throws BadLocationException {
        if (spec.blockCommentOpen == null || !spec.blockCommentOpen.equals("/*")) {
            return false;
        }
        String t = before.trim();
        boolean opener = t.startsWith("/**") || t.startsWith("/*!");
        boolean middle = t.startsWith("*") && !t.startsWith("*/");
        if (!(opener || middle) || t.endsWith("*/")) {
            return false;
        }
        if (component instanceof CodeTextPane p && p.getSyntax() != null) {
            TokenKind k = p.getSyntax().liveKindAt(Math.max(0, caret - 1));
            if (k != TokenKind.COMMENT) {
                return false;
            }
        }
        String prefix = "\n" + indent + (opener ? " * " : "* ");
        component.getDocument().insertString(caret, prefix, null);
        component.setCaretPosition(caret + prefix.length());
        return true;
    }

    private boolean opensBlock(String code, String fullLine) {
        String trimmed = code.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        char last = trimmed.charAt(trimmed.length() - 1);
        for (char c : spec.indentAfter) {
            if (last == c) {
                return true;
            }
        }
        return spec.increaseIndent != null && spec.increaseIndent.matcher(fullLine).matches();
    }

    /** The text before the caret without a trailing comment. */
    private String stripTrailingComment(String before, int lineStart) {
        if (!(component instanceof CodeTextPane p) || p.getSyntax() == null) {
            return before;
        }
        int end = before.length();
        while (end > 0) {
            char c = before.charAt(end - 1);
            TokenKind k = p.getSyntax().liveKindAt(lineStart + end - 1);
            if (Character.isWhitespace(c) || k == TokenKind.COMMENT) {
                end--;
            } else {
                break;
            }
        }
        return before.substring(0, end);
    }

    private String previousIndent(int lineIndex) throws BadLocationException {
        for (int i = lineIndex - 1; i >= 0; i--) {
            String t = lineText(i);
            if (!t.isBlank()) {
                return leadingWhitespace(t);
            }
        }
        return "";
    }

    /** How many characters of an indent make up one level. */
    private int removableUnit(String indent) {
        if (indent.isEmpty()) {
            return 0;
        }
        if (indent.charAt(0) == '\t') {
            return 1;
        }
        int spaces = 0;
        while (spaces < indent.length() && spaces < tabSize && indent.charAt(spaces) == ' ') {
            spaces++;
        }
        return spaces;
    }

    private int columns(String ws) {
        return CodeTextPane.indentColumns(ws + "x", tabSize);
    }

    // ---- Tab, Backspace, Home ----------------------------------------------

    private void onTab(boolean shift) {
        BooleanSupplier interceptor = shift ? shiftTabInterceptor : tabInterceptor;
        if (interceptor != null && interceptor.getAsBoolean()) {
            return;
        }
        int s = component.getSelectionStart();
        int e = component.getSelectionEnd();
        Element root = component.getDocument().getDefaultRootElement();
        boolean multiLine = root.getElementIndex(s) != root.getElementIndex(e);
        if (shift || multiLine) {
            EditorActions.indentLines(component, unit, tabSize, shift);
            return;
        }
        atomic(() -> {
            if (useTabs) {
                component.replaceSelection("\t");
                return;
            }
            try {
                int caret = component.getSelectionStart();
                int lineStart = root.getElement(root.getElementIndex(caret)).getStartOffset();
                String before = component.getDocument().getText(lineStart, caret - lineStart);
                int width = tabSize - (visualColumn(before) % tabSize);
                component.replaceSelection(" ".repeat(Math.max(1, width)));
            } catch (BadLocationException ex) {
                component.replaceSelection(unit);
            }
        });
    }

    private int visualColumn(String before) {
        int col = 0;
        for (int i = 0; i < before.length(); i++) {
            col = before.charAt(i) == '\t' ? col + tabSize - (col % tabSize) : col + 1;
        }
        return col;
    }

    /** True when it handled the key. */
    private boolean onBackspace() {
        if (component.getSelectionStart() != component.getSelectionEnd()) {
            return false;
        }
        try {
            Document doc = component.getDocument();
            int caret = component.getCaretPosition();
            if (caret <= 0) {
                return false;
            }
            char prev = doc.getText(caret - 1, 1).charAt(0);
            char next = caret < doc.getLength() ? doc.getText(caret, 1).charAt(0) : 0;
            boolean pair = next != 0 && (SyntaxHighlighter.matches(prev, next)
                    || (prev == next && (prev == '"' || prev == '\'' || prev == '`' || prev == '$')))
                    && spec.autoClosePairs.indexOf(prev) >= 0;
            if (pair) {
                atomic(() -> {
                    try {
                        doc.remove(caret - 1, 2);
                    } catch (BadLocationException ignored) {
                        // nothing to delete
                    }
                });
                return true;
            }
            // In leading spaces, go back to the previous tab stop.
            Element root = doc.getDefaultRootElement();
            int lineStart = root.getElement(root.getElementIndex(caret)).getStartOffset();
            String before = doc.getText(lineStart, caret - lineStart);
            if (!useTabs && before.length() > 1 && before.chars().allMatch(c -> c == ' ')) {
                int remove = before.length() % tabSize == 0 ? tabSize : before.length() % tabSize;
                remove = Math.min(remove, before.length());
                final int r = remove;
                atomic(() -> {
                    try {
                        doc.remove(caret - r, r);
                    } catch (BadLocationException ignored) {
                        // nothing to delete
                    }
                });
                return true;
            }
        } catch (BadLocationException ex) {
            return false;
        }
        return false;
    }

    private void smartHome(boolean select) {
        try {
            Document doc = component.getDocument();
            int caret = component.getCaretPosition();
            Element root = doc.getDefaultRootElement();
            Element line = root.getElement(root.getElementIndex(caret));
            int start = line.getStartOffset();
            String text = doc.getText(start, Math.max(0, line.getEndOffset() - 1 - start));
            int first = start + leadingWhitespace(text).length();
            int target = caret == first ? start : first;
            if (select) {
                component.moveCaretPosition(target);
            } else {
                component.setCaretPosition(target);
            }
        } catch (BadLocationException ignored) {
            // leave the caret where it is
        }
    }

    // ---- Pairs -------------------------------------------------------------

    private void onOpener(char open, char close) {
        atomic(() -> {
            try {
                Document doc = component.getDocument();
                int s = component.getSelectionStart();
                int e = component.getSelectionEnd();
                if (s != e && autoClose) {
                    String inner = doc.getText(s, e - s);
                    component.replaceSelection(open + inner + close);
                    component.select(s + 1, s + 1 + inner.length());
                    return;
                }
                int caret = component.getCaretPosition();
                if (autoClose && canCloseAt(caret) && !inLiteral(caret)) {
                    component.replaceSelection(String.valueOf(open) + close);
                    int closerAt = component.getCaretPosition() - 1;
                    component.setCaretPosition(closerAt);
                    track(closerAt);
                } else {
                    component.replaceSelection(String.valueOf(open));
                }
            } catch (BadLocationException ex) {
                component.replaceSelection(String.valueOf(open));
            }
        });
    }

    private void onQuote(char q) {
        atomic(() -> {
            try {
                Document doc = component.getDocument();
                int s = component.getSelectionStart();
                int e = component.getSelectionEnd();
                if (s != e && autoClose) {
                    String inner = doc.getText(s, e - s);
                    component.replaceSelection(q + inner + q);
                    component.select(s + 1, s + 1 + inner.length());
                    return;
                }
                int caret = component.getCaretPosition();
                char next = caret < doc.getLength() ? doc.getText(caret, 1).charAt(0) : 0;
                char prev = caret > 0 ? doc.getText(caret - 1, 1).charAt(0) : 0;
                if (next == q && isTracked(caret)) {
                    component.setCaretPosition(caret + 1);
                    untrack(caret);
                    return;
                }
                // Typing the third quote of a docstring closes it too.
                if (spec.tripleQuotes && caret >= 2 && next != q
                        && doc.getText(caret - 2, 2).equals("" + q + q)) {
                    component.replaceSelection("" + q + q + q + q);
                    component.setCaretPosition(caret + 1);
                    return;
                }
                boolean literal = inLiteral(caret);
                boolean wordBefore = Character.isLetterOrDigit(prev) || prev == '_' || prev == q;
                if (autoClose && !literal && !wordBefore && canCloseAt(caret) && prev != '\\') {
                    component.replaceSelection("" + q + q);
                    component.setCaretPosition(caret + 1);
                    track(caret + 1);
                } else {
                    component.replaceSelection(String.valueOf(q));
                }
            } catch (BadLocationException ex) {
                component.replaceSelection(String.valueOf(q));
            }
        });
    }

    private void onCloser(char closer) {
        atomic(() -> {
            try {
                Document doc = component.getDocument();
                int caret = component.getCaretPosition();
                if (component.getSelectionStart() == component.getSelectionEnd() && caret < doc.getLength()
                        && doc.getText(caret, 1).charAt(0) == closer && isTracked(caret)) {
                    component.setCaretPosition(caret + 1);
                    untrack(caret);
                    return;
                }
                component.replaceSelection(String.valueOf(closer));
                dedentCurrentLine(closer);
            } catch (BadLocationException ex) {
                component.replaceSelection(String.valueOf(closer));
            }
        });
    }

    /** Auto-close only before whitespace, a closer or punctuation, as VS Code does. */
    private boolean canCloseAt(int caret) throws BadLocationException {
        Document doc = component.getDocument();
        if (caret >= doc.getLength()) {
            return true;
        }
        char next = doc.getText(caret, 1).charAt(0);
        return Character.isWhitespace(next) || ")]};,:".indexOf(next) >= 0;
    }

    private boolean inLiteral(int caret) {
        if (!(component instanceof CodeTextPane p) || p.getSyntax() == null || caret <= 0) {
            return false;
        }
        TokenKind before = p.getSyntax().liveKindAt(caret - 1);
        TokenKind at = p.getSyntax().liveKindAt(caret);
        // Inside a string or a comment both sides carry its kind; at its closing quote only the left one does.
        return (before == TokenKind.COMMENT && at == TokenKind.COMMENT)
            || (before == TokenKind.STRING && at == TokenKind.STRING)
            || (before == TokenKind.COMMENT && caret >= component.getDocument().getLength());
    }

    private void track(int offset) {
        try {
            autoClosed.add(component.getDocument().createPosition(offset));
            if (autoClosed.size() > 32) {
                autoClosed.remove(0);
            }
        } catch (BadLocationException ignored) {
            // not tracked: the closer will simply be typed again
        }
    }

    private boolean isTracked(int offset) {
        for (Position p : autoClosed) {
            if (p.getOffset() == offset) {
                return true;
            }
        }
        return false;
    }

    private void untrack(int offset) {
        autoClosed.removeIf(p -> p.getOffset() == offset);
    }

    private void forgetStaleClosers(int dot) {
        if (autoClosed.isEmpty()) {
            return;
        }
        Element root = component.getDocument().getDefaultRootElement();
        int line = root.getElementIndex(dot);
        Iterator<Position> it = autoClosed.iterator();
        while (it.hasNext()) {
            Position p = it.next();
            if (p.getOffset() < dot || root.getElementIndex(p.getOffset()) != line) {
                it.remove();
            }
        }
    }

    private void dedentCurrentLine(char closer) {
        boolean known = false;
        for (char c : spec.dedentOn) {
            if (c == closer) {
                known = true;
                break;
            }
        }
        if (!known) {
            return;
        }
        try {
            Element root = component.getDocument().getDefaultRootElement();
            int lineIndex = root.getElementIndex(component.getCaretPosition());
            Element line = root.getElement(lineIndex);
            int start = line.getStartOffset();
            String text = component.getDocument().getText(start, line.getEndOffset() - start);

            // Only when the closer is the sole content typed so far on the line.
            if (!text.trim().equals(String.valueOf(closer))) {
                return;
            }
            String indent = leadingWhitespace(text);
            // Align with the line that opened the block, rather than one level blindly.
            String target = openerIndent(start + indent.length(), closer);
            if (target != null && target.length() < indent.length()) {
                component.getDocument().remove(start, indent.length() - target.length());
                return;
            }
            int remove = removableUnit(indent);
            if (target == null && remove > 0 && indent.length() >= remove) {
                component.getDocument().remove(start, remove);
            }
        } catch (BadLocationException ignored) {
            // nothing to pull back
        }
    }

    /** Indentation of the line holding the opener matched by a closer at offset, or null. */
    private String openerIndent(int closerOffset, char closer) throws BadLocationException {
        Document doc = component.getDocument();
        String text = doc.getText(0, closerOffset);
        char opener = closer == '}' ? '{' : closer == ')' ? '(' : '[';
        int depth = 0;
        for (int i = text.length() - 1; i >= 0 && text.length() - i < 200_000; i--) {
            char c = text.charAt(i);
            if (c == closer) {
                depth++;
            } else if (c == opener) {
                if (depth == 0) {
                    Element root = doc.getDefaultRootElement();
                    Element line = root.getElement(root.getElementIndex(i));
                    String lt = doc.getText(line.getStartOffset(), line.getEndOffset() - line.getStartOffset());
                    return leadingWhitespace(lt);
                }
                depth--;
            }
        }
        return null;
    }

    // ---- Helpers -----------------------------------------------------------

    private String lineText(int lineIndex) throws BadLocationException {
        Element root = component.getDocument().getDefaultRootElement();
        Element line = root.getElement(lineIndex);
        int start = line.getStartOffset();
        return component.getDocument().getText(start, line.getEndOffset() - start);
    }

    private static char lastNonSpace(String s) {
        for (int i = s.length() - 1; i >= 0; i--) {
            if (!Character.isWhitespace(s.charAt(i))) {
                return s.charAt(i);
            }
        }
        return 0;
    }

    static String leadingWhitespace(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return line.substring(0, i);
    }
}
