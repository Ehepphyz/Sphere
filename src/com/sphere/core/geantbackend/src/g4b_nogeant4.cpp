// g4b_nogeant4.cpp
//
// Stand-ins for the Geant4 commands, in a geant4-bridge built without Geant4
// (cmake -DG4BRIDGE_WITHOUT_GEANT4=ON). It still speaks both transports, so
// the channel between Sphere and the engine can be built and tested on a
// machine where Geant4 is not installed; every Geant4 command says so.

#include "g4b_service.h"

namespace g4b {

std::string engineDescription() { return "geant4-bridge (built without Geant4: transport test engine)"; }

void registerGeant4() {
  auto &r = Registry::instance();
  r.add("version", "what this engine is", [](const Json &, Context &) {
    Json j = Json::object();
    j.set("engine", "geant4-bridge");
    j.set("geant4", Json());
    j.set("note", "built without Geant4: rebuild against a Geant4 installation (GEANT4_DIR, geant4-config, conda)");
    return j;
  });
  for (const char *name : {"load", "overlaps", "material", "locate", "scan", "run", "ui", "mass", "export"}) {
    r.add(name, "needs Geant4", [name](const Json &, Context &) -> Json {
      throw Failure(std::string(name) + ": this geant4-bridge was built without Geant4");
    });
  }
}

} // namespace g4b
