package com.sphere.core.python.jupyterlab;

import javax.swing.Timer;
import javax.swing.JTextPane;
import javax.swing.text.StyledDocument;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyleContext;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.event.DocumentListener;
import javax.swing.event.DocumentEvent;

import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

import com.sphere.theme.ThemePalette;

public final class PythonSyntaxHighlighter {

    private final ThemePalette palette;
    private final Timer timer;
    private final JTextPane pane;
    private final DocumentListener docListener;
    private boolean isHighlighting = false;

    // Track dirty editing boundaries to scale performance incrementally 
    private int dirtyOffset = -1;
    private int dirtyLength = 0;

    public PythonSyntaxHighlighter(ThemePalette themePalette, JTextPane pane) {
        this.palette = themePalette;
        this.pane = pane;
        setupStyles(pane);

        StyledDocument doc = pane.getStyledDocument();

        // 120ms is an excellent sweet spot for keystroke debouncing
        timer = new Timer(120, e -> applyIncrementalHighlight(doc));
        timer.setRepeats(false);

        this.docListener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                registerDirtyRegion(e.getOffset(), e.getLength());
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                registerDirtyRegion(e.getOffset(), 0);
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                // Safely ignored for style updates
            }
        };

        doc.addDocumentListener(docListener);
        
        // Initial highlight of the whole document on load
        registerDirtyRegion(0, doc.getLength());
    }

    private synchronized void registerDirtyRegion(int offset, int length) {
        if (isHighlighting) return; 

        if (dirtyOffset == -1) {
            dirtyOffset = offset;
            dirtyLength = length;
        } else {
            int end = Math.max(dirtyOffset + dirtyLength, offset + length);
            dirtyOffset = Math.min(dirtyOffset, offset);
            dirtyLength = end - dirtyOffset;
        }
        timer.restart();
    }

    public static void attachTo(JTextPane pane, ThemePalette palette) {
        new PythonSyntaxHighlighter(palette, pane);
    }

    private void setupStyles(JTextPane pane) {
        StyledDocument doc = pane.getStyledDocument();
        Style def = StyleContext.getDefaultStyleContext().getStyle(StyleContext.DEFAULT_STYLE);

        doc.addStyle("normal", def);

        createStyle(doc, "keyword", palette.getJupyPyKeywords(), true);
        createStyle(doc, "builtin", palette.getJupyPyBuiltin(), false);
        createStyle(doc, "string", palette.getJupyPyString(), false);
        createStyle(doc, "comment", palette.getJupyPyComment(), false);
        createStyle(doc, "number", palette.getJupyPyNumbers(), false);
        createStyle(doc, "operator", palette.getJupyPyOperator(), false);
        createStyle(doc, "decorator", palette.getJupyPyDecorator(), false);
        createStyle(doc, "magic", palette.getJupyPyMagic(), false);
        createStyle(doc, "exception", palette.getJupyPyException(), false);
        createStyle(doc, "self", palette.getJupyPySelf(), true);
        createStyle(doc, "import", palette.getJupyPyImport(), true);
        createStyle(doc, "class", palette.getJupyPyClass(), true);       
        createStyle(doc, "class_type", palette.getJupyPyClass(), false);  
        createStyle(doc, "function", palette.getJupyPyFunction(), false);
        createStyle(doc, "attributeLeft", palette.getJupyPyAttributeLeft(), false);
        createStyle(doc, "attributeRight", palette.getJupyPyAttributeRight(), false);
        createStyle(doc, "module", palette.getJupyPyAttributeLeft(), false);
        createStyle(doc, "identifier", palette.getJupyPyIdentifier(), false);
        createStyle(doc, "variable", palette.getJupyPyVariable(), false);
        createStyle(doc, "constant", palette.getJupyPyConstant(), false);
        
        // Dedicated Type Hint style (defaults to class styling or custom palette)
        createStyle(doc, "type_hint", palette.getJupyPyClass(), false);
    }

    private void createStyle(StyledDocument doc, String name, java.awt.Color color, boolean bold) {
        Style def = StyleContext.getDefaultStyleContext().getStyle(StyleContext.DEFAULT_STYLE);
        Style style = doc.addStyle(name, def);
        if (color != null) {
            StyleConstants.setForeground(style, color);
        }
        StyleConstants.setBold(style, bold);
    }

    private static final Set<String> KEYWORDS_SET = new HashSet<>(Arrays.asList(
        "def", "class", "import", "from", "for", "while", "if", "elif", "else",
        "return", "yield", "try", "except", "finally", "with", "as", "lambda",
        "True", "False", "None", "and", "or", "not", "in", "is", "global", "nonlocal",
        "pass", "break", "continue", "assert", "del", "match", "case"
    ));

    private static final Set<String> BUILTINS_SET = new HashSet<>(Arrays.asList(
        "print", "len", "range", "open", "enumerate", "zip", "map", "sum", "min", "max",
        "dict", "list", "set", "tuple", "type", "isinstance", "super", "vars", "locals",
        "globals", "dir", "help", "abs", "round", "sorted", "any", "all", "next", "iter"
    ));

    private static final Set<String> EXCEPTIONS = Set.of(
        "Exception", "ValueError", "TypeError", "KeyError", "IndexError",
        "RuntimeError", "ImportError", "NameError", "AttributeError",
        "StopIteration", "AssertionError", "KeyboardInterrupt", "FileNotFoundError"
    );

    private static final Set<String> PY_TYPES = Set.of(
        "int", "float", "str", "bool", "list", "dict", "set", "tuple", "bytes", "bytearray",
        "object", "Any", "Union", "Optional", "List", "Dict", "Tuple", "Set", "Callable", "Type"
    );

    private void applyIncrementalHighlight(StyledDocument doc) {
        int startPos;
        int targetLength;

        synchronized (this) {
            if (dirtyOffset == -1) return;
            startPos = dirtyOffset;
            targetLength = dirtyLength;
            
            dirtyOffset = -1;
            dirtyLength = 0;
        }

        isHighlighting = true;
        doc.removeDocumentListener(docListener);

        try {
            int totalDocLength = doc.getLength();
            if (totalDocLength == 0) return;

            Element root = doc.getDefaultRootElement();
            int startLineIndex = root.getElementIndex(Math.min(startPos, totalDocLength));
            int endLineIndex = root.getElementIndex(Math.min(startPos + targetLength, totalDocLength));

            Element startLineElem = root.getElement(startLineIndex);
            Element endLineElem = root.getElement(endLineIndex);

            int scanStart = startLineElem.getStartOffset();
            int scanEnd = Math.min(endLineElem.getEndOffset(), totalDocLength);

            // OPTION 3: Docstring Multi-line Scanner Expansion
            // If the local scanning window starts inside or contains an unclosed multi-line triple quote,
            // we dynamically expand our scanning window until the true end of that docstring or the document.
            String temporaryText = doc.getText(0, totalDocLength);
            scanEnd = expandScanRangeForTripleQuotes(temporaryText, scanStart, scanEnd, totalDocLength);
            
            int scanLength = scanEnd - scanStart;
            if (scanLength <= 0) return;

            String textSegment = temporaryText.substring(scanStart, scanEnd);

            // Clear styling inside the resolved physical segment
            doc.setCharacterAttributes(scanStart, scanLength, doc.getStyle("normal"), true);

            int i = 0;
            int segmentLen = textSegment.length();

            while (i < segmentLen) {
                char c = textSegment.charAt(i);

                if (Character.isWhitespace(c)) {
                    i++;
                    continue;
                }

                // 1. Single-line Comments
                if (c == '#') {
                    int wordStart = i;
                    while (i < segmentLen && textSegment.charAt(i) != '\n') i++;
                    doc.setCharacterAttributes(scanStart + wordStart, i - wordStart, doc.getStyle("comment"), false);
                    continue;
                }

                // 2. Jupyter Cell Magics
                if (c == '%' && (i == 0 || textSegment.charAt(i - 1) == '\n' || Character.isWhitespace(textSegment.charAt(i - 1)))) {
                    int wordStart = i;
                    i++;
                    if (i < segmentLen && textSegment.charAt(i) == '%') i++; 
                    while (i < segmentLen && !Character.isWhitespace(textSegment.charAt(i))) i++;
                    doc.setCharacterAttributes(scanStart + wordStart, i - wordStart, doc.getStyle("magic"), false);
                    continue;
                }

                // 3. Decorators
                if (c == '@') {
                    int wordStart = i;
                    i++;
                    while (i < segmentLen && (Character.isJavaIdentifierPart(textSegment.charAt(i)) || textSegment.charAt(i) == '.')) i++;
                    doc.setCharacterAttributes(scanStart + wordStart, i - wordStart, doc.getStyle("decorator"), false);
                    continue;
                }

                // 4. String Literals & OPTION 4: f-strings Interpolation
                int stringStart = getStartIndexAfterPrefix(textSegment, i, segmentLen);
                if (stringStart != -1) {
                    int wordStart = i;
                    boolean isFString = false;
                    
                    // Check if prefix contains 'f' or 'F' (e.g., f"", rf"")
                    for (int p = wordStart; p < stringStart; p++) {
                        char prefixChar = Character.toLowerCase(textSegment.charAt(p));
                        if (prefixChar == 'f') {
                            isFString = true;
                            break;
                        }
                    }

                    i = stringStart; 
                    char quoteChar = textSegment.charAt(i);
                    boolean isTripleQuote = (i + 2 < segmentLen && textSegment.charAt(i + 1) == quoteChar && textSegment.charAt(i + 2) == quoteChar);
                    
                    if (isTripleQuote) {
                        i += 3;
                        while (i <= segmentLen - 3 && !(textSegment.charAt(i) == quoteChar && textSegment.charAt(i+1) == quoteChar && textSegment.charAt(i+2) == quoteChar)) {
                            i++;
                        }
                        i = (i <= segmentLen - 3) ? i + 3 : segmentLen;
                    } else {
                        i++;
                        while (i < segmentLen && textSegment.charAt(i) != quoteChar && textSegment.charAt(i) != '\n') {
                            if (textSegment.charAt(i) == '\\' && i + 1 < segmentLen) i++; 
                            i++;
                        }
                        if (i < segmentLen && textSegment.charAt(i) == quoteChar) i++;
                    }

                    int totalStringLength = i - wordStart;
                    int absoluteStringStart = scanStart + wordStart;

                    // Apply base string color first
                    doc.setCharacterAttributes(absoluteStringStart, totalStringLength, doc.getStyle("string"), false);

                    // OPTION 4: Parse variable braces inside f-strings
                    if (isFString) {
                        highlightFStringBraces(doc, textSegment, wordStart, i, scanStart);
                    }
                    continue;
                }

                // 5. Numeric Literals
                if (Character.isDigit(c) || (c == '.' && i + 1 < segmentLen && Character.isDigit(textSegment.charAt(i + 1)))) {
                    int wordStart = i;
                    i++;
                    while (i < segmentLen && (Character.isDigit(textSegment.charAt(i)) || textSegment.charAt(i) == '.' 
                            || textSegment.charAt(i) == 'e' || textSegment.charAt(i) == 'E' 
                            || textSegment.charAt(i) == 'x' || textSegment.charAt(i) == 'o' || textSegment.charAt(i) == 'b'
                            || textSegment.charAt(i) == '_')) {
                        i++;
                    }
                    doc.setCharacterAttributes(scanStart + wordStart, i - wordStart, doc.getStyle("number"), false);
                    continue;
                }

                // OPTION 6: Python Return Type Hinting ("->")
                if (c == '-' && i + 1 < segmentLen && textSegment.charAt(i + 1) == '>') {
                    doc.setCharacterAttributes(scanStart + i, 2, doc.getStyle("operator"), false);
                    i += 2;
                    // Highlight the upcoming return type hint
                    while (i < segmentLen && Character.isWhitespace(textSegment.charAt(i))) i++;
                    int typeStart = i;
                    while (i < segmentLen && (Character.isJavaIdentifierPart(textSegment.charAt(i)) || textSegment.charAt(i) == '[' || textSegment.charAt(i) == ']')) {
                        i++;
                    }
                    if (i > typeStart) {
                        highlightTypeAnnotations(doc, textSegment.substring(typeStart, i), scanStart + typeStart);
                    }
                    continue;
                }

                // 6. Identifiers & Inline Type Hinting (Option 6 continued)
                if (Character.isJavaIdentifierStart(c)) {
                    int wordStart = i;
                    while (i < segmentLen && (Character.isJavaIdentifierPart(textSegment.charAt(i)) || textSegment.charAt(i) == '.')) i++;

                    String word = textSegment.substring(wordStart, i);
                    int length = i - wordStart;

                    if (word.contains(".")) {
                        highlightDotNotation(doc, word, scanStart + wordStart, textSegment, scanStart);
                        continue;
                    }

                    String prevWord = getPreviousWord(textSegment, wordStart);

                    if ("import".equals(prevWord) || "from".equals(prevWord)) {
                        doc.setCharacterAttributes(scanStart + wordStart, length, doc.getStyle("module"), false);
                    }
                    else if (KEYWORDS_SET.contains(word)) {
                        if ("import".equals(word) || "from".equals(word) || "as".equals(word)) {
                            doc.setCharacterAttributes(scanStart + wordStart, length, doc.getStyle("import"), false);
                        } else {
                            doc.setCharacterAttributes(scanStart + wordStart, length, doc.getStyle("keyword"), false);
                        }
                    }
                    else if (BUILTINS_SET.contains(word)) {
                        doc.setCharacterAttributes(scanStart + wordStart, length, doc.getStyle("builtin"), false);
                    }
                    else if (PY_TYPES.contains(word)) {
                        doc.setCharacterAttributes(scanStart + wordStart, length, doc.getStyle("builtin"), false);
                    }
                    else if (EXCEPTIONS.contains(word)) {
                        doc.setCharacterAttributes(scanStart + wordStart, length, doc.getStyle("exception"), false);
                    }
                    else if ("self".equals(word) || "cls".equals(word)) {
                        doc.setCharacterAttributes(scanStart + wordStart, length, doc.getStyle("self"), false);
                    }
                    else if (word.startsWith("__") && word.endsWith("__")) {
                        doc.setCharacterAttributes(scanStart + wordStart, length, doc.getStyle("magic"), false);
                    }
                    else if ("def".equals(prevWord)) {
                        doc.setCharacterAttributes(scanStart + wordStart, length, doc.getStyle("function"), false);
                    }
                    else if ("class".equals(prevWord)) {
                        doc.setCharacterAttributes(scanStart + wordStart, length, doc.getStyle("class"), false);
                    }
                    else if (isFollowedByParenthesis(textSegment, i)) {
                        doc.setCharacterAttributes(scanStart + wordStart, length, doc.getStyle("function"), false);
                    }
                    else if (Character.isUpperCase(word.charAt(0))) {
                        if (word.matches("[A-Z_][A-Z0-9_]*")) {
                            doc.setCharacterAttributes(scanStart + wordStart, length, doc.getStyle("constant"), false);
                        } else {
                            doc.setCharacterAttributes(scanStart + wordStart, length, doc.getStyle("class_type"), false);
                        }
                    }
                    else if (word.matches("[a-z_][a-z0-9_]*")) {
                        doc.setCharacterAttributes(scanStart + wordStart, length, doc.getStyle("variable"), false);
                    }
                    else {
                        doc.setCharacterAttributes(scanStart + wordStart, length, doc.getStyle("identifier"), false);
                    }

                    // OPTION 6: Inline Parameter/Variable Type Hint Detection
                    // Detect: identifier : type_name (e.g., name: str)
                    int nextNonSpace = getNextNonWhitespaceIndex(textSegment, i);
                    if (nextNonSpace < segmentLen && textSegment.charAt(nextNonSpace) == ':') {
                        // Scan past ':' to find the type reference token
                        int typeIndex = nextNonSpace + 1;
                        while (typeIndex < segmentLen && Character.isWhitespace(textSegment.charAt(typeIndex))) typeIndex++;
                        
                        int typeTokenStart = typeIndex;
                        while (typeIndex < segmentLen && (Character.isJavaIdentifierPart(textSegment.charAt(typeIndex)) 
                                || textSegment.charAt(typeIndex) == '[' || textSegment.charAt(typeIndex) == ']')) {
                            typeIndex++;
                        }
                        if (typeIndex > typeTokenStart) {
                            highlightTypeAnnotations(doc, textSegment.substring(typeTokenStart, typeIndex), scanStart + typeTokenStart);
                        }
                    }

                    continue;
                }

                // 7. Base operators & Structural Punctuation Symbols
                // Added structural parenthetical boundaries "()", "{}", and "[]" to execute highlighting updates
                if ("+-*/%&|^=<>!()[]{},:".indexOf(c) != -1) {
                    doc.setCharacterAttributes(scanStart + i, 1, doc.getStyle("operator"), false);
                }

                i++;
            }

        } catch (BadLocationException ignored) {
        } finally {
            doc.addDocumentListener(docListener);
            isHighlighting = false;
        }
    }

    // OPTION 3: Walk back to confirm if we are scanning within an opened/closed multi-line block
    private int expandScanRangeForTripleQuotes(String text, int scanStart, int scanEnd, int totalDocLength) {
        int occurrencesBefore = countTripleQuotes(text, 0, scanStart);
        
        // If there's an odd number of triple quotes before scanStart, we are inside a docstring.
        // We must stretch scanStart backward to the opening quote.
        if (occurrencesBefore % 2 != 0) {
            int indexSingle = text.lastIndexOf("'''", scanStart);
            int indexDouble = text.lastIndexOf("\"\"\"", scanStart);
            scanStart = Math.max(0, Math.max(indexSingle, indexDouble));
        }

        // Now find if the block contains an unclosed triple-quote going forward
        int occurrencesInside = countTripleQuotes(text, scanStart, scanEnd);
        if (occurrencesInside % 2 != 0) {
            // Expand scanEnd to include the closing quote
            int nextSingle = text.indexOf("'''", scanEnd);
            int nextDouble = text.indexOf("\"\"\"", scanEnd);
            
            int nextMatch = -1;
            if (nextSingle != -1 && nextDouble != -1) nextMatch = Math.min(nextSingle, nextDouble);
            else if (nextSingle != -1) nextMatch = nextSingle;
            else if (nextDouble != -1) nextMatch = nextDouble;

            if (nextMatch != -1) {
                scanEnd = Math.min(nextMatch + 3, totalDocLength);
            } else {
                scanEnd = totalDocLength; // String continues to end of file
            }
        }
        return scanEnd;
    }

    private int countTripleQuotes(String text, int start, int end) {
        int count = 0;
        int i = start;
        while (i <= end - 3) {
            if ((text.charAt(i) == '"' && text.charAt(i+1) == '"' && text.charAt(i+2) == '"') ||
                (text.charAt(i) == '\'' && text.charAt(i+1) == '\'' && text.charAt(i+2) == '\'')) {
                count++;
                i += 3;
            } else {
                i++;
            }
        }
        return count;
    }

    // OPTION 4: Sub-parser dedicated to extracting nested variable definitions inside F-Strings
    private void highlightFStringBraces(StyledDocument doc, String segmentText, int start, int end, int scanStart) {
        int i = start;
        while (i < end) {
            if (segmentText.charAt(i) == '{') {
                int braceStart = i;
                int braceCount = 1;
                i++;
                
                // Track matching closing brace inside f-string
                while (i < end && braceCount > 0) {
                    char insideChar = segmentText.charAt(i);
                    if (insideChar == '{') braceCount++;
                    else if (insideChar == '}') braceCount--;
                    i++;
                }
                int braceEnd = i;

                if (braceEnd > braceStart + 2) {
                    int absoluteInnerStart = scanStart + braceStart + 1;
                    int innerLength = (braceEnd - braceStart) - 2;

                    // Set matching expressions to standard code layout style
                    doc.setCharacterAttributes(absoluteInnerStart, innerLength, doc.getStyle("normal"), false);

                    // Re-evaluate inner variable references, dot notations, or numbers
                    String innerText = segmentText.substring(braceStart + 1, braceEnd - 1);
                    int subIndex = 0;
                    while (subIndex < innerText.length()) {
                        char subC = innerText.charAt(subIndex);
                        if (Character.isJavaIdentifierStart(subC)) {
                            int localStart = subIndex;
                            while (subIndex < innerText.length() && (Character.isJavaIdentifierPart(innerText.charAt(subIndex)) || innerText.charAt(subIndex) == '.')) {
                                subIndex++;
                            }
                            String word = innerText.substring(localStart, subIndex);
                            if (word.contains(".")) {
                                highlightDotNotation(doc, word, absoluteInnerStart + localStart, innerText, absoluteInnerStart);
                            } else if ("self".equals(word) || "cls".equals(word)) {
                                doc.setCharacterAttributes(absoluteInnerStart + localStart, word.length(), doc.getStyle("self"), false);
                            } else if (Character.isUpperCase(word.charAt(0))) {
                                doc.setCharacterAttributes(absoluteInnerStart + localStart, word.length(), doc.getStyle("class_type"), false);
                            } else {
                                doc.setCharacterAttributes(absoluteInnerStart + localStart, word.length(), doc.getStyle("variable"), false);
                            }
                            continue;
                        }
                        subIndex++;
                    }
                    
                    // Highlight outer braces `{` and `}` as operator symbols
                    doc.setCharacterAttributes(scanStart + braceStart, 1, doc.getStyle("operator"), false);
                    doc.setCharacterAttributes(scanStart + braceEnd - 1, 1, doc.getStyle("operator"), false);
                }
            }
            i++;
        }
    }

    // OPTION 6: Complex Type annotations helper style-mapper (e.g., List[str], Optional[Union[int, str]])
    private void highlightTypeAnnotations(StyledDocument doc, String typeExpression, int absolutePosition) {
        int i = 0;
        int len = typeExpression.length();
        while (i < len) {
            char c = typeExpression.charAt(i);
            if (Character.isJavaIdentifierStart(c)) {
                int start = i;
                while (i < len && Character.isJavaIdentifierPart(typeExpression.charAt(i))) i++;
                String word = typeExpression.substring(start, i);
                
                if (PY_TYPES.contains(word)) {
                    doc.setCharacterAttributes(absolutePosition + start, word.length(), doc.getStyle("builtin"), false);
                } else {
                    doc.setCharacterAttributes(absolutePosition + start, word.length(), doc.getStyle("type_hint"), false);
                }
                continue;
            }
            if (c == '[' || c == ']' || c == ',' || c == '|') {
                doc.setCharacterAttributes(absolutePosition + i, 1, doc.getStyle("operator"), false);
            }
            i++;
        }
    }

    private int getStartIndexAfterPrefix(String text, int index, int len) {
        char c = text.charAt(index);
        if (c == '"' || c == '\'') return index;
        
        if (index + 1 < len) {
            char next = text.charAt(index + 1);
            if (next == '"' || next == '\'') {
                char low = Character.toLowerCase(c);
                if (low == 'f' || low == 'r' || low == 'b' || low == 'u') return index + 1;
            }
        }
        if (index + 2 < len) {
            char nextNext = text.charAt(index + 2);
            if (nextNext == '"' || nextNext == '\'') {
                String prefix = text.substring(index, index + 2).toLowerCase();
                if (prefix.equals("fr") || prefix.equals("rf") || prefix.equals("br") || prefix.equals("rb")) {
                    return index + 2;
                }
            }
        }
        return -1;
    }

    private String getPreviousWord(String text, int start) {
        int i = start - 1;
        while (i >= 0 && Character.isWhitespace(text.charAt(i))) {
            i--;
        }
        if (i < 0) return "";
        int end = i + 1;
        while (i >= 0 && Character.isJavaIdentifierPart(text.charAt(i))) {
            i--;
        }
        return text.substring(i + 1, end);
    }

    private int getNextNonWhitespaceIndex(String text, int index) {
        int i = index;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private boolean isFollowedByParenthesis(String text, int index) {
        int i = index;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i < text.length() && text.charAt(i) == '(';
    }

    private void highlightDotNotation(StyledDocument doc, String word, int absoluteStartPosition, String segmentText, int scanStart) {
        String[] parts = word.split("\\.");
        int pos = absoluteStartPosition;

        for (int p = 0; p < parts.length; p++) {
            String part = parts[p];
            int plen = part.length();

            if (p == 0) {
                if ("self".equals(part) || "cls".equals(part)) {
                    doc.setCharacterAttributes(pos, plen, doc.getStyle("self"), false);
                } else if (Character.isUpperCase(part.charAt(0))) {
                    doc.setCharacterAttributes(pos, plen, doc.getStyle("class_type"), false);
                } else {
                    doc.setCharacterAttributes(pos, plen, doc.getStyle("module"), false);
                }
            } else {
                int relativeIndex = pos - scanStart;
                if (p == parts.length - 1 && isFollowedByParenthesis(segmentText, relativeIndex + plen)) {
                    doc.setCharacterAttributes(pos, plen, doc.getStyle("function"), false);
                } else {
                    doc.setCharacterAttributes(pos, plen, doc.getStyle("attributeRight"), false);
                }
            }
            pos += plen + 1;
        }
    }
}