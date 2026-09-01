// root_format.h

// On-disk format helpers for CERN ROOT files

#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>

namespace Sphere::IO::RootFormat {

// -----------------------------------------------------------------------------
// Constants
// -----------------------------------------------------------------------------

/// Size of the compression block header that prefixes every compressed block.
inline constexpr std::size_t kCompressionHeaderSize = 9;

/// Size of the xxhash64 checksum ROOT writes after the header for LZ4 blocks.
inline constexpr std::size_t kLz4ChecksumSize = 8;

/// Largest payload ROOT places in a single compressed block (kMAXZIPBUF).
/// Anything larger is split into several blocks, each with its own header.
inline constexpr std::uint32_t kMaxBlockSize = 0xffffff;

/// Minimum number of bytes needed before a TKey header can be parsed.
inline constexpr std::size_t kMinKeyHeaderSize = 16;

// -----------------------------------------------------------------------------
// Big-endian readers (TKey fields)
// -----------------------------------------------------------------------------

[[nodiscard]] inline std::uint16_t read_be16(const std::uint8_t *p) noexcept {
  return static_cast<std::uint16_t>((static_cast<std::uint16_t>(p[0]) << 8) |
                                    static_cast<std::uint16_t>(p[1]));
}

[[nodiscard]] inline std::uint32_t read_be32(const std::uint8_t *p) noexcept {
  return (static_cast<std::uint32_t>(p[0]) << 24) |
         (static_cast<std::uint32_t>(p[1]) << 16) |
         (static_cast<std::uint32_t>(p[2]) << 8) |
         static_cast<std::uint32_t>(p[3]);
}

// -----------------------------------------------------------------------------
// TKey
// -----------------------------------------------------------------------------

struct KeyHeader {
  std::uint32_t nbytes{0};  // total on-disk size: key header + payload
  std::uint16_t version{0};
  std::uint32_t objlen{0};  // uncompressed payload length
  std::uint16_t keylen{0};  // size of the key header itself
  std::uint16_t cycle{0};
  bool valid{false};

  /// Byte offset of the payload relative to the start of the key.
  [[nodiscard]] std::size_t payload_offset() const noexcept { return keylen; }

  /// On-disk payload size, excluding the key header.
  [[nodiscard]] std::uint32_t payload_bytes() const noexcept {
    return (nbytes > keylen) ? (nbytes - keylen) : 0u;
  }

  [[nodiscard]] bool is_uncompressed() const noexcept {
    return valid && nbytes == static_cast<std::uint32_t>(keylen) + objlen;
  }
};

[[nodiscard]] inline KeyHeader parse_key_header(const std::uint8_t *src,
                                                std::size_t size) noexcept {
  KeyHeader key{};
  if (src == nullptr || size < kMinKeyHeaderSize) {
    return key;
  }

  key.nbytes = read_be32(src + 0);
  key.version = read_be16(src + 4);
  key.objlen = read_be32(src + 6);
  // bytes 10..13 are fDatime, which this project does not use
  key.keylen = read_be16(src + 14);

  if (size >= 18) {
    key.cycle = read_be16(src + 16);
  }

  if (key.keylen < kMinKeyHeaderSize || key.keylen > key.nbytes) {
    return key;
  }

  key.valid = true;
  return key;
}

[[nodiscard]] inline bool record_fits(const KeyHeader &key,
                                      std::size_t available) noexcept {
  return key.valid && key.nbytes <= available;
}

// -----------------------------------------------------------------------------
// Compression blocks
// -----------------------------------------------------------------------------

enum class Algo : std::uint8_t {
  Unknown = 0,
  Uncompressed,
  ZLIB, // "ZL", raw zlib stream (RFC 1950)
  LZ4,  // "L4", preceded by an 8-byte xxhash64 checksum
  ZSTD, // "ZS"
  LZMA, // "XZ"
  OldZLIB // "CS", the pre-v6 zlib variant
};

/**
 * One compression block: a 9-byte header plus its payload.
 */
struct BlockHeader {
  Algo algo{Algo::Unknown};
  std::uint32_t compressed_size{0};   // payload bytes after the header
  std::uint32_t uncompressed_size{0}; // bytes this block expands to
  std::size_t payload_offset{0};      // header, plus the LZ4 checksum if any
  bool valid{false};

  /// Total on-disk size of this block, header included.
  [[nodiscard]] std::size_t total_size() const noexcept {
    return kCompressionHeaderSize + compressed_size;
  }
};

/**
 * Parses one compression block header
 */
[[nodiscard]] inline BlockHeader parse_block_header(
    const std::uint8_t *src, std::size_t available) noexcept {
  BlockHeader block{};
  if (src == nullptr || available < kCompressionHeaderSize) {
    return block;
  }

  const char a0 = static_cast<char>(src[0]);
  const char a1 = static_cast<char>(src[1]);

  if (a0 == 'Z' && a1 == 'L') {
    block.algo = Algo::ZLIB;
  } else if (a0 == 'L' && a1 == '4') {
    block.algo = Algo::LZ4;
  } else if (a0 == 'Z' && a1 == 'S') {
    block.algo = Algo::ZSTD;
  } else if (a0 == 'X' && a1 == 'Z') {
    block.algo = Algo::LZMA;
  } else if (a0 == 'C' && a1 == 'S') {
    block.algo = Algo::OldZLIB;
  } else {
    block.algo = Algo::Unknown;
    return block;
  }
  block.compressed_size = static_cast<std::uint32_t>(src[3]) |
                          (static_cast<std::uint32_t>(src[4]) << 8) |
                          (static_cast<std::uint32_t>(src[5]) << 16);

  block.uncompressed_size = static_cast<std::uint32_t>(src[6]) |
                            (static_cast<std::uint32_t>(src[7]) << 8) |
                            (static_cast<std::uint32_t>(src[8]) << 16);

  block.payload_offset = kCompressionHeaderSize;
  if (block.algo == Algo::LZ4) {
    block.payload_offset += kLz4ChecksumSize;
    if (block.compressed_size < kLz4ChecksumSize) {
      return block; // truncated
    }
  }

  if (block.compressed_size == 0 ||
      kCompressionHeaderSize + block.compressed_size > available) {
    return block;
  }

  block.valid = true;
  return block;
}

[[nodiscard]] inline std::uint32_t codec_input_size(
    const BlockHeader &block) noexcept {
  if (block.algo == Algo::LZ4) {
    return block.compressed_size -
           static_cast<std::uint32_t>(kLz4ChecksumSize);
  }
  return block.compressed_size;
}

/**
 * Sums the uncompressed sizes of every block in a multi-block payload
 */
[[nodiscard]] inline std::uint64_t total_uncompressed_size(
    const std::uint8_t *src, std::size_t available,
    std::size_t *out_block_count = nullptr) noexcept {
  std::uint64_t total = 0;
  std::size_t offset = 0;
  std::size_t blocks = 0;

  while (offset + kCompressionHeaderSize <= available) {
    const BlockHeader block =
        parse_block_header(src + offset, available - offset);
    if (!block.valid) {
      break;
    }
    total += block.uncompressed_size;
    offset += block.total_size();
    ++blocks;
  }

  if (out_block_count != nullptr) {
    *out_block_count = blocks;
  }
  return (blocks > 0) ? total : 0;
}

[[nodiscard]] inline const char *algo_name(Algo algo) noexcept {
  switch (algo) {
  case Algo::Uncompressed:
    return "uncompressed";
  case Algo::ZLIB:
    return "zlib";
  case Algo::LZ4:
    return "lz4";
  case Algo::ZSTD:
    return "zstd";
  case Algo::LZMA:
    return "lzma";
  case Algo::OldZLIB:
    return "zlib(legacy CS)";
  case Algo::Unknown:
  default:
    return "unknown";
  }
}

} // namespace Sphere::IO::RootFormat
