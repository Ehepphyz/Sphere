# SphereMLink.jl
#
# TensorFlow, PyTorch, Keras, JAX and pyhf from Julia, through the gateway (sphere_mlink.py says how), started if it
# is not running. Nothing to install, and no PyCall: the frameworks stay in the Python that holds them.
#
#     include(joinpath(ENV["SPHERE_BRIDGE_LIB"], "SphereMLink.jl"))
#     using .SphereMLink
#
#     SphereMLink.detect()                                # Dict("torch" => "2.11.0", ...): starts nothing
#     x = [1.0 2.0 3.0; 4.0 5.0 6.5]
#     SphereMLink.py("torch.linalg.norm(torch.as_tensor(x)).item()"; x)
#     SphereMLink.py("tf.nn.softmax(tf.constant(x)).numpy()"; x)          # a Matrix{Float64}, indices as in Python
#     SphereMLink.predict("model.pt"; x)                  # TorchScript, torch.export, Keras, a SavedModel
#     SphereMLink.pyhf([5.,10.], [50.,60.], [7.,8.], [53.,65.]; points=21)
#     SphereMLink.call("train"; data="jets", epochs=5)    # the engine's commands
#
# Code runs in one namespace per session (the keyword session), so that a model made by one call is there for the next.

module SphereMLink

isdefined(Main, :SphereSPX) || Base.include(@__MODULE__, joinpath(@__DIR__, "SphereSPX.jl"))
const SPX = isdefined(Main, :SphereSPX) ? Main.SphereSPX : SphereSPX

export LinkError

struct LinkError <: Exception
    message::String
end
Base.showerror(io::IO, e::LinkError) = print(io, "LinkError: ", e.message)

folder() = get(ENV, "SPHERE_MLINK", joinpath(SPX.bridge_folder(), "mlink"))

function readkeys(path)
    out = Dict{String,String}()
    isfile(path) || return out
    for line in eachline(path)
        at = findfirst(':', line)
        at === nothing && continue
        out[strip(line[1:at-1])] = strip(line[at+1:end])
    end
    out
end

"""What the gateway's Python holds, from frameworks.txt: starts nothing."""
function detect(; spool = folder())
    found = readkeys(joinpath(spool, "frameworks.txt"))
    if isempty(found) && haskey(ENV, "SPHERE_ML_FRAMEWORKS")
        for item in split(ENV["SPHERE_ML_FRAMEWORKS"], ';')
            occursin('=', item) || continue
            k, v = split(item, '='; limit = 2)
            found[k] = v
        end
    end
    found
end

escape(s) = replace(String(s), "\\" => "\\\\", "\r" => "\\r", "\n" => "\\n")

function unescape(s::AbstractString)
    out = IOBuffer()
    i, n = firstindex(s), lastindex(s)
    while i <= n
        c = s[i]
        if c == '\\' && i < n && s[nextind(s, i)] in ('\\', 'n', 'r')
            i = nextind(s, i)
            write(out, s[i] == 'n' ? '\n' : s[i] == 'r' ? '\r' : '\\')
        else
            write(out, c)
        end
        i = nextind(s, i)
    end
    String(take!(out))
end

mutable struct State
    spool::String
    token::String
    counter::Int
    nonce::String
end

const STATE = Ref{Union{State,Nothing}}(nothing)

function start!(spool)
    script = Sys.iswindows() ? joinpath(spool, "start.bat") : joinpath(spool, "start.sh")
    isfile(script) || return
    run(Sys.iswindows() ? `cmd /c $script` : `sh $script`; wait = true)
end

token(spool) = get(readkeys(joinpath(spool, "gateway.txt")), "Token", "")

function connect(; spool = folder(), timeout = 120.0)
    st = State(spool, "", 0, string(time_ns() % 0xffffff; base = 16))
    deadline, started = time() + timeout, false
    while true
        st.token = token(spool)
        if !isempty(st.token)
            try
                r = request(st, "ping"; timeout = 2.0, retry = false)
                if r.meta["Ok"] == "1"
                    STATE[] = st
                    return st
                end
            catch e
                e isa InterruptException && rethrow()
                before = heartbeat(spool)   # busy with someone's long computation: it will come to this one
                sleep(1.5)
                if !isempty(before) && before != heartbeat(spool)
                    STATE[] = st
                    return st
                end
            end
        end
        if !started
            start!(spool)
            started = true
        end
        time() > deadline && throw(LinkError("no gateway answers in $spool. In Sphere: ':ml link start'; elsewhere: " *
                                             "python sphere_mlink.py --serve <folder>. Its log is gateway.log there."))
        sleep(0.05)
    end
end

state() = STATE[] === nothing ? connect() : STATE[]

struct Reply
    meta::Dict{String,String}
    columns::Dict{String,Vector{Float64}}
end

function readreply(path)
    f = nothing
    for attempt in 1:500        # a scanner may hold a new file for a moment (Windows)
        try
            f = SPX.SPXFile(path)
            break
        catch
            attempt == 500 && rethrow()
            sleep(0.01)
        end
    end
    meta = Dict(k => unescape(v) for (k, v) in SPX.meta(f))
    cols = Dict{String,Vector{Float64}}()
    for (name, s) in f.sections
        s.type == SPX.F64 && (cols[name] = copy(SPX.f64(f, name)))
    end
    Reply(meta, cols)
end

shapeof(text) = Tuple(parse.(Int, split(text)))
shaped(r::Reply, column) = reshape(r.columns[column], shapeof(get(r.meta, "Shape.$column", string(length(r.columns[column])))))

function request(st::State, op; fields = Pair{String,String}[], arrays = Pair{String,Any}[], timeout = 0.0, retry = true)
    if retry                    # the gateway stops when idle: the next call starts it again
        st.token = token(st.spool)
        isempty(st.token) && (connect(; spool = st.spool); st = STATE[])
    end
    st.counter += 1
    id = "$(getpid())-$(st.counter)-$(st.nonce)"
    meta = IOBuffer()
    print(meta, "Op: ", op, "\nProtocol: 1\nToken: ", st.token, "\nOrder: F\n")
    for (k, v) in fields
        print(meta, k, ": ", escape(v), "\n")
    end
    cols = Pair{String,Any}[]
    for (name, a) in arrays
        length(String(name)) <= 24 || error("an array's name holds 24 characters: $name")
        print(meta, "Shape.", name, ": ", join(size(a), " "), "\n")
        push!(cols, String(name) => Float64.(vec(a)))
    end
    text = String(take!(meta))
    for attempt in 1:100        # a scanner may hold a new file for a moment (Windows)
        try
            SPX.write_table(joinpath(st.spool, "in", id * ".spx"), "mlink", cols; meta = text)
            break
        catch
            attempt == 100 && rethrow()
            sleep(0.01)
        end
    end
    path = joinpath(st.spool, "out", id * ".spx")
    started, pause = time(), 1e-4
    beat, seen, kicked = heartbeat(st.spool), time(), false
    while !isfile(path)
        sleep(pause)
        pause = min(pause * 1.5, 0.02)
        now = time()
        if timeout > 0 && now - started > timeout
            touch(joinpath(st.spool, "in", id * ".cancel"))
            throw(LinkError("no answer to '$op' after $timeout s"))
        end
        if now - seen > 5
            current = heartbeat(st.spool)
            if retry && token(st.spool) != st.token    # gone, or another run: nobody will answer what was left for this one
                connect(; spool = st.spool)
                return request(STATE[], op; fields, arrays, timeout, retry = false)
            end
            if current != beat
                beat, seen, kicked = current, now, false
            else
                if retry && !kicked && now - seen > 15     # dead, it is replaced; alive, its lock turns the new one away
                    start!(st.spool)
                    kicked = true
                end
                # already sent twice, this request is what stops it
                now - seen > (retry ? 90 : 30) && throw(LinkError("the gateway has stopped answering (see gateway.log in $(st.spool))"))
            end
        end
    end
    reply = readreply(path)
    GC.gc(false)                # the mapping of the answer goes with its SPXFile: Windows will not remove a mapped file
    for attempt in 1:50
        try
            rm(path; force = true)
            break
        catch
            attempt == 50 || sleep(0.01)
        end
    end
    if get(reply.meta, "Retry", "") == "1" && retry
        st.token = token(st.spool)
        return request(st, op; fields, arrays, timeout, retry = false)
    end
    reply
end

heartbeat(spool) = (p = joinpath(spool, "heartbeat.txt"); isfile(p) ? read(p, String) : "")

function value(r::Reply)
    get(r.meta, "Ok", "0") == "1" || throw(LinkError(get(r.meta, "Error", "?") * "\n" * get(r.meta, "Traceback", "")))
    isempty(get(r.meta, "Output", "")) || print(r.meta["Output"])
    kind = get(r.meta, "Type", "none")
    kind == "number" && return parse(Float64, r.meta["Value"])
    kind == "text" && return get(r.meta, "Value", "")
    kind == "array" && return shaped(r, "a0")
    if kind == "dict"
        out = Dict{String,Any}()
        function put(path, v)
            d = out
            keys = split(path, '.')
            for k in keys[1:end-1]
                d = get!(d, String(k), Dict{String,Any}())
            end
            d[String(keys[end])] = v
        end
        for (k, v) in r.meta
            startswith(k, "R.") && put(k[3:end], parse(Float64, v))
            startswith(k, "S.") && put(k[3:end], v)
            startswith(k, "Array.") && put(v, shaped(r, k[7:end]))
        end
        return out
    end
    nothing
end

pairs_of(kw) = Pair{String,Any}[String(k) => v for (k, v) in kw]

"""Runs Python in the gateway with the keywords as arrays; the value of its last expression comes back."""
py(code::AbstractString; session = "default", timeout = 0.0, arrays...) =
    value(request(state(), "py"; fields = ["Code" => String(code), "Session" => String(session)], arrays = pairs_of(arrays), timeout))

"""A saved model on arrays given by name: TorchScript, torch.export, Keras, a SavedModel, a Sphere network or graph."""
predict(model::AbstractString; kind = "", timeout = 0.0, arrays...) =
    value(request(state(), "predict"; fields = ["Model" => abspath(model), "Kind" => String(kind), "Inputs" => join(keys(arrays), ",")],
                  arrays = pairs_of(arrays), timeout))

"""One channel of counts by pyhf: CLs at mu; with points > 0 the scan in mu too, for the 95 % CL upper limit."""
function pyhf(signal, background, uncertainty, observed; mu = 1.0, points = 0, mu_max = nothing, backends = "numpy", timeout = 0.0)
    fields = ["Mu" => string(Float64(mu)), "Points" => string(points), "Backends" => backends]
    mu_max === nothing || push!(fields, "MuMax" => string(Float64(mu_max)))
    value(request(state(), "pyhf"; fields, arrays = Pair{String,Any}["signal" => signal, "background" => background,
                                                                    "bkg_uncertainty" => uncertainty, "observed" => observed], timeout))
end

"""A pyhf JSON workspace."""
workspace(path::AbstractString; mu = 1.0, points = 0, backends = "numpy") =
    value(request(state(), "pyhf"; fields = ["Workspace" => abspath(path), "Mu" => string(Float64(mu)), "Points" => string(points), "Backends" => backends]))

"""One of the engine's commands (train, predict, stats, ...)."""
function call(command::AbstractString; timeout = 0.0, arguments...)
    json = "{" * join(("\"$k\": " * (v isa AbstractString ? "\"" * replace(v, "\\" => "\\\\", "\"" => "\\\"") * "\"" : string(v)) for (k, v) in arguments), ", ") * "}"
    value(request(state(), "call"; fields = ["Cmd" => String(command), "Args" => json], timeout))
end

"""What ':ml link check' asks of every language: the same requests, the same numbers, written to out."""
function crosscheck(out::AbstractString)
    x = [1.0 2.0 3.0; 4.0 5.0 6.5]
    first = py("torch.linalg.norm(torch.as_tensor(x)).item()"; x)
    second = py("float(tf.reduce_sum(tf.constant(x) ** 2))"; x)
    p = pyhf([5.0, 10.0], [50.0, 60.0], [7.0, 8.0], [53.0, 65.0])["backends"]["numpy"]
    sines = py("np.sin(x).ravel()"; x)
    started = time_ns()
    for _ in 1:20
        py("1+1")
    end
    ms = (time_ns() - started) / 20 / 1e6
    SPX.write_table(out, "mlink check", Pair{String,Any}["values" => vcat(first, second, p["cls_obs"], p["mu_hat"], sines), "ms" => [ms]];
                    meta = "Engine: Julia\n")
end

frameworks() = value(request(state(), "frameworks"; fields = ["Devices" => "1"]))
ping() = value(request(state(), "ping"))
stop() = value(request(state(), "stop"; timeout = 10.0))

end # module
