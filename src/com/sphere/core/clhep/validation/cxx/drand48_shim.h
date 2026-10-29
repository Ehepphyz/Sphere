#ifndef DRAND48_SHIM_H
#define DRAND48_SHIM_H
#ifdef __cplusplus
extern "C" {
#endif
double drand48(void);
void srand48(long seedval);
unsigned short *seed48(unsigned short seed16v[3]);
#ifdef __cplusplus
}
#endif
#endif
