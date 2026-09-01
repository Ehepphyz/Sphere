package com.sphere.components.workspace;

import java.util.*;

/**
 * Lightweight, zero-dependency JSON serialization and rehydration tokenizer pipeline.
 * Engineered specifically to parse local workflow manifests safely with robust EOF safety checks.
 */
public final class MinimalJson {

    private MinimalJson() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * Parses a raw JSON string into a structured key-value property map block.
     * @param jsonText Target JSON context payload to parse.
     * @return A map structure housing native structural properties.
     */
    public static Map<String, Object> parse(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) {
            return new LinkedHashMap<>();
        }
        Parser parsedTokenInstance = new Parser(jsonText);
        return parsedTokenInstance.parseObject();
    }

    /**
     * Converts a property data map configuration cleanly back into a formatted JSON document.
     * @param metadataMap Source variable configurations map stack.
     * @return Structured formatted JSON string layout representation.
     */
    public static String toJson(Map<String, Object> metadataMap) {
        if (metadataMap == null) return "{}";
        StringBuilder serializedOutput = new StringBuilder();
        writeObject(serializedOutput, metadataMap, 0);
        return serializedOutput.toString();
    }

    /**
     * Properly quotes and escapes control tokens inside a target text block.
     */
    public static String quote(String rawContentText) {
        if (rawContentText == null) return "\"\"";
        return "\"" + rawContentText
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\t", "\\t")
                .replace("\r", "\\r") + "\"";
    }

    // ------------------------------------------------------------------------
    // JSON High-Performance Serialization Layout Formatting Methods
    // ------------------------------------------------------------------------

    private static void writeIndent(StringBuilder sb, int indentDepth) {
        sb.append("  ".repeat(Math.max(0, indentDepth)));
    }

    private static void writeObject(StringBuilder sb, Map<String, Object> activeMap, int indentDepth) {
        sb.append("{\n");
        int iterationPointer = 0;
        int entitySize = activeMap.size();

        for (Map.Entry<String, Object> objectNodeEntry : activeMap.entrySet()) {
            writeIndent(sb, indentDepth + 1);
            sb.append(quote(objectNodeEntry.getKey())).append(": ");
            writeValue(sb, objectNodeEntry.getValue(), indentDepth + 1);

            if (iterationPointer < entitySize - 1) {
                sb.append(",");
            }
            sb.append("\n");
            iterationPointer++;
        }

        writeIndent(sb, indentDepth);
        sb.append("}");
    }

    private static void writeArray(StringBuilder sb, List<?> elementsList, int indentDepth) {
        sb.append("[");
        int entriesSize = elementsList.size();
        for (int index = 0; index < entriesSize; index++) {
            writeValue(sb, elementsList.get(index), indentDepth);
            if (index < entriesSize - 1) {
                sb.append(", ");
            }
        }
        sb.append("]");
    }

    private static void writeValue(StringBuilder sb, Object contextValue, int indentDepth) {
        if (contextValue == null) {
            sb.append("null");
        } else if (contextValue instanceof String targetStr) {
            sb.append(quote(targetStr));
        } else if (contextValue instanceof Boolean targetBool) {
            sb.append(targetBool);
        } else if (contextValue instanceof Number targetNum) {
            sb.append(targetNum);
        } else if (contextValue instanceof Map<?, ?> contextualNestedMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> boundCheckedMap = (Map<String, Object>) contextualNestedMap;
            writeObject(sb, boundCheckedMap, indentDepth);
        } else if (contextValue instanceof List<?> structuralList) {
            writeArray(sb, structuralList, indentDepth);
        } else {
            sb.append(quote(String.valueOf(contextValue)));
        }
    }

    // ------------------------------------------------------------------------
    // Hardened Structural JSON Lexical Parsing Engine
    // ------------------------------------------------------------------------

    private static final class Parser {
        private final String inputSourceBuffer;
        private final int bufferLengthLimit;
        private int internalBufferPointer = 0;

        Parser(String diagnosticText) {
            this.inputSourceBuffer = Objects.requireNonNull(diagnosticText, "Payload source cannot be null.").trim();
            this.bufferLengthLimit = this.inputSourceBuffer.length();
        }

        private char peekCharacter() {
            return internalBufferPointer < bufferLengthLimit ? inputSourceBuffer.charAt(internalBufferPointer) : '\0';
        }

        private char extractNextCharacter() {
            return internalBufferPointer < bufferLengthLimit ? inputSourceBuffer.charAt(internalBufferPointer++) : '\0';
        }

        private void skipSystemWhitespace() {
            while (internalBufferPointer < bufferLengthLimit && Character.isWhitespace(peekCharacter())) {
                internalBufferPointer++;
            }
        }

        Map<String, Object> parseObject() {
            skipSystemWhitespace();
            if (extractNextCharacter() != '{') return new LinkedHashMap<>();

            Map<String, Object> compiledMapInstance = new LinkedHashMap<>();

            while (true) {
                skipSystemWhitespace();
                char activeLookahead = peekCharacter();
                if (activeLookahead == '}' || activeLookahead == '\0') {
                    extractNextCharacter(); // step beyond closures
                    break;
                }

                String dictionaryKeyString = parseString();
                skipSystemWhitespace();

                if (extractNextCharacter() != ':') {
                    break; // Terminate early on structural syntax failure
                }

                skipSystemWhitespace();
                Object parsedValueObject = parseValue();
                compiledMapInstance.put(dictionaryKeyString, parsedValueObject);

                skipSystemWhitespace();
                char trailingDelimiterToken = extractNextCharacter();
                if (trailingDelimiterToken == '}') break;
                if (trailingDelimiterToken != ',') break; // Malformed separator recovery break point
            }

            return compiledMapInstance;
        }

        List<Object> parseArray() {
            skipSystemWhitespace();
            extractNextCharacter(); // step beyond opening square brace '['

            List<Object> collectedSequenceList = new ArrayList<>();

            while (true) {
                skipSystemWhitespace();
                char lookaheadCharacter = peekCharacter();
                if (lookaheadCharacter == ']' || lookaheadCharacter == '\0') {
                    extractNextCharacter(); // drop terminating array token
                    break;
                }

                collectedSequenceList.add(parseValue());
                skipSystemWhitespace();

                char sequenceSeparator = extractNextCharacter();
                if (sequenceSeparator == ']') break;
                if (sequenceSeparator != ',') break;
            }

            return collectedSequenceList;
        }

        Object parseValue() {
            skipSystemWhitespace();
            char evaluationChar = peekCharacter();

            if (evaluationChar == '"') return parseString();
            if (evaluationChar == '{') return parseObject();
            if (evaluationChar == '[') return parseArray();

            return parseNativePrimitiveLiteral();
        }

        String parseString() {
            StringBuilder stringValueAccumulator = new StringBuilder();
            extractNextCharacter(); // skip standard initial boundary quote (")

            while (internalBufferPointer < bufferLengthLimit) {
                char processingToken = extractNextCharacter();
                if (processingToken == '\\') {
                    if (internalBufferPointer >= bufferLengthLimit) break;
                    char escapedActionCharacter = extractNextCharacter();
                    switch (escapedActionCharacter) {
                        case 'n' -> stringValueAccumulator.append('\n');
                        case 't' -> stringValueAccumulator.append('\t');
                        case 'r' -> stringValueAccumulator.append('\r');
                        case 'b' -> stringValueAccumulator.append('\b');
                        case 'f' -> stringValueAccumulator.append('\f');
                        default -> stringValueAccumulator.append(escapedActionCharacter);
                    }
                } else if (processingToken == '"') {
                    break;
                } else {
                    stringValueAccumulator.append(processingToken);
                }
            }

            return stringValueAccumulator.toString();
        }

        Object parseNativePrimitiveLiteral() {
            StringBuilder sequenceStringBuilder = new StringBuilder();
            while (internalBufferPointer < bufferLengthLimit) {
                char currentActiveChar = peekCharacter();
                if (currentActiveChar == ',' || currentActiveChar == '}' || currentActiveChar == ']' 
                        || Character.isWhitespace(currentActiveChar) || currentActiveChar == '\0') {
                    break;
                }
                sequenceStringBuilder.append(extractNextCharacter());
            }

            String strippedLiteralText = sequenceStringBuilder.toString().trim();

            // Transform raw tokens into native Java typed primitives
            if ("true".equalsIgnoreCase(strippedLiteralText)) return Boolean.TRUE;
            if ("false".equalsIgnoreCase(strippedLiteralText)) return Boolean.FALSE;
            if ("null".equalsIgnoreCase(strippedLiteralText)) return null;

            try {
                if (strippedLiteralText.contains(".") || strippedLiteralText.contains("e") || strippedLiteralText.contains("E")) {
                    return Double.parseDouble(strippedLiteralText);
                }
                return Long.parseLong(strippedLiteralText);
            } catch (NumberFormatException ignoredFallbackEx) {
                return strippedLiteralText; // Fall back to safe text formatting if numeric casting fails
            }
        }
    }
}
