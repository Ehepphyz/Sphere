// sphere_mlink.hpp
//
// TensorFlow, PyTorch, Keras, JAX and pyhf from C++: a ROOT macro, a Geant4 application, any C++17 program, with
// nothing to link and no Python in the program. The frameworks stay in the Python that holds them; this header talks
// to the gateway there through a folder (sphere_mlink.py says how), started if it is not running, on this side of WSL
// or the other. It is included by name wherever Sphere started the program; elsewhere give the compiler
// -I <config/bridge/lib> (':ml link env' prints what to set).
//
//     #include "sphere_mlink.hpp"
//     using namespace sphere::mlink;
//
//     Frameworks f = detect();                                 // reads what is there; starts nothing
//     if (f.has("torch")) std::cout << f.version("torch");
//
//     Array x({1, 2, 3, 4, 5, 6}, {2, 3});                     // 2 rows, 3 columns
//     double n = link().number("torch.linalg.norm(torch.as_tensor(x)).item()", {{"x", x}});
//     Array g  = link().array("tf.nn.softmax(tf.constant(x)).numpy()", {{"x", x}});
//
//     Array y = link().predict("model.pt", {{"x", x}});        // TorchScript, torch.export, Keras, a SavedModel
//
//     Limit l = link().pyhf(signal, background, uncertainty, observed);    // CLs; add points for the upper limit
//     Limit r = link().pyhf(hsig, hbkg, hdata, 1.0, 21);                   // the same from ROOT histograms
//
// Code runs in one namespace per session, so that a model made by one call is there for the next. A failure there
// comes back as a LinkError holding Python's message; the gateway carries on. Several threads may call at once
// (Geant4's workers): each waits for its own answer.
//
// A call costs some milliseconds: right for a batch, an event, a fit. predict() needs no thought for the rest: a model
// called again and again is made a graph by the gateway, held to its framework on the program's own inputs, and from
// then on runs inside the program, through sphere_graph.hpp, if a measurement says that is faster -- microseconds,
// at every step of a simulation if need be: a SavedModel or frozen graph, a Keras model, a TorchScript or torch.export
// program. Define SPHERE_MLINK_NO_NATIVE before the include to always ask the gateway.

#ifndef SPHERE_MLINK_HPP
#define SPHERE_MLINK_HPP

#include "sphere_spx.hpp"

#if !defined(SPHERE_MLINK_NO_NATIVE) && defined(__has_include)
#if __has_include("sphere_graph.hpp")
#include "sphere_graph.hpp"
#define SPHERE_MLINK_NATIVE 1
#endif
#endif

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cctype>
#include <cmath>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <initializer_list>
#include <limits>
#include <locale>
#include <map>
#include <memory>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#if defined(_WIN32)
#include <process.h>
#else
#include <unistd.h>
#endif

namespace sphere {
namespace mlink {

class LinkError : public std::runtime_error {
public:
    explicit LinkError(const std::string& what) : std::runtime_error(what) {}
};

/** Numbers and their shape, rows last-index-fastest (as numpy and C++ arrays are). */
struct Array {
    std::vector<double> data;
    std::vector<std::int64_t> shape;

    Array() = default;
    Array(std::vector<double> d) : data(std::move(d)), shape{static_cast<std::int64_t>(data.size())} {}
    Array(std::initializer_list<double> d) : data(d), shape{static_cast<std::int64_t>(data.size())} {}
    Array(std::vector<double> d, std::vector<std::int64_t> s) : data(std::move(d)), shape(std::move(s)) {}

    std::size_t size() const { return data.size(); }
    std::size_t rank() const { return shape.size(); }
    double operator[](std::size_t k) const { return data[k]; }
    double& operator[](std::size_t k) { return data[k]; }
    /** Row i, column j of a matrix. */
    double at(std::size_t i, std::size_t j) const { return data[i * static_cast<std::size_t>(shape[1]) + j]; }
};

using Inputs = std::vector<std::pair<std::string, Array>>;
using Keys = std::map<std::string, std::string>;

namespace detail {

inline std::string env(const char* name) {
    const char* v = std::getenv(name);
    return v ? std::string(v) : std::string();
}

inline std::string folder(const std::string& given) {
    if (!given.empty()) return given;
    const std::string v = env("SPHERE_MLINK");
    return v.empty() ? spx::bridge_folder() + "/mlink" : v;
}

inline bool exists(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    return f.good();
}

inline std::string strip(const std::string& s) {
    const std::size_t a = s.find_first_not_of(" \t\r\n");
    if (a == std::string::npos) return "";
    return s.substr(a, s.find_last_not_of(" \t\r\n") - a + 1);
}

inline Keys read_keys(const std::string& path) {
    Keys out;
    std::ifstream in(path);
    std::string line;
    while (std::getline(in, line)) {
        const std::size_t colon = line.find(':');
        if (colon != std::string::npos) out[strip(line.substr(0, colon))] = strip(line.substr(colon + 1));
    }
    return out;
}

inline std::string read_all(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    std::ostringstream text;
    text << in.rdbuf();
    return strip(text.str());
}

inline std::string escape(const std::string& s) {
    std::string out;
    out.reserve(s.size());
    for (char c : s) {
        if (c == '\\') out += "\\\\";
        else if (c == '\n') out += "\\n";
        else if (c == '\r') out += "\\r";
        else out += c;
    }
    return out;
}

inline std::string unescape(const std::string& s) {
    std::string out;
    out.reserve(s.size());
    for (std::size_t k = 0; k < s.size(); ++k) {
        if (s[k] == '\\' && k + 1 < s.size() && (s[k + 1] == '\\' || s[k + 1] == 'n' || s[k + 1] == 'r')) {
            ++k;
            out += s[k] == 'n' ? '\n' : s[k] == 'r' ? '\r' : '\\';
        } else {
            out += s[k];
        }
    }
    return out;
}

inline std::string shape_text(const std::vector<std::int64_t>& shape) {
    std::string out;
    for (std::size_t k = 0; k < shape.size(); ++k) out += (k ? " " : "") + std::to_string(shape[k]);
    return out;
}

inline std::vector<std::int64_t> shape_of(const std::string& text) {
    std::vector<std::int64_t> out;
    std::istringstream in(text);
    std::int64_t v;
    while (in >> v) out.push_back(v);
    return out;
}

inline int pid() {
#if defined(_WIN32)
    return _getpid();
#else
    return static_cast<int>(getpid());
#endif
}

inline void pause(double microseconds) {
    std::this_thread::sleep_for(std::chrono::duration<double, std::micro>(microseconds));
}

inline double now() {
    return std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

/** A number as Python's repr writes it, nan and inf included: strtod reads them all, whatever the locale's decimal
    point (ROOT sets the C locale; a Geant4 application may not), hence the stream imbued with the classic one. */
inline double to_number(const std::string& text) {
    if (text == "nan") return std::numeric_limits<double>::quiet_NaN();
    if (text == "inf") return std::numeric_limits<double>::infinity();
    if (text == "-inf") return -std::numeric_limits<double>::infinity();
    std::istringstream in(text);
    in.imbue(std::locale::classic());
    double v = std::numeric_limits<double>::quiet_NaN();
    in >> v;
    return v;
}

}  // namespace detail

/** What the gateway's Python holds. */
struct Frameworks {
    Keys found;
    bool has(const std::string& name) const { return found.count(name) != 0; }
    std::string version(const std::string& name) const {
        auto it = found.find(name);
        return it == found.end() ? "" : it->second;
    }
    std::string python() const { return version("python"); }
    std::string describe() const {
        std::string out;
        for (const char* f : {"torch", "tensorflow", "keras", "jax", "pyhf"}) {
            if (has(f)) out += (out.empty() ? "" : ", ") + std::string(f) + " " + version(f);
        }
        return out.empty() ? "no framework found" : out;
    }
};

/**
 * What can be reached from here, without starting anything: frameworks.txt in the gateway's folder, written by Sphere
 * and by the gateway; failing that $SPHERE_ML_FRAMEWORKS ("torch=2.11.0;pyhf=0.7.6").
 */
inline Frameworks detect(const std::string& folder = "") {
    Frameworks f;
    f.found = detail::read_keys(detail::folder(folder) + "/frameworks.txt");
    if (f.found.empty()) {
        std::istringstream in(detail::env("SPHERE_ML_FRAMEWORKS"));
        std::string item;
        while (std::getline(in, item, ';')) {
            const std::size_t eq = item.find('=');
            if (eq != std::string::npos) f.found[item.substr(0, eq)] = item.substr(eq + 1);
        }
        if (!f.found.empty() && !detail::env("SPHERE_ML_PYTHON").empty()) f.found["python"] = detail::env("SPHERE_ML_PYTHON");
    }
    return f;
}

/** An answer of the gateway. */
class Reply {
public:
    Reply() = default;
    /** Not a file but a state of the connection: type() says it. */
    explicit Reply(const char* state) { meta_["Type"] = state; }
    explicit Reply(const std::string& path) {
        spx::File f(path);
        for (const auto& kv : f.meta()) meta_[kv.first] = detail::unescape(kv.second);
        for (const auto& s : f.sections()) {
            if (s.second.type != spx::F64) continue;
            std::int64_t n = 0;
            const double* p = f.f64(s.first, &n);
            columns_[s.first] = std::vector<double>(p, p + n);
        }
    }

    bool ok() const { return get("Ok") == "1"; }
    bool retry() const { return get("Retry") == "1"; }
    std::string error() const { return get("Error"); }
    std::string traceback() const { return get("Traceback"); }
    /** What the code printed. */
    std::string output() const { return get("Output"); }
    /** The graph the gateway made of this model, once it has one. */
    std::string native() const { return get("Native"); }
    std::string type() const { return get("Type"); }
    double seconds() const { return detail::to_number(get("Seconds")); }
    const Keys& meta() const { return meta_; }

    /** The value of a code that gave one number. */
    double number() const {
        if (type() != "number") throw LinkError("the result is not a number but " + describe());
        return detail::to_number(get("Value"));
    }
    /** One number of a nested result, by its path: number("backends.numpy.cls_obs"). */
    double number(const std::string& key) const {
        auto it = meta_.find("R." + key);
        if (it == meta_.end()) throw LinkError("the result has no number " + key);
        return detail::to_number(it->second);
    }
    bool has(const std::string& key) const { return meta_.count("R." + key) || meta_.count("S." + key) || !column(key).empty(); }

    std::string text() const { return get("Value"); }
    std::string text(const std::string& key) const { return get("S." + key); }

    /** An array by its path in the result; "result" (the default) for a code that gave one array. */
    Array array(const std::string& key = "result") const {
        const std::string name = column(key);
        if (name.empty()) throw LinkError("the result has no array " + key + " (it is " + describe() + ")");
        return Array(columns_.at(name), detail::shape_of(get("Shape." + name)));
    }
    /** The path of every array the result holds. */
    std::vector<std::string> arrays() const {
        std::vector<std::string> out;
        for (const auto& kv : meta_) {
            if (kv.first.compare(0, 6, "Array.") == 0) out.push_back(kv.second);
        }
        return out;
    }

private:
    std::string get(const std::string& key) const {
        auto it = meta_.find(key);
        return it == meta_.end() ? "" : it->second;
    }
    std::string column(const std::string& key) const {
        for (const auto& kv : meta_) {
            if (kv.first.compare(0, 6, "Array.") == 0 && kv.second == key) return kv.first.substr(6);
        }
        return "";
    }
    std::string describe() const {
        const std::string t = type();
        return t.empty() ? "empty" : t == "text" ? "the text '" + text() + "'" : "of type " + t;
    }

    Keys meta_;
    std::map<std::string, std::vector<double>> columns_;
};

/** What pyhf answered. upper_limit and expected_limits are 0 and empty unless a scan was asked (points > 0). */
struct Limit {
    double mu_hat = 0, cls_obs = 0, upper_limit = 0;
    std::vector<double> cls_exp, expected_limits;   // -2, -1, 0, +1, +2 sigma
};

namespace detail {
template <class H> const H& deref(const H& h) { return h; }
template <class H> const H& deref(const H* h) { return *h; }
template <class H> const H& deref(H* h) { return *h; }
}  // namespace detail

/** The bin contents of a ROOT histogram (anything with GetNbinsX and GetBinContent), under- and overflow left out. */
template <class H>
std::vector<double> contents(const H& histogram) {
    const auto& h = detail::deref(histogram);
    std::vector<double> out;
    for (int b = 1; b <= h.GetNbinsX(); ++b) out.push_back(h.GetBinContent(b));
    return out;
}

template <class H>
std::vector<double> errors(const H& histogram) {
    const auto& h = detail::deref(histogram);
    std::vector<double> out;
    for (int b = 1; b <= h.GetNbinsX(); ++b) out.push_back(h.GetBinError(b));
    return out;
}

/** A connection to the gateway. link() is the one most programs want. */
class Link {
public:
    explicit Link(const std::string& folder = "", double timeout = 120.0) : folder_(detail::folder(folder)) {
        nonce_ = std::to_string(static_cast<long long>(std::chrono::steady_clock::now().time_since_epoch().count() & 0xffffff));
        connect(timeout);
    }

    const std::string& folder() const { return folder_; }

    /** A request as the protocol has it (sphere_mlink.py); the others are this. timeout 0: as long as the gateway lives. */
    Reply request(const std::string& op, const Keys& fields = {}, const Inputs& inputs = {}, double timeout = 0,
                  bool retry = true) {
        if (retry) {                            // the gateway stops when idle: the next call starts it again
            setToken(detail::read_keys(folder_ + "/gateway.txt")["Token"]);
            if (token().empty()) connect(120.0);
        }
        static std::atomic<unsigned long long> counter{0};
        const std::string id = std::to_string(detail::pid()) + "-" + std::to_string(++counter) + "-" + nonce_;
        spx::Table table("mlink");
        table.meta("Op", op).meta("Protocol", "1").meta("Token", token());
        for (const auto& kv : fields) table.meta(kv.first, detail::escape(kv.second));
        for (const auto& in : inputs) {
            if (in.first.size() > 24) throw LinkError("an array's name holds 24 characters: " + in.first);
            table.meta("Shape." + in.first, detail::shape_text(in.second.shape));
            table.column(in.first, in.second.data);
        }
        for (int attempt = 0;; ++attempt) {     // a scanner may hold a new file for a moment (Windows)
            try {
                table.write(folder_ + "/in/" + id + ".spx");
                break;
            } catch (const std::exception&) {
                if (attempt >= 50) throw;
                detail::pause(10000);
            }
        }
        Reply reply = wait(id, op, timeout, retry);
        if (reply.type() == gone()) {
            connect(120.0);
            return request(op, fields, inputs, timeout, false);
        }
        if (reply.retry() && retry) {           // the gateway restarted since this connection read its token
            setToken(detail::read_keys(folder_ + "/gateway.txt")["Token"]);
            return request(op, fields, inputs, timeout, false);
        }
        return reply;
    }

    /** Runs Python in the gateway; the answer is the value of its last expression. */
    Reply run(const std::string& code, const Inputs& inputs = {}, const std::string& session = "default", double timeout = 0) {
        return checked(request("py", {{"Code", code}, {"Session", session}}, inputs, timeout));
    }
    void exec(const std::string& code, const Inputs& inputs = {}, const std::string& session = "default") { run(code, inputs, session); }
    double number(const std::string& code, const Inputs& inputs = {}, const std::string& session = "default") { return run(code, inputs, session).number(); }
    Array array(const std::string& code, const Inputs& inputs = {}, const std::string& session = "default") { return run(code, inputs, session).array(); }
    std::string text(const std::string& code, const Inputs& inputs = {}, const std::string& session = "default") { return run(code, inputs, session).text(); }

    /** A saved model on arrays: its only output, or the first of several (predict_all gives them all). */
    Array predict(const std::string& model, const Inputs& inputs, const std::string& kind = "") {
#ifdef SPHERE_MLINK_NATIVE
        if (auto graph = graphOf(model)) {
            try {
                return ran(*graph, inputs);
            } catch (const std::exception&) {      // a batch or a shape the graph was not made for: back to asking
                drop(model);
            }
        }
        const double started = detail::now();
#endif
        const Reply r = predict_all(model, inputs, kind);
        const std::vector<std::string> names = r.arrays();
        if (names.empty()) throw LinkError(model + " gave no array");
        Array out = r.array(names.front());
#ifdef SPHERE_MLINK_NATIVE
        if (!r.native().empty()) adopt(model, r.native(), inputs, out, detail::now() - started);
#endif
        return out;
    }

    /** Is this model running inside the program now? */
    bool native(const std::string& model) {
#ifdef SPHERE_MLINK_NATIVE
        return graphOf(model) != nullptr;
#else
        (void)model;
        return false;
#endif
    }
    Reply predict_all(const std::string& model, const Inputs& inputs, const std::string& kind = "") {
        std::string names;
        for (const auto& in : inputs) names += (names.empty() ? "" : ",") + in.first;
        return checked(request("predict", {{"Model", std::filesystem::absolute(model).string()}, {"Kind", kind}, {"Inputs", names}}, inputs));
    }

    /** One channel of counts by pyhf: CLs at mu; with points > 0 the scan in mu too, for the 95 % CL upper limit. */
    Limit pyhf(const std::vector<double>& signal, const std::vector<double>& background, const std::vector<double>& uncertainty,
               const std::vector<double>& observed, double mu = 1.0, int points = 0, double mu_max = 0, const std::string& backend = "numpy") {
        Keys fields{{"Mu", repr(mu)}, {"Points", std::to_string(points)}, {"Backends", backend}};
        if (mu_max > 0) fields["MuMax"] = repr(mu_max);
        return limit(checked(request("pyhf", fields, {{"signal", signal}, {"background", background},
                                                      {"bkg_uncertainty", uncertainty}, {"observed", observed}})), backend);
    }
    /** From ROOT histograms: signal, background (its bin errors the uncertainty), data. */
    template <class H>
    Limit pyhf(const H& signal, const H& background, const H& observed, double mu = 1.0, int points = 0, double mu_max = 0,
               const std::string& backend = "numpy") {
        return pyhf(contents(signal), contents(background), errors(background), contents(observed), mu, points, mu_max, backend);
    }
    /** A pyhf JSON workspace (as HEPData publishes likelihoods). */
    Limit workspace(const std::string& path, double mu = 1.0, int points = 0, const std::string& backend = "numpy") {
        return limit(checked(request("pyhf", {{"Workspace", std::filesystem::absolute(path).string()}, {"Mu", repr(mu)}, {"Points", std::to_string(points)},
                                              {"Backends", backend}})), backend);
    }

    /** One of the engine's commands, its arguments as JSON: call("train", R"({"data": "jets", "epochs": 5})"). */
    Reply call(const std::string& command, const std::string& json_arguments = "{}") {
        return checked(request("call", {{"Cmd", command}, {"Args", json_arguments}}));
    }

    /** The frameworks and the devices they see, asked of the gateway (detect() reads them without it). */
    Reply frameworks() { return checked(request("frameworks", {{"Devices", "1"}})); }
    void stop() { request("stop", {}, {}, 10.0); }

private:
#ifdef SPHERE_MLINK_NATIVE
    struct Native {
        std::filesystem::file_time_type stamp;
        std::shared_ptr<sphere::graph::Graph> graph;   // none: decided against, for this version of the model
        double looked = 0;
    };

    static Array ran(const sphere::graph::Graph& graph, const Inputs& inputs) {
        std::vector<sphere::graph::Tensor> in;
        for (const auto& i : inputs) in.push_back(sphere::graph::Tensor::wrap(i.second.data.data(), i.second.shape));
        // What a run asks of the machine costs more than a small network does: the memory free is read once a second, and
        // a few rows stay on the caller's thread (a Geant4 worker is one already). A large batch still spreads.
        static std::atomic<double> memory{0}, read{-1e9};
        const double now = detail::now();
        if (now - read.load(std::memory_order_relaxed) > 1.0) {
            memory.store(sphere::graph::budget(), std::memory_order_relaxed);
            read.store(now, std::memory_order_relaxed);
        }
        const bool few = !in.empty() && !in.front().shape.empty() && in.front().shape[0] <= 256;
        const auto out = graph.run(in, memory.load(std::memory_order_relaxed), few ? 1 : 0);
        if (out.empty()) throw LinkError("the graph gave no output");
        return Array(out.front().values(), out.front().shape);
    }

    std::shared_ptr<sphere::graph::Graph> graphOf(const std::string& model) {
        std::lock_guard<std::mutex> hold(mutex_);
        auto it = natives_.find(model);
        if (it == natives_.end()) return nullptr;
        const double now = detail::now();
        if (now - it->second.looked > 1.0) {       // a model saved again is not the graph of the old one
            std::error_code none;
            const auto stamp = std::filesystem::last_write_time(model, none);
            if (none || stamp != it->second.stamp) {
                natives_.erase(it);
                return nullptr;
            }
            it->second.looked = now;
        }
        return it->second.graph;
    }

    void drop(const std::string& model) {
        std::lock_guard<std::mutex> hold(mutex_);
        auto it = natives_.find(model);
        if (it != natives_.end()) it->second.graph.reset();
    }

    /** A file as this side names it: the gateway may be on the other side of WSL. */
    static std::string here(std::string path) {
#if defined(_WIN32)
        if (path.compare(0, 5, "/mnt/") == 0 && path.size() > 6) return std::string(1, static_cast<char>(std::toupper(path[5]))) + ":" + path.substr(6);
#else
        if (path.size() > 2 && path[1] == ':') {
            for (char& c : path) if (c == '\\') c = '/';
            return "/mnt/" + std::string(1, static_cast<char>(std::tolower(path[0]))) + path.substr(2);
        }
#endif
        return path;
    }

    /** Takes the graph the gateway made if it gives the same numbers here, faster than asking. Decided once per version. */
    void adopt(const std::string& model, const std::string& where, const Inputs& inputs, const Array& want, double asked) {
        std::error_code none;
        const auto stamp = std::filesystem::last_write_time(model, none);
        if (none) return;
        {
            std::lock_guard<std::mutex> hold(mutex_);
            auto it = natives_.find(model);
            if (it != natives_.end() && it->second.stamp == stamp) return;
            natives_[model] = Native{stamp, nullptr, detail::now()};
        }
        try {
            auto graph = std::make_shared<sphere::graph::Graph>(here(where));
            const Array got = ran(*graph, inputs);
            const double started = detail::now();
            ran(*graph, inputs);
            const double seconds = detail::now() - started;
            bool same = got.shape == want.shape && got.size() == want.size();
            for (std::size_t k = 0; same && k < got.size(); ++k) {
                same = std::fabs(got[k] - want[k]) <= 1e-5 * std::max(1.0, std::fabs(want[k]));
            }
            if (same && seconds < asked) {
                std::lock_guard<std::mutex> hold(mutex_);
                natives_[model].graph = graph;
            }
        } catch (const std::exception&) {
            // stays with the gateway
        }
    }

    std::map<std::string, Native> natives_;
#endif

    static std::string repr(double v) {
        std::ostringstream out;
        out.imbue(std::locale::classic());
        out.precision(17);
        out << v;
        return out.str();
    }

    static Reply checked(Reply reply) {
        if (!reply.ok()) throw LinkError(reply.error() + (reply.traceback().empty() ? "" : "\n" + reply.traceback()));
        return reply;
    }

    static Limit limit(const Reply& r, const std::string& backend) {
        const std::string at = "backends." + backend + ".";
        if (!r.text(at + "error").empty()) throw LinkError("pyhf: " + r.text(at + "error"));
        Limit out;
        out.mu_hat = r.number(at + "mu_hat");
        out.cls_obs = r.number(at + "cls_obs");
        out.cls_exp = r.array(at + "cls_exp").data;
        if (r.has(at + "upper_limit")) {
            out.upper_limit = r.number(at + "upper_limit");
            out.expected_limits = r.array(at + "expected_limits").data;
        }
        return out;
    }

    std::string token() {
        std::lock_guard<std::mutex> hold(mutex_);
        return token_;
    }
    void setToken(const std::string& t) {
        std::lock_guard<std::mutex> hold(mutex_);
        token_ = t;
    }

    void connect(double timeout) {
        const double deadline = detail::now() + timeout;
        bool started = false;
        for (;;) {
            setToken(detail::read_keys(folder_ + "/gateway.txt")["Token"]);
            if (!token().empty()) {
                try {
                    if (request("ping", {}, {}, 2.0, false).ok()) return;
                } catch (const std::exception&) {
                    // a token left by a gateway that is gone, or a gateway busy with a long computation:
                    // that one still beats, and will come to this program in its turn
                    const std::string before = detail::read_all(folder_ + "/heartbeat.txt");
                    detail::pause(1500000);
                    if (!before.empty() && before != detail::read_all(folder_ + "/heartbeat.txt")) return;
                }
            }
            if (!started) {
                start();
                started = true;
            }
            if (detail::now() > deadline) {
                throw LinkError("no gateway answers in " + folder_ + ". In Sphere: ':ml link start'; elsewhere: python "
                                "sphere_mlink.py --serve <folder>. Its log is gateway.log there.");
            }
            detail::pause(50000);
        }
    }

    void start() const {
#if defined(_WIN32)
        std::string script = folder_ + "\\start.bat";
        for (char& c : script) if (c == '/') c = '\\';
        if (detail::exists(script)) std::system(("\"\"" + script + "\"\"").c_str());
#else
        const std::string script = folder_ + "/start.sh";
        if (detail::exists(script)) std::system(("sh '" + script + "'").c_str());
#endif
    }

    static const char* gone() { return "(gateway gone)"; }

    Reply wait(const std::string& id, const std::string& op, double timeout, bool retry) {
        const std::string path = folder_ + "/out/" + id + ".spx";
        const double started = detail::now();
        double pause = 100, seen = started;
        bool kicked = false;
        std::string beat = detail::read_all(folder_ + "/heartbeat.txt");
        while (!detail::exists(path)) {
            detail::pause(pause);
            pause = std::min(pause * 1.5, 20000.0);
            const double now = detail::now();
            if (timeout > 0 && now - started > timeout) {
                std::ofstream(folder_ + "/in/" + id + ".cancel");
                throw LinkError("no answer to '" + op + "' after " + std::to_string(timeout) + " s");
            }
            if (now - seen > 5.0) {
                const std::string current = detail::read_all(folder_ + "/heartbeat.txt");
                // gone, or another run: nobody will answer what was left for this one
                if (retry && detail::read_keys(folder_ + "/gateway.txt")["Token"] != token()) return Reply(gone());
                if (current != beat) {
                    beat = current;
                    seen = now;
                    kicked = false;
                } else {
                    if (retry && !kicked && now - seen > 15.0) {   // dead, it is replaced; alive, its lock turns the new one away
                        start();
                        kicked = true;
                    }
                    if (now - seen > (retry ? 90.0 : 30.0)) {    // already sent twice: this request is what stops it
                        throw LinkError("the gateway has stopped answering (see gateway.log in " + folder_ + ")");
                    }
                }
            }
        }
        Reply reply;
        for (int attempt = 0;; ++attempt) {   // a scanner may hold a new file for a moment (Windows)
            try {
                reply = Reply(path);
                break;
            } catch (const std::exception&) {
                if (attempt >= 50) throw;
                detail::pause(2000);
            }
        }
        std::remove(path.c_str());
        return reply;
    }

    std::string folder_, nonce_, token_;
    std::mutex mutex_;
};

/** The connection of this program, made at its first use. */
inline Link& link() {
    static Link shared;
    return shared;
}

/** What ':ml link check' asks of every language: the same requests, the same numbers, written to out. */
inline void crosscheck(const std::string& out, const std::string& engine = "C++") {
    Link& l = link();
    const Array x({1, 2, 3, 4, 5, 6.5}, {2, 3});
    std::vector<double> values;
    values.push_back(l.number("torch.linalg.norm(torch.as_tensor(x)).item()", {{"x", x}}));
    values.push_back(l.number("float(tf.reduce_sum(tf.constant(x) ** 2))", {{"x", x}}));
    const Limit p = l.pyhf(std::vector<double>{5, 10}, std::vector<double>{50, 60}, std::vector<double>{7, 8}, std::vector<double>{53, 65});
    values.push_back(p.cls_obs);
    values.push_back(p.mu_hat);
    for (double s : l.array("np.sin(x).ravel()", {{"x", x}}).data) values.push_back(s);
    const double started = detail::now();
    for (int k = 0; k < 20; ++k) l.number("1+1");
    const std::vector<double> ms{(detail::now() - started) / 20 * 1000};
    spx::Table table("mlink check");
    table.meta("Engine", engine).column("values", values).column("ms", ms);
    table.write(out);
}

}  // namespace mlink
}  // namespace sphere

#endif  // SPHERE_MLINK_HPP
