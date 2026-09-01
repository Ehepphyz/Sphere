// diagnostics.h

// Self-contained checks reachable from the command line

#ifndef SPHERE_DIAGNOSTICS_H
#define SPHERE_DIAGNOSTICS_H

#include <cstddef>
#include <string>

namespace Sphere::diag {

// --test-shm: creates a region from nothing, timing every syscall.
class RegionCreationTest {
public:
  static int run(std::size_t size_mib, const std::string &path, bool keep);
};

// --test-shmprobe: reports on an existing region. Read-only.
class RegionProbeTest {
public:
  static int run(const std::string &path);
};

// --test-root: times ROOT coming up.
class RootInitTest {
public:
  static int run();
};

} // namespace Sphere::diag

#endif // SPHERE_DIAGNOSTICS_H
