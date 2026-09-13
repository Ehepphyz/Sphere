#ifndef SPHERE_TTREE_HANDLERS_COMMON_H
#define SPHERE_TTREE_HANDLERS_COMMON_H

#include "../cmd_ttree.h"
#include "packets.h"
#include "shm_layout.h"

#include <cstdint>
#include <string>
#include <string_view>

// Forward declarations to maintain fast compilation and lightweight headers
class TTree;
class TLeaf;

namespace Sphere::cmd::ttree {

/**
* Structured mapping result for ROOT TLeaf data types.
*/
struct LeafTypeInfo {
  ShmDType dtype;
  std::size_t elem_size;
  /// False for a leaf that is not a number, such as a branch of objects.
  bool known{true};
};

/**
* Retrieves a registered TTree pointer associated with a specific job ID
*/
TTree *get_tree(std::uint32_t job_id);

/**
* Escapes control characters in strings for safe JSON serialization
*/
std::string escape_json(const char *s);

// Reads a request's payload out of the region as text.
[[nodiscard]] std::string read_payload_text(const ShmLayout &shm,
                                            const Proto::PacketHeader &pkt);

/**
* Maps ROOT TLeaf type names to internal Shared Memory types (ShmDType)
*/
LeafTypeInfo map_root_leaf_type(TLeaf *leaf);

} // namespace Sphere::cmd::ttree

#endif // SPHERE_TTREE_HANDLERS_COMMON_H
