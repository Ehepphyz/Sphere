package com.sphere.core.clhep.evaluator;

import com.sphere.core.clhep.CMath;
import com.sphere.core.hepmc3.cxx.CStr;
import com.sphere.core.hepmc3.cxx.StdStreams;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * CLHEP's HepTool::Evaluator (Evaluator/Evaluator.h, Evaluator.cc, setStdMath.cc, setSystemOfUnits.cc):
 * E. Chernyaev's evaluator of arithmetic expressions — numbers, variables (values or expressions),
 * functions of up to five parameters, + - * / ^ (or **), comparisons and || &&, with the C++'s operator
 * precedence tables, the positions of its errors and its quirks.
 *
 * <p>The C++ walks a char buffer with pointers; here they are indices into the same characters, read as
 * C reads them (a NUL past the end; strtod reading on past the end of a sub-expression). A function
 * reports a domain or range error as C's do, through {@link #setErrno} (errno), which the evaluator
 * clears before each call and reads after it; the standard functions set it as the C library does.
 */
public final class Evaluator {

    public static final int OK = 0;
    public static final int WARNING_EXISTING_VARIABLE = 1;
    public static final int WARNING_EXISTING_FUNCTION = 2;
    public static final int WARNING_BLANK_STRING = 3;
    public static final int ERROR_NOT_A_NAME = 4;
    public static final int ERROR_SYNTAX_ERROR = 5;
    public static final int ERROR_UNPAIRED_PARENTHESIS = 6;
    public static final int ERROR_UNEXPECTED_SYMBOL = 7;
    public static final int ERROR_UNKNOWN_VARIABLE = 8;
    public static final int ERROR_UNKNOWN_FUNCTION = 9;
    public static final int ERROR_EMPTY_PARAMETER = 10;
    public static final int ERROR_CALCULATION_ERROR = 11;

    /** C's errno values for a domain and a range error. */
    public static final int EDOM = 33;
    public static final int ERANGE = 34;

    @FunctionalInterface
    public interface F0 {
        double apply();
    }

    @FunctionalInterface
    public interface F1 {
        double apply(double a);
    }

    @FunctionalInterface
    public interface F2 {
        double apply(double a, double b);
    }

    @FunctionalInterface
    public interface F3 {
        double apply(double a, double b, double c);
    }

    @FunctionalInterface
    public interface F4 {
        double apply(double a, double b, double c, double d);
    }

    @FunctionalInterface
    public interface F5 {
        double apply(double a, double b, double c, double d, double e);
    }

    /* ---- errno ---- */

    private static final ThreadLocal<int[]> ERRNO = ThreadLocal.withInitial(() -> new int[1]);

    /** C's errno on this thread, as the functions the evaluator calls leave it. */
    public static int errno() {
        return ERRNO.get()[0];
    }

    /** What a function sets to report an error (EDOM, ERANGE), as a C function sets errno. */
    public static void setErrno(int e) {
        ERRNO.get()[0] = e;
    }

    /* ---- the dictionary ---- */

    private enum What { UNKNOWN, VARIABLE, EXPRESSION, FUNCTION }

    private record Item(What what, double variable, String expression, Object function) {
        static Item of(double x) {
            return new Item(What.VARIABLE, x, "", null);
        }

        static Item of(String x) {
            return new Item(What.EXPRESSION, 0, x, null);
        }

        static Item function(Object f) {
            return new Item(What.FUNCTION, 0, "", f);
        }
    }

    private static final int MAX_N_PAR = 5;
    private static final String sss = "012345";

    private static final int ENDL = 0, LBRA = 1, OR = 2, AND = 3, EQ = 4, NE = 5, GE = 6, GT = 7, LE = 8, LT = 9,
        PLUS = 10, MINUS = 11, UNARY_PLUS = 12, UNARY_MINUS = 13, MULT = 14, DIV = 15, POW = 16, RBRA = 17, VALUE = 18;

    private final Map<String, Item> theDictionary = new HashMap<>();
    private char[] theExpression;
    private int thePosition;
    private int theStatus = OK;
    private double theResult = 0.0;

    public Evaluator() {
    }

    /* ---- C's characters ---- */

    private static boolean isspace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r';
    }

    private static boolean isalpha(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isalnum(char c) {
        return isalpha(c) || (c >= '0' && c <= '9');
    }

    /** *p: the character at p, NUL past the end of the buffer. */
    private static char at(char[] b, int p) {
        return p >= 0 && p < b.length ? b[p] : '\0';
    }

    /* ---- the engine ---- */

    private static int variable(String name, double[] result, Map<String, Item> dictionary) {
        final Item item = dictionary.get(name);
        if (item == null) return ERROR_UNKNOWN_VARIABLE;
        switch (item.what()) {
            case VARIABLE:
                result[0] = item.variable();
                return OK;
            case EXPRESSION: {
                final char[] exp = item.expression().toCharArray();
                final int expEnd = exp.length - 1;
                if (engine(exp, 0, expEnd, result, new int[] {expEnd}, dictionary) == OK) return OK;
                else return ERROR_CALCULATION_ERROR;
            }
            default:
                return ERROR_CALCULATION_ERROR;
        }
    }

    private static int function(String name, ArrayDeque<Double> par, double[] result, Map<String, Item> dictionary) {
        final int npar = par.size();
        if (npar > MAX_N_PAR) return ERROR_UNKNOWN_FUNCTION;
        final Item item = dictionary.get(sss.charAt(npar) + name);
        if (item == null) return ERROR_UNKNOWN_FUNCTION;
        final double[] pp = new double[MAX_N_PAR];
        for (int i = 0; i < npar; ++i) pp[i] = par.pop();
        setErrno(0);
        if (item.function() == null) return ERROR_CALCULATION_ERROR;
        switch (npar) {
            case 0 -> result[0] = ((F0) item.function()).apply();
            case 1 -> result[0] = ((F1) item.function()).apply(pp[0]);
            case 2 -> result[0] = ((F2) item.function()).apply(pp[1], pp[0]);
            case 3 -> result[0] = ((F3) item.function()).apply(pp[2], pp[1], pp[0]);
            case 4 -> result[0] = ((F4) item.function()).apply(pp[3], pp[2], pp[1], pp[0]);
            default -> result[0] = ((F5) item.function()).apply(pp[4], pp[3], pp[2], pp[1], pp[0]);
        }
        return (errno() == 0) ? OK : ERROR_CALCULATION_ERROR;
    }

    /** A number (strtod's), a variable, or a function and its parameters. */
    private static int operand(char[] b, int begin, int end, double[] result, int[] endp,
                               Map<String, Item> dictionary) {
        int pointer = begin;
        int status;
        char c;

        // get number
        if (!isalpha(at(b, pointer))) {
            final String buffer = buffer(b);
            result[0] = CStr.strtod(buffer, pointer);
            final int stop = CStr.end();
            final int errno = strtodErrno(buffer, pointer, stop, result[0]);
            if (errno == 0) {
                endp[0] = (stop > pointer ? stop : pointer) - 1;
                return OK;
            } else {
                endp[0] = begin;
                return ERROR_CALCULATION_ERROR;
            }
        }

        // get name
        while (pointer <= end) {
            c = at(b, pointer);
            if (c != '_' && !isalnum(c)) break;
            pointer++;
        }
        final String name = new String(b, begin, Math.min(pointer, b.length) - begin);

        // get variable
        result[0] = 0.0;
        for (;; pointer++) {
            c = (pointer > end) ? '\0' : at(b, pointer);
            if (!isspace(c)) break;
        }
        if (c != '(') {
            status = variable(name, result, dictionary);
            endp[0] = (status == OK) ? pointer - 1 : begin;
            return status;
        }

        // get function
        final ArrayDeque<Integer> pos = new ArrayDeque<>();
        final ArrayDeque<Double> par = new ArrayDeque<>();
        final double[] value = new double[1];
        int parBegin = pointer + 1;
        for (;; pointer++) {
            c = (pointer > end) ? '\0' : at(b, pointer);
            switch (c) {
                case '\0':
                    endp[0] = pos.peek();
                    return ERROR_UNPAIRED_PARENTHESIS;
                case '(':
                    pos.push(pointer);
                    break;
                case ',':
                    if (pos.size() == 1) {
                        final int[] parEnd = {pointer - 1};
                        status = engine(b, parBegin, parEnd[0], value, parEnd, dictionary);
                        if (status == WARNING_BLANK_STRING) {
                            endp[0] = parEnd[0] - 1;
                            return ERROR_EMPTY_PARAMETER;
                        }
                        if (status != OK) {
                            endp[0] = parEnd[0];
                            return status;
                        }
                        par.push(value[0]);
                        parBegin = pointer + 1;
                    }
                    break;
                case ')':
                    if (pos.size() > 1) {
                        pos.pop();
                        break;
                    } else {
                        final int[] parEnd = {pointer - 1};
                        status = engine(b, parBegin, parEnd[0], value, parEnd, dictionary);
                        switch (status) {
                            case OK:
                                par.push(value[0]);
                                break;
                            case WARNING_BLANK_STRING:
                                if (par.size() != 0) {
                                    endp[0] = parEnd[0] - 1;
                                    return ERROR_EMPTY_PARAMETER;
                                }
                                break;
                            default:
                                endp[0] = parEnd[0];
                                return status;
                        }
                        status = function(name, par, result, dictionary);
                        endp[0] = (status == OK) ? pointer : begin;
                        return status;
                    }
                default:
                    break;
            }
        }
    }

    /** The characters as the C string they are: up to the first NUL. */
    private static String buffer(char[] b) {
        int n = 0;
        while (n < b.length && b[n] != '\0') n++;
        return new String(b, 0, n);
    }

    /**
     * strtod's errno, as glibc and Microsoft's UCRT both set it: ERANGE when the number overflows
     * (±HUGE_VAL), underflows to zero from non-zero digits, or is subnormal and not exact.
     */
    private static int strtodErrno(String s, int from, int stop, double v) {
        if (stop <= from) return 0;
        if (Double.isInfinite(v)) return ERANGE;
        if (v != 0 && Math.abs(v) < Double.MIN_NORMAL) {
            return exactValue(s.substring(from, stop)).compareTo(new BigDecimal(Math.abs(v))) == 0 ? 0 : ERANGE;
        }
        if (v == 0) {
            final boolean hex = stop - from > 1 && s.charAt(from) == '0'
                && (s.charAt(from + 1) == 'x' || s.charAt(from + 1) == 'X');
            for (int k = hex ? from + 2 : from; k < stop; k++) {
                final char c = s.charAt(k);
                if (!hex && (c == 'e' || c == 'E')) break;
                if (hex && (c == 'p' || c == 'P')) break;
                if (c != '0' && c != '.') return ERANGE;
            }
        }
        return 0;
    }

    /** The exact value of a decimal or hexadecimal (0x...p...) number as strtod reads it. */
    private static BigDecimal exactValue(String lit) {
        if (lit.length() > 1 && lit.charAt(0) == '0' && (lit.charAt(1) == 'x' || lit.charAt(1) == 'X')) {
            int p = lit.indexOf('p');
            if (p < 0) p = lit.indexOf('P');
            final String mant = p < 0 ? lit.substring(2) : lit.substring(2, p);
            final int exp = p < 0 ? 0 : Integer.parseInt(lit.substring(p + 1).replace("+", ""));
            final int dot = mant.indexOf('.');
            final String digits = dot < 0 ? mant : mant.substring(0, dot) + mant.substring(dot + 1);
            final int binaryExp = exp - 4 * (dot < 0 ? 0 : mant.length() - dot - 1);
            final BigDecimal m = new BigDecimal(new BigInteger(digits.isEmpty() ? "0" : digits, 16));
            return binaryExp >= 0 ? m.multiply(new BigDecimal(BigInteger.TWO.pow(binaryExp)))
                : m.divide(new BigDecimal(BigInteger.TWO.pow(-binaryExp)));
        }
        return new BigDecimal(lit.endsWith(".") ? lit + "0" : lit);
    }

    /** Executes the operator of the top of the stack on its two values. */
    private static int maker(int op, ArrayDeque<Double> val) {
        if (val.size() < 2) return ERROR_SYNTAX_ERROR;
        final double val2 = val.pop();
        final double val1 = val.pop();
        final double r;
        switch (op) {
            case OR -> r = (val1 != 0 || val2 != 0) ? 1. : 0.;
            case AND -> r = (val1 != 0 && val2 != 0) ? 1. : 0.;
            case EQ -> r = (val1 == val2) ? 1. : 0.;
            case NE -> r = (val1 != val2) ? 1. : 0.;
            case GE -> r = (val1 >= val2) ? 1. : 0.;
            case GT -> r = (val1 > val2) ? 1. : 0.;
            case LE -> r = (val1 <= val2) ? 1. : 0.;
            case LT -> r = (val1 < val2) ? 1. : 0.;
            case PLUS, UNARY_PLUS -> r = val1 + val2;
            case MINUS, UNARY_MINUS -> r = val1 - val2;
            case MULT -> r = val1 * val2;
            case DIV -> {
                if (val2 == 0.0) return ERROR_CALCULATION_ERROR;
                r = val1 / val2;
            }
            case POW -> {
                setErrno(0);
                r = StdMath.pow(val1, val2);
                if (errno() != 0) return ERROR_CALCULATION_ERROR;
            }
            default -> {
                return ERROR_CALCULATION_ERROR;
            }
        }
        val.push(r);
        return OK;
    }

    private static final int[][] SyntaxTable = {
        // E  (  || && == != >= >  <= <  +  -  u+ u- *  /  ^  )  V - current token
        {0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 2, 2, 2, 2, 0, 0, 0, 0, 1},   // E - previous
        {0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 2, 2, 2, 2, 0, 0, 0, 0, 1},   // (   token
        {0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 2, 2, 2, 2, 0, 0, 0, 0, 1},   // ||
        {0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 2, 2, 2, 2, 0, 0, 0, 0, 1},   // &&
        {0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 2, 2, 2, 2, 0, 0, 0, 0, 1},   // ==
        {0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 2, 2, 2, 2, 0, 0, 0, 0, 1},   // !=
        {0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 2, 2, 2, 2, 0, 0, 0, 0, 1},   // >=
        {0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 2, 2, 2, 2, 0, 0, 0, 0, 1},   // >
        {0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 2, 2, 2, 2, 0, 0, 0, 0, 1},   // <=
        {0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 2, 2, 2, 2, 0, 0, 0, 0, 1},   // <
        {0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 2, 2, 2, 2, 0, 0, 0, 0, 1},   // +
        {0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 2, 2, 2, 2, 0, 0, 0, 0, 1},   // -
        {0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 2, 2, 2, 2, 0, 0, 0, 0, 1},   // unary +
        {0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 2, 2, 2, 2, 0, 0, 0, 0, 1},   // unary -
        {0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 2, 2, 2, 2, 0, 0, 0, 0, 1},   // *
        {0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 2, 2, 2, 2, 0, 0, 0, 0, 1},   // /
        {0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 2, 2, 2, 2, 0, 0, 0, 0, 1},   // ^
        {3, 0, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 0},   // )
        {3, 0, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 0}    // V = {.,N,C}
    };

    private static final int[][] ActionTable = {
        // E  (  || && == != >= >  <= <  +  -  u+ u- *  /  ^  ) - current operator
        {0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, -1}, // E - top operator
        {-1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 3}, // (   in stack
        {4, 1, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 4},  // ||
        {4, 1, 4, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 4},  // &&
        {4, 1, 4, 4, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 4},  // ==
        {4, 1, 4, 4, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 4},  // !=
        {4, 1, 4, 4, 4, 4, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 4},  // >=
        {4, 1, 4, 4, 4, 4, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 4},  // >
        {4, 1, 4, 4, 4, 4, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 4},  // <=
        {4, 1, 4, 4, 4, 4, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 4},  // <
        {4, 1, 4, 4, 4, 4, 4, 4, 4, 4, 2, 2, 1, 1, 1, 1, 1, 4},  // +
        {4, 1, 4, 4, 4, 4, 4, 4, 4, 4, 2, 2, 1, 1, 1, 1, 1, 4},  // -
        {4, 1, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 1, 1, 4, 4, 1, 4},  // unary +
        {4, 1, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 1, 1, 4, 4, 1, 4},  // unary -
        {4, 1, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 1, 1, 2, 2, 1, 4},  // *
        {4, 1, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 1, 1, 2, 2, 1, 4},  // /
        {4, 1, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 1, 1, 4, 4, 4, 4}   // ^
    };

    /** Evaluates the characters from begin to end (inclusive): the operator-precedence engine. */
    private static int engine(char[] b, int begin, int end, double[] result, int[] endp,
                              Map<String, Item> dictionary) {
        final ArrayDeque<Integer> op = new ArrayDeque<>();
        final ArrayDeque<Integer> pos = new ArrayDeque<>();
        final ArrayDeque<Double> val = new ArrayDeque<>();
        final double[] value = new double[1];
        final int[] operandEnd = new int[1];
        int pointer = begin;
        int iWhat;
        int iCur;
        int iPrev = 0;
        int iTop;
        int status;
        char c;

        op.push(0);
        pos.push(pointer);
        for (;; pointer++) {
            c = (pointer > end) ? '\0' : at(b, pointer);
            if (!isspace(c)) break;
        }
        if (c == '\0') {
            endp[0] = begin;
            return WARNING_BLANK_STRING;
        }
        for (;; pointer++) {

            // next token
            c = (pointer > end) ? '\0' : at(b, pointer);
            if (isspace(c)) continue;
            switch (c) {
                case '\0' -> iCur = ENDL;
                case '(' -> iCur = LBRA;
                case '|' -> {
                    if (at(b, pointer + 1) == '|') {
                        pointer++;
                        iCur = OR;
                    } else {
                        endp[0] = pointer;
                        return ERROR_UNEXPECTED_SYMBOL;
                    }
                }
                case '&' -> {
                    if (at(b, pointer + 1) == '&') {
                        pointer++;
                        iCur = AND;
                    } else {
                        endp[0] = pointer;
                        return ERROR_UNEXPECTED_SYMBOL;
                    }
                }
                case '=' -> {
                    if (at(b, pointer + 1) == '=') {
                        pointer++;
                        iCur = EQ;
                    } else {
                        endp[0] = pointer;
                        return ERROR_UNEXPECTED_SYMBOL;
                    }
                }
                case '!' -> {
                    if (at(b, pointer + 1) == '=') {
                        pointer++;
                        iCur = NE;
                    } else {
                        endp[0] = pointer;
                        return ERROR_UNEXPECTED_SYMBOL;
                    }
                }
                case '>' -> {
                    if (at(b, pointer + 1) == '=') {
                        pointer++;
                        iCur = GE;
                    } else {
                        iCur = GT;
                    }
                }
                case '<' -> {
                    if (at(b, pointer + 1) == '=') {
                        pointer++;
                        iCur = LE;
                    } else {
                        iCur = LT;
                    }
                }
                case '+' -> iCur = PLUS;
                case '-' -> iCur = MINUS;
                case '*' -> {
                    if (at(b, pointer + 1) == '*') {
                        pointer++;
                        iCur = POW;
                    } else {
                        iCur = MULT;
                    }
                }
                case '/' -> iCur = DIV;
                case '^' -> iCur = POW;
                case ')' -> iCur = RBRA;
                default -> {
                    if (c == '.' || isalnum(c)) {
                        iCur = VALUE;
                    } else {
                        endp[0] = pointer;
                        return ERROR_UNEXPECTED_SYMBOL;
                    }
                }
            }

            // syntax analysis
            iWhat = SyntaxTable[iPrev][iCur];
            iPrev = iCur;
            if (iWhat == 0) {
                endp[0] = pointer;
                return ERROR_SYNTAX_ERROR;
            }
            if (iWhat == 1) {
                // operand: number, variable, function
                status = operand(b, pointer, end, value, operandEnd, dictionary);
                pointer = operandEnd[0];
                if (status != OK) {
                    endp[0] = pointer;
                    return status;
                }
                val.push(value[0]);
                continue;
            }
            if (iWhat == 2) {
                // unary + or -: the same for the syntax, a zero before it for the values
                val.push(0.0);
                if (iCur == PLUS) iCur = UNARY_PLUS;
                if (iCur == MINUS) iCur = UNARY_MINUS;
            }

            // next operator
            for (;;) {
                if (op.isEmpty()) {
                    endp[0] = pointer;
                    return ERROR_SYNTAX_ERROR;
                }
                iTop = op.peek();
                final int action = ActionTable[iTop][iCur];
                if (action == -1) {
                    if (op.size() > 1) pointer = pos.peek();
                    endp[0] = pointer;
                    return ERROR_UNPAIRED_PARENTHESIS;
                } else if (action == 0) {
                    // the end: one value left
                    if (val.size() == 1) {
                        result[0] = val.peek();
                        endp[0] = pointer;
                        return OK;
                    } else {
                        endp[0] = pointer;
                        return ERROR_SYNTAX_ERROR;
                    }
                } else if (action == 1) {
                    op.push(iCur);
                    pos.push(pointer);
                } else if (action == 2) {
                    // execute the top operator, the current one in its place
                    status = maker(iTop, val);
                    if (status != OK) {
                        endp[0] = pos.peek();
                        return status;
                    }
                    op.pop();
                    op.push(iCur);
                    pos.pop();
                    pos.push(pointer);
                } else if (action == 3) {
                    op.pop();
                    pos.pop();
                } else {
                    // execute the top operator, delete it, again with the same current one
                    status = maker(iTop, val);
                    if (status != OK) {
                        endp[0] = pos.peek();
                        return status;
                    }
                    op.pop();
                    pos.pop();
                    continue;
                }
                break;
            }
        }
    }

    /* ---- the dictionary's entries ---- */

    /** The name without its leading and trailing blanks (REMOVE_BLANKS); null when there is none. */
    private static String trimmed(String name) {
        int p = 0;
        while (p < name.length() && isspace(name.charAt(p))) p++;
        int n = name.length() - p;
        while (n > 0 && isspace(name.charAt(p + n - 1))) n--;
        return name.substring(p, p + n);
    }

    private void setItem(String prefix, String name, Item item) {
        if (name == null || name.isEmpty()) {
            theStatus = ERROR_NOT_A_NAME;
            return;
        }
        final String t = trimmed(name);
        if (t.isEmpty()) {
            theStatus = ERROR_NOT_A_NAME;
            return;
        }
        for (int i = 0; i < t.length(); i++) {
            final char c = t.charAt(i);
            if (c != '_' && !isalnum(c)) {
                theStatus = ERROR_NOT_A_NAME;
                return;
            }
        }
        final String itemName = prefix + t;
        if (theDictionary.containsKey(itemName)) {
            theDictionary.put(itemName, item);
            // the C++ compares the prefixed name with the name as given: a variable named with blanks
            // around it is "an existing function"
            theStatus = itemName.equals(name) ? WARNING_EXISTING_VARIABLE : WARNING_EXISTING_FUNCTION;
        } else {
            theDictionary.put(itemName, item);
            theStatus = OK;
        }
    }

    /* ---- the API ---- */

    /**
     * The value of the expression; status() says whether it could be computed. An expression variable
     * that refers to itself overflows the C++'s stack; here it is a calculation error.
     */
    public double evaluate(String expression) {
        theExpression = null;
        thePosition = 0;
        theStatus = WARNING_BLANK_STRING;
        theResult = 0.0;
        if (expression != null) {
            final int nul = expression.indexOf('\0');
            theExpression = (nul < 0 ? expression : expression.substring(0, nul)).toCharArray();
            final double[] result = {0.0};
            final int[] position = {0};
            try {
                theStatus = engine(theExpression, 0, theExpression.length - 1, result, position, theDictionary);
                thePosition = position[0];
            } catch (StackOverflowError e) {
                theStatus = ERROR_CALCULATION_ERROR;
                thePosition = 0;
            }
            theResult = result[0];
        }
        return theResult;
    }

    public int status() {
        return theStatus;
    }

    /** Where the evaluation stopped: an index into the expression. */
    public int error_position() {
        return thePosition;
    }

    public void print_error() {
        if (theStatus != OK) StdStreams.cerr().print(error_name() + "\n");
    }

    public String error_name() {
        final String prefix = "Evaluator : ";
        return switch (theStatus) {
            case ERROR_NOT_A_NAME -> prefix + "invalid name";
            case ERROR_SYNTAX_ERROR -> prefix + "syntax error";
            case ERROR_UNPAIRED_PARENTHESIS -> prefix + "unpaired parenthesis";
            case ERROR_UNEXPECTED_SYMBOL -> prefix + "unexpected symbol";
            case ERROR_UNKNOWN_VARIABLE -> prefix + "unknown variable";
            case ERROR_UNKNOWN_FUNCTION -> prefix + "unknown function";
            case ERROR_EMPTY_PARAMETER -> prefix + "empty parameter in function call";
            case ERROR_CALCULATION_ERROR -> prefix + "calculation error";
            default -> " ";
        };
    }

    public void setVariable(String name, double value) {
        setItem("", name, Item.of(value));
    }

    /** A variable whose value is an expression, evaluated each time the variable is read. */
    public void setVariable(String name, String expression) {
        setItem("", name, Item.of(expression));
    }

    public void setFunction(String name, F0 fun) {
        setItem("0", name, Item.function(fun));
    }

    public void setFunction(String name, F1 fun) {
        setItem("1", name, Item.function(fun));
    }

    public void setFunction(String name, F2 fun) {
        setItem("2", name, Item.function(fun));
    }

    public void setFunction(String name, F3 fun) {
        setItem("3", name, Item.function(fun));
    }

    public void setFunction(String name, F4 fun) {
        setItem("4", name, Item.function(fun));
    }

    public void setFunction(String name, F5 fun) {
        setItem("5", name, Item.function(fun));
    }

    /** Whether the name is in the dictionary (any entry: "1sin" finds the function sin, as in C++). */
    public boolean findVariable(String name) {
        if (name == null || name.isEmpty()) return false;
        final String t = trimmed(name);
        if (t.isEmpty()) return false;
        return theDictionary.containsKey(t);
    }

    public boolean findFunction(String name, int npar) {
        if (name == null || name.isEmpty()) return false;
        if (npar < 0 || npar > MAX_N_PAR) return false;
        final String t = trimmed(name);
        if (t.isEmpty()) return false;
        return theDictionary.containsKey(sss.charAt(npar) + t);
    }

    public void removeVariable(String name) {
        if (name == null || name.isEmpty()) return;
        final String t = trimmed(name);
        if (t.isEmpty()) return;
        theDictionary.remove(t);
    }

    public void removeFunction(String name, int npar) {
        if (name == null || name.isEmpty()) return;
        if (npar < 0 || npar > MAX_N_PAR) return;
        final String t = trimmed(name);
        if (t.isEmpty()) return;
        theDictionary.remove(sss.charAt(npar) + t);
    }

    public void clear() {
        theDictionary.clear();
        theExpression = null;
        thePosition = 0;
        theStatus = OK;
        theResult = 0.0;
    }

    /** The names in the dictionary, functions as "name/npar": for completion and listings. */
    public java.util.SortedSet<String> names() {
        final java.util.TreeSet<String> s = new java.util.TreeSet<>();
        for (Map.Entry<String, Item> e : theDictionary.entrySet()) {
            final String k = e.getKey();
            if (e.getValue().what() == What.FUNCTION && !k.isEmpty() && Character.isDigit(k.charAt(0))) {
                s.add(k.substring(1) + "/" + k.charAt(0));
            } else {
                s.add(k);
            }
        }
        return s;
    }

    /** The value of a variable as evaluate(name) gives it; NaN when there is none. */
    public double value(String name) {
        final double[] r = {0.0};
        return variable(name, r, theDictionary) == OK ? r[0] : Double.NaN;
    }

    /* ---- setStdMath ---- */

    /** pi, e, gamma, radian, rad, degree, deg; abs, min, max, sqrt, pow and the C library's functions. */
    public void setStdMath() {
        setVariable("pi", 3.14159265358979323846);
        setVariable("e", 2.7182818284590452354);
        setVariable("gamma", 0.577215664901532861);
        setVariable("radian", 1.0);
        setVariable("rad", 1.0);
        setVariable("degree", 3.14159265358979323846 / 180.);
        setVariable("deg", 3.14159265358979323846 / 180.);

        setFunction("abs", (F1) a -> (a < 0) ? -a : a);
        setFunction("min", (F2) (a, b) -> (a < b) ? a : b);
        setFunction("max", (F2) (a, b) -> (a > b) ? a : b);
        setFunction("sqrt", (F1) StdMath::sqrt);
        setFunction("pow", (F2) StdMath::pow);
        setFunction("sin", (F1) StdMath::sin);
        setFunction("cos", (F1) StdMath::cos);
        setFunction("tan", (F1) StdMath::tan);
        setFunction("asin", (F1) StdMath::asin);
        setFunction("acos", (F1) StdMath::acos);
        setFunction("atan", (F1) CMath::atan);
        setFunction("atan2", (F2) CMath::atan2);
        setFunction("sinh", (F1) StdMath::sinh);
        setFunction("cosh", (F1) StdMath::cosh);
        setFunction("tanh", (F1) CMath::tanh);
        setFunction("exp", (F1) StdMath::exp);
        setFunction("log", (F1) StdMath::log);
        setFunction("log10", (F1) StdMath::log10);
    }

    /**
     * The C library's functions as setStdMath registers them, with the errno they set: EDOM for an
     * argument out of the domain, ERANGE for a pole or an overflow, and for an underflow to zero in glibc
     * (Microsoft's UCRT, which Windows programs use, leaves errno then).
     */
    static final class StdMath {

        /** Whether the C library is glibc's (it sets ERANGE when exp or pow underflow to zero). */
        private static final boolean GLIBC = !System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
            .contains("win");

        private StdMath() {
        }

        static double sqrt(double a) {
            if (a < 0) setErrno(EDOM);
            return Math.sqrt(a);
        }

        static double sin(double a) {
            if (Double.isInfinite(a)) setErrno(EDOM);
            return CMath.sin(a);
        }

        static double cos(double a) {
            if (Double.isInfinite(a)) setErrno(EDOM);
            return CMath.cos(a);
        }

        static double tan(double a) {
            if (Double.isInfinite(a)) setErrno(EDOM);
            return CMath.tan(a);
        }

        static double asin(double a) {
            if (a > 1 || a < -1) setErrno(EDOM);
            return CMath.asin(a);
        }

        static double acos(double a) {
            if (a > 1 || a < -1) setErrno(EDOM);
            return CMath.acos(a);
        }

        static double sinh(double a) {
            final double r = CMath.sinh(a);
            if (Double.isInfinite(r) && !Double.isInfinite(a)) setErrno(ERANGE);
            return r;
        }

        static double cosh(double a) {
            final double r = CMath.cosh(a);
            if (Double.isInfinite(r) && !Double.isInfinite(a)) setErrno(ERANGE);
            return r;
        }

        static double exp(double a) {
            final double r = CMath.exp(a);
            if (!Double.isInfinite(a) && !Double.isNaN(a) && (Double.isInfinite(r) || (GLIBC && r == 0))) {
                setErrno(ERANGE);
            }
            return r;
        }

        static double log(double a) {
            if (a < 0) setErrno(EDOM);
            else if (a == 0) setErrno(ERANGE);
            return CMath.log(a);
        }

        static double log10(double a) {
            if (a < 0) setErrno(EDOM);
            else if (a == 0) setErrno(ERANGE);
            return CMath.log10(a);
        }

        static double pow(double x, double y) {
            final double r = CMath.pow(x, y);
            final boolean finite = !Double.isInfinite(x) && !Double.isNaN(x) && !Double.isInfinite(y) && !Double.isNaN(y);
            if (finite) {
                if (x < 0 && y != Math.rint(y)) {
                    setErrno(EDOM);
                } else if (x == 0 && y < 0) {
                    setErrno(ERANGE);
                } else if (Double.isInfinite(r) || (GLIBC && r == 0 && x != 0)) {
                    setErrno(ERANGE);
                }
            }
            return r;
        }
    }

    /* ---- setSystemOfUnits ---- */

    public void setSystemOfUnits() {
        setSystemOfUnits(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0);
    }

    public void setSystemOfUnits(double meter) {
        setSystemOfUnits(meter, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0);
    }

    public void setSystemOfUnits(double meter, double kilogram) {
        setSystemOfUnits(meter, kilogram, 1.0, 1.0, 1.0, 1.0, 1.0);
    }

    public void setSystemOfUnits(double meter, double kilogram, double second) {
        setSystemOfUnits(meter, kilogram, second, 1.0, 1.0, 1.0, 1.0);
    }

    public void setSystemOfUnits(double meter, double kilogram, double second, double ampere) {
        setSystemOfUnits(meter, kilogram, second, ampere, 1.0, 1.0, 1.0);
    }

    public void setSystemOfUnits(double meter, double kilogram, double second, double ampere, double kelvin) {
        setSystemOfUnits(meter, kilogram, second, ampere, kelvin, 1.0, 1.0);
    }

    public void setSystemOfUnits(double meter, double kilogram, double second, double ampere, double kelvin,
                                 double mole) {
        setSystemOfUnits(meter, kilogram, second, ampere, kelvin, mole, 1.0);
    }

    /** The SI and derived units in the given base units (Geant4's: mm, MeV/(mm/ns)², ns, eplus/ns...). */
    public void setSystemOfUnits(double meter, double kilogram, double second, double ampere, double kelvin,
                                 double mole, double candela) {
        EvaluatorUnits.set(this, meter, kilogram, second, ampere, kelvin, mole, candela);
    }
}
