# SphereSPX.jl -- Sphere Physics eXchange, the Julia reader.
#
# Standard library only (Mmap). Sphere writes PDF sets, event samples and jets
# into SPX files; this maps them and wraps the arrays in place, without a copy.
# The PDF interpolation is the same arithmetic, in the same order, as Sphere's
# Java one and LHAPDF's log-bicubic, so the answers agree to the bit:
# ":bridge crosscheck" shows it.
#
#   pdf = SphereSPX.pdf("CT18NLO")            # $SPHERE_BRIDGE/CT18NLO.spx
#   xfxQ2(pdf, 21, 1e-3, 1e4)                 # member 0
#   uncertainty(pdf, 21, 1e-3, 1e4)           # Hessian or replicas
#   ev = SphereSPX.events("events")           # particles, jets, weights
#   hep = SphereSPX.events("hepmc")           # ':hepmc bridge': the HepMC3 record too
#   finalstate(hep, 1), vertices(hep, 1), mothers(hep, 1), SphereSPX.weights(hep, 1)
#   SphereSPX.publish("pt", edges, counts)    # shows up in Sphere's Plots tab
#
# Inside Sphere's Julia session this module is already loaded, and
# ":bridge julia pdf <handle>" binds a set to a variable of Main.

module SphereSPX

using Mmap

export SPXFile, PDFSet, Events, xfxQ2, xfxQ, alphasQ2, alphasQ, uncertainty, member_values,
       inrange, npart, njets, particle, jet, jet_of, incoming, publish,
       ishepmc, finalstate, genmass, vertices, prodvertex, endvertex, mothers, daughters, eventnumber,
       weightnames, crosssection

const F64 = Int32(1)
const I64 = Int32(2)
const TEXT = Int32(3)

struct Section
    name::String
    type::Int32
    offset::Int64
    count::Int64
end

"""A mapped SPX file. The arrays handed out point into it, so it is kept alive with them."""
struct SPXFile
    path::String
    kind::Int32
    title::String
    bytes::Vector{UInt8}
    sections::Dict{String,Section}
end

bridge_folder() = get(ENV, "SPHERE_BRIDGE", "bridge")

"""A name, or a path: a bare name is looked for in the bridge folder."""
function resolve(name::AbstractString)
    (occursin('/', name) || occursin('\\', name)) && return String(name)
    endswith(name, ".spx") && return joinpath(bridge_folder(), name)
    return joinpath(bridge_folder(), name * ".spx")
end

readi32(b, at) = ltoh(unsafe_load(Ptr{Int32}(pointer(b, at + 1))))
readi64(b, at) = ltoh(unsafe_load(Ptr{Int64}(pointer(b, at + 1))))
readname(b, at, n) = strip(String(copy(b[at+1:at+n])))

function SPXFile(path::AbstractString)
    size = filesize(path)
    size >= 64 || error("$path is too short to be an SPX file")
    bytes = open(path, "r") do io
        Mmap.mmap(io, Vector{UInt8}, size)
    end
    String(copy(bytes[1:8])) == "SPHRSPX1" || error("$path is not an SPX file")
    readi32(bytes, 8) == 1 || error("$path: unsupported SPX version")
    kind = readi32(bytes, 12)
    n = readi32(bytes, 16)
    dir = readi64(bytes, 24)
    sections = Dict{String,Section}()
    for k in 0:n-1
        at = dir + 64k
        s = Section(readname(bytes, at, 24), readi32(bytes, at + 24), readi64(bytes, at + 32), readi64(bytes, at + 40))
        s.offset + (s.type == TEXT ? s.count : 8s.count) <= size || error("$path: section $(s.name) runs past the end")
        sections[s.name] = s
    end
    SPXFile(String(path), kind, readname(bytes, 40, 24), bytes, sections)
end

Base.haskey(f::SPXFile, name::AbstractString) = haskey(f.sections, name)

function section(f::SPXFile, name, type)
    s = get(f.sections, name, nothing)
    s === nothing && error("$(f.path) has no section $name")
    s.type == type || error("section $name has another type")
    s
end

"""A Float64 array over the mapped bytes: no copy."""
function f64(f::SPXFile, name)
    s = section(f, name, F64)
    unsafe_wrap(Array, Ptr{Float64}(pointer(f.bytes, s.offset + 1)), s.count)
end

function i64(f::SPXFile, name)
    s = section(f, name, I64)
    unsafe_wrap(Array, Ptr{Int64}(pointer(f.bytes, s.offset + 1)), s.count)
end

function text(f::SPXFile, name)
    s = section(f, name, TEXT)
    String(copy(f.bytes[s.offset+1:s.offset+s.count]))
end

function meta(f::SPXFile)
    out = Dict{String,String}()
    haskey(f, "meta") || return out
    for line in split(text(f, "meta"), '\n')
        colon = findfirst(':', line)
        colon === nothing && continue
        out[strip(line[1:colon-1])] = strip(line[colon+1:end])
    end
    out
end

# ---------------------------------------------------------------------------
# Parton distributions
# ---------------------------------------------------------------------------

mutable struct PDFSet            # mutable: passed as one pointer, not copied field by field into every call
    file::SPXFile
    meta::Dict{String,String}
    nf::Int
    nq::Int
    nx::Int
    nm::Int
    pids::Vector{Int64}
    xs::Vector{Float64}
    q2s::Vector{Float64}
    logx::Vector{Float64}
    logq2::Vector{Float64}
    xf::Vector{Float64}
    weighted::Bool
    lookup::Vector{Int}          # 1..13 for pid -6..6, 14 gluon, 15 photon; 0-based flavour or -1
    asq2::Vector{Float64}
    aslog::Vector{Float64}
    asval::Vector{Float64}
    ashold::Bool
    pieces::Vector{Int}          # 0-based first index of each alpha_s subgrid
end

function PDFSet(f::SPXFile)
    f.kind == 1 || error("$(f.path) does not hold a PDF set")
    m = meta(f)
    shape = i64(f, "shape")
    nf, nq, nx, nm = Int(shape[1]), Int(shape[2]), Int(shape[3]), Int(shape[4])
    pids = i64(f, "pids")
    lookup = fill(-1, 15)
    for k in 0:nf-1
        p = pids[k+1]
        -6 <= p <= 6 && (lookup[p+7] = k)
        p == 21 && (lookup[14] = k)
        p == 22 && (lookup[15] = k)
    end
    if haskey(f, "as_q2")
        asq2, aslog, asval = f64(f, "as_q2"), f64(f, "as_logq2"), f64(f, "as_val")
        pieces = [0]
        for k in 1:length(asq2)-1
            abs(asq2[k+1] - asq2[k]) < 2.220446049250313e-16 && push!(pieces, k)
        end
    else
        asq2, aslog, asval, pieces = Float64[], Float64[], Float64[], Int[]
    end
    PDFSet(f, m, nf, nq, nx, nm, pids, f64(f, "xknots"), f64(f, "q2knots"), f64(f, "logx"), f64(f, "logq2"),
           f64(f, "xf"), get(m, "Accuracy", "lhapdf") == "weighted", lookup, asq2, aslog, asval,
           get(m, "AlphaS_Above", "hold") == "hold", pieces)
end

"""A set Sphere exported, by name or by path."""
pdf(name::AbstractString) = PDFSet(SPXFile(resolve(name)))

Base.show(io::IO, p::PDFSet) =
    print(io, "PDFSet(", get(p.meta, "SetName", p.file.title), ", ", p.nm, " member", p.nm == 1 ? "" : "s", ", ",
          get(p.meta, "ErrorType", "none"), ")")

members(p::PDFSet) = p.nm
name(p::PDFSet) = get(p.meta, "SetName", p.file.title)
errortype(p::PDFSet) = get(p.meta, "ErrorType", "none")
xmin(p::PDFSet) = p.xs[1]
xmax(p::PDFSet) = p.xs[end]
q2min(p::PDFSet) = p.q2s[1]
q2max(p::PDFSet) = p.q2s[end]

function flavor(p::PDFSet, pid::Integer)
    (pid == 0 || pid == 21) && return p.lookup[14]
    pid == 22 && return p.lookup[15]
    -6 <= pid <= 6 && return p.lookup[pid+7]
    return -1
end

inrange(p::PDFSet, x, q2) = x >= p.xs[1] && x <= p.xs[end] && q2 >= p.q2s[1] && q2 <= p.q2s[end]

# Everything below counts from 0, as the Java and C++ versions do, so the three
# can be read side by side; g() is the one place the 1 is added.
@inline g(a, i) = @inbounds a[i+1]

function below(value, knots, n)
    low, high = 0, n
    while low < high
        mid = (low + high) >>> 1
        if g(knots, mid) <= value
            low = mid + 1
        else
            high = mid
        end
    end
    low >= n && (low = n - 1)
    low == 0 ? 0 : low - 1
end

@inline val(p, base, ix, iq, f) = @inbounds p.xf[base+(ix*p.nq+iq)*p.nf+f+1]

function slope(p, base, ix, iq, f)
    axis = p.logx
    nx = p.nx
    if ix != 0 && ix != nx - 1
        back = g(axis, ix) - g(axis, ix - 1)
        ahead = g(axis, ix + 1) - g(axis, ix)
        left = (val(p, base, ix, iq, f) - val(p, base, ix - 1, iq, f)) / back
        right = (val(p, base, ix + 1, iq, f) - val(p, base, ix, iq, f)) / ahead
        p.weighted && return (ahead * left + back * right) / (back + ahead)
        return (left + right) / 2.0
    end
    ix == 0 && return (val(p, base, 1, iq, f) - val(p, base, 0, iq, f)) / (g(axis, 1) - g(axis, 0))
    return (val(p, base, nx - 1, iq, f) - val(p, base, nx - 2, iq, f)) / (g(axis, nx - 1) - g(axis, nx - 2))
end

@inline function alongx(p, base, ix, iq, f, t)
    dlogx = g(p.logx, ix + 1) - g(p.logx, ix)
    vl = val(p, base, ix, iq, f)
    vh = val(p, base, ix + 1, iq, f)
    vdl = slope(p, base, ix, iq, f) * dlogx
    vdh = slope(p, base, ix + 1, iq, f) * dlogx
    c0 = vdh + vdl - 2.0 * vh + 2.0 * vl
    c1 = 3.0 * vh - 3.0 * vl - 2.0 * vdl - vdh
    c2 = vdl
    c3 = vl
    t2 = t * t
    return c0 * t2 * t + c1 * t2 + c2 * t + c3
end

function hermite(t, vl, vdl, vh, vdh)
    t2 = t * t
    t3 = t * t2
    return (2 * t3 - 3 * t2 + 1) * vl + (t3 - 2 * t2 + t) * vdl + (-2 * t3 + 3 * t2) * vh + (t3 - t2) * vdh
end

linear(x, xl, xh, yl, yh) = yl + (x - xl) / (xh - xl) * (yh - yl)

function interpolate(p::PDFSet, base, f, x, q2)
    nq = p.nq
    ix = below(x, p.xs, p.nx)
    iq = below(q2, p.q2s, nq)
    lx = log(x)
    lq = log(q2)
    lowseam = (iq == 0) || (g(p.q2s, iq) == g(p.q2s, iq - 1))
    highseam = (iq + 1 == nq - 1) || (g(p.q2s, iq + 1) == g(p.q2s, iq + 2))
    dlogx = g(p.logx, ix + 1) - g(p.logx, ix)
    tx = (lx - g(p.logx, ix)) / dlogx
    dlogq1 = g(p.logq2, iq + 1) - g(p.logq2, iq)
    tq = (lq - g(p.logq2, iq)) / dlogq1
    if lowseam && highseam
        fl = linear(lx, g(p.logx, ix), g(p.logx, ix + 1), val(p, base, ix, iq, f), val(p, base, ix + 1, iq, f))
        fh = linear(lx, g(p.logx, ix), g(p.logx, ix + 1), val(p, base, ix, iq + 1, f), val(p, base, ix + 1, iq + 1, f))
        return linear(lq, g(p.logq2, iq), g(p.logq2, iq + 1), fl, fh)
    end
    vl = alongx(p, base, ix, iq, f, tx)
    vh = alongx(p, base, ix, iq + 1, f, tx)
    if lowseam
        vdl = vh - vl
        vhh = alongx(p, base, ix, iq + 2, f, tx)
        dlogq2 = 1.0 / (g(p.logq2, iq + 2) - g(p.logq2, iq + 1))
        vdh = (vdl + (vhh - vh) * dlogq1 * dlogq2) * 0.5
    elseif highseam
        vdh = vh - vl
        vll = alongx(p, base, ix, iq - 1, f, tx)
        dlogq0 = 1.0 / (g(p.logq2, iq) - g(p.logq2, iq - 1))
        vdl = (vdh + (vl - vll) * dlogq1 * dlogq0) * 0.5
    else
        vll = alongx(p, base, ix, iq - 1, f, tx)
        dlogq0 = 1.0 / (g(p.logq2, iq) - g(p.logq2, iq - 1))
        vdl = ((vh - vl) + (vl - vll) * dlogq1 * dlogq0) * 0.5
        vhh = alongx(p, base, ix, iq + 2, f, tx)
        dlogq2 = 1.0 / (g(p.logq2, iq + 2) - g(p.logq2, iq + 1))
        vdh = ((vh - vl) + (vhh - vh) * dlogq1 * dlogq2) * 0.5
    end
    return hermite(tq, vl, vdl, vh, vdh)
end

function alonglog(x, xa, xb, fa, fb)
    t = (log(x) - log(xa)) / (log(xb) - log(xa))
    (fa > 1e-3 && fb > 1e-3) && return exp(log(fa) + t * (log(fb) - log(fa)))
    return fa + t * (fb - fa)
end

# LHAPDF's continuation outside the grid (the MSTW rule below Q min).
function continuation(p::PDFSet, base, f, x, q2)
    xs, q2s = p.xs, p.q2s
    xMin, xMin1, xMax = xs[1], xs[2], xs[end]
    q2Min, q2Max1, q2Max = q2s[1], q2s[end-1], q2s[end]
    I(xx, qq) = interpolate(p, base, f, xx, qq)
    alongxq(xx, qq) = alonglog(xx, xMin, xMin1, I(xMin, qq), I(xMin1, qq))
    x > xMax && return 0.0          # LHAPDF refuses; a momentum fraction above one carries nothing.
    (x < xMin && q2 >= q2Min && q2 <= q2Max) && return alongxq(x, q2)
    (x >= xMin && q2 > q2Max) && return alonglog(q2, q2Max, q2Max1, I(x, q2Max), I(x, q2Max1))
    if x < xMin && q2 > q2Max
        atmin = alonglog(q2, q2Max, q2Max1, I(xMin, q2Max), I(xMin, q2Max1))
        atmin1 = alonglog(q2, q2Max, q2Max1, I(xMin1, q2Max), I(xMin1, q2Max1))
        return alonglog(x, xMin, xMin1, atmin, atmin1)
    end
    if q2 < q2Min
        if x < xMin
            atedge, justabove = alongxq(x, q2Min), alongxq(x, 1.01 * q2Min)
        else
            atedge, justabove = I(x, q2Min), I(x, 1.01 * q2Min)
        end
        anomalous = abs(atedge) >= 1e-5 ? max(-2.5, (justabove - atedge) / atedge / 0.01) : 1.0
        ratio = q2 / q2Min
        return atedge * ratio^(anomalous * ratio + 1.0 - ratio)
    end
    return I(x, q2)
end

"""x f(x, Q²) of one member (0 is the central one); 0 for a flavour the set does not carry."""
function xfxQ2(p::PDFSet, pid::Integer, x::Real, q2::Real, member::Integer = 0)
    f = flavor(p, pid)
    (f < 0 || member < 0 || member >= p.nm) && return 0.0
    (x <= 0.0 || q2 <= 0.0) && return 0.0
    x, q2 = Float64(x), Float64(q2)
    base = member * p.nx * p.nq * p.nf
    inrange(p, x, q2) && return interpolate(p, base, f, x, q2)
    return continuation(p, base, f, x, q2)
end

xfxQ(p::PDFSet, pid::Integer, x::Real, q::Real, member::Integer = 0) = xfxQ2(p, pid, x, Float64(q)^2, member)

member_values(p::PDFSet, pid, x, q2) = [xfxQ2(p, pid, x, q2, m) for m in 0:p.nm-1]

"""LHAPDF's PDFSet::uncertainty at the set's own confidence level: (central, errplus, errminus, errsymm)."""
function uncertainty(p::PDFSet, values::AbstractVector)
    n = length(values)
    central = n > 0 ? values[1] : 0.0
    type = errortype(p)
    (n < 2 || type == "none") && return (central = central, errplus = 0.0, errminus = 0.0, errsymm = 0.0)
    if type == "replicas"
        nrep = n - 1.0
        mean = 0.0
        mean2 = 0.0
        for m in 2:n
            mean += values[m]
            mean2 += values[m] * values[m]
        end
        mean /= nrep
        mean2 /= nrep
        s = sqrt(max(0.0, nrep / (nrep - 1.0) * (mean2 - mean * mean)))
        return (central = mean, errplus = s, errminus = s, errsymm = s)
    elseif type == "symmhessian"
        s = 0.0
        for m in 2:n
            s += (values[m] - central) * (values[m] - central)
        end
        s = sqrt(s)
        return (central = central, errplus = s, errminus = s, errsymm = s)
    end
    up = down = symm = 0.0
    m = 2
    while m + 1 <= n
        a, b = values[m] - central, values[m+1] - central
        rise, fall = max(max(a, b), 0.0), max(max(-a, -b), 0.0)
        up += rise * rise
        down += fall * fall
        symm += (values[m] - values[m+1]) * (values[m] - values[m+1])
        m += 2
    end
    return (central = central, errplus = sqrt(up), errminus = sqrt(down), errsymm = 0.5 * sqrt(symm))
end

uncertainty(p::PDFSet, pid::Integer, x::Real, q2::Real) = uncertainty(p, member_values(p, pid, x, q2))

"""alpha_s(Q²) from the table the set carries, interpolated the LHAPDF way."""
function alphasQ2(p::PDFSet, q2::Real)
    q, a = p.asq2, p.asval
    n = length(q)
    (n == 0 || q2 < 0.0) && return NaN
    q2 = Float64(q2)
    if q2 < q[1]
        next = 1
        while next < n && q[1] == g(q, next)
            next += 1
        end
        (next >= n || a[1] <= 0.0 || g(a, next) <= 0.0) && return a[1]
        slope = log10(g(a, next) / a[1]) / log10(g(q, next) / q[1])
        return a[1] * (q2 / q[1])^slope
    end
    if q2 > q[n]
        (p.ashold || n < 3) && return a[n]
        lo, hi = a[n-1], a[n]
        (lo <= 0.0 || hi <= 0.0 || q[n] == q[n-1]) && return hi
        slope = log(hi / lo) / log(q[n] / q[n-1])
        return hi * (q2 / q[n])^slope
    end
    pc = 1
    while pc < length(p.pieces) && g(q, p.pieces[pc+1]) <= q2
        pc += 1
    end
    s = p.pieces[pc]
    len = (pc < length(p.pieces) ? p.pieces[pc+1] : n) - s
    pq = view(q, s+1:s+len)
    pl = view(p.aslog, s+1:s+len)
    pa = view(a, s+1:s+len)
    i = below(q2, pq, len)
    forward(k) = (g(pa, k + 1) - g(pa, k)) / (g(pl, k + 1) - g(pl, k))
    backward(k) = (g(pa, k) - g(pa, k - 1)) / (g(pl, k) - g(pl, k - 1))
    central(k) = 0.5 * (forward(k) + backward(k))
    if len == 2
        sl = sh = forward(0)
    elseif i == 0
        sl, sh = forward(i), central(i + 1)
    elseif i == len - 2
        sl, sh = central(i), backward(i + 1)
    else
        sl, sh = central(i), central(i + 1)
    end
    dlog = g(pl, i + 1) - g(pl, i)
    t = (log(q2) - g(pl, i)) / dlog
    out = hermite(t, g(pa, i), sl * dlog, g(pa, i + 1), sh * dlog)
    return abs(out) < 2.0 ? out : floatmax(Float64)
end

alphasQ(p::PDFSet, q::Real) = alphasQ2(p, Float64(q)^2)

# ---------------------------------------------------------------------------
# Events and jets
# ---------------------------------------------------------------------------

mutable struct Events
    file::SPXFile
    meta::Dict{String,String}
    offset::Vector{Int64}
    p4::Vector{Float64}
    pdg::Vector{Int64}
    weight::Vector{Float64}
    incoming::Union{Nothing,Vector{Float64}}
    jet_offset::Union{Nothing,Vector{Int64}}
    jet_p4::Union{Nothing,Vector{Float64}}
    jet_of::Union{Nothing,Vector{Int64}}
end

function Events(f::SPXFile)
    f.kind == 2 || error("$(f.path) does not hold events")
    jets = haskey(f, "jet_offset")
    Events(f, meta(f), i64(f, "evt_offset"), f64(f, "p4"), i64(f, "pdg"), f64(f, "weight"),
           haskey(f, "incoming") ? f64(f, "incoming") : nothing,
           jets ? i64(f, "jet_offset") : nothing, jets ? f64(f, "jet_p4") : nothing, jets ? i64(f, "jet_of") : nothing)
end

events(name::AbstractString) = Events(SPXFile(resolve(name)))

Base.length(ev::Events) = length(ev.offset) - 1
Base.show(io::IO, ev::Events) =
    print(io, "Events(", length(ev), " events, ", length(ev.pdg), " particles",
          ishepmc(ev) ? ", $(i64(ev.file, "vtx_offset")[end]) vertices (HepMC3)" : "",
          ev.jet_offset === nothing ? "" : ", $(ev.jet_offset[end]) jets", ")")

npart(ev::Events, e::Integer) = Int(ev.offset[e+1] - ev.offset[e])
"""The particles of event e (1-based) as a 4×n matrix of px, py, pz, E: a view, no copy."""
particles(ev::Events, e::Integer) = reshape(view(ev.p4, 4ev.offset[e]+1:4ev.offset[e+1]), 4, :)
particle(ev::Events, e::Integer, i::Integer) = view(ev.p4, 4(ev.offset[e]+i-1)+1:4(ev.offset[e]+i))
pdgs(ev::Events, e::Integer) = view(ev.pdg, ev.offset[e]+1:ev.offset[e+1])
njets(ev::Events, e::Integer) = Int(ev.jet_offset[e+1] - ev.jet_offset[e])
jets(ev::Events, e::Integer) = reshape(view(ev.jet_p4, 4ev.jet_offset[e]+1:4ev.jet_offset[e+1]), 4, :)
jet(ev::Events, e::Integer, k::Integer) = view(ev.jet_p4, 4(ev.jet_offset[e]+k-1)+1:4(ev.jet_offset[e]+k))
"""The jet (1-based) each particle of event e ended up in, 0 when none."""
jet_of(ev::Events, e::Integer) = view(ev.jet_of, ev.offset[e]+1:ev.offset[e+1]) .+ 1
incoming(ev::Events, e::Integer) = (v = view(ev.incoming, 5(e-1)+1:5e);
    (id1 = Int(v[1]), id2 = Int(v[2]), x1 = v[3], x2 = v[4], scale = v[5]))

# HepMC3 events (':hepmc bridge'): the whole record, as HepMC3 holds it. Indices
# are 1-based here as everywhere in Julia: vertex k of event e, particle i.

"""True when the sample holds HepMC3 events: statuses, vertices and the graph."""
ishepmc(ev::Events) = haskey(ev.file, "links")
_needhepmc(ev::Events) = ishepmc(ev) || error("$(ev.file.path) holds particles only, not a HepMC3 record")

"""The HepMC status of each particle of event e (1: final state)."""
status(ev::Events, e::Integer) = (_needhepmc(ev); view(i64(ev.file, "status"), ev.offset[e]+1:ev.offset[e+1]))
"""The particles of status 1 of event e, as indices into particles(ev, e)."""
finalstate(ev::Events, e::Integer) = findall(==(1), status(ev, e))
"""The generated mass of each particle, NaN where none was set."""
genmass(ev::Events, e::Integer) = (_needhepmc(ev); view(f64(ev.file, "gen_mass"), ev.offset[e]+1:ev.offset[e+1]))
"""The vertices of event e as a 4×n matrix of x, y, z, t."""
function vertices(ev::Events, e::Integer)
    _needhepmc(ev)
    vo = i64(ev.file, "vtx_offset")
    reshape(view(f64(ev.file, "vtx_pos"), 4vo[e]+1:4vo[e+1]), 4, :)
end
"""The vertex each particle of event e comes from (1-based in the event), 0 for none."""
prodvertex(ev::Events, e::Integer) = (_needhepmc(ev); view(i64(ev.file, "prod_vtx"), ev.offset[e]+1:ev.offset[e+1]) .+ 1)
"""The vertex each particle of event e ends in (1-based in the event), 0 for none."""
endvertex(ev::Events, e::Integer) = (_needhepmc(ev); view(i64(ev.file, "end_vtx"), ev.offset[e]+1:ev.offset[e+1]) .+ 1)
"""HEPEVT's JMOHEP: a 2×n matrix of first and last mother, 1-based in the event, 0 for none."""
mothers(ev::Events, e::Integer) = (_needhepmc(ev); reshape(view(i64(ev.file, "mothers"), 2ev.offset[e]+1:2ev.offset[e+1]), 2, :))
"""HEPEVT's JDAHEP: a 2×n matrix of first and last daughter."""
daughters(ev::Events, e::Integer) = (_needhepmc(ev); reshape(view(i64(ev.file, "daughters"), 2ev.offset[e]+1:2ev.offset[e+1]), 2, :))
eventnumber(ev::Events, e::Integer) = haskey(ev.file, "event_number") ? Int(i64(ev.file, "event_number")[e]) : e
"""Every weight of event e; their names are weightnames(ev)."""
function weights(ev::Events, e::Integer)
    haskey(ev.file, "wgt_offset") || return [ev.weight[e]]
    wo = i64(ev.file, "wgt_offset")
    view(f64(ev.file, "wgt_all"), wo[e]+1:wo[e+1])
end
weightnames(ev::Events) = (n = get(ev.meta, "WeightNames", ""); isempty(n) ? String[] : strip.(split(n, "|")))
"""(cross-section, error) of event e in pb; NaN when the event has none."""
crosssection(ev::Events, e::Integer) = (x = f64(ev.file, "xsec"); (x[2e-1], x[2e]))

pt(p) = hypot(p[1], p[2])
rapidity(p) = 0.5 * log((p[4] + p[3]) / (p[4] - p[3]))
mass(p) = (m2 = p[4]^2 - p[1]^2 - p[2]^2 - p[3]^2; m2 < 0 ? -sqrt(-m2) : sqrt(m2))

"""Any SPX file, as what it holds: a PDFSet, Events, or a Dict of columns for a table."""
function load(name::AbstractString)
    f = SPXFile(resolve(name))
    f.kind == 1 && return PDFSet(f)
    f.kind == 2 && return Events(f)
    out = Dict{String,Any}()
    for (k, s) in f.sections
        out[k] = s.type == F64 ? f64(f, k) : s.type == I64 ? i64(f, k) : text(f, k)
    end
    return out
end

# ---------------------------------------------------------------------------
# Writing results back
# ---------------------------------------------------------------------------

"""Writes a table of named columns (Float64 or Int64 vectors) as an SPX file."""
function write_table(path::AbstractString, title::AbstractString, columns::Vector{<:Pair}; meta::AbstractString = "")
    entries = Any[]
    metatext = "Title: " * title * "\n" * meta
    push!(entries, ("meta", TEXT, Vector{UInt8}(metatext)))
    for (name, values) in columns
        v = collect(values)
        eltype(v) <: Integer ? push!(entries, (String(name), I64, Int64.(v))) :
                               push!(entries, (String(name), F64, Float64.(v)))
    end
    aligned(v) = (v + 63) ÷ 64 * 64
    bytesof(e) = e[2] == TEXT ? length(e[3]) : 8 * length(e[3])
    offsets = Int64[]
    at = aligned(64 + 64 * length(entries))
    for e in entries
        push!(offsets, at)
        at = aligned(at + bytesof(e))
    end
    buf = zeros(UInt8, at)
    put(pos, x) = (b = reinterpret(UInt8, [htol(x)]); buf[pos+1:pos+length(b)] .= b)
    putname(pos, s) = (buf[pos+1:pos+24] .= UInt8(' '); c = codeunits(s)[1:min(24, ncodeunits(s))];
                       buf[pos+1:pos+length(c)] .= c)
    buf[1:8] .= codeunits("SPHRSPX1")
    put(8, Int32(1)); put(12, Int32(3)); put(16, Int32(length(entries))); put(24, Int64(64)); put(32, Int64(at))
    putname(40, title)
    for (k, e) in enumerate(entries)
        d = 64 + 64 * (k - 1)
        putname(d, e[1])
        put(d + 24, e[2])
        put(d + 32, offsets[k])
        put(d + 40, Int64(length(e[3])))
        raw = e[2] == TEXT ? e[3] : reinterpret(UInt8, htol.(e[3]))
        buf[offsets[k]+1:offsets[k]+length(raw)] .= raw
    end
    mkpath(dirname(abspath(path)))
    part = path * ".part"
    write(part, buf)
    mv(part, path; force = true)
    return path
end

"""Hands a histogram to Sphere: it appears in the Plots tab by itself."""
function publish(name::AbstractString, edges::AbstractVector, values::AbstractVector;
                 err_plus = nothing, err_minus = nothing, xlabel::AbstractString = name)
    cols = Pair{String,Any}["edges" => Float64.(edges), "values" => Float64.(values)]
    err_plus === nothing || push!(cols, "err_plus" => Float64.(err_plus))
    err_minus === nothing || push!(cols, "err_minus" => Float64.(err_minus))
    write_table(joinpath(bridge_folder(), "outbox", name * ".spx"), name, cols;
                meta = "Kind: histogram\nXLabel: $xlabel\nEngine: Julia\n")
end

# ---------------------------------------------------------------------------
# The cross-check Sphere runs on every engine
# ---------------------------------------------------------------------------

function crosscheck(pdfpath::AbstractString, pointspath::AbstractString, outpath::AbstractString)
    p = PDFSet(SPXFile(pdfpath))
    pts = SPXFile(pointspath)
    pid, x, q2, mem, aq2 = f64(pts, "pid"), f64(pts, "x"), f64(pts, "q2"), f64(pts, "member"), f64(pts, "as_q2")
    n = length(pid)
    evalall() = [xfxQ2(p, Int(pid[k]), x[k], q2[k], Int(mem[k])) for k in 1:n]
    xf = evalall()                     # also compiles everything before the clock starts
    as = [alphasQ2(p, v) for v in aq2]
    rounds = 0
    sink = 0.0
    start = time_ns()
    while true
        for k in 1:n
            sink += xfxQ2(p, Int(pid[k]), x[k], q2[k], Int(mem[k]))
        end
        rounds += 1
        (time_ns() - start > 200_000_000 || rounds >= 1000) && break
    end
    elapsed = (time_ns() - start) / (n * rounds)
    write_table(outpath, "crosscheck", ["xf" => xf, "as" => as, "ns_per_eval" => [elapsed]];
                meta = "Engine: Julia $(VERSION)\nRounds: $rounds\nSink: $sink\n")
end

"""Per event of a HepMC3 sample: the checksums ':hepmc crosscheck' compares with Java's, bit for bit."""
function hepmccheck(eventspath::AbstractString, outpath::AbstractString)
    ev = Events(SPXFile(eventspath))
    _needhepmc(ev)
    f = ev.file
    st, mo, vo = i64(f, "status"), i64(f, "mothers"), i64(f, "vtx_offset")
    wo, wa, p4 = i64(f, "wgt_offset"), f64(f, "wgt_all"), ev.p4
    n = length(ev)
    np_, nfinal, nvtx, mosum = zeros(Int64, n), zeros(Int64, n), zeros(Int64, n), zeros(Int64, n)
    efinal, pzfinal, wsum = zeros(Float64, n), zeros(Float64, n), zeros(Float64, n)
    for e in 1:n
        a, b = ev.offset[e], ev.offset[e+1]
        nf, ms, ef, pzf, ws = 0, 0, 0.0, 0.0, 0.0
        for i in a:b-1                       # the order Java sums in: no pairwise sum()
            if st[i+1] == 1
                nf += 1
                ef += p4[4i+4]
                pzf += p4[4i+3]
            end
            ms += mo[2i+1]
        end
        for k in wo[e]:wo[e+1]-1
            ws += wa[k+1]
        end
        np_[e], nfinal[e], nvtx[e], mosum[e] = b - a, nf, vo[e+1] - vo[e], ms
        efinal[e], pzfinal[e], wsum[e] = ef, pzf, ws
    end
    write_table(outpath, "hepmc_check", ["np" => np_, "nfinal" => nfinal, "nvtx" => nvtx, "mosum" => mosum,
                "efinal" => efinal, "pzfinal" => pzfinal, "wsum" => wsum]; meta = "Engine: Julia $(VERSION)\n")
end

end # module
