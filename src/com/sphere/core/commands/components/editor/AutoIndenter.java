package com.sphere.components.editor;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.JTextComponent;
import java.awt.event.ActionEvent;

/**
 * Language-aware indentation. Enter keeps the current level and adds one after an
 * opener; typing a closer pulls the line back a level.
 */
public final class AutoIndenter {

    private final JTextComponent component;
    private final String unit;
    private LanguageSpec spec;

    public AutoIndenter(JTextComponent component, LanguageSpec spec, int spacesPerLevel) {
        this.component = component;
        this.spec = spec == null ? LanguageSpec.NONE : spec;
        this.unit = " ".repeat(Math.max(1, spacesPerLevel));
        install();
    }

    public void setLanguage(LanguageSpec spec) {
        this.spec = spec == null ? LanguageSpec.NONE : spec;
    }

    private void install() {
        InputMap inputMap = component.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap actionMap = component.getActionMap();

        Object enterKey = inputMap.get(KeyStroke.getKeyStroke("ENTER"));
        final Action defaultEnter = enterKey == null ? null : actionMap.get(enterKey);
        if (enterKey != null) {
            actionMap.put(enterKey, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    if (defaultEnter != null) {
                        defaultEnter.actionPerformed(e);
                    }
                    indentNewLine();
                }
            });
        }

        // Closers realign the line as they are typed, which is what makes a
        // block visibly close in C++ and Julia.
        for (char closer : new char[] { '}', ')', ']' }) {
            final String key = "sphere-dedent-" + closer;
            final char ch = closer;
            inputMap.put(KeyStroke.getKeyStroke(closer), key);
            actionMap.put(key, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    component.replaceSelection(String.valueOf(ch));
                    dedentCurrentLine(ch);
                }
            });
        }
    }

    private void indentNewLine() {
        try {
            int pos = component.getCaretPosition();
            Element root = component.getDocument().getDefaultRootElement();
            int lineIndex = root.getElementIndex(pos);
            if (lineIndex <= 0) {
                return;
            }
            String previous = lineText(lineIndex - 1);
            StringBuilder indent = new StringBuilder(leadingWhitespace(previous));

            String trimmed = previous.trim();
            if (!trimmed.isEmpty() && opensBlock(trimmed)) {
                indent.append(unit);
            }
            if (indent.length() > 0) {
                component.getDocument().insertString(pos, indent.toString(), null);
            }
        } catch (BadLocationException ignored) {
            // caret moved during the edit; leaving the line unindented is harmless
        }
    }

    private boolean opensBlock(String trimmedLine) {
        char last = trimmedLine.charAt(trimmedLine.length() - 1);
        for (char c : spec.indentAfter) {
            if (last == c) {
                return true;
            }
        }
        // Julia opens a block with a keyword rather than a brace.
        if (spec == LanguageSpec.JULIA) {
            String head = trimmedLine.split("\\s+")[0];
            return head.equals("function") || head.equals("if") || head.equals("for")
                || head.equals("while") || head.equals("begin") || head.equals("struct")
                || head.equals("module") || head.equals("try") || head.equals("let")
                || head.equals("macro") || head.equals("quote");
        }
        return false;
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
            String text = component.getDocument()
                                   .getText(start, line.getEndOffset() - start);

            // Only when the closer is the sole content typed so far on the line.
            if (!text.trim().equals(String.valueOf(closer))) {
                return;
            }
            String indent = leadingWhitespace(text);
            if (indent.length() < unit.length()) {
                return;
            }
            component.getDocument().remove(start, unit.length());
        } catch (BadLocationException ignored) {
            // nothing to pull back
        }
    }

    private String lineText(int lineIndex) throws BadLocationException {
        Element root = component.getDocument().getDefaultRootElement();
        Element line = root.getElement(lineIndex);
        int start = line.getStartOffset();
        return component.getDocument().getText(start, line.getEndOffset() - start);
    }

    private static String leadingWhitespace(String line) {
        StringBuilder sb = new StringBuilder();
        for (char c : line.toCharArray()) {
            if (c == ' ' || c == '\t') {
                sb.append(c);
            } else {
                break;
            }
        }
        return sb.toString();
    }
}
