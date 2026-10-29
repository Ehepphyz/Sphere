// Every engine of CLHEP's Random package: its numbers, raw bits, and its saved states as text and as
// words, written for the Java port to be compared with. Its twin: validation.EnginesReference.
#include "CLHEP/Random/MixMaxRng.h"
#include "CLHEP/Random/JamesRandom.h"
#include "CLHEP/Random/MTwistEngine.h"
#include "CLHEP/Random/RanecuEngine.h"
#include "CLHEP/Random/RanluxEngine.h"
#include "CLHEP/Random/Ranlux64Engine.h"
#include "CLHEP/Random/RanluxppEngine.h"
#include "CLHEP/Random/RanshiEngine.h"
#include "CLHEP/Random/DualRand.h"
#include "CLHEP/Random/TripleRand.h"
#include "CLHEP/Random/Hurd160Engine.h"
#include "CLHEP/Random/Hurd288Engine.h"
#include "CLHEP/Random/DRand48Engine.h"
#include "CLHEP/Random/NonRandomEngine.h"
#include "CLHEP/Random/Random.h"
#include "CLHEP/Random/RandEngine.h"
#include <cstdio>
#include <cstring>
#include <cstdint>
#include <sstream>
#include <vector>
#include <memory>

using namespace CLHEP;

static void d(const char* n, double x) {
  uint64_t b; std::memcpy(&b, &x, 8);
  std::printf("%s %016llx\n", n, (unsigned long long)b);
}
static void text(const char* n, const std::string& t) { std::printf("%s [%s]\n", n, t.c_str()); }

// what any engine shows: numbers, unsigned ints, array, words, text, and restoring them
static void exercise(const char* n, HepRandomEngine& e) {
  std::string s(n);
  for (int i = 0; i < 300; i++) d((s + ".flat").c_str(), e.flat());
  for (int i = 0; i < 20; i++) std::printf("%s.uint %u\n", n, (unsigned int)e);
  for (int i = 0; i < 5; i++) d((s + ".float").c_str(), (double)(float)e);
  double arr[37]; e.flatArray(37, arr); for (int i = 0; i < 37; i++) d((s + ".arr").c_str(), arr[i]);
  std::vector<unsigned long> v = e.put();
  std::printf("%s.words %u:", n, (unsigned)v.size());
  for (unsigned long w : v) std::printf(" %lu", w);
  std::printf("\n");
  std::ostringstream os; os << e; text((s + ".text").c_str(), os.str());
  std::vector<double> after; for (int i = 0; i < 50; i++) after.push_back(e.flat());
  // the same numbers from the state restored by words and by text
  std::unique_ptr<HepRandomEngine> w(HepRandomEngine::newEngine(v));
  if (w) { for (int i = 0; i < 50; i++) d((s + ".fromWords").c_str(), w->flat()); } else std::printf("%s.fromWords null\n", n);
  std::istringstream is(os.str());
  std::unique_ptr<HepRandomEngine> t(HepRandomEngine::newEngine(is));
  if (t) { for (int i = 0; i < 50; i++) d((s + ".fromText").c_str(), t->flat()); } else std::printf("%s.fromText null\n", n);
  for (int i = 0; i < 50; i++) d((s + ".after").c_str(), after[i]);
  std::printf("%s.seed %ld\n", n, e.getSeed());
}

int main() {
  { MixMaxRng e; exercise("mixmax.default", e); }
  { MixMaxRng e(12345); exercise("mixmax.12345", e); }
  { MixMaxRng e; long s[4] = {123, 456, 789, 1011}; e.setSeeds(s, 4); exercise("mixmax.seeds4", e); }
  { MixMaxRng e; long s[2] = {2147483647L, 7}; e.setSeeds(s, 0); exercise("mixmax.seeds2", e); }
  { MixMaxRng e; e.setSeed(987654321L); exercise("mixmax.setSeed", e); }

  { HepJamesRandom e; exercise("james.default", e); }
  { HepJamesRandom e(31415926); exercise("james.seed", e); }
  { HepJamesRandom e(17, 1); exercise("james.rowcol", e); }
  { HepJamesRandom e; long s[1] = {900000000L}; e.setSeeds(s, 0); exercise("james.setSeeds", e); }
  { MTwistEngine e; exercise("mtwist.default", e); }
  { MTwistEngine e(2718281); exercise("mtwist.seed", e); }
  { MTwistEngine e(300, 1); exercise("mtwist.rowcol", e); }
  { MTwistEngine e; long s[2] = {123456789, 987654321}; e.setSeeds(s, 7); exercise("mtwist.setSeeds", e); }
  { RanecuEngine e; exercise("ranecu.default", e); }
  { RanecuEngine e(250); exercise("ranecu.index", e); }
  { RanecuEngine e; e.setSeed(77, 5); exercise("ranecu.setSeed", e); }
  { RanecuEngine e; long s[2] = {1234567, 7654321}; e.setSeeds(s, 3); exercise("ranecu.setSeeds", e); }
  { RanluxEngine e; exercise("ranlux.default", e); }
  { RanluxEngine e(19780503L, 4); exercise("ranlux.seed4", e); }
  { RanluxEngine e(12345L, 0); exercise("ranlux.seed0", e); }
  { RanluxEngine e(5, 1, 2); exercise("ranlux.rowcol", e); }
  { RanluxEngine e; long s[4] = {11, 22, 33, 0}; e.setSeeds(s, 30); exercise("ranlux.setSeeds", e); }
  { Ranlux64Engine e; exercise("ranlux64.default", e); }
  { Ranlux64Engine e(19780503L, 2); exercise("ranlux64.seed2", e); }
  { Ranlux64Engine e(4242L, 0); exercise("ranlux64.seed0", e); }
  { Ranlux64Engine e(9, 0, 1); exercise("ranlux64.rowcol", e); }
  { Ranlux64Engine e; long s[3] = {111, 222, 0}; e.setSeeds(s, 14); exercise("ranlux64.setSeeds", e); }
  { Ranlux64Engine e; e.setSeed(55555, 1); exercise("ranlux64.setSeed", e); }
  { RanluxppEngine e; exercise("ranluxpp.default", e); }
  { RanluxppEngine e(1234567); exercise("ranluxpp.seed", e); }
  { RanluxppEngine e(42); e.skip(1000); exercise("ranluxpp.skip", e); }
  { RanshiEngine e; exercise("ranshi.default", e); }
  { RanshiEngine e(888888); exercise("ranshi.seed", e); }
  { RanshiEngine e(3, 4); exercise("ranshi.rowcol", e); }
  { RanshiEngine e; long s[3] = {5, 6, 0}; e.setSeeds(s, 0); exercise("ranshi.setSeeds", e); }
  { DualRand e; exercise("dualrand.default", e); }
  { DualRand e(65432); exercise("dualrand.seed", e); }
  { DualRand e(7, 3); exercise("dualrand.rowcol", e); }
  { TripleRand e; exercise("triplerand.default", e); }
  { TripleRand e(65432); exercise("triplerand.seed", e); }
  { TripleRand e(7, 3); exercise("triplerand.rowcol", e); }
  { Hurd160Engine e; exercise("hurd160.default", e); }
  { Hurd160Engine e(1999); exercise("hurd160.seed", e); }
  { Hurd160Engine e(400, 1); exercise("hurd160.rowcol", e); }
  { Hurd288Engine e; exercise("hurd288.default", e); }
  { Hurd288Engine e(1999); exercise("hurd288.seed", e); }
  { Hurd288Engine e(400, 1); exercise("hurd288.rowcol", e); }
  { DRand48Engine e; exercise("drand48.default", e); }
  { DRand48Engine e(31337); exercise("drand48.seed", e); }
  { RandEngine e; exercise("rand.default", e); }
  { RandEngine e(271828); exercise("rand.seed", e); }
  { NonRandomEngine e; double seq[5] = {0.1, 0.2, 0.3, 0.4, 0.5}; e.setRandomSequence(seq, 5); e.setNextRandom(0.25);
    e.setRandomInterval(0.0625); for (int i = 0; i < 40; i++) d("nonrandom.flat", e.flat());
    std::vector<unsigned long> v = e.put(); std::printf("nonrandom.words %u\n", (unsigned)v.size());
    std::unique_ptr<HepRandomEngine> w(HepRandomEngine::newEngine(v)); for (int i = 0; i < 10; i++) d("nonrandom.restored", w->flat()); }
  { MixMaxRng e; exercise("mixmax.default2", e); }
  return 0;
}
