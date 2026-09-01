// span_scope.h

// RAII latency span

#pragma once

#include "span_ring.h"
#include "utils.h"

#include <cstdint>

namespace Sphere::log {

class SpanScope {
public:
  SpanScope(SpanRing &ring, std::uint16_t module_id, std::uint32_t job_id,
            std::uint32_t req_id, std::uint8_t level = 0) noexcept
      : ring_(&ring), module_id_(module_id), job_id_(job_id), req_id_(req_id),
        level_(level), start_(utils::rdtsc()) {}

  ~SpanScope() noexcept {
    if (ring_ == nullptr) {
      return;
    }
    (void)span_ring_push(*ring_, level_, module_id_, job_id_, req_id_, start_,
                         utils::rdtsc());
  }

  SpanScope(const SpanScope &) = delete;
  SpanScope &operator=(const SpanScope &) = delete;
  SpanScope(SpanScope &&) = delete;
  SpanScope &operator=(SpanScope &&) = delete;

  /// Cycles elapsed so far, without closing the span.
  [[nodiscard]] std::uint64_t elapsed_cycles() const noexcept {
    return utils::rdtsc() - start_;
  }

private:
  SpanRing *ring_{nullptr};
  std::uint16_t module_id_{0};
  std::uint32_t job_id_{0};
  std::uint32_t req_id_{0};
  std::uint8_t level_{0};
  std::uint64_t start_{0};
};

} // namespace Sphere::log
