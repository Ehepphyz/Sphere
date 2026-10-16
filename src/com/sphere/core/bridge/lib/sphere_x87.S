/*
 * sphere_x87.S - the Sphere x87 ABI: long double arithmetic (the x87 80-bit extended format)
 * and the long double functions of MinGW's C library, computed by the x87 unit itself, with
 * one C interface that C, C++ (ROOT macros), Fortran, Julia, Python (ctypes) and Java (FFM)
 * can all call.
 *
 * One source for x86-64 on every operating system:
 *   Windows                    Win64 convention: arguments in rcx, rdx, r8;
 *                              the callee owns the 32 bytes of home space above the return address
 *   Linux, macOS Intel, BSD    System V convention: arguments in rdi, rsi, rdx; a 128-byte red zone
 * The convention follows the target (_WIN64), or is forced with -DSPHERE_X87_WIN64 / -DSPHERE_X87_SYSV
 * (Sphere assembles both and carries the bytes of each).
 *
 * Every value is passed by address: a pointer to the 10 bytes of an 80-bit value, as fldt and
 * fstpt read and write it (64-bit significand with its integer bit, then sign and 15-bit
 * exponent, little-endian). The conventions disagree on how a long double is passed (on the
 * x87 stack for System V, not at all for MSVC, whose long double is double); by address they
 * agree, so the same functions serve every compiler and every language.
 *
 * Each function runs under the control word C++ programs run with on x86-64: 64-bit precision,
 * rounding to nearest, every exception masked (0x037F), and gives the caller's back. The
 * functions are leaves that never move the stack pointer (the saved control word lives in the
 * Win64 home space or the System V red zone), position independent, without relocations: Sphere
 * copies their bytes into executable memory; they also build as a shared library:
 *   gcc -shared -o sphere_x87.dll sphere_x87.S              Windows (MinGW-w64)
 *   gcc -shared -fPIC -o libsphere_x87.so sphere_x87.S      Linux, BSD
 *   clang -shared -o libsphere_x87.dylib sphere_x87.S       macOS Intel
 * The x87 unit exists on x86 processors only: elsewhere (ARM, RISC-V...) the same interface is
 * served by Sphere's software long double (com.sphere.core.hepmc3.cxx.LongDouble), which
 * X87Abi.check compares bit for bit with this code.
 */

#if defined(SPHERE_X87_SYSV)
#  define WIN64_ABI 0
#elif defined(SPHERE_X87_WIN64) || defined(_WIN64)
#  define WIN64_ABI 1
#else
#  define WIN64_ABI 0
#endif

#if WIN64_ABI
#  define A1  %rcx
#  define A2  %rdx
#  define A3  %r8
#  define CW  8(%rsp)    /* home space: the caller's control word */
#  define CWN 10(%rsp)   /* and the one this code runs with */
#else
#  define A1  %rdi
#  define A2  %rsi
#  define A3  %rdx
#  define CW  -8(%rsp)   /* red zone */
#  define CWN -6(%rsp)
#endif

#if defined(__APPLE__)
#  define SYM(n) _##n
#else
#  define SYM(n) n
#endif

#if defined(__ELF__)
#  define FUNCTION(n) .globl SYM(n); .type SYM(n), @function; .p2align 4; SYM(n):
#elif defined(_WIN32) || defined(__CYGWIN__)
#  define FUNCTION(n) .globl SYM(n); .def SYM(n); .scl 2; .type 32; .endef; .p2align 4; SYM(n):
#else
#  define FUNCTION(n) .globl SYM(n); .p2align 4; SYM(n):
#endif

/* the control word of C++ programs, the caller's kept */
.macro ENTER cw=0x037f
    fnstcw  CW
    movw    $\cw, CWN
    fldcw   CWN
.endm

/* the caller's control word back, and return */
.macro LEAVE
    fldcw   CW
    ret
.endm

    .text

/* void sx87_add(const ld80 *a, const ld80 *b, ld80 *r): *r = *a + *b */
FUNCTION(sx87_add)
    ENTER
    fldt    (A2)
    fldt    (A1)
    fadd    %st(1), %st
    fstpt   (A3)
    fstp    %st(0)
    LEAVE

/* void sx87_sub(const ld80 *a, const ld80 *b, ld80 *r): *r = *a - *b */
FUNCTION(sx87_sub)
    ENTER
    fldt    (A2)
    fldt    (A1)
    fsub    %st(1), %st             /* st0 = st0 - st1 */
    fstpt   (A3)
    fstp    %st(0)
    LEAVE

/* void sx87_mul(const ld80 *a, const ld80 *b, ld80 *r): *r = *a * *b */
FUNCTION(sx87_mul)
    ENTER
    fldt    (A2)
    fldt    (A1)
    fmul    %st(1), %st
    fstpt   (A3)
    fstp    %st(0)
    LEAVE

/* void sx87_div(const ld80 *a, const ld80 *b, ld80 *r): *r = *a / *b */
FUNCTION(sx87_div)
    ENTER
    fldt    (A2)
    fldt    (A1)
    fdiv    %st(1), %st             /* st0 = st0 / st1 */
    fstpt   (A3)
    fstp    %st(0)
    LEAVE

/* void sx87_sqrt(const ld80 *a, ld80 *r): *r = sqrtl(*a) (MinGW's sqrtl is this instruction) */
FUNCTION(sx87_sqrt)
    ENTER
    fldt    (A1)
    fsqrt
    fstpt   (A2)
    LEAVE

/* int sx87_cmp(const ld80 *a, const ld80 *b): -1 when a < b, 0 when a == b, 1 when a > b, 2 unordered (a NaN) */
FUNCTION(sx87_cmp)
    ENTER
    fldt    (A2)
    fldt    (A1)
    fucomip %st(1), %st
    fstp    %st(0)
    jp      3f
    jb      1f
    je      2f
    movl    $1, %eax
    LEAVE
1:  movl    $-1, %eax
    LEAVE
2:  xorl    %eax, %eax
    LEAVE
3:  movl    $2, %eax
    LEAVE

/* void sx87_from_f64(const double *x, ld80 *r): exact */
FUNCTION(sx87_from_f64)
    ENTER
    fldl    (A1)
    fstpt   (A2)
    LEAVE

/* void sx87_to_f64(const ld80 *a, double *r): rounded to nearest, as a store to a double variable */
FUNCTION(sx87_to_f64)
    ENTER
    fldt    (A1)
    fstpl   (A2)
    LEAVE

/* void sx87_from_i64(const long long *x, ld80 *r): exact */
FUNCTION(sx87_from_i64)
    ENTER
    fildq   (A1)
    fstpt   (A2)
    LEAVE

/* void sx87_to_i64(const ld80 *a, long long *r): truncated toward zero, as the C cast (long long) a */
FUNCTION(sx87_to_i64)
    ENTER   0x0f7f                  /* rounding toward zero */
    fldt    (A1)
    fistpq  (A2)
    LEAVE

/*
 * void sx87_sin(const ld80 *x, ld80 *r), sx87_cos: MinGW-w64's sinl and cosl. fsin (fcos);
 * beyond the instruction's range (|x| >= 2^63, C2 set) the argument is first reduced by 2 pi
 * with fprem1, repeated while the reduction is partial.
 */
FUNCTION(sx87_sin)
    ENTER
    fldt    (A1)
    fsin
    fnstsw  %ax
    testl   $0x400, %eax
    jnz     1f
    fstpt   (A2)
    LEAVE
1:  fldpi
    fadd    %st(0), %st
    fxch    %st(1)
2:  fprem1
    fnstsw  %ax
    testl   $0x400, %eax
    jnz     2b
    fstp    %st(1)
    fsin
    fstpt   (A2)
    LEAVE

FUNCTION(sx87_cos)
    ENTER
    fldt    (A1)
    fcos
    fnstsw  %ax
    testl   $0x400, %eax
    jnz     1f
    fstpt   (A2)
    LEAVE
1:  fldpi
    fadd    %st(0), %st
    fxch    %st(1)
2:  fprem1
    fnstsw  %ax
    testl   $0x400, %eax
    jnz     2b
    fstp    %st(1)
    fcos
    fstpt   (A2)
    LEAVE

/* void sx87_asin(const ld80 *x, ld80 *r): MinGW-w64's asinl, atan2(x, sqrt(1 - x*x)) on the x87 unit */
FUNCTION(sx87_asin)
    ENTER
    fldt    (A1)
    fld     %st(0)
    fmul    %st(0), %st             /* x*x */
    fld1
    fsubp   %st, %st(1)             /* 1 - x*x (the AT&T spelling of Intel's fsubrp st(1), st) */
    fsqrt
    fpatan
    fstpt   (A2)
    LEAVE

/* void sx87_atan2(const ld80 *y, const ld80 *x, ld80 *r): MinGW-w64's atan2l, the fpatan instruction */
FUNCTION(sx87_atan2)
    ENTER
    fldt    (A1)
    fldt    (A2)
    fpatan                          /* st1 = atan(st1 / st0), popped */
    fstpt   (A3)
    LEAVE

#if defined(__ELF__)
    .section .note.GNU-stack,"",@progbits
#endif
