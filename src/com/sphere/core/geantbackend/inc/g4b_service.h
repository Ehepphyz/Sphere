// g4b_service.h
//
// geant4-bridge's commands: a registry of named handlers taking a JSON object
// and answering one, run one at a time on the engine's main thread -- the
// thread that owns Geant4's run manager. The transports (shared memory, or
// JSON lines on stdin/stdout) only move requests and replies.

#pragma once

#include "json.h"

#include <functional>
#include <map>
#include <string>
#include <vector>

namespace g4b {

/** What a command may do besides answering: report progress, notice a cancel. */
struct Context {
  std::function<void(double fraction, const std::string &message)> progress = [](double, const std::string &) {};
  std::function<bool()> cancelled = [] { return false; };
};

/** A command failed in a way the client should be told about. */
struct Failure : std::runtime_error {
  explicit Failure(const std::string &m) : std::runtime_error(m) {}
};

using Command = std::function<Json(const Json &args, Context &ctx)>;

class Registry {
public:
  static Registry &instance() {
    static Registry r;
    return r;
  }

  void add(const std::string &name, const std::string &help, Command c) {
    commands_[name] = Entry{help, std::move(c)};
  }

  /** {"ok":true,"result":...} or {"ok":false,"error":"..."}. */
  Json dispatch(const Json &request, Context &ctx) const {
    Json reply = Json::object();
    const std::string name = request.str("cmd", "");
    auto it = commands_.find(name);
    if (it == commands_.end()) {
      reply.set("ok", false);
      reply.set("error", "unknown command '" + name + "'; 'commands' lists them");
      return reply;
    }
    try {
      reply.set("result", it->second.run(request["args"], ctx));
      reply.set("ok", true);
    } catch (const std::exception &e) {
      reply.set("ok", false);
      reply.set("error", e.what());
    } catch (...) {
      reply.set("ok", false);
      reply.set("error", "unknown failure");
    }
    return reply;
  }

  Json list() const {
    Json out = Json::object();
    for (const auto &[k, v] : commands_) {
      out.set(k, v.help);
    }
    return out;
  }

private:
  struct Entry {
    std::string help;
    Command run;
  };
  std::map<std::string, Entry> commands_;
};

/** ping, commands, echo: what every build answers. */
void registerCommon();

/** The Geant4 commands, or their stand-ins in a build without Geant4. */
void registerGeant4();

/** One line saying what this engine is (Geant4 version, or "no Geant4"). */
std::string engineDescription();

/** Set by the transports so a command can ask the engine to stop. */
extern bool g_quit;

} // namespace g4b
