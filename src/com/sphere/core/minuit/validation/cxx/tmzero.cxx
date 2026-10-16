// TMinuit::BuildArrays allocates its work arrays with new Double_t[n] and
// leaves them as they are: a few results read them before writing them (the
// parabolic error of MNERRS before any covariance matrix exists, for one), and
// in C++ that is whatever the heap held. Java arrays start at zero; so that
// the reference shows what the code computes and not what the heap held, the
// reference programs get memory that starts at zero too.
#include <cstdlib>
#include <new>

void *operator new(std::size_t n)
{
   void *p = std::calloc(n ? n : 1, 1);
   if (!p) throw std::bad_alloc();
   return p;
}

void *operator new[](std::size_t n)
{
   void *p = std::calloc(n ? n : 1, 1);
   if (!p) throw std::bad_alloc();
   return p;
}

void operator delete(void *p) noexcept { std::free(p); }
void operator delete[](void *p) noexcept { std::free(p); }
void operator delete(void *p, std::size_t) noexcept { std::free(p); }
void operator delete[](void *p, std::size_t) noexcept { std::free(p); }
