// g4b_shm.h
//
// geant4-bridge's shared-memory channel: one file-backed region mapped by the
// engine and by Sphere (Java FFM), holding a header and two lock-free
// single-producer single-consumer byte rings, requests one way and replies
// the other. The same design as Sphere's ROOT bridge -- a mapped region,
// rings advanced by acquire/release atomics, a heartbeat -- but its own code
// and its own file: geant4-bridge builds and runs without ROOT.
//
// Region layout (little endian, offsets in bytes):
//
//     0  u32 magic "G4BR"          4  u32 layout version
//     8  u64 region size          16  u64 request ring offset   24 u64 request capacity
//    32  u64 reply ring offset    40  u64 reply capacity
//    64  u64 engine heartbeat    128  u64 client heartbeat
//   192  u32 cancel request      196  u32 engine state (0 starting, 1 ready, 2 busy, 3 stopped)
//   200  u32 engine pid          256  char[256] engine description (NUL-terminated)
//  4096  request ring, then the reply ring
//
// Each ring: u64 head at +0 (consumer), u64 tail at +64 (producer), data from
// +128 for `capacity` bytes. Positions grow forever; the byte at position p is
// data[p % capacity]. A message is u32 length, u32 id, then length bytes,
// padded to 8; it may wrap around the end of the data.

#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <string>

namespace g4b {

inline constexpr std::uint32_t kMagic = 0x52423447u; // "G4BR"
inline constexpr std::uint32_t kLayoutVersion = 1;
inline constexpr std::size_t kHeaderBytes = 4096;
inline constexpr std::size_t kRingHeader = 128;

enum class EngineState : std::uint32_t { Starting = 0, Ready = 1, Busy = 2, Stopped = 3 };

namespace off {
inline constexpr std::size_t magic = 0, version = 4, size = 8, reqOffset = 16, reqCapacity = 24, repOffset = 32,
                             repCapacity = 40, engineBeat = 64, clientBeat = 128, cancel = 192, state = 196,
                             pid = 200, description = 256;
} // namespace off

/** A mapped file. */
class Region {
public:
  Region() = default;
  ~Region();
  Region(const Region &) = delete;
  Region &operator=(const Region &) = delete;

  /** Creates (or reuses) the file at that size and maps it read-write. */
  bool open(const std::string &path, std::size_t size, std::string &error);
  void close();

  std::uint8_t *base() const { return base_; }
  std::size_t size() const { return size_; }

private:
  std::uint8_t *base_{nullptr};
  std::size_t size_{0};
#if defined(_WIN32)
  void *file_{nullptr};
  void *mapping_{nullptr};
#else
  int fd_{-1};
#endif
};

/** One direction of the channel. */
class Ring {
public:
  Ring() = default;
  Ring(std::uint8_t *at, std::uint64_t capacity) : at_(at), capacity_(capacity) {}

  /** Lays out an empty ring. */
  void format();

  /** Appends a message; false when the ring has no room now. */
  bool push(std::uint32_t id, const void *data, std::uint32_t length);

  /** Takes the next message if there is one. */
  bool pop(std::uint32_t &id, std::string &data);

  std::uint64_t capacity() const { return capacity_; }
  std::uint64_t used() const;

private:
  std::atomic<std::uint64_t> &head() const { return *reinterpret_cast<std::atomic<std::uint64_t> *>(at_); }
  std::atomic<std::uint64_t> &tail() const { return *reinterpret_cast<std::atomic<std::uint64_t> *>(at_ + 64); }
  std::uint8_t *data() const { return at_ + kRingHeader; }
  void copyIn(std::uint64_t pos, const void *src, std::size_t n);
  void copyOut(std::uint64_t pos, void *dst, std::size_t n) const;

  std::uint8_t *at_{nullptr};
  std::uint64_t capacity_{0};
};

/** The engine's end of the channel. */
class Channel {
public:
  /** Maps the region, formatting it when it is new or of another layout. */
  bool open(const std::string &path, std::size_t size, std::string &error);

  Ring &requests() { return req_; }
  Ring &replies() { return rep_; }

  void beat();
  void setState(EngineState s);
  bool cancelRequested() const;
  void clearCancel();
  void describe(const std::string &text);

  /** Sends a reply, waiting while the client drains a full ring (up to the timeout). */
  bool send(std::uint32_t id, const std::string &text, int timeoutMs = 30000);

private:
  template <typename T> std::atomic<T> &field(std::size_t at) const {
    return *reinterpret_cast<std::atomic<T> *>(region_.base() + at);
  }

  Region region_;
  Ring req_, rep_;
};

} // namespace g4b
