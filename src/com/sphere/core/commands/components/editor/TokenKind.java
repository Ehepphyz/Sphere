package com.sphere.components.editor;

/** Token classes the highlighter can color. */
public enum TokenKind {
    DEFAULT,
    KEYWORD,
    TYPE,
    FUNCTION,
    NUMBER,
    STRING,
    CHARACTER,
    COMMENT,
    PREPROCESSOR,
    DECORATOR,
    OPERATOR,
    /** ${VAR} in CMake and shells, keys of a config or JSON file. */
    VARIABLE,
    /** Named constants: CMake's PUBLIC / REQUIRED, ON / OFF, true / false. */
    CONSTANT,
    /** Brackets, colored by depth so a nesting reads at a glance. */
    BRACKET_1,
    BRACKET_2,
    BRACKET_3,
    /** A bracket with no partner. */
    BRACKET_BAD;

    /** Text inside these is not code: no brackets, symbols or completion there. */
    public boolean isLiteralOrComment() {
        return this == STRING || this == CHARACTER || this == COMMENT || this == PREPROCESSOR;
    }

    public boolean isBracket() {
        return this == BRACKET_1 || this == BRACKET_2 || this == BRACKET_3 || this == BRACKET_BAD;
    }
}
