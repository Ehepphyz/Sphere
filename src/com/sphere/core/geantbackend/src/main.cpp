// main.cpp
//
// geant4-bridge: Sphere's Geant4 engine.
//
//   geant4-bridge --shm <file> [--size <MiB>] [--parent-pid <pid>]
//       Serves requests through a shared-memory region (g4b_shm.h), the
//       channel G4editor uses: two lock-free rings in a mapped file.
//   geant4-bridge --stdio
//       Serves JSON lines on stdin/stdout: one request per line, replies and
//       progress lines back. For a remote or WSL engine, or a shell.
//   geant4-bridge --ping | --version | --commands
//   geant4-bridge --selftest <design.gdml>
//
// A request is {"id":n,"cmd":"...","args":{...}}; a reply {"id":n,"ok":true,
// "result":{...}} or {"id":n,"ok":false,"error":"..."}; while a command runs
// it may send {"id":n,"progress":0.4,"message":"..."}.

#include "g4b_service.h"
#include "g4b_shm.h"

#include <chrono>
#include <csignal>
#include <cstdlib>
#include <iostream>
#include <string>
#include <thread>

#if defined(_WIN32)
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#else
#include <signal.h>
#include <unistd.h>
#endif

namespace {

volatile std::sig_atomic_t g_signal = 0;

extern "C" void onSignal(int) { g_signal = 1; }

/** Exits when the process that started us does: no engine outlives Sphere. */
void watchParent(long long pid) {
  if (pid <= 0) {
    return;
  }
#if defined(_WIN32)
  HANDLE h = ::OpenProcess(SYNCHRONIZE, FALSE, static_cast<DWORD>(pid));
  if (h == nullptr) {
    return;
  }
  std::thread([h] {
    ::WaitForSingleObject(h, INFINITE);
    std::_Exit(0);
  }).detach();
#else
  std::thread([pid] {
    while (::kill(static_cast<pid_t>(pid), 0) == 0) {
      std::this_thread::sleep_for(std::chrono::seconds(1));
    }
    std::_Exit(0);
  }).detach();
#endif
}

g4b::Json request(const std::string &cmd) {
  g4b::Json r = g4b::Json::object();
  r.set("cmd", cmd);
  r.set("args", g4b::Json::object());
  return r;
}

// ---------------------------------------------------------------------------

int serveStdio() {
  std::ios::sync_with_stdio(false);
  {
    g4b::Json ready = g4b::Json::object();
    ready.set("ready", true);
    ready.set("engine", g4b::engineDescription());
    ready.set("transport", "stdio");
    std::cout << ready.dump() << '\n' << std::flush;
  }
  std::string line;
  while (!g4b::g_quit && g_signal == 0 && std::getline(std::cin, line)) {
    if (line.empty()) {
      continue;
    }
    double id = 0;
    g4b::Json reply;
    try {
      const g4b::Json req = g4b::Json::parse(line);
      id = req.num("id", 0);
      g4b::Context ctx;
      ctx.progress = [id](double f, const std::string &m) {
        g4b::Json p = g4b::Json::object();
        p.set("id", id);
        p.set("progress", f);
        p.set("message", m);
        std::cout << p.dump() << '\n' << std::flush;
      };
      reply = g4b::Registry::instance().dispatch(req, ctx);
    } catch (const std::exception &e) {
      reply = g4b::Json::object();
      reply.set("ok", false);
      reply.set("error", std::string("bad request: ") + e.what());
    }
    reply.set("id", id);
    std::cout << reply.dump() << '\n' << std::flush;
  }
  return 0;
}

int serveShm(const std::string &path, std::size_t mib) {
  g4b::Channel ch;
  std::string error;
  if (!ch.open(path, mib << 20, error)) {
    std::cerr << "[geant4-bridge] " << error << "\n";
    return 2;
  }
  ch.describe(g4b::engineDescription());
  ch.setState(g4b::EngineState::Ready);
  std::cerr << "[geant4-bridge] serving " << path << " (" << g4b::engineDescription() << ")\n";
  std::uint32_t id = 0;
  std::string payload;
  int idle = 0;
  auto lastBeat = std::chrono::steady_clock::now();
  while (!g4b::g_quit && g_signal == 0) {
    if (!ch.requests().pop(id, payload)) {
      // Spin briefly for latency, then sleep so an idle engine costs nothing
      const auto now = std::chrono::steady_clock::now();
      if (now - lastBeat > std::chrono::milliseconds(100)) {
        ch.beat();
        lastBeat = now;
      }
      if (++idle < 2000) {
        std::this_thread::yield();
      } else {
        std::this_thread::sleep_for(std::chrono::milliseconds(idle < 20000 ? 1 : 5));
      }
      continue;
    }
    idle = 0;
    ch.setState(g4b::EngineState::Busy);
    ch.clearCancel();
    g4b::Json reply;
    try {
      const g4b::Json req = g4b::Json::parse(payload);
      g4b::Context ctx;
      ctx.progress = [&ch, id](double f, const std::string &m) {
        g4b::Json p = g4b::Json::object();
        p.set("id", static_cast<double>(id));
        p.set("progress", f);
        p.set("message", m);
        ch.send(id, p.dump(), 1000);
        ch.beat();
      };
      ctx.cancelled = [&ch] { return ch.cancelRequested(); };
      reply = g4b::Registry::instance().dispatch(req, ctx);
    } catch (const std::exception &e) {
      reply = g4b::Json::object();
      reply.set("ok", false);
      reply.set("error", std::string("bad request: ") + e.what());
    }
    reply.set("id", static_cast<double>(id));
    std::string text = reply.dump();
    if (!ch.send(id, text)) {
      g4b::Json r = g4b::Json::object();
      r.set("id", static_cast<double>(id));
      r.set("ok", false);
      r.set("error", "the reply (" + std::to_string(text.size()) + " bytes) does not fit the region; ask for less "
                     "(fewer tracks), or start the engine with a larger --size");
      ch.send(id, r.dump());
    }
    ch.setState(g4b::EngineState::Ready);
    ch.beat();
  }
  ch.setState(g4b::EngineState::Stopped);
  return 0;
}

int once(const std::string &cmd, const g4b::Json &args) {
  g4b::Json req = request(cmd);
  req.set("args", args);
  g4b::Context ctx;
  ctx.progress = [](double f, const std::string &m) { std::cerr << "  " << static_cast<int>(f * 100) << "% " << m << "\n"; };
  const g4b::Json reply = g4b::Registry::instance().dispatch(req, ctx);
  std::cout << reply.dump() << "\n";
  return reply["ok"].boolean() ? 0 : 1;
}

void usage() {
  std::cout << "geant4-bridge: Sphere's Geant4 engine (" << g4b::engineDescription() << ")\n\n"
            << "  --shm <file> [--size <MiB>] [--parent-pid <pid>]   serve through shared memory\n"
            << "  --stdio                                             serve JSON lines on stdin/stdout\n"
            << "  --ping | --version | --commands                     one answer, then exit\n"
            << "  --selftest <design.gdml>                            load, check overlaps, scan, run 10 events\n";
}

} // namespace

int main(int argc, char **argv) {
  std::signal(SIGINT, onSignal);
  std::signal(SIGTERM, onSignal);
  g4b::registerCommon();
  g4b::registerGeant4();

  std::string shm, selftest;
  std::size_t mib = 64;
  long long parent = 0;
  bool stdio = false;
  std::string single;
  for (int i = 1; i < argc; ++i) {
    const std::string a = argv[i];
    auto next = [&]() -> std::string { return i + 1 < argc ? argv[++i] : ""; };
    if (a == "--shm") {
      shm = next();
    } else if (a == "--size") {
      mib = static_cast<std::size_t>(std::strtoull(next().c_str(), nullptr, 10));
    } else if (a == "--parent-pid") {
      parent = std::strtoll(next().c_str(), nullptr, 10);
    } else if (a == "--stdio") {
      stdio = true;
    } else if (a == "--ping" || a == "--version" || a == "--commands") {
      single = a.substr(2);
    } else if (a == "--selftest") {
      selftest = next();
    } else if (a == "-h" || a == "--help") {
      usage();
      return 0;
    } else {
      std::cerr << "[geant4-bridge] ignoring unknown option " << a << "\n";
    }
  }
  watchParent(parent);

  if (!single.empty()) {
    return once(single == "ping" ? "ping" : single, g4b::Json::object());
  }
  if (!selftest.empty()) {
    g4b::Json args = g4b::Json::object();
    args.set("path", selftest);
    int rc = once("load", args);
    if (rc != 0) {
      return rc;
    }
    g4b::Json ov = g4b::Json::object();
    ov.set("resolution", 500);
    rc |= once("overlaps", ov);
    g4b::Json sc = g4b::Json::object();
    sc.set("n", 8);
    sc.set("nphi", 4);
    rc |= once("scan", sc);
    g4b::Json run = g4b::Json::object();
    run.set("events", 10);
    run.set("particle", "geantino");
    run.set("trackEvents", 2);
    rc |= once("run", run);
    return rc;
  }
  if (stdio) {
    return serveStdio();
  }
  if (!shm.empty()) {
    return serveShm(shm, mib);
  }
  usage();
  return 1;
}
