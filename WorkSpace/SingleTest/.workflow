{
  "modules": {
    "root": true,
    "geant4": false,
    "madgraph": false,
    "herwig": false,
    "python": true,
    "latex": true
  },
  "buildSystem": "cmake",
  "pipelines": {
    "simulation": ["geant4:run", "madgraph:generate", "herwig:shower"],
    "analysis": ["root:macro", "python:script", "jupyter:notebook"]
  }
}
