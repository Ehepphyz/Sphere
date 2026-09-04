package com.sphere.components.editor.debug;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads one line of GDB machine interface output. The syntax looks like JSON but
 * is not: keys are bare, strings are C strings, and a record carries a leading
 * token and a class before its payload.
 */
public final class MiParser {

    /** One line of gdb output, already sorted by kind. */
    public static final class Record {
        public enum Kind {
            RESULT,        // ^done, ^error, ^running
            EXEC,          // *stopped, *running
            NOTIFY,        // =breakpoint-modified and friends
            STATUS,        // +download progress
            CONSOLE,       // ~"..."  gdb's own prose
            TARGET,        // @"..."  the program, when gdb relays it
            LOG,           // &"..."  gdb's internal log
            PROMPT,        // (gdb)
            PROGRAM        // anything else: the program wrote straight to the terminal
        }

        public final Kind kind;
        public final String token;
        public final String klass;
        public final Map<String, Object> fields;
        public final String text;

        Record(Kind kind, String token, String klass, Map<String, Object> fields, String text) {
            this.kind = kind;
            this.token = token;
            this.klass = klass;
            this.fields = fields == null ? Map.of() : fields;
            this.text = text;
        }

        public String string(String key) {
            Object value = fields.get(key);
            return value instanceof String s ? s : null;
        }

        @SuppressWarnings("unchecked")
        public Map<String, Object> tuple(String key) {
            Object value = fields.get(key);
            return value instanceof Map ? (Map<String, Object>) value : null;
        }

        @SuppressWarnings("unchecked")
        public List<Object> list(String key) {
            Object value = fields.get(key);
            return value instanceof List ? (List<Object>) value : List.of();
        }
    }

    private final String s;
    private int pos;

    private MiParser(String s) {
        this.s = s;
    }

    public static Record parse(String line) {
        if (line == null || line.isEmpty()) {
            return new Record(Record.Kind.PROGRAM, null, null, null, "");
        }
        if (line.startsWith("(gdb)")) {
            return new Record(Record.Kind.PROMPT, null, null, null, line);
        }
        char first = line.charAt(0);
        if (first == '~' || first == '@' || first == '&') {
            Record.Kind kind = first == '~' ? Record.Kind.CONSOLE
                             : first == '@' ? Record.Kind.TARGET : Record.Kind.LOG;
            MiParser p = new MiParser(line);
            p.pos = 1;
            return new Record(kind, null, null, null, p.readCString());
        }

        // An optional numeric token precedes the record character.
        int i = 0;
        while (i < line.length() && Character.isDigit(line.charAt(i))) {
            i++;
        }
        if (i >= line.length()) {
            return new Record(Record.Kind.PROGRAM, null, null, null, line);
        }
        String token = i > 0 ? line.substring(0, i) : null;
        char marker = line.charAt(i);
        Record.Kind kind = switch (marker) {
            case '^' -> Record.Kind.RESULT;
            case '*' -> Record.Kind.EXEC;
            case '=' -> Record.Kind.NOTIFY;
            case '+' -> Record.Kind.STATUS;
            default  -> null;
        };
        if (kind == null) {
            // Not a record at all: the inferior shares gdb's terminal, so its own
            // output arrives here untouched.
            return new Record(Record.Kind.PROGRAM, null, null, null, line);
        }

        MiParser p = new MiParser(line);
        p.pos = i + 1;
        String klass = p.readIdentifier();
        Map<String, Object> fields = new LinkedHashMap<>();
        while (p.pos < line.length() && line.charAt(p.pos) == ',') {
            p.pos++;
            String key = p.readIdentifier();
            if (p.pos < line.length() && line.charAt(p.pos) == '=') {
                p.pos++;
                fields.put(key, p.readValue());
            } else {
                fields.put(key, "");
            }
        }
        return new Record(kind, token, klass, fields, line);
    }

    private String readIdentifier() {
        int start = pos;
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == ',' || c == '=' || c == '{' || c == '}' || c == '[' || c == ']') {
                break;
            }
            pos++;
        }
        return s.substring(start, pos);
    }

    private Object readValue() {
        if (pos >= s.length()) {
            return "";
        }
        char c = s.charAt(pos);
        if (c == '"') {
            return readCString();
        }
        if (c == '{') {
            return readTuple();
        }
        if (c == '[') {
            return readList();
        }
        return readIdentifier();
    }

    private Map<String, Object> readTuple() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++;                                   // {
        while (pos < s.length() && s.charAt(pos) != '}') {
            String key = readIdentifier();
            if (pos < s.length() && s.charAt(pos) == '=') {
                pos++;
                map.put(key, readValue());
            } else {
                map.put(key, "");
            }
            if (pos < s.length() && s.charAt(pos) == ',') {
                pos++;
            }
        }
        if (pos < s.length()) {
            pos++;                               // }
        }
        return map;
    }

    private List<Object> readList() {
        List<Object> list = new ArrayList<>();
        pos++;                                   // [
        while (pos < s.length() && s.charAt(pos) != ']') {
            // A list holds either plain values or key=value pairs; gdb uses both,
            // for example stack=[frame={...},frame={...}].
            int mark = pos;
            String key = readIdentifier();
            if (pos < s.length() && s.charAt(pos) == '=') {
                pos++;
                list.add(readValue());
            } else {
                pos = mark;
                list.add(readValue());
            }
            if (pos < s.length() && s.charAt(pos) == ',') {
                pos++;
            }
        }
        if (pos < s.length()) {
            pos++;                               // ]
        }
        return list;
    }

    /** C string with the escapes gdb emits. */
    private String readCString() {
        StringBuilder sb = new StringBuilder();
        if (pos < s.length() && s.charAt(pos) == '"') {
            pos++;
        }
        while (pos < s.length()) {
            char c = s.charAt(pos++);
            if (c == '"') {
                break;
            }
            if (c != '\\' || pos >= s.length()) {
                sb.append(c);
                continue;
            }
            char e = s.charAt(pos++);
            switch (e) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case '\\' -> sb.append('\\');
                case '"' -> sb.append('"');
                default -> sb.append(e);
            }
        }
        return sb.toString();
    }
}
