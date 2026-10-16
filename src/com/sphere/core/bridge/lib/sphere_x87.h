/*
 * sphere_x87.h - the Sphere x87 ABI (see sphere_x87.S): long double arithmetic and MinGW's long
 * double functions on the x87 unit, every value passed by address as the 10 bytes fldt/fstpt
 * use. Link sphere_x87.dll / libsphere_x87.so / libsphere_x87.dylib, or, in a ROOT macro:
 *   gSystem->Load("libsphere_x87");
 * x86-64 only; elsewhere Sphere's software long double gives the same bits.
 */
#ifndef SPHERE_X87_H
#define SPHERE_X87_H

#ifdef __cplusplus
extern "C" {
#endif

/* an 80-bit value in memory: 10 bytes used, 16 reserved (the alignment of a long double) */
typedef struct sx87_ld80 { unsigned char bytes[16]; } sx87_ld80;

void sx87_add(const sx87_ld80 *a, const sx87_ld80 *b, sx87_ld80 *r);
void sx87_sub(const sx87_ld80 *a, const sx87_ld80 *b, sx87_ld80 *r);
void sx87_mul(const sx87_ld80 *a, const sx87_ld80 *b, sx87_ld80 *r);
void sx87_div(const sx87_ld80 *a, const sx87_ld80 *b, sx87_ld80 *r);
void sx87_sqrt(const sx87_ld80 *a, sx87_ld80 *r);
/* -1 a < b, 0 a == b, 1 a > b, 2 unordered (a NaN) */
int sx87_cmp(const sx87_ld80 *a, const sx87_ld80 *b);
void sx87_from_f64(const double *x, sx87_ld80 *r);
void sx87_to_f64(const sx87_ld80 *a, double *r);
void sx87_from_i64(const long long *x, sx87_ld80 *r);
void sx87_to_i64(const sx87_ld80 *a, long long *r);
/* MinGW-w64's sinl, cosl, asinl, atan2l */
void sx87_sin(const sx87_ld80 *x, sx87_ld80 *r);
void sx87_cos(const sx87_ld80 *x, sx87_ld80 *r);
void sx87_asin(const sx87_ld80 *x, sx87_ld80 *r);
void sx87_atan2(const sx87_ld80 *y, const sx87_ld80 *x, sx87_ld80 *r);

#ifdef __cplusplus
}
#endif

#endif
