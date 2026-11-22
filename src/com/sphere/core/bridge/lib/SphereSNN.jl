# SphereSNN.jl
#
# The Sphere Neural Network from Julia (sphere_snn.py says what the network is): what the machine holds, Julia's own
# neurons (JetReconstruction.jl) wired to the others, and every neuron of any Python, on either side of WSL, through
# the gateway -- ROOT, FastJet and fjcontrib, HepMC3, Geant4 (through Cling), PyTorch, TensorFlow, JAX, pyhf. Nothing
# to install: arrays go as SPX files, as everywhere in Sphere.
#
#     include(joinpath(ENV["SPHERE_BRIDGE_LIB"], "SphereSNN.jl"))
#     using .SphereSNN
#
#     SphereSNN.neurons()                                   # every neuron: id, version, side, languages
#     SphereSNN.has("geant4")                               # held by the machine, on either side
#     jets = SphereSNN.jets(px, py, pz, E; R = 0.4)          # JetReconstruction.jl when installed, else FastJet
#     jets = SphereSNN.jets(px, py, pz, E; via = :fastjet)   # FastJet, in the Python that holds it
#     SphereSNN.hub("ROOT.gROOT.GetVersion()")               # Python where the neurons are; arrays as keywords
#     SphereSNN.material("G4_WATER")                        # Geant4's: density, X0, lambda_I (through Cling)
#     SphereSNN.tree(path, "jets", ["pt", "eta"])            # a ROOT tree's branches as a Matrix
#     SphereSNN.pyhf(s, b, db, n; points = 21)              # CLs and the upper limit
#     SphereSNN.route("hepmc3", "pyhf")                     # the synapses carrying data from one neuron to another

__precompile__(false)      # loaded by `using SphereSNN` in what Sphere starts: nothing is written to Julia's depot
module SphereSNN

isdefined(Main, :SphereMLink) || Base.include(@__MODULE__, joinpath(@__DIR__, "SphereMLink.jl"))
const ML = isdefined(Main, :SphereMLink) ? Main.SphereMLink : SphereMLink

export neurons, has, jets, hub, material, tree, pyhf, route, synapses

"""windows, wsl-<distribution>, linux or macos: where this Julia runs."""
function side()
    Sys.iswindows() && return "windows"
    Sys.isapple() && return "macos"
    text = isfile("/proc/version") ? lowercase(read("/proc/version", String)) : ""
    occursin("microsoft", text) || return "linux"
    d = get(ENV, "WSL_DISTRO_NAME", "")
    isempty(d) ? "wsl" : "wsl-" * d
end

"""\$SPHERE_SNN: the network's folder, one subfolder a side."""
network_folder() = get(ENV, "SPHERE_SNN", joinpath(ML.SPX.bridge_folder(), "snn"))

const Row = NamedTuple{(:id, :title, :version, :side, :languages, :where, :family),NTuple{7,String}}

function readtsv(path, n)
    rows = Vector{Vector{String}}()
    isfile(path) || return rows
    for line in eachline(path)
        (isempty(line) || startswith(line, "#")) && continue
        f = String.(split(line, '\t'))
        length(f) < n && append!(f, fill("", n - length(f)))
        push!(rows, f[1:n])
    end
    rows
end

"""The neurons the machine holds as this side sees them (from Windows, those of WSL too)."""
function neurons(; of_side = side())
    [Row(Tuple(r)) for r in readtsv(joinpath(network_folder(), of_side, "neurons.tsv"), 7)]
end

has(id::AbstractString) = any(n -> n.id == id, neurons())
version(id::AbstractString) = (i = findfirst(n -> n.id == id, neurons()); i === nothing ? "" : neurons()[i].version)

"""The synapses of the network: (id, from, to, what, languages)."""
function synapses()
    rows = readtsv(joinpath(network_folder(), side(), "synapses.tsv"), 5)
    [(id = r[1], from = r[2], to = r[3], what = r[4], languages = r[5]) for r in rows]
end

"""The shortest chain of synapses carrying data from one neuron to another; nothing when none does."""
function route(a::AbstractString, b::AbstractString)
    edges = Dict{String,Vector{Tuple{String,Any}}}()
    for s in synapses()
        push!(get!(edges, s.from, []), (s.to, s))
        occursin("<>", s.id) && push!(get!(edges, s.to, []), (s.from, s))
    end
    prev = Dict{String,Any}(a => nothing)
    todo = [a]
    while !isempty(todo)
        x = popfirst!(todo)
        x == b && break
        for (y, s) in get(edges, x, [])
            haskey(prev, y) && continue
            prev[y] = (x, s)
            push!(todo, y)
        end
    end
    haskey(prev, b) || return nothing
    chain = []
    while prev[b] !== nothing
        x, s = prev[b]
        pushfirst!(chain, s)
        b = x
    end
    chain
end

"""Python run in the gateway, every neuron of its Python at hand (one it lacks is asked of the Python that has it,
on either side of WSL); the keywords are its arrays, the value of the last expression comes back."""
hub(code::AbstractString; session = "snn", timeout = 0.0, arrays...) = ML.py(code; session, timeout, arrays...)

pyhf(s, b, db, n; kw...) = ML.pyhf(s, b, db, n; kw...)

"""A Geant4 material through Cling, in the Python that holds Geant4: density (g/cm3), X0 and lambda_I (cm)."""
material(name::AbstractString) = hub("g4\nsnn.g4_material($(repr(String(name))))")

"""A ROOT tree's branches (or expressions uproot reads) as the columns of a Matrix, one row an entry (a vector
branch flattened)."""
tree(path::AbstractString, name::AbstractString, branches::Vector{<:AbstractString}) =
    hub("uproot\nsnn._columns($(repr(String(path))), $(repr(String(name))), [$(join(repr.(String.(branches)), ", "))])")

# ---------------------------------------------------------------------------------------------------------------------
# Jets: Julia's JetReconstruction.jl, or FastJet through the gateway
# ---------------------------------------------------------------------------------------------------------------------

const JETRECO = Base.PkgId(Base.UUID("44e8cb2c-dfab-4825-9c70-d4808a591196"), "JetReconstruction")

jetreconstruction_installed() = Base.find_package("JetReconstruction") !== nothing

function jetreconstruction()
    isdefined(Main, :JetReconstruction) && return Main.JetReconstruction
    Base.require(JETRECO)
end

const ALGORITHM = Dict(-1 => "antikt_algorithm", 0 => "cambridge_algorithm", 1 => "kt_algorithm")

"""Jets of particles given by their four-momenta: a Vector of (px, py, pz, E, pt, rapidity, phi, m), the hardest
first. via = :auto takes JetReconstruction.jl when it is installed and FastJet otherwise; :jetreconstruction or
:fastjet say which. p = -1 is anti-kt, 0 Cambridge/Aachen, 1 kt."""
function jets(px, py, pz, E; R = 0.4, p = -1, ptmin = 0.0, via = :auto)
    use = via == :auto ? (jetreconstruction_installed() ? :jetreconstruction : :fastjet) : via
    if use == :jetreconstruction
        JR = jetreconstruction()
        call(f, args...; kw...) = Base.invokelatest(f, args...; kw...)
        parts = [call(JR.PseudoJet, Float64(px[i]), Float64(py[i]), Float64(pz[i]), Float64(E[i])) for i in eachindex(px)]
        algorithm = p == -1 ? JR.JetAlgorithm.AntiKt : p == 0 ? JR.JetAlgorithm.CA : JR.JetAlgorithm.Kt
        cs = call(JR.jet_reconstruct, parts; algorithm, R = Float64(R))
        found = call(JR.inclusive_jets, cs, JR.PseudoJet; ptmin = Float64(ptmin))
        out = [(px = call(JR.px, j), py = call(JR.py, j), pz = call(JR.pz, j), E = call(JR.energy, j), pt = call(JR.pt, j),
                rapidity = call(JR.rapidity, j), phi = call(JR.phi, j), m = call(JR.mass, j)) for j in found]
        return sort(out; by = j -> -j.pt)
    end
    x = hcat(Float64.(px), Float64.(py), Float64.(pz), Float64.(E))
    code = "fastjet\n[[j.px(), j.py(), j.pz(), j.E(), j.pt(), j.rap(), j.phi(), j.m()] for j in fastjet.sorted_by_pt(" *
           "fastjet.ClusterSequence([fastjet.PseudoJet(*map(float, r)) for r in x], fastjet.JetDefinition(fastjet.$(ALGORITHM[p]), " *
           "float(R.ravel()[0]))).inclusive_jets(float(ptmin.ravel()[0])))]"
    a = hub(code; x, R = [Float64(R)], ptmin = [Float64(ptmin)])
    a isa AbstractMatrix || return NamedTuple[]
    [(px = a[i, 1], py = a[i, 2], pz = a[i, 3], E = a[i, 4], pt = a[i, 5], rapidity = a[i, 6], phi = a[i, 7], m = a[i, 8])
     for i in 1:size(a, 1)]
end

# ---------------------------------------------------------------------------------------------------------------------
# The check: what ':snn check' asks of Julia
# ---------------------------------------------------------------------------------------------------------------------

"""The event every language of the check builds the same, bit for bit (only +, -, *, / and sqrt)."""
function reference_particles()
    out = NTuple{4,Float64}[]
    for k in 0:59
        a, i = k % 3, k ÷ 3
        if a == 0
            px, py, pz = 40.0 + (i % 5) * 1.5, 10.0 + (i % 4) * 0.75, 25.0 + (i % 6) * 2.0
        elseif a == 1
            px, py, pz = -35.0 - (i % 4) * 1.25, -12.0 - (i % 5) * 0.5, -30.0 + (i % 3) * 1.0
        else
            px, py, pz = (i % 7) * 0.5 - 1.5, (i % 5) * 0.4 - 0.8, (i % 9) * 0.7 - 2.8
        end
        push!(out, (px, py, pz, sqrt(px * px + py * py + pz * pz + 0.13957 * 0.13957)))
    end
    out
end

say(id, ok, detail) = (println("SNN ", id, " ", ok === nothing ? "skip" : ok ? "ok" : "FAIL", " ", detail); flush(stdout))

function attempt(f, id)
    try
        f()
    catch e
        say(id, false, sprint(showerror, e)[1:min(end, 280)])
    end
end

"""Julia's part of the check: its own neurons, and the others through the gateway, numbers held to FastJet's."""
function check()
    say("julia", true, "Julia $(VERSION) on $(side()), $(length(neurons())) neurons seen")
    ref = reference_particles()
    px, py, pz, E = getindex.(ref, 1), getindex.(ref, 2), getindex.(ref, 3), getindex.(ref, 4)
    native = nothing
    if jetreconstruction_installed()
        attempt("jetreconstruction") do
            native = jets(px, py, pz, E; R = 0.4, ptmin = 5.0, via = :jetreconstruction)
            say("jetreconstruction", true, "JetReconstruction.jl: $(length(ref)) particles -> $(length(native)) jets")
        end
    else
        say("jetreconstruction", nothing, "JetReconstruction.jl is not installed in this Julia")
    end
    attempt("mlink>fastjet") do
        theirs = jets(px, py, pz, E; R = 0.4, ptmin = 5.0, via = :fastjet)
        say("mlink>fastjet", true, "FastJet (in the gateway's Python) clustered Julia's particles: $(length(theirs)) jets")
        if native !== nothing
            same = length(native) == length(theirs) &&
                   all(isapprox(a.pt, b.pt; rtol = 1e-12) && isapprox(a.m, b.m; rtol = 1e-9, atol = 1e-9) for (a, b) in zip(native, theirs))
            say("jetreconstruction<>fastjet", same, same ? "the same $(length(native)) jets, pt to 1e-12" :
                                                    "JetReconstruction $(getfield.(native, :pt)) against FastJet $(getfield.(theirs, :pt))")
        end
    end
    attempt("mlink>root") do
        say("mlink>root", true, "ROOT $(hub("ROOT\nROOT.gROOT.GetVersion()")) answered Julia")
    end
    attempt("geant4") do
        m = material("G4_WATER")
        say("geant4", true, "G4_WATER through the gateway and Cling: $(round(m["density"]; digits = 4)) g/cm3, X0 $(round(m["X0"]; digits = 3)) cm")
    end
    attempt("pyhf") do
        r = pyhf([5.0, 10.0], [50.0, 60.0], [7.0, 8.0], [53.0, 65.0])["backends"]["numpy"]
        say("pyhf", true, "CLs $(round(r["cls_obs"]; digits = 6)) through the gateway")
    end
    nothing
end

end # module
