// g4b_score.h
//
// What a run measures besides the energy in each volume, free of Geant4 so
// that it builds and is tested without it: where the run spends its steps,
// by volume and by particle, and how long an event takes; and the energy of
// each copy of a volume (the layers of a calorimeter, the planes of a
// tracker), from which Sphere reads the shower's profile. Volumes and
// particles are keys the caller owns (a G4LogicalVolume*, a
// G4ParticleDefinition*), named when the result is written.

#pragma once

#include "json.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <functional>
#include <map>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

namespace g4b {

using Namer = std::function<std::string(const void *)>;

/** Steps and their length by volume and by particle, tracks by particle, and the time each event took. */
class StepProfile {
public:
  void step(const void *volume, const void *particle, double length) {
    Count &v = volumes_[volume];
    ++v.steps;
    v.length += length;
    Count &p = particles_[particle];
    ++p.steps;
    p.length += length;
    ++steps_;
  }

  void track(const void *particle) { ++particles_[particle].tracks; }

  void event(double ms) {
    ++events_;
    ms_ += ms;
    slowest_ = std::max(slowest_, ms);
  }

  std::uint64_t steps() const { return steps_; }

  /**
   * {"steps", "events", "ms_per_event", "slowest_ms", "volumes": [{"name", "steps", "fraction", "length_mm"}],
   * "particles": [{"name", "steps", "fraction", "tracks"}]}: the busiest `top` of each, busiest first.
   */
  Json json(const Namer &volume, const Namer &particle, std::size_t top = 16) const {
    Json j = Json::object();
    j.set("steps", static_cast<double>(steps_));
    j.set("events", events_);
    j.set("ms_per_event", events_ > 0 ? ms_ / events_ : 0.0);
    j.set("slowest_ms", slowest_);
    j.set("volumes", list(volumes_, volume, top, false));
    j.set("particles", list(particles_, particle, top, true));
    return j;
  }

private:
  struct Count {
    std::uint64_t steps = 0, tracks = 0;
    double length = 0;
  };

  Json list(const std::unordered_map<const void *, Count> &m, const Namer &name, std::size_t top, bool tracks) const {
    std::vector<std::pair<const void *, Count>> v(m.begin(), m.end());
    std::sort(v.begin(), v.end(), [](const auto &a, const auto &b) {
      return a.second.steps != b.second.steps ? a.second.steps > b.second.steps : a.second.tracks > b.second.tracks;
    });
    Json out = Json::array();
    for (std::size_t i = 0; i < v.size() && i < top; ++i) {
      Json e = Json::object();
      e.set("name", name(v[i].first));
      e.set("steps", static_cast<double>(v[i].second.steps));
      e.set("fraction", steps_ > 0 ? static_cast<double>(v[i].second.steps) / static_cast<double>(steps_) : 0.0);
      if (tracks) {
        e.set("tracks", static_cast<double>(v[i].second.tracks));
      } else {
        e.set("length_mm", v[i].second.length);
      }
      out.push(e);
    }
    return out;
  }

  std::unordered_map<const void *, Count> volumes_, particles_;
  std::uint64_t steps_ = 0;
  int events_ = 0;
  double ms_ = 0, slowest_ = 0;
};

/**
 * The energy each copy of each volume received in each event, then its mean and spread over the events:
 * the profile of a shower along a calorimeter's layers, the occupancy of a tracker's planes.
 */
class CopyEnergy {
public:
  void deposit(const void *volume, int copy, double e) { event_[{volume, copy}] += e; }

  /** The event's energies added to the sums: each copy's, and each volume's total of the event. */
  void endEvent() {
    std::map<const void *, double> total;
    for (const auto &[k, e] : event_) {
      auto &s = sums_[k];
      s.first += e;
      s.second += e * e;
      total[k.first] += e;
    }
    for (const auto &[v, e] : total) {
      auto &s = totals_[v];
      s.first += e;
      s.second += e * e;
    }
    event_.clear();
    ++events_;
  }

  int events() const { return events_; }

  /**
   * {volume: {"copy": [...], "mean": [...], "rms": [...]}} for each volume whose energy fell in two copies or
   * more (a volume placed once has no profile), energies divided by `unit`, at most `maxCopies`
   * copies each (the lowest numbers).
   */
  Json json(const Namer &name, double unit, std::size_t maxCopies = 4096) const {
    std::map<const void *, std::vector<std::pair<int, std::pair<double, double>>>> by;
    for (const auto &[k, s] : sums_) {
      by[k.first].emplace_back(k.second, s);
    }
    Json out = Json::object();
    const double n = std::max(1, events_);
    for (const auto &[v, copies] : by) {
      if (copies.size() < 2) {
        continue;
      }
      Json c = Json::array(), mean = Json::array(), rms = Json::array();
      for (std::size_t i = 0; i < copies.size() && i < maxCopies; ++i) {
        const double m = copies[i].second.first / n, var = std::max(0.0, copies[i].second.second / n - m * m);
        c.push(copies[i].first);
        mean.push(m / unit);
        rms.push(std::sqrt(var) / unit);
      }
      Json e = Json::object();
      e.set("copy", c);
      e.set("mean", mean);
      e.set("rms", rms);
      e.set("copies", static_cast<double>(copies.size()));
      // the spread of the volume's whole energy per event: what a calorimeter's resolution is read from
      const auto t = totals_.find(v);
      if (t != totals_.end()) {
        const double m = t->second.first / n, var = std::max(0.0, t->second.second / n - m * m);
        e.set("total_mean", m / unit);
        e.set("total_rms", std::sqrt(var) / unit);
      }
      out.set(name(v), e);
    }
    return out;
  }

private:
  std::map<std::pair<const void *, int>, double> event_;
  std::map<std::pair<const void *, int>, std::pair<double, double>> sums_;
  std::map<const void *, std::pair<double, double>> totals_;
  int events_ = 0;
};

} // namespace g4b
