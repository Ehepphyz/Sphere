// g4b_common.cpp
//
// Commands every geant4-bridge answers, Geant4 or not.

#include "g4b_service.h"

#include <chrono>
#include <thread>

namespace g4b {

bool g_quit = false;

void registerCommon() {
  auto &r = Registry::instance();
  r.add("ping", "answers pong and what the engine is", [](const Json &, Context &) {
    Json j = Json::object();
    j.set("pong", true);
    j.set("engine", engineDescription());
    return j;
  });
  r.add("commands", "the commands of this engine and their arguments", [](const Json &, Context &) {
    return Registry::instance().list();
  });
  r.add("echo", "returns its arguments: a transport test", [](const Json &a, Context &) { return a; });
  r.add("sleep", "waits {ms} milliseconds, reporting progress: a transport and cancel test", [](const Json &a, Context &ctx) {
    const int ms = static_cast<int>(a.num("ms", 100));
    for (int i = 0; i < 10; ++i) {
      if (ctx.cancelled()) {
        Json j = Json::object();
        j.set("cancelled", true);
        return j;
      }
      std::this_thread::sleep_for(std::chrono::milliseconds(ms / 10));
      ctx.progress((i + 1) / 10.0, "step " + std::to_string(i + 1));
    }
    Json j = Json::object();
    j.set("slept_ms", ms);
    return j;
  });
  r.add("quit", "stops the engine", [](const Json &, Context &) {
    g_quit = true;
    Json j = Json::object();
    j.set("bye", true);
    return j;
  });
}

} // namespace g4b
