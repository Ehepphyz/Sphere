// sphere_graph.hpp
//
// A model of PyTorch -- or of Keras, on its torch backend -- run from C++ with nothing but this header and
// sphere_spx.hpp: no LibTorch, no TensorFlow, no ONNX Runtime. A ROOT macro, a Geant4 program (a tagger in an
// analysis, a fast simulation), any C++17 program. The model is the portable graph sphere_graph.export wrote
// (models/<name>.sng.spx): core ATen operations one a line, evaluated in double precision with the formulas of
// numpy's reference, which ':torch check' holds this header to; the weights are read in place from the mapped
// file, not copied.
//
//     #include "sphere_graph.hpp"
//     sphere::graph::Graph g("tagger");                            // $SPHERE_BRIDGE/models/tagger.sng.spx
//     auto out = g.run({Tensor::wrap(x, {n, 32, 7}), Tensor::wrap(mask, {n, 32})});
//     g.run_file("jets", "jets_tagged");                           // a dataset of any size, streamed
//
// Its memory is planned rather than hoped for. The graph is run once on batches of 1 and 2 rows, the bytes
// its values hold after every node counted, so that node t holds a_t + b_t B bytes for a batch B (checked at
// 4). A run is then held to what the machine spares now (budget(): its available memory beyond a reserve, and
// its commit): a batch too large runs in chunks -- the rows being independent, which the export checked --
// on as many threads as the memory affords, each value freed after its last use; the chunks change nothing,
// to the bit. Where even one row would not fit, a value beyond the budget goes to a temporary file mapped in
// memory, which the system pages to disk rather than refusing it. run_file reads a dataset's rows in place
// from its mapped file and writes the outputs into the mapped file of the result: neither is ever whole in
// memory.

#ifndef SPHERE_GRAPH_HPP
#define SPHERE_GRAPH_HPP

#include "sphere_spx.hpp"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <exception>
#include <filesystem>
#include <fstream>
#include <limits>
#include <map>
#include <memory>
#include <mutex>
#include <numeric>
#include <set>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <unordered_map>
#include <utility>
#include <vector>

#if !defined(_WIN32)
#include <fcntl.h>
#include <sys/mman.h>
#include <unistd.h>
#endif

// A product and a sum stay two roundings, as in Java and numpy's elementwise operations: no fused multiply-add.
#if defined(__clang__)
#pragma float_control(push)
#pragma clang fp contract(off)
#elif defined(__GNUC__)
#pragma GCC push_options
#pragma GCC optimize("fp-contract=off")
#elif defined(_MSC_VER)
#pragma float_control(push)
#pragma fp_contract(off)
#endif

namespace sphere {
namespace graph {

using I = std::int64_t;
using Shape = std::vector<I>;

class GraphError : public std::runtime_error {
public:
    using std::runtime_error::runtime_error;
};

inline I numel(const Shape& s) {
    I n = 1;
    for (I v : s) n *= v;
    return n;
}

inline std::string str(const Shape& s) {
    std::string o = "[";
    for (size_t i = 0; i < s.size(); ++i) o += (i ? "," : "") + std::to_string(s[i]);
    return o + "]";
}

// ---------------------------------------------------------------------------
// Memory: the machine's, and a run's
// ---------------------------------------------------------------------------

struct Machine {
    double total = 0, avail = 0, commit = 0;   // physical memory, what of it is available, what the commit can still back
    bool known = false;
};

/** GlobalMemoryStatusEx on Windows, /proc/meminfo elsewhere (commit there: available memory and free swap). */
inline Machine machine() {
    Machine m;
#if defined(_WIN32)
    MEMORYSTATUSEX s;
    s.dwLength = sizeof(s);
    if (GlobalMemoryStatusEx(&s)) {
        m.total = static_cast<double>(s.ullTotalPhys);
        m.avail = static_cast<double>(s.ullAvailPhys);
        m.commit = static_cast<double>(s.ullAvailPageFile);
        m.known = true;
    }
#else
    std::ifstream in("/proc/meminfo");
    std::string key, rest;
    double v = 0, total = -1, avail = -1, swap = 0;
    while (in >> key >> v) {
        std::getline(in, rest);
        if (key == "MemTotal:") total = v * 1024;
        else if (key == "MemAvailable:") avail = v * 1024;
        else if (key == "SwapFree:") swap = v * 1024;
    }
    if (total > 0 && avail >= 0) {
        m.total = total;
        m.avail = avail;
        m.commit = avail + swap;
        m.known = true;
    }
#endif
    return m;
}

/**
 * What a run may take now: half of what the machine has available beyond its reserve (12 % of the RAM, 400 MB
 * at least), held to what its commit has left beyond 1 GB -- as sphere_ml's governor reckons it; 256 MB where
 * the system does not say.
 */
inline double budget() {
    const double mb = 1048576.0;
    const Machine m = machine();
    if (!m.known) return 256 * mb;
    const double reserve = std::max(400 * mb, 0.12 * m.total);
    return 0.5 * std::max(32 * mb, std::min(m.avail - reserve, m.commit - 1024 * mb));
}

/** One run's accounting: the bytes its values hold, the most they held, what they may hold, what went to files. */
struct Arena {
    std::atomic<I> live{0};
    I peak = 0;
    I mark = 0;                // the most held since the node running began: its temporaries counted

    double limit = std::numeric_limits<double>::infinity();
    I spill_at = I(1) << 20;   // values smaller than this stay in memory, whatever the budget
    I spilled = 0;
    std::string folder;        // where temporary files go: $SPHERE_GRAPH_TMP, else the system's
};

inline std::shared_ptr<Arena>& arena_here() {
    static thread_local std::shared_ptr<Arena> a;
    return a;
}

/** Sets the thread's arena for a scope. */
struct ArenaScope {
    std::shared_ptr<Arena> saved;
    explicit ArenaScope(std::shared_ptr<Arena> a) : saved(arena_here()) { arena_here() = std::move(a); }
    ~ArenaScope() { arena_here() = saved; }
    ArenaScope(const ArenaScope&) = delete;
    ArenaScope& operator=(const ArenaScope&) = delete;
};

/** What holds a tensor's values: memory, or a temporary file mapped in memory (deleted when unmapped). */
struct Buffer {
    double* data = nullptr;
    I n = 0;
    std::unique_ptr<double[]> heap;
    std::shared_ptr<Arena> arena;
    bool mapped = false;
#if defined(_WIN32)
    HANDLE file = INVALID_HANDLE_VALUE, view = nullptr;
#else
    size_t bytes = 0;
#endif
    Buffer() = default;
    Buffer(const Buffer&) = delete;
    Buffer& operator=(const Buffer&) = delete;
    ~Buffer() {
        if (arena) arena->live -= n * 8;
        if (!mapped) return;
#if defined(_WIN32)
        UnmapViewOfFile(data);
        CloseHandle(view);
        CloseHandle(file);
#else
        munmap(data, bytes);
#endif
    }
};

inline std::string environment(const char* name) {
#if defined(_MSC_VER)
    char* v = nullptr;
    size_t n = 0;
    if (_dupenv_s(&v, &n, name) != 0 || !v) return "";
    const std::string s(v);
    std::free(v);
    return s;
#else
    const char* v = std::getenv(name);
    return v ? v : "";
#endif
}

inline std::string temporary_folder(const std::string& asked) {
    if (!asked.empty()) return asked;
    const std::string at = environment("SPHERE_GRAPH_TMP");
    if (!at.empty()) return at;
    std::error_code ec;
    const auto t = std::filesystem::temp_directory_path(ec);
    return ec ? std::string(".") : t.string();
}

/** bytes in a temporary file of folder, mapped: zeroed, paged by the system, gone when unmapped. */
inline bool map_temporary(Buffer& b, I bytes, const std::string& folder) {
    std::error_code ec;
    const auto room = std::filesystem::space(folder, ec);
    if (ec || static_cast<double>(room.available) < static_cast<double>(bytes) + 512.0 * 1048576) return false;
#if defined(_WIN32)
    char name[MAX_PATH];
    if (!GetTempFileNameA(folder.c_str(), "sng", 0, name)) return false;
    HANDLE f = CreateFileA(name, GENERIC_READ | GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS,
                           FILE_ATTRIBUTE_TEMPORARY | FILE_FLAG_DELETE_ON_CLOSE, nullptr);
    if (f == INVALID_HANDLE_VALUE) {
        DeleteFileA(name);
        return false;
    }
    const auto size = static_cast<std::uint64_t>(bytes);
    HANDLE v = CreateFileMappingA(f, nullptr, PAGE_READWRITE, static_cast<DWORD>(size >> 32),
                                  static_cast<DWORD>(size & 0xffffffffu), nullptr);
    if (!v) {
        CloseHandle(f);
        return false;
    }
    void* p = MapViewOfFile(v, FILE_MAP_ALL_ACCESS, 0, 0, 0);
    if (!p) {
        CloseHandle(v);
        CloseHandle(f);
        return false;
    }
    b.file = f;
    b.view = v;
#else
    std::string path = folder + "/sphere-graph-XXXXXX";
    std::vector<char> c(path.begin(), path.end());
    c.push_back('\0');
    const int fd = mkstemp(c.data());
    if (fd < 0) return false;
    unlink(c.data());
    if (ftruncate(fd, static_cast<off_t>(bytes)) != 0) {
        close(fd);
        return false;
    }
    void* p = mmap(nullptr, static_cast<size_t>(bytes), PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    close(fd);
    if (p == MAP_FAILED) return false;
    b.bytes = static_cast<size_t>(bytes);
#endif
    b.data = static_cast<double*>(p);
    b.mapped = true;
    return true;
}

/** n doubles, zeroed: in memory while the run's arena has room, else in a temporary file. */
inline std::shared_ptr<Buffer> allocate(I n) {
    auto b = std::make_shared<Buffer>();
    b->n = n;
    const std::shared_ptr<Arena>& a = arena_here();
    const I bytes = n * 8;
    if (a && bytes >= a->spill_at && static_cast<double>(a->live + bytes) > a->limit) {
        if (map_temporary(*b, bytes, temporary_folder(a->folder))) {
            a->spilled += bytes;
            return b;
        }
    }
    b->heap.reset(new double[static_cast<size_t>(std::max<I>(n, 1))]());
    b->data = b->heap.get();
    if (a) {
        b->arena = a;
        const I now = (a->live += bytes);
        if (now > a->peak) a->peak = now;
        if (now > a->mark) a->mark = now;
    }
    return b;
}

// ---------------------------------------------------------------------------
// Tensors
// ---------------------------------------------------------------------------

/** Row-major doubles and a shape; integers and booleans are doubles too, as indices are. */
struct Tensor {
    std::shared_ptr<Buffer> buf;   // none for values read in place (weights, a mapped dataset, the caller's)
    const double* p = nullptr;
    Shape shape;

    I numel() const { return graph::numel(shape); }
    int rank() const { return static_cast<int>(shape.size()); }
    double operator[](I k) const { return p[k]; }
    std::vector<double> values() const { return std::vector<double>(p, p + numel()); }

    /** The caller's values, copied. */
    static Tensor from(const std::vector<double>& v, Shape s) {
        if (graph::numel(s) != static_cast<I>(v.size()))
            throw GraphError("a shape " + str(s) + " for " + std::to_string(v.size()) + " values");
        Tensor t;
        t.buf = allocate(static_cast<I>(v.size()));
        std::copy(v.begin(), v.end(), t.buf->data);
        t.p = t.buf->data;
        t.shape = std::move(s);
        return t;
    }

    /** The caller's values, not copied: they must outlive the run. */
    static Tensor wrap(const double* v, Shape s) {
        Tensor t;
        t.p = v;
        t.shape = std::move(s);
        return t;
    }
};

inline Tensor fresh(Shape s) {
    Tensor t;
    t.buf = allocate(numel(s));
    t.p = t.buf->data;
    t.shape = std::move(s);
    return t;
}

inline double* out(const Tensor& t) { return const_cast<double*>(t.p); }   // a fresh tensor's values, to fill

inline Tensor scalar(double v) {
    Tensor t = fresh({});
    out(t)[0] = v;
    return t;
}

inline Tensor reshaped(const Tensor& x, Shape s) {   // the same values, another shape: no copy
    Tensor t = x;
    t.shape = std::move(s);
    return t;
}

// ---------------------------------------------------------------------------
// Values: what a graph's nodes take and give
// ---------------------------------------------------------------------------

struct Value {
    enum Kind { NONE, INT, FLOAT, BOOL, STR, TENSOR, LIST, REF };
    Kind kind = NONE;
    I i = 0;
    double f = 0;
    bool b = false;
    std::string s;
    Tensor t;
    std::vector<Value> list;
    int slot = -1;

    static Value of(Tensor x) {
        Value v;
        v.kind = TENSOR;
        v.t = std::move(x);
        return v;
    }
    static Value integer(I x) {
        Value v;
        v.kind = INT;
        v.i = x;
        return v;
    }
    static Value real(double x) {
        Value v;
        v.kind = FLOAT;
        v.f = x;
        return v;
    }
    static Value tuple(std::vector<Value> xs) {
        Value v;
        v.kind = LIST;
        v.list = std::move(xs);
        return v;
    }
    bool none() const { return kind == NONE; }
};

using Args = std::vector<Value>;

inline bool has(const Args& a, size_t k) { return a.size() > k && !a[k].none(); }

inline Tensor T(const Value& v) {
    switch (v.kind) {
        case Value::TENSOR: return v.t;
        case Value::INT: return scalar(static_cast<double>(v.i));
        case Value::FLOAT: return scalar(v.f);
        case Value::BOOL: return scalar(v.b ? 1 : 0);
        default: throw GraphError("a tensor expected");
    }
}

inline double N(const Value& v) {
    switch (v.kind) {
        case Value::INT: return static_cast<double>(v.i);
        case Value::FLOAT: return v.f;
        case Value::BOOL: return v.b ? 1 : 0;
        case Value::TENSOR:
            if (v.t.numel() == 1) return v.t.p[0];
            [[fallthrough]];
        default: throw GraphError("a number expected");
    }
}

inline I L(const Value& v) { return v.kind == Value::INT ? v.i : static_cast<I>(N(v)); }

inline bool B(const Value& v) { return v.kind == Value::BOOL ? v.b : (v.kind == Value::INT ? v.i != 0 : false); }

inline Shape ints(const Value& v) {
    Shape s;
    if (v.kind == Value::LIST) {
        for (const Value& e : v.list) s.push_back(L(e));
    } else {
        s.push_back(L(v));   // an int[2] argument given one int, as padding=0
    }
    return s;
}

inline int dim(I d, int rank) {
    const I r = std::max(rank, 1);
    return static_cast<int>(((d % r) + r) % r);
}

inline std::vector<int> dims(const Value& v, int rank) {   // none or [] for all
    std::vector<int> d;
    if (v.none()) return d;
    if (v.kind == Value::LIST) {
        for (const Value& e : v.list) d.push_back(dim(L(e), rank));
    } else {
        d.push_back(dim(L(v), rank));
    }
    return d;
}

// ---------------------------------------------------------------------------
// Shapes, broadcasting
// ---------------------------------------------------------------------------

inline std::vector<I> strides(const Shape& s) {
    std::vector<I> st(s.size());
    I acc = 1;
    for (int i = static_cast<int>(s.size()) - 1; i >= 0; --i) {
        st[i] = acc;
        acc *= s[i];
    }
    return st;
}

inline Shape broadcast(const Shape& a, const Shape& b) {
    const size_t n = std::max(a.size(), b.size());
    Shape o(n);
    for (size_t i = 0; i < n; ++i) {
        const I da = i + a.size() >= n ? a[i + a.size() - n] : 1;
        const I db = i + b.size() >= n ? b[i + b.size() - n] : 1;
        if (da != db && da != 1 && db != 1) throw GraphError("shapes " + str(a) + " and " + str(b) + " do not broadcast");
        o[i] = (da == 0 || db == 0) ? 0 : std::max(da, db);
    }
    return o;
}

/** The strides of x read as shape out: 0 where x has 1 or nothing. */
inline std::vector<I> bstrides(const Shape& xs, const Shape& o) {
    const std::vector<I> st = strides(xs);
    std::vector<I> r(o.size(), 0);
    const long lead = static_cast<long>(o.size()) - static_cast<long>(xs.size());
    for (size_t i = 0; i < o.size(); ++i) {
        const long j = static_cast<long>(i) - lead;
        r[i] = j >= 0 && xs[j] != 1 ? st[j] : 0;
    }
    return r;
}

template <class F>
Tensor binary(const Tensor& a, const Tensor& b, F f) {
    if (a.shape == b.shape) {
        Tensor o = fresh(a.shape);
        double* w = out(o);
        const I n = o.numel();
        for (I k = 0; k < n; ++k) w[k] = f(a.p[k], b.p[k]);
        return o;
    }
    const Shape s = broadcast(a.shape, b.shape);
    const std::vector<I> sa = bstrides(a.shape, s), sb = bstrides(b.shape, s);
    Tensor o = fresh(s);
    double* w = out(o);
    const I n = o.numel();
    const int r = static_cast<int>(s.size());
    std::vector<I> idx(r, 0);
    I ia = 0, ib = 0;
    for (I k = 0; k < n; ++k) {
        w[k] = f(a.p[ia], b.p[ib]);
        for (int d = r - 1; d >= 0; --d) {
            ++idx[d];
            ia += sa[d];
            ib += sb[d];
            if (idx[d] < s[d]) break;
            ia -= sa[d] * s[d];
            ib -= sb[d] * s[d];
            idx[d] = 0;
        }
    }
    return o;
}

template <class F>
Tensor unary(const Tensor& x, F f) {
    Tensor o = fresh(x.shape);
    double* w = out(o);
    const I n = x.numel();
    for (I k = 0; k < n; ++k) w[k] = f(x.p[k]);
    return o;
}

inline Tensor broadcast_to(const Tensor& x, const Shape& s) {
    if (x.shape == s) return x;
    const std::vector<I> st = bstrides(x.shape, s);
    Tensor o = fresh(s);
    double* w = out(o);
    const I n = o.numel();
    const int r = static_cast<int>(s.size());
    std::vector<I> idx(r, 0);
    I ix = 0;
    for (I k = 0; k < n; ++k) {
        w[k] = x.p[ix];
        for (int d = r - 1; d >= 0; --d) {
            ++idx[d];
            ix += st[d];
            if (idx[d] < s[d]) break;
            ix -= st[d] * s[d];
            idx[d] = 0;
        }
    }
    return o;
}

/** x seen as (outer, n, inner) around one dimension. */
struct Around {
    I outer = 1, n = 1, inner = 1;
    Around(const Shape& s, int d) {
        for (int i = 0; i < d; ++i) outer *= s[i];
        for (size_t i = d + 1; i < s.size(); ++i) inner *= s[i];
        n = s.empty() ? 1 : s[d];
    }
};

enum Reduction { SUM, MEAN, MAX, MIN, PROD, ANY };

/** Reduces dims (empty: all), summing in order. */
inline Tensor reduce(const Tensor& x, const std::vector<int>& ds, bool keep, Reduction kind) {
    const int r = x.rank();
    std::vector<bool> red(r, ds.empty());
    for (int d : ds) red[d] = true;
    Shape ks = x.shape;
    I count = 1;
    for (int d = 0; d < r; ++d)
        if (red[d]) {
            count *= ks[d];
            ks[d] = 1;
        }
    const std::vector<I> ost = strides(ks);
    Tensor o = fresh(ks);
    double* w = out(o);
    const I m = o.numel();
    const double start = kind == MAX ? -INFINITY : kind == MIN ? INFINITY : kind == PROD ? 1 : 0;
    for (I k = 0; k < m; ++k) w[k] = start;
    std::vector<I> idx(r, 0);
    const I n = x.numel();
    for (I k = 0; k < n; ++k) {
        I oi = 0;
        for (int d = 0; d < r; ++d)
            if (!red[d]) oi += idx[d] * ost[d];
        const double v = x.p[k];
        switch (kind) {
            case SUM:
            case MEAN: w[oi] += v; break;
            case MAX: w[oi] = std::isnan(v) || std::isnan(w[oi]) ? NAN : std::max(w[oi], v); break;
            case MIN: w[oi] = std::isnan(v) || std::isnan(w[oi]) ? NAN : std::min(w[oi], v); break;
            case PROD: w[oi] *= v; break;
            default: w[oi] = (w[oi] != 0 || v != 0) ? 1 : 0;
        }
        for (int d = r - 1; d >= 0; --d) {
            if (++idx[d] < x.shape[d]) break;
            idx[d] = 0;
        }
    }
    if (kind == MEAN)
        for (I k = 0; k < m; ++k) w[k] /= static_cast<double>(count);
    if (keep) return o;
    Shape s;
    for (int d = 0; d < r; ++d)
        if (!red[d]) s.push_back(x.shape[d]);
    return reshaped(o, s);
}

inline Tensor softmax(const Tensor& x, int d, bool log) {
    const Around a(x.shape, d);
    Tensor o = fresh(x.shape);
    double* w = out(o);
    for (I p = 0; p < a.outer; ++p)
        for (I q = 0; q < a.inner; ++q) {
            const I base = p * a.n * a.inner + q;
            double m = -INFINITY;
            for (I i = 0; i < a.n; ++i) m = std::max(m, x.p[base + i * a.inner]);
            double s = 0;
            for (I i = 0; i < a.n; ++i) {
                const double e = std::exp(x.p[base + i * a.inner] - m);
                w[base + i * a.inner] = e;
                s += e;
            }
            for (I i = 0; i < a.n; ++i) {
                const I at = base + i * a.inner;
                w[at] = log ? (x.p[at] - m) - std::log(s) : w[at] / s;
            }
        }
    return o;
}

inline Tensor permute(const Tensor& x, const std::vector<int>& perm) {
    const int r = x.rank();
    Shape s(r);
    const std::vector<I> st = strides(x.shape);
    std::vector<I> pst(r);
    for (int i = 0; i < r; ++i) {
        s[i] = x.shape[perm[i]];
        pst[i] = st[perm[i]];
    }
    Tensor o = fresh(s);
    double* w = out(o);
    const I n = x.numel();
    std::vector<I> idx(r, 0);
    I ix = 0;
    for (I k = 0; k < n; ++k) {
        w[k] = x.p[ix];
        for (int d = r - 1; d >= 0; --d) {
            ++idx[d];
            ix += pst[d];
            if (idx[d] < s[d]) break;
            ix -= pst[d] * s[d];
            idx[d] = 0;
        }
    }
    return o;
}

inline Tensor view(const Tensor& x, Shape s) {
    I known = 1;
    int free = -1;
    for (size_t i = 0; i < s.size(); ++i) {
        if (s[i] == -1) free = static_cast<int>(i);
        else known *= s[i];
    }
    if (free >= 0) s[free] = known == 0 ? 0 : x.numel() / known;
    if (numel(s) != x.numel()) throw GraphError("a view " + str(s) + " of " + str(x.shape));
    return reshaped(x, s);
}

inline Tensor expand(const Tensor& x, const Shape& sizes) {
    const long lead = static_cast<long>(sizes.size()) - x.rank();
    Shape target(sizes.size());
    for (size_t i = 0; i < sizes.size(); ++i)
        target[i] = sizes[i] == -1 && static_cast<long>(i) >= lead ? x.shape[i - lead] : sizes[i];
    return broadcast_to(x, target);
}

/** Elements [lo, hi) step `step` along a dimension; along the first, contiguous, no copy. */
inline Tensor narrow(const Tensor& x, int d, I lo, I hi, I step) {
    const Around a(x.shape, d);
    const I m = hi <= lo ? 0 : (hi - lo + step - 1) / step;
    Shape s = x.shape;
    s[d] = m;
    if (step == 1 && a.outer == 1) {
        Tensor t = x;
        t.p = x.p + lo * a.inner;
        t.shape = s;
        return t;
    }
    Tensor o = fresh(s);
    double* w = out(o);
    for (I p = 0; p < a.outer; ++p)
        for (I i = 0; i < m; ++i)
            std::copy(x.p + (p * a.n + lo + i * step) * a.inner, x.p + (p * a.n + lo + i * step + 1) * a.inner,
                      w + (p * m + i) * a.inner);
    return o;
}

inline Tensor rows(const Tensor& x, I lo, I hi) { return x.rank() == 0 ? x : narrow(x, 0, lo, hi, 1); }

inline Tensor select(const Tensor& x, int d, I i) {
    const I n = x.shape[d];
    const I at = i < 0 ? i + n : i;
    Tensor t = narrow(x, d, at, at + 1, 1);
    Shape s;
    for (int k = 0; k < x.rank(); ++k)
        if (k != d) s.push_back(x.shape[k]);
    return reshaped(t, s);
}

inline Tensor cat(const std::vector<Tensor>& ts, I dv) {
    std::vector<Tensor> parts;
    for (const Tensor& t : ts)
        if (!(t.rank() == 1 && t.numel() == 0)) parts.push_back(t);
    if (parts.empty()) return ts.at(0);
    const int r = parts[0].rank(), d = dim(dv, r);
    Shape s = parts[0].shape;
    I total = 0;
    for (const Tensor& t : parts) total += t.shape[d];
    s[d] = total;
    const Around a(s, d);
    Tensor o = fresh(s);
    double* w = out(o);
    I at = 0;
    for (const Tensor& t : parts) {
        const I m = t.shape[d];
        for (I p = 0; p < a.outer; ++p)
            std::copy(t.p + p * m * a.inner, t.p + (p + 1) * m * a.inner, w + (p * total + at) * a.inner);
        at += m;
    }
    return o;
}

inline Tensor squeeze(const Tensor& x, const std::vector<int>* ds) {
    Shape s;
    for (int d = 0; d < x.rank(); ++d) {
        bool drop = x.shape[d] == 1;
        if (ds) drop = drop && std::find(ds->begin(), ds->end(), d) != ds->end();
        if (!drop) s.push_back(x.shape[d]);
    }
    return reshaped(x, s);
}

inline Tensor unsqueeze(const Tensor& x, I dv) {
    const int d = dim(dv, x.rank() + 1);
    Shape s(x.rank() + 1);
    for (int i = 0, j = 0; i < static_cast<int>(s.size()); ++i) s[i] = i == d ? 1 : x.shape[j++];
    return reshaped(x, s);
}

// ---- products

/** a @ b with numpy's matmul rules: batch dimensions broadcast, read in place; a vector's dimension added and removed. */
inline Tensor matmul(const Tensor& a0, const Tensor& b0) {
    const Tensor a = a0.rank() == 1 ? reshaped(a0, {1, a0.shape[0]}) : a0;
    const Tensor b = b0.rank() == 1 ? reshaped(b0, {b0.shape[0], 1}) : b0;
    const I n = a.shape[a.rank() - 2], k = a.shape[a.rank() - 1], m = b.shape[b.rank() - 1];
    if (b.shape[b.rank() - 2] != k) throw GraphError("matmul of " + str(a.shape) + " and " + str(b.shape));
    const Shape ba(a.shape.begin(), a.shape.end() - 2), bb(b.shape.begin(), b.shape.end() - 2);
    const Shape batch = broadcast(ba, bb);
    const std::vector<I> bst = strides(batch), sa = bstrides(ba, batch), sb = bstrides(bb, batch);
    const I nb = numel(batch);
    Shape s = batch;
    s.push_back(n);
    s.push_back(m);
    Tensor o = fresh(s);
    double* w = out(o);
    for (I z = 0; z < nb; ++z) {
        I za = 0, zb = 0;
        for (size_t d = 0; d < batch.size(); ++d) {
            const I i = (z / bst[d]) % batch[d];
            za += i * sa[d];
            zb += i * sb[d];
        }
        const double* ap = a.p + za * n * k;
        const double* bp = b.p + zb * k * m;
        double* op = w + z * n * m;
        for (I i = 0; i < n; ++i)
            for (I p = 0; p < k; ++p) {
                const double av = ap[i * k + p];
                const double* brow = bp + p * m;
                double* orow = op + i * m;
                for (I j = 0; j < m; ++j) orow[j] += av * brow[j];
            }
    }
    if (a0.rank() == 1) s.erase(s.end() - 2);
    if (b0.rank() == 1) s.erase(s.end() - 1);
    return reshaped(o, s);
}

/** beta c + alpha (a @ b), the product's fresh values combined in place: no second tensor of its size. */
inline Tensor addmm(const Tensor& c, const Tensor& a, const Tensor& b, double be, double al) {
    Tensor o = matmul(a, b);
    if (broadcast(c.shape, o.shape) != o.shape) return binary(c, o, [be, al](double x, double p) { return be * x + al * p; });
    const std::vector<I> sc = bstrides(c.shape, o.shape);
    double* w = out(o);
    const I n = o.numel();
    const int r = o.rank();
    std::vector<I> idx(r, 0);
    I ic = 0;
    for (I k = 0; k < n; ++k) {
        w[k] = be * c.p[ic] + al * w[k];
        for (int d = r - 1; d >= 0; --d) {
            ++idx[d];
            ic += sc[d];
            if (idx[d] < o.shape[d]) break;
            ic -= sc[d] * o.shape[d];
            idx[d] = 0;
        }
    }
    return o;
}

// ---- images

inline Shape rep(const Shape& v, size_t nd) {
    if (v.size() == nd) return v;
    return Shape(nd, v.empty() ? 0 : v[0]);
}

inline Tensor conv(const Tensor& x, const Tensor& w, const Value& bias, const Shape& stride, const Shape& pad,
                   const Shape& dil, I groups) {
    const size_t nd = w.shape.size() - 2;
    const I n = x.shape[0], cin = x.shape[1], cout = w.shape[0], cg = w.shape[1];
    const Shape in(x.shape.begin() + 2, x.shape.end()), ks(w.shape.begin() + 2, w.shape.end());
    const Shape st = rep(stride, nd), pd = rep(pad, nd), dl = rep(dil, nd);
    Shape o(nd);
    for (size_t i = 0; i < nd; ++i) o[i] = (in[i] + 2 * pd[i] - dl[i] * (ks[i] - 1) - 1) / st[i] + 1;
    const I og = cout / groups, osz = numel(o), isz = numel(in), ksz = numel(ks);
    const std::vector<I> ost = strides(o), ist = strides(in), kst = strides(ks);
    Shape shape = {n, cout};
    shape.insert(shape.end(), o.begin(), o.end());
    Tensor y = fresh(shape);
    double* yw = out(y);
    const bool hasBias = !bias.none();
    const Tensor b = hasBias ? T(bias) : Tensor();
    for (I s = 0; s < n; ++s)
        for (I co = 0; co < cout; ++co) {
            const I g = co / og;
            for (I op = 0; op < osz; ++op) {
                double acc = hasBias ? b.p[co] : 0;
                for (I ci = 0; ci < cg; ++ci) {
                    const I xc = (s * cin + g * cg + ci) * isz, wc = (co * cg + ci) * ksz;
                    for (I kp = 0; kp < ksz; ++kp) {
                        I xi = 0;
                        bool inside = true;
                        for (size_t d = 0; d < nd; ++d) {
                            const I pos = (op / ost[d]) % o[d] * st[d] - pd[d] + (kp / kst[d]) % ks[d] * dl[d];
                            if (pos < 0 || pos >= in[d]) {
                                inside = false;
                                break;
                            }
                            xi += pos * ist[d];
                        }
                        if (inside) acc += x.p[xc + xi] * w.p[wc + kp];
                    }
                }
                yw[(s * cout + co) * osz + op] = acc;
            }
        }
    return y;
}

/** A 2-d pooling: the window's maximum, or its sum over divisor (> 0), the window's size (countPad), or the input positions it covers. */
inline Tensor pool(const Tensor& x, const Shape& kernel0, const Shape& stride0, const Shape& pad0, const Shape& dil0, bool max,
                   bool countPad, double divisor) {
    const size_t nd = 2;
    const int lead = x.rank() - static_cast<int>(nd);
    const Shape kernel = rep(kernel0, nd), stride = rep(stride0.empty() ? kernel : stride0, nd), pad = rep(pad0, nd),
                dil = rep(dil0.empty() ? Shape{1} : dil0, nd);
    const Shape in(x.shape.begin() + lead, x.shape.end());
    Shape o(nd);
    for (size_t i = 0; i < nd; ++i) o[i] = (in[i] + 2 * pad[i] - dil[i] * (kernel[i] - 1) - 1) / stride[i] + 1;
    I planes = 1;
    for (int i = 0; i < lead; ++i) planes *= x.shape[i];
    const I isz = numel(in), osz = numel(o), ksz = numel(kernel);
    const std::vector<I> ost = strides(o), ist = strides(in), kst = strides(kernel);
    Shape shape(x.shape.begin(), x.shape.begin() + lead);
    shape.insert(shape.end(), o.begin(), o.end());
    Tensor y = fresh(shape);
    double* w = out(y);
    for (I p = 0; p < planes; ++p)
        for (I op = 0; op < osz; ++op) {
            double acc = max ? -INFINITY : 0;
            I covered = 0;
            for (I kp = 0; kp < ksz; ++kp) {
                I xi = 0;
                bool inside = true;
                for (size_t d = 0; d < nd; ++d) {
                    const I pos = (op / ost[d]) % o[d] * stride[d] - pad[d] + (kp / kst[d]) % kernel[d] * dil[d];
                    if (pos < 0 || pos >= in[d]) {
                        inside = false;
                        break;
                    }
                    xi += pos * ist[d];
                }
                const double v = inside ? x.p[p * isz + xi] : (max ? -INFINITY : 0);
                covered += inside ? 1 : 0;
                acc = max ? (std::isnan(v) || std::isnan(acc) ? NAN : std::max(acc, v)) : acc + v;
            }
            w[p * osz + op] = max ? acc : acc / (divisor > 0 ? divisor : countPad ? static_cast<double>(ksz)
                                                                                   : static_cast<double>(covered));
        }
    return y;
}

// ---- gathering, ordering

inline Tensor gather(const Tensor& x, int d, const Tensor& idx) {
    const int r = x.rank();
    const std::vector<I> xst = strides(x.shape);
    Tensor o = fresh(idx.shape);
    double* w = out(o);
    const I n = idx.numel();
    std::vector<I> i(r, 0);
    for (I k = 0; k < n; ++k) {
        I at = 0;
        for (int q = 0; q < r; ++q) at += (q == d ? static_cast<I>(idx.p[k]) : i[q]) * xst[q];
        w[k] = x.p[at];
        for (int q = r - 1; q >= 0; --q) {
            if (++i[q] < idx.shape[q]) break;
            i[q] = 0;
        }
    }
    return o;
}

inline Tensor index_select(const Tensor& x, int d, const Tensor& idx) {
    const Around a(x.shape, d);
    const I m = idx.numel();
    Shape s = x.shape;
    s[d] = m;
    Tensor o = fresh(s);
    double* w = out(o);
    for (I p = 0; p < a.outer; ++p)
        for (I j = 0; j < m; ++j) {
            I at = static_cast<I>(idx.p[j]);
            at = at < 0 ? at + a.n : at;
            std::copy(x.p + (p * a.n + at) * a.inner, x.p + (p * a.n + at + 1) * a.inner, w + (p * m + j) * a.inner);
        }
    if (idx.rank() == 1) return o;
    Shape full(x.shape.begin(), x.shape.begin() + d);
    full.insert(full.end(), idx.shape.begin(), idx.shape.end());
    full.insert(full.end(), x.shape.begin() + d + 1, x.shape.end());
    return reshaped(o, full);
}

/** numpy's order of floats for sorting: NaN last, -0 and 0 alike. */
inline bool before(double a, double b) { return a < b || (std::isnan(b) && !std::isnan(a)); }

/** Stable order along a dimension, as numpy's argsort(kind="stable"): values and indices of the first k. */
inline Value order(const Tensor& x, int d, I k, bool descending) {
    const Around a(x.shape, d);
    Shape s = x.shape;
    s[d] = k;
    Tensor vals = fresh(s), idx = fresh(s);
    double *vw = out(vals), *iw = out(idx);
    std::vector<I> perm(a.n);
    for (I p = 0; p < a.outer; ++p)
        for (I q = 0; q < a.inner; ++q) {
            const I base = p * a.n * a.inner + q;
            std::iota(perm.begin(), perm.end(), I(0));
            std::stable_sort(perm.begin(), perm.end(), [&](I u, I v) {
                const double xu = x.p[base + u * a.inner], xv = x.p[base + v * a.inner];
                return descending ? before(-xu, -xv) : before(xu, xv);
            });
            for (I i = 0; i < k; ++i) {
                const I at = (p * k + i) * a.inner + q;
                vw[at] = x.p[base + perm[i] * a.inner];
                iw[at] = static_cast<double>(perm[i]);
            }
        }
    return Value::tuple({Value::of(vals), Value::of(idx)});
}

inline Value arg_reduce(const Tensor& x, I dv, bool keep, bool max) {
    const int d = dim(dv, x.rank());
    const Around a(x.shape, d);
    Shape s = x.shape;
    if (!s.empty()) s[d] = 1;
    Shape shape = s;
    if (!keep && !shape.empty()) shape.erase(shape.begin() + d);
    Tensor v = fresh(shape), ix = fresh(shape);
    double *vw = out(v), *iw = out(ix);
    for (I p = 0; p < a.outer; ++p)
        for (I q = 0; q < a.inner; ++q) {
            const I base = p * a.n * a.inner + q;
            double best = x.p[base];
            I at = 0;
            for (I i = 1; i < a.n; ++i) {
                const double c = x.p[base + i * a.inner];
                if (std::isnan(best)) break;
                if (std::isnan(c) || (max ? c > best : c < best)) {
                    best = c;
                    at = i;
                }
            }
            vw[p * a.inner + q] = best;
            iw[p * a.inner + q] = static_cast<double>(at);
        }
    return Value::tuple({Value::of(v), Value::of(ix)});
}

inline Tensor index(const Tensor& x, const std::vector<Value>& indices) {
    int first = -1, count = 0;
    std::vector<Tensor> ts;
    for (size_t i = 0; i < indices.size(); ++i)
        if (!indices[i].none()) {
            if (first < 0) first = static_cast<int>(i);
            else if (static_cast<int>(i) != first + count) throw GraphError("index with index tensors apart");
            ++count;
            ts.push_back(T(indices[i]));
        }
    if (ts.empty()) return x;
    Shape bs = ts[0].shape;
    for (const Tensor& t : ts) bs = broadcast(bs, t.shape);
    std::vector<Tensor> b;
    for (const Tensor& t : ts) b.push_back(broadcast_to(t, bs));
    const int before = first, after = x.rank() - first - count;
    const std::vector<I> xst = strides(x.shape);
    const Shape pre(x.shape.begin(), x.shape.begin() + before), post(x.shape.begin() + first + count, x.shape.end());
    const I npre = numel(pre), nb = numel(bs), npost = numel(post);
    const std::vector<I> pst = strides(pre), qst = strides(post);
    Shape shape = pre;
    shape.insert(shape.end(), bs.begin(), bs.end());
    shape.insert(shape.end(), post.begin(), post.end());
    Tensor o = fresh(shape);
    double* w = out(o);
    for (I p = 0; p < npre; ++p) {
        I off = 0;
        for (int d = 0; d < before; ++d) off += (p / pst[d]) % pre[d] * xst[d];
        for (I j = 0; j < nb; ++j) {
            I at = off;
            for (int c = 0; c < count; ++c) {
                I v = static_cast<I>(b[c].p[j]);
                v = v < 0 ? v + x.shape[first + c] : v;
                at += v * xst[first + c];
            }
            for (I q = 0; q < npost; ++q) {
                I pq = 0;
                for (int d = 0; d < after; ++d) pq += (q / qst[d]) % post[d] * xst[first + count + d];
                w[(p * nb + j) * npost + q] = x.p[at + pq];
            }
        }
    }
    return o;
}

// ---- normalizations

inline Value layer_norm(const Tensor& x, const Shape& normalized, const Value& wv, const Value& bv, double eps) {
    const I inner = numel(normalized), outer = x.numel() / std::max<I>(inner, 1);
    Shape ks = x.shape;
    for (size_t i = ks.size() - normalized.size(); i < ks.size(); ++i) ks[i] = 1;
    Tensor y = fresh(x.shape), mean = fresh(ks), rstd = fresh(ks);
    double *yw = out(y), *mw = out(mean), *rw = out(rstd);
    const bool hw = !wv.none(), hb = !bv.none();
    const Tensor wt = hw ? T(wv) : Tensor(), bt = hb ? T(bv) : Tensor();
    for (I p = 0; p < outer; ++p) {
        double m = 0;
        for (I i = 0; i < inner; ++i) m += x.p[p * inner + i];
        m /= static_cast<double>(inner);
        double v = 0;
        for (I i = 0; i < inner; ++i) {
            const double dl = x.p[p * inner + i] - m;
            v += dl * dl;
        }
        v /= static_cast<double>(inner);
        const double r = 1.0 / std::sqrt(v + eps);
        mw[p] = m;
        rw[p] = r;
        for (I i = 0; i < inner; ++i) {
            double e = (x.p[p * inner + i] - m) * r;
            if (hw) e *= wt.p[i];
            if (hb) e += bt.p[i];
            yw[p * inner + i] = e;
        }
    }
    return Value::tuple({Value::of(y), Value::of(mean), Value::of(rstd)});
}

inline Value batch_norm(const Tensor& x, const Value& wv, const Value& bv, const Tensor& mean, const Tensor& var, double eps) {
    const I c = x.shape[1], n = x.shape[0];
    I inner = 1;
    for (int i = 2; i < x.rank(); ++i) inner *= x.shape[i];
    Tensor y = fresh(x.shape);
    double* w = out(y);
    const bool hw = !wv.none(), hb = !bv.none();
    const Tensor wt = hw ? T(wv) : Tensor(), bt = hb ? T(bv) : Tensor();
    for (I s = 0; s < n; ++s)
        for (I ch = 0; ch < c; ++ch) {
            const double sd = std::sqrt(var.p[ch] + eps);
            for (I i = 0; i < inner; ++i) {
                const I at = (s * c + ch) * inner + i;
                double e = (x.p[at] - mean.p[ch]) / sd;
                if (hw) e *= wt.p[ch];
                if (hb) e += bt.p[ch];
                w[at] = e;
            }
        }
    return Value::tuple({Value::of(y), Value::of(fresh({0})), Value::of(fresh({0}))});
}

inline Tensor full(const Shape& s, double v) {
    Tensor o = fresh(s);
    std::fill(out(o), out(o) + o.numel(), v);
    return o;
}

inline Tensor arange(double start, double end, double step) {
    const I n = static_cast<I>(std::max(0.0, std::ceil((end - start) / step)));
    Tensor o = fresh({n});
    double* w = out(o);
    for (I i = 0; i < n; ++i) w[i] = start + static_cast<double>(i) * step;
    return o;
}

/** c10::Half's rounding of a float (IEEE binary16, to nearest, ties to even), as a double. */
inline double half(double v) {
    const float f = static_cast<float>(v);
    if (std::isnan(f) || std::isinf(f) || f == 0) return f;
    const double a = std::fabs(static_cast<double>(f));
    const int e = std::max(std::ilogb(a), -14) - 10;   // the exponent of the half's last place
    const double c = std::ldexp(1.5, e + 52);
    double r = (c + a) - c;                             // rounded to that place, ties to even, by the double addition
    if (r > 65504.0) r = INFINITY;
    return std::copysign(r, static_cast<double>(f));
}

/** c10::BFloat16's rounding of a float: to nearest, ties to even, on the upper 16 bits. */
inline double bf16(double v) {
    const float f = static_cast<float>(v);
    if (std::isnan(f)) return NAN;
    std::uint32_t u;
    std::memcpy(&u, &f, 4);
    const std::uint64_t r = ((static_cast<std::uint64_t>(u) + ((u >> 16) & 1) + 0x7fff) >> 16) << 16;
    const std::uint32_t k = static_cast<std::uint32_t>(r);
    float g;
    std::memcpy(&g, &k, 4);
    return g;
}

/** A cast, rounding as PyTorch's own does: to float32 directly, to half and bfloat16 by way of float32. */
inline Tensor cast(const Tensor& x, const Value& dtype) {
    const std::string t = dtype.kind == Value::STR ? dtype.s : "";
    if (t == "bool") return unary(x, [](double v) { return v != 0 ? 1.0 : 0.0; });
    if (t == "int64" || t == "int32" || t == "int16" || t == "int8" || t == "uint8")
        return unary(x, [](double v) { return std::trunc(v); });
    if (t == "float32") return unary(x, [](double v) { return static_cast<double>(static_cast<float>(v)); });
    if (t == "float16") return unary(x, half);
    if (t == "bfloat16") return unary(x, bf16);
    return unary(x, [](double v) { return v; });
}

/** numpy's (and Python's) modulo of floats: fmod, its sign made the divisor's. */
inline double pymod(double u, double w) {
    double m = std::fmod(u, w);
    if (m != 0) {
        if ((w < 0) != (m < 0)) m += w;
    } else {
        m = std::copysign(0.0, w);
    }
    return m;
}

inline Tensor cumsum(const Tensor& x, int d) {
    const Around a(x.shape, d);
    Tensor o = fresh(x.shape);
    double* w = out(o);
    for (I p = 0; p < a.outer; ++p)
        for (I q = 0; q < a.inner; ++q) {
            double s = 0;
            for (I i = 0; i < a.n; ++i) {
                const I at = (p * a.n + i) * a.inner + q;
                s += x.p[at];
                w[at] = s;
            }
        }
    return o;
}

inline Tensor flip(const Tensor& x, const std::vector<int>& ds) {
    Tensor t = x;
    for (int d : ds) {
        const Around a(t.shape, d);
        Tensor o = fresh(t.shape);
        double* w = out(o);
        for (I p = 0; p < a.outer; ++p)
            for (I i = 0; i < a.n; ++i)
                std::copy(t.p + (p * a.n + i) * a.inner, t.p + (p * a.n + i + 1) * a.inner,
                          w + (p * a.n + (a.n - 1 - i)) * a.inner);
        t = o;
    }
    return t;
}

inline Tensor roll(const Tensor& x, const Shape& shifts, const std::vector<int>& ds) {
    if (ds.empty()) {
        const I n = x.numel(), s = n == 0 ? 0 : ((shifts.at(0) % n) + n) % n;
        Tensor o = fresh(x.shape);
        double* w = out(o);
        for (I i = 0; i < n; ++i) w[(i + s) % n] = x.p[i];
        return o;
    }
    Tensor t = x;
    for (size_t k = 0; k < ds.size(); ++k) {
        const Around a(t.shape, ds[k]);
        const I s = a.n == 0 ? 0 : ((shifts.at(k) % a.n) + a.n) % a.n;
        Tensor o = fresh(t.shape);
        double* w = out(o);
        for (I p = 0; p < a.outer; ++p)
            for (I i = 0; i < a.n; ++i)
                std::copy(t.p + (p * a.n + i) * a.inner, t.p + (p * a.n + i + 1) * a.inner,
                          w + (p * a.n + (i + s) % a.n) * a.inner);
        t = o;
    }
    return t;
}

inline Tensor repeat(const Tensor& x, const Shape& reps) {
    Tensor t = x;
    if (reps.size() > static_cast<size_t>(t.rank())) {
        Shape s(reps.size(), 1);
        std::copy(t.shape.begin(), t.shape.end(), s.end() - t.rank());
        t = reshaped(t, s);
    }
    for (size_t d = 0; d < reps.size(); ++d) t = cat(std::vector<Tensor>(static_cast<size_t>(reps[d]), t), static_cast<I>(d));
    return t;
}

/** F.pad's constant padding: pairs from the last dimension back, a negative one cropping. */
inline Tensor constant_pad(const Tensor& x, const Shape& pad, double value) {
    const int r = x.rank();
    Shape lo(r, 0), hi(r, 0), s(r);
    for (size_t i = 0; i < pad.size() / 2; ++i) {
        lo[r - 1 - i] = pad[2 * i];
        hi[r - 1 - i] = pad[2 * i + 1];
    }
    for (int d = 0; d < r; ++d) s[d] = x.shape[d] + lo[d] + hi[d];
    Tensor o = full(s, value);
    double* w = out(o);
    const std::vector<I> ost = strides(s);
    std::vector<I> idx(r, 0);
    const I n = x.numel();
    for (I k = 0; k < n; ++k) {
        I at = 0;
        bool inside = true;
        for (int d = 0; d < r; ++d) {
            const I pos = idx[d] + lo[d];
            if (pos < 0 || pos >= s[d]) {
                inside = false;
                break;
            }
            at += pos * ost[d];
        }
        if (inside) w[at] = x.p[k];
        for (int d = r - 1; d >= 0; --d) {
            if (++idx[d] < x.shape[d]) break;
            idx[d] = 0;
        }
    }
    return o;
}

inline Tensor var(const Tensor& x, const std::vector<int>& ds, double ddof, bool keep) {
    const Tensor mean = reduce(x, ds, true, MEAN);
    const Tensor sq = binary(x, mean, [](double a, double b) { return (a - b) * (a - b); });
    const Tensor s = reduce(sq, ds, keep, SUM);
    const double count = static_cast<double>(x.numel()) / static_cast<double>(std::max<I>(1, mean.numel()));
    return unary(s, [&](double v) { return v / (count - ddof); });
}

// ---- erf, as fdlibm computes it (s_erf.c), the same as Java's NeuralGraph

inline double erf(double x) {
    static const double erx = 8.45062911510467529297e-01, efx = 1.28379167095512586316e-01,
        efx8 = 1.02703333676410069053e+00, pp0 = 1.28379167095512558561e-01, pp1 = -3.25042107247001499370e-01,
        pp2 = -2.84817495755985104766e-02, pp3 = -5.77027029648944159157e-03, pp4 = -2.37630166566501626084e-05,
        qq1 = 3.97917223959155352819e-01, qq2 = 6.50222499887672944485e-02, qq3 = 5.08130628187576562776e-03,
        qq4 = 1.32494738004321644526e-04, qq5 = -3.96022827877536812320e-06, pa0 = -2.36211856075265944077e-03,
        pa1 = 4.14856118683748331666e-01, pa2 = -3.72207876035701323847e-01, pa3 = 3.18346619901161753674e-01,
        pa4 = -1.10894694282396677476e-01, pa5 = 3.54783043256182359371e-02, pa6 = -2.16637559486879084300e-03,
        qa1 = 1.06420880400844228286e-01, qa2 = 5.40397917702171048937e-01, qa3 = 7.18286544141962662868e-02,
        qa4 = 1.26171219808761642112e-01, qa5 = 1.36370839120290507362e-02, qa6 = 1.19844998467991074170e-02,
        ra0 = -9.86494403484714822705e-03, ra1 = -6.93858572707181764372e-01, ra2 = -1.05586262253232909814e+01,
        ra3 = -6.23753324503260060396e+01, ra4 = -1.62396669462573470355e+02, ra5 = -1.84605092906711035994e+02,
        ra6 = -8.12874355063065934246e+01, ra7 = -9.81432934416914548592e+00, sa1 = 1.96512716674392571292e+01,
        sa2 = 1.37657754143519042600e+02, sa3 = 4.34565877475229228821e+02, sa4 = 6.45387271733267880336e+02,
        sa5 = 4.29008140027567833386e+02, sa6 = 1.08635005541779435134e+02, sa7 = 6.57024977031928170135e+00,
        sa8 = -6.04244152148580987438e-02, rb0 = -9.86494292470009928597e-03, rb1 = -7.99283237680523006574e-01,
        rb2 = -1.77579549177547519889e+01, rb3 = -1.60636384855821916062e+02, rb4 = -6.37566443368389627722e+02,
        rb5 = -1.02509513161107724954e+03, rb6 = -4.83519191608651397019e+02, sb1 = 3.03380607434824582924e+01,
        sb2 = 3.25792512996573918826e+02, sb3 = 1.53672958608443695994e+03, sb4 = 3.19985821950859553908e+03,
        sb5 = 2.55305040643316442583e+03, sb6 = 4.74528541206955367215e+02, sb7 = -2.24409524465858183362e+01;
    if (std::isnan(x)) return x;
    if (std::isinf(x)) return x > 0 ? 1 : -1;
    const double ax = std::fabs(x);
    if (ax < 0.84375) {
        if (ax < 0x1.0p-28) return ax < 0x1.0p-1015 ? 0.125 * (8.0 * x + efx8 * x) : x + efx * x;
        const double z = x * x;
        const double r = pp0 + z * (pp1 + z * (pp2 + z * (pp3 + z * pp4)));
        const double s = 1.0 + z * (qq1 + z * (qq2 + z * (qq3 + z * (qq4 + z * qq5))));
        return x + x * (r / s);
    }
    if (ax < 1.25) {
        const double s = ax - 1.0;
        const double p = pa0 + s * (pa1 + s * (pa2 + s * (pa3 + s * (pa4 + s * (pa5 + s * pa6)))));
        const double q = 1.0 + s * (qa1 + s * (qa2 + s * (qa3 + s * (qa4 + s * (qa5 + s * qa6)))));
        return x >= 0 ? erx + p / q : -erx - p / q;
    }
    if (ax >= 6) return x >= 0 ? 1.0 - 1e-300 : 1e-300 - 1.0;
    const double s = 1.0 / (ax * ax);
    std::uint64_t split = 0x4006DB6E00000000ull;   // fdlibm's 1/0.35, by its high word
    double cut;
    std::memcpy(&cut, &split, 8);
    double r, ss;
    if (ax < cut) {
        r = ra0 + s * (ra1 + s * (ra2 + s * (ra3 + s * (ra4 + s * (ra5 + s * (ra6 + s * ra7))))));
        ss = 1.0 + s * (sa1 + s * (sa2 + s * (sa3 + s * (sa4 + s * (sa5 + s * (sa6 + s * (sa7 + s * sa8)))))));
    } else {
        r = rb0 + s * (rb1 + s * (rb2 + s * (rb3 + s * (rb4 + s * (rb5 + s * rb6)))));
        ss = 1.0 + s * (sb1 + s * (sb2 + s * (sb3 + s * (sb4 + s * (sb5 + s * (sb6 + s * sb7))))));
    }
    std::uint64_t bits;
    std::memcpy(&bits, &ax, 8);
    bits &= 0xffffffff00000000ull;
    double z;
    std::memcpy(&z, &bits, 8);
    const double rr = std::exp(-z * z - 0.5625) * std::exp((z - ax) * (z + ax) + r / ss);
    return x >= 0 ? 1.0 - rr / ax : rr / ax - 1.0;
}

// ---------------------------------------------------------------------------
// The operations, by their ATen names
// ---------------------------------------------------------------------------

using Op = Value (*)(const Args&);

inline Value V(Tensor t) { return Value::of(std::move(t)); }

template <class F>
Value bin(const Args& a, F f) {
    return V(binary(T(a[0]), T(a[1]), f));
}

template <class F>
Value un(const Args& a, F f) {
    return V(unary(T(a[0]), f));
}

inline double alpha(const Args& a, size_t k) { return has(a, k) ? N(a[k]) : 1.0; }

inline Value arith(const Args& a, char op) {
    if (a[0].kind == Value::INT && a[1].kind == Value::INT && op != '/') {
        const I x = a[0].i, y = a[1].i;
        switch (op) {
            case '*': return Value::integer(x * y);
            case '+': return Value::integer(x + y);
            case '-': return Value::integer(x - y);
            case 'f': {
                I q = x / y;
                if ((x % y != 0) && ((x < 0) != (y < 0))) --q;
                return Value::integer(q);
            }
            case '%': {
                I m = x % y;
                if (m != 0 && ((m < 0) != (y < 0))) m += y;
                return Value::integer(m);
            }
            default: return Value::integer(static_cast<I>(std::pow(static_cast<double>(x), static_cast<double>(y))));
        }
    }
    const double x = N(a[0]), y = N(a[1]);
    switch (op) {
        case '*': return Value::real(x * y);
        case '+': return Value::real(x + y);
        case '-': return Value::real(x - y);
        case 'f': return Value::real(std::floor(x / y));
        case '%': return Value::real(pymod(x, y));
        case '^': return Value::real(std::pow(x, y));
        default: return Value::real(x / y);
    }
}

inline const Value& item(const Value& seq, I i) {
    const I n = static_cast<I>(seq.list.size());
    return seq.list.at(static_cast<size_t>(i < 0 ? i + n : i));
}

inline Value split(const Tensor& x, const std::vector<I>& sizes, int d) {
    std::vector<Value> out;
    I at = 0;
    for (I s : sizes) {
        out.push_back(V(narrow(x, d, at, at + s, 1)));
        at += s;
    }
    return Value::tuple(out);
}

inline Value nothing(const Args&) { return Value(); }

inline const std::unordered_map<std::string, Op>& operations() {
    static const std::unordered_map<std::string, Op> ops = {
        // the trace's own assertions on shapes and types: nothing to compute
        {"_assert_tensor_metadata.default", nothing}, {"_assert_async.msg", nothing}, {"_assert_scalar.default", nothing},
        {"_functional_assert_async.msg", nothing}, {"sym_constrain_range_for_size.default", nothing},
        {"sym_constrain_range.default", nothing},
        // sizes
        {"sym_size.int", [](const Args& a) { const Tensor x = T(a[0]); return Value::integer(x.shape.at(dim(L(a[1]), x.rank()))); }},
        {"sym_numel.default", [](const Args& a) { return Value::integer(T(a[0]).numel()); }},
        {"py.mul", [](const Args& a) { return arith(a, '*'); }}, {"py.add", [](const Args& a) { return arith(a, '+'); }},
        {"py.sub", [](const Args& a) { return arith(a, '-'); }}, {"py.floordiv", [](const Args& a) { return arith(a, 'f'); }},
        {"py.truediv", [](const Args& a) { return arith(a, '/'); }}, {"py.mod", [](const Args& a) { return arith(a, '%'); }},
        {"py.pow", [](const Args& a) { return arith(a, '^'); }},
        {"py.neg", [](const Args& a) { return a[0].kind == Value::INT ? Value::integer(-a[0].i) : Value::real(-N(a[0])); }},
        {"py.getitem", [](const Args& a) { return item(a[0], L(a[1])); }},
        // shapes
        {"view.default", [](const Args& a) { return V(view(T(a[0]), ints(a[1]))); }},
        {"_unsafe_view.default", [](const Args& a) { return V(view(T(a[0]), ints(a[1]))); }},
        {"reshape.default", [](const Args& a) { return V(view(T(a[0]), ints(a[1]))); }},
        {"permute.default", [](const Args& a) { const Tensor x = T(a[0]); return V(permute(x, dims(a[1], x.rank()))); }},
        {"expand.default", [](const Args& a) { return V(expand(T(a[0]), ints(a[1]))); }},
        {"clone.default", [](const Args& a) { return V(unary(T(a[0]), [](double v) { return v; })); }},
        {"lift_fresh_copy.default", [](const Args& a) { return V(unary(T(a[0]), [](double v) { return v; })); }},
        {"contiguous.default", [](const Args& a) { return V(T(a[0])); }},
        {"alias.default", [](const Args& a) { return a[0]; }}, {"detach.default", [](const Args& a) { return a[0]; }},
        {"squeeze.dims", [](const Args& a) { const Tensor x = T(a[0]); const auto d = dims(a[1], x.rank()); return V(squeeze(x, &d)); }},
        {"squeeze.dim", [](const Args& a) { const Tensor x = T(a[0]); const auto d = dims(a[1], x.rank()); return V(squeeze(x, &d)); }},
        {"squeeze.default", [](const Args& a) { return V(squeeze(T(a[0]), nullptr)); }},
        {"unsqueeze.default", [](const Args& a) { return V(unsqueeze(T(a[0]), L(a[1]))); }},
        {"select.int", [](const Args& a) { const Tensor x = T(a[0]); return V(select(x, dim(L(a[1]), x.rank()), L(a[2]))); }},
        {"slice.Tensor", [](const Args& a) {
             const Tensor x = T(a[0]);
             const int d = dim(has(a, 1) ? L(a[1]) : 0, x.rank());
             const I n = x.shape[d];
             I lo = has(a, 2) ? L(a[2]) : 0, hi = has(a, 3) ? L(a[3]) : n;
             lo = lo < 0 ? std::max<I>(0, lo + n) : std::min(lo, n);
             hi = hi < 0 ? std::max<I>(0, hi + n) : std::min(hi, n);
             return V(narrow(x, d, lo, std::max(lo, hi), has(a, 4) ? L(a[4]) : 1));
         }},
        {"cat.default", [](const Args& a) {
             std::vector<Tensor> ts;
             for (const Value& v : a[0].list) ts.push_back(T(v));
             return V(cat(ts, has(a, 1) ? L(a[1]) : 0));
         }},
        {"stack.default", [](const Args& a) {
             std::vector<Tensor> ts;
             for (const Value& v : a[0].list) ts.push_back(T(v));
             const int d = dim(has(a, 1) ? L(a[1]) : 0, ts.at(0).rank() + 1);
             for (Tensor& t : ts) t = unsqueeze(t, d);
             return V(cat(ts, d));
         }},
        {"split_with_sizes.default", [](const Args& a) {
             const Tensor x = T(a[0]);
             return split(x, ints(a[1]), dim(has(a, 2) ? L(a[2]) : 0, x.rank()));
         }},
        {"split.Tensor", [](const Args& a) {
             const Tensor x = T(a[0]);
             const int d = dim(has(a, 2) ? L(a[2]) : 0, x.rank());
             const I size = L(a[1]);
             std::vector<I> sizes;
             for (I at = 0; at < x.shape[d]; at += size) sizes.push_back(std::min(size, x.shape[d] - at));
             return split(x, sizes, d);
         }},
        {"unbind.int", [](const Args& a) {
             const Tensor x = T(a[0]);
             const int d = dim(has(a, 1) ? L(a[1]) : 0, x.rank());
             std::vector<Value> out;
             for (I i = 0; i < x.shape[d]; ++i) out.push_back(V(select(x, d, i)));
             return Value::tuple(out);
         }},
        {"flip.default", [](const Args& a) { const Tensor x = T(a[0]); return V(flip(x, dims(a[1], x.rank()))); }},
        {"roll.default", [](const Args& a) {
             const Tensor x = T(a[0]);
             return V(roll(x, ints(a[1]), has(a, 2) ? dims(a[2], x.rank()) : std::vector<int>()));
         }},
        {"repeat.default", [](const Args& a) { return V(repeat(T(a[0]), ints(a[1]))); }},
        {"constant_pad_nd.default", [](const Args& a) { return V(constant_pad(T(a[0]), ints(a[1]), has(a, 2) ? N(a[2]) : 0)); }},
        {"_to_copy.default", [](const Args& a) { return V(cast(T(a[0]), a.size() > 1 ? a[1] : Value())); }},
        {"to.dtype", [](const Args& a) { return V(cast(T(a[0]), a.size() > 1 ? a[1] : Value())); }},
        // creation
        {"full.default", [](const Args& a) { return V(full(ints(a[0]), N(a[1]))); }},
        {"full_like.default", [](const Args& a) { return V(full(T(a[0]).shape, N(a[1]))); }},
        {"zeros_like.default", [](const Args& a) { return V(full(T(a[0]).shape, 0)); }},
        {"ones_like.default", [](const Args& a) { return V(full(T(a[0]).shape, 1)); }},
        {"zeros.default", [](const Args& a) { return V(full(ints(a[0]), 0)); }},
        {"empty.memory_format", [](const Args& a) { return V(full(ints(a[0]), 0)); }},
        {"ones.default", [](const Args& a) { return V(full(ints(a[0]), 1)); }},
        {"scalar_tensor.default", [](const Args& a) { return V(scalar(N(a[0]))); }},
        {"arange.start_step", [](const Args& a) { return V(arange(N(a[0]), N(a[1]), has(a, 2) ? N(a[2]) : 1)); }},
        {"arange.default", [](const Args& a) { return V(arange(0, N(a[0]), 1)); }},
        {"arange.start", [](const Args& a) { return V(arange(N(a[0]), N(a[1]), 1)); }},
        // products
        {"mm.default", [](const Args& a) { return V(matmul(T(a[0]), T(a[1]))); }},
        {"bmm.default", [](const Args& a) { return V(matmul(T(a[0]), T(a[1]))); }},
        {"matmul.default", [](const Args& a) { return V(matmul(T(a[0]), T(a[1]))); }},
        {"addmm.default", [](const Args& a) { return V(addmm(T(a[0]), T(a[1]), T(a[2]), alpha(a, 3), alpha(a, 4))); }},
        {"baddbmm.default", [](const Args& a) { return V(addmm(T(a[0]), T(a[1]), T(a[2]), alpha(a, 3), alpha(a, 4))); }},
        {"dot.default", [](const Args& a) {
             const Tensor x = T(a[0]), y = T(a[1]);
             double s = 0;
             for (I i = 0; i < x.numel(); ++i) s += x.p[i] * y.p[i];
             return V(scalar(s));
         }},
        // elementwise, one argument
        {"relu.default", [](const Args& a) { return un(a, [](double v) { return std::isnan(v) ? v : std::max(v, 0.0); }); }},
        {"gelu.default", [](const Args& a) {
             if (a.size() > 1 && a[1].kind == Value::STR && a[1].s == "tanh") {
                 const double c = std::sqrt(2.0 / 3.14159265358979323846);
                 return un(a, [c](double v) { return 0.5 * v * (1.0 + std::tanh(c * (v + 0.044715 * v * v * v))); });
             }
             const double r2 = std::sqrt(2.0);
             return un(a, [r2](double v) { return 0.5 * v * (1.0 + graph::erf(v / r2)); });
         }},
        {"sigmoid.default", [](const Args& a) { return un(a, [](double v) { return 1.0 / (1.0 + std::exp(-v)); }); }},
        {"tanh.default", [](const Args& a) { return un(a, [](double v) { return std::tanh(v); }); }},
        {"exp.default", [](const Args& a) { return un(a, [](double v) { return std::exp(v); }); }},
        {"log.default", [](const Args& a) { return un(a, [](double v) { return std::log(v); }); }},
        {"sqrt.default", [](const Args& a) { return un(a, [](double v) { return std::sqrt(v); }); }},
        {"rsqrt.default", [](const Args& a) { return un(a, [](double v) { return 1.0 / std::sqrt(v); }); }},
        {"neg.default", [](const Args& a) { return un(a, [](double v) { return -v; }); }},
        {"abs.default", [](const Args& a) { return un(a, [](double v) { return std::fabs(v); }); }},
        {"sin.default", [](const Args& a) { return un(a, [](double v) { return std::sin(v); }); }},
        {"cos.default", [](const Args& a) { return un(a, [](double v) { return std::cos(v); }); }},
        {"sinh.default", [](const Args& a) { return un(a, [](double v) { return std::sinh(v); }); }},
        {"cosh.default", [](const Args& a) { return un(a, [](double v) { return std::cosh(v); }); }},
        {"atan.default", [](const Args& a) { return un(a, [](double v) { return std::atan(v); }); }},
        {"asin.default", [](const Args& a) { return un(a, [](double v) { return std::asin(v); }); }},
        {"acos.default", [](const Args& a) { return un(a, [](double v) { return std::acos(v); }); }},
        {"log1p.default", [](const Args& a) { return un(a, [](double v) { return std::log1p(v); }); }},
        {"expm1.default", [](const Args& a) { return un(a, [](double v) { return std::expm1(v); }); }},
        {"log2.default", [](const Args& a) { return un(a, [](double v) { return std::log2(v); }); }},
        {"log10.default", [](const Args& a) { return un(a, [](double v) { return std::log10(v); }); }},
        {"erf.default", [](const Args& a) { return un(a, [](double v) { return graph::erf(v); }); }},
        {"reciprocal.default", [](const Args& a) { return un(a, [](double v) { return 1.0 / v; }); }},
        {"sign.default", [](const Args& a) { return un(a, [](double v) { return v > 0 ? 1.0 : v < 0 ? -1.0 : v; }); }},
        {"floor.default", [](const Args& a) { return un(a, [](double v) { return std::floor(v); }); }},
        {"ceil.default", [](const Args& a) { return un(a, [](double v) { return std::ceil(v); }); }},
        {"round.default", [](const Args& a) { return un(a, [](double v) { return std::nearbyint(v); }); }},
        {"trunc.default", [](const Args& a) { return un(a, [](double v) { return std::trunc(v); }); }},
        {"silu.default", [](const Args& a) { return un(a, [](double v) { return v / (1.0 + std::exp(-v)); }); }},
        {"leaky_relu.default", [](const Args& a) {
             const double s = has(a, 1) ? N(a[1]) : 0.01;
             return un(a, [s](double v) { return v > 0 ? v : s * v; });
         }},
        {"hardtanh.default", [](const Args& a) {
             const double lo = has(a, 1) ? N(a[1]) : -1, hi = has(a, 2) ? N(a[2]) : 1;
             return un(a, [lo, hi](double v) { return std::isnan(v) ? v : std::min(std::max(v, lo), hi); });
         }},
        {"elu.default", [](const Args& a) {
             const double al = has(a, 1) ? N(a[1]) : 1, sc = has(a, 2) ? N(a[2]) : 1, is = has(a, 3) ? N(a[3]) : 1;
             return un(a, [al, sc, is](double v) { return sc * (v > 0 ? v : al * (std::exp(is * v) - 1.0)); });
         }},
        {"softplus.default", [](const Args& a) {
             const double be = has(a, 1) ? N(a[1]) : 1, th = has(a, 2) ? N(a[2]) : 20;
             return un(a, [be, th](double v) { return be * v > th ? v : std::log1p(std::exp(be * v)) / be; });
         }},
        {"clamp.default", [](const Args& a) {
             Tensor x = T(a[0]);
             if (has(a, 1)) x = binary(x, T(a[1]), [](double u, double w) { return std::isnan(u) ? u : std::max(u, w); });
             if (has(a, 2)) x = binary(x, T(a[2]), [](double u, double w) { return std::isnan(u) ? u : std::min(u, w); });
             return V(x);
         }},
        {"clamp.Tensor", [](const Args& a) {
             Tensor x = T(a[0]);
             if (has(a, 1)) x = binary(x, T(a[1]), [](double u, double w) { return std::isnan(u) ? u : std::max(u, w); });
             if (has(a, 2)) x = binary(x, T(a[2]), [](double u, double w) { return std::isnan(u) ? u : std::min(u, w); });
             return V(x);
         }},
        {"clamp_min.default", [](const Args& a) { return bin(a, [](double u, double w) { return std::isnan(u) ? u : std::max(u, w); }); }},
        {"clamp_max.default", [](const Args& a) { return bin(a, [](double u, double w) { return std::isnan(u) ? u : std::min(u, w); }); }},
        {"pow.Tensor_Scalar", [](const Args& a) { return bin(a, [](double u, double w) { return std::pow(u, w); }); }},
        {"pow.Tensor_Tensor", [](const Args& a) { return bin(a, [](double u, double w) { return std::pow(u, w); }); }},
        {"pow.Scalar", [](const Args& a) { return bin(a, [](double u, double w) { return std::pow(u, w); }); }},
        {"logical_not.default", [](const Args& a) { return un(a, [](double v) { return v == 0 ? 1.0 : 0.0; }); }},
        {"isnan.default", [](const Args& a) { return un(a, [](double v) { return std::isnan(v) ? 1.0 : 0.0; }); }},
        {"isinf.default", [](const Args& a) { return un(a, [](double v) { return std::isinf(v) ? 1.0 : 0.0; }); }},
        // elementwise, two arguments, broadcast
        {"add.Tensor", [](const Args& a) { const double al = alpha(a, 2); return bin(a, [al](double u, double w) { return al == 1 ? u + w : u + al * w; }); }},
        {"add.Scalar", [](const Args& a) { const double al = alpha(a, 2); return bin(a, [al](double u, double w) { return al == 1 ? u + w : u + al * w; }); }},
        {"sub.Tensor", [](const Args& a) { const double al = alpha(a, 2); return bin(a, [al](double u, double w) { return u - al * w; }); }},
        {"sub.Scalar", [](const Args& a) { const double al = alpha(a, 2); return bin(a, [al](double u, double w) { return u - al * w; }); }},
        {"rsub.Scalar", [](const Args& a) { const double al = alpha(a, 2); return bin(a, [al](double u, double w) { return w - al * u; }); }},
        {"rsub.Tensor", [](const Args& a) { const double al = alpha(a, 2); return bin(a, [al](double u, double w) { return w - al * u; }); }},
        {"mul.Tensor", [](const Args& a) { return bin(a, [](double u, double w) { return u * w; }); }},
        {"mul.Scalar", [](const Args& a) { return bin(a, [](double u, double w) { return u * w; }); }},
        {"div.Tensor", [](const Args& a) { return bin(a, [](double u, double w) { return u / w; }); }},
        {"div.Scalar", [](const Args& a) { return bin(a, [](double u, double w) { return u / w; }); }},
        {"div.Tensor_mode", [](const Args& a) {
             const std::string mode = a.size() > 2 && a[2].kind == Value::STR ? a[2].s : "";
             if (mode == "floor") return bin(a, [](double u, double w) { return std::floor(u / w); });
             if (mode == "trunc") return bin(a, [](double u, double w) { return std::trunc(u / w); });
             return bin(a, [](double u, double w) { return u / w; });
         }},
        {"remainder.Scalar", [](const Args& a) { return bin(a, pymod); }},
        {"remainder.Tensor", [](const Args& a) { return bin(a, pymod); }},
        {"fmod.Scalar", [](const Args& a) { return bin(a, [](double u, double w) { return std::fmod(u, w); }); }},
        {"maximum.default", [](const Args& a) { return bin(a, [](double u, double w) { return std::isnan(u) || std::isnan(w) ? NAN : std::max(u, w); }); }},
        {"minimum.default", [](const Args& a) { return bin(a, [](double u, double w) { return std::isnan(u) || std::isnan(w) ? NAN : std::min(u, w); }); }},
        {"atan2.default", [](const Args& a) { return bin(a, [](double u, double w) { return std::atan2(u, w); }); }},
        {"eq.Scalar", [](const Args& a) { return bin(a, [](double u, double w) { return u == w ? 1.0 : 0.0; }); }},
        {"eq.Tensor", [](const Args& a) { return bin(a, [](double u, double w) { return u == w ? 1.0 : 0.0; }); }},
        {"ne.Scalar", [](const Args& a) { return bin(a, [](double u, double w) { return u != w ? 1.0 : 0.0; }); }},
        {"ne.Tensor", [](const Args& a) { return bin(a, [](double u, double w) { return u != w ? 1.0 : 0.0; }); }},
        {"lt.Scalar", [](const Args& a) { return bin(a, [](double u, double w) { return u < w ? 1.0 : 0.0; }); }},
        {"lt.Tensor", [](const Args& a) { return bin(a, [](double u, double w) { return u < w ? 1.0 : 0.0; }); }},
        {"le.Scalar", [](const Args& a) { return bin(a, [](double u, double w) { return u <= w ? 1.0 : 0.0; }); }},
        {"le.Tensor", [](const Args& a) { return bin(a, [](double u, double w) { return u <= w ? 1.0 : 0.0; }); }},
        {"gt.Scalar", [](const Args& a) { return bin(a, [](double u, double w) { return u > w ? 1.0 : 0.0; }); }},
        {"gt.Tensor", [](const Args& a) { return bin(a, [](double u, double w) { return u > w ? 1.0 : 0.0; }); }},
        {"ge.Scalar", [](const Args& a) { return bin(a, [](double u, double w) { return u >= w ? 1.0 : 0.0; }); }},
        {"ge.Tensor", [](const Args& a) { return bin(a, [](double u, double w) { return u >= w ? 1.0 : 0.0; }); }},
        {"logical_and.default", [](const Args& a) { return bin(a, [](double u, double w) { return u != 0 && w != 0 ? 1.0 : 0.0; }); }},
        {"logical_or.default", [](const Args& a) { return bin(a, [](double u, double w) { return u != 0 || w != 0 ? 1.0 : 0.0; }); }},
        {"logical_xor.default", [](const Args& a) { return bin(a, [](double u, double w) { return (u != 0) != (w != 0) ? 1.0 : 0.0; }); }},
        {"where.self", [](const Args& a) {
             const Tensor c = T(a[0]), x = T(a[1]), y = T(a[2]);
             const Shape s = broadcast(broadcast(c.shape, x.shape), y.shape);
             const Tensor cb = broadcast_to(c, s), xb = broadcast_to(x, s), yb = broadcast_to(y, s);
             Tensor o = fresh(s);
             double* w = out(o);
             for (I i = 0; i < o.numel(); ++i) w[i] = cb.p[i] != 0 ? xb.p[i] : yb.p[i];
             return V(o);
         }},
        // reductions
        {"sum.dim_IntList", [](const Args& a) { const Tensor x = T(a[0]); return V(reduce(x, dims(a.size() > 1 ? a[1] : Value(), x.rank()), a.size() > 2 && B(a[2]), SUM)); }},
        {"mean.dim", [](const Args& a) { const Tensor x = T(a[0]); return V(reduce(x, dims(a.size() > 1 ? a[1] : Value(), x.rank()), a.size() > 2 && B(a[2]), MEAN)); }},
        {"amax.default", [](const Args& a) { const Tensor x = T(a[0]); return V(reduce(x, dims(a.size() > 1 ? a[1] : Value(), x.rank()), a.size() > 2 && B(a[2]), MAX)); }},
        {"amin.default", [](const Args& a) { const Tensor x = T(a[0]); return V(reduce(x, dims(a.size() > 1 ? a[1] : Value(), x.rank()), a.size() > 2 && B(a[2]), MIN)); }},
        {"sum.default", [](const Args& a) { return V(reduce(T(a[0]), {}, false, SUM)); }},
        {"mean.default", [](const Args& a) { return V(reduce(T(a[0]), {}, false, MEAN)); }},
        {"max.default", [](const Args& a) { return V(reduce(T(a[0]), {}, false, MAX)); }},
        {"min.default", [](const Args& a) { return V(reduce(T(a[0]), {}, false, MIN)); }},
        {"prod.dim_int", [](const Args& a) { const Tensor x = T(a[0]); return V(reduce(x, dims(a[1], x.rank()), a.size() > 2 && B(a[2]), PROD)); }},
        {"any.dim", [](const Args& a) { const Tensor x = T(a[0]); return V(reduce(x, dims(a[1], x.rank()), a.size() > 2 && B(a[2]), ANY)); }},
        {"any.default", [](const Args& a) { return V(reduce(T(a[0]), {}, false, ANY)); }},
        {"max.dim", [](const Args& a) { return arg_reduce(T(a[0]), L(a[1]), a.size() > 2 && B(a[2]), true); }},
        {"min.dim", [](const Args& a) { return arg_reduce(T(a[0]), L(a[1]), a.size() > 2 && B(a[2]), false); }},
        {"argmax.default", [](const Args& a) {
             const Tensor x = T(a[0]);
             if (!has(a, 1)) return item(arg_reduce(reshaped(x, {x.numel()}), 0, false, true), 1);
             return item(arg_reduce(x, L(a[1]), a.size() > 2 && B(a[2]), true), 1);
         }},
        {"argmin.default", [](const Args& a) {
             const Tensor x = T(a[0]);
             if (!has(a, 1)) return item(arg_reduce(reshaped(x, {x.numel()}), 0, false, false), 1);
             return item(arg_reduce(x, L(a[1]), a.size() > 2 && B(a[2]), false), 1);
         }},
        {"var.correction", [](const Args& a) {
             const Tensor x = T(a[0]);
             return V(var(x, dims(a.size() > 1 ? a[1] : Value(), x.rank()), has(a, 2) ? N(a[2]) : 1, a.size() > 3 && B(a[3])));
         }},
        {"cumsum.default", [](const Args& a) { const Tensor x = T(a[0]); return V(cumsum(x, dim(L(a[1]), x.rank()))); }},
        // normalizations, softmax
        {"_softmax.default", [](const Args& a) { const Tensor x = T(a[0]); return V(softmax(x, dim(L(a[1]), x.rank()), false)); }},
        {"_log_softmax.default", [](const Args& a) { const Tensor x = T(a[0]); return V(softmax(x, dim(L(a[1]), x.rank()), true)); }},
        {"native_layer_norm.default", [](const Args& a) { return layer_norm(T(a[0]), ints(a[1]), a[2], a[3], N(a[4])); }},
        {"_native_batch_norm_legit_no_training.default", [](const Args& a) { return batch_norm(T(a[0]), a[1], a[2], T(a[3]), T(a[4]), N(a[6])); }},
        // gathering
        {"gather.default", [](const Args& a) { const Tensor x = T(a[0]); return V(gather(x, dim(L(a[1]), x.rank()), T(a[2]))); }},
        {"index_select.default", [](const Args& a) { const Tensor x = T(a[0]); return V(index_select(x, dim(L(a[1]), x.rank()), T(a[2]))); }},
        {"embedding.default", [](const Args& a) { return V(index_select(T(a[0]), 0, T(a[1]))); }},
        {"index.Tensor", [](const Args& a) { return V(index(T(a[0]), a[1].list)); }},
        {"topk.default", [](const Args& a) {
             const Tensor x = T(a[0]);
             const int d = dim(has(a, 2) ? L(a[2]) : -1, x.rank());
             return order(x, d, L(a[1]), a.size() <= 3 || a[3].none() || B(a[3]));
         }},
        {"sort.default", [](const Args& a) {
             const Tensor x = T(a[0]);
             const int d = dim(has(a, 1) ? L(a[1]) : -1, x.rank());
             return order(x, d, x.shape[d], a.size() > 2 && B(a[2]));
         }},
        // images
        {"convolution.default", [](const Args& a) {
             if (B(a[6])) throw GraphError("transposed convolutions");
             return V(conv(T(a[0]), T(a[1]), a[2], ints(a[3]), ints(a[4]), ints(a[5]), L(a[8])));
         }},
        {"max_pool2d_with_indices.default", [](const Args& a) {
             if (a.size() > 5 && B(a[5])) throw GraphError("pooling with ceil_mode");
             const Tensor y = pool(T(a[0]), ints(a[1]), has(a, 2) ? ints(a[2]) : Shape(), has(a, 3) ? ints(a[3]) : Shape{0},
                                   has(a, 4) ? ints(a[4]) : Shape{1}, true, true, 0);
             return Value::tuple({V(y), V(fresh({0}))});
         }},
        {"avg_pool2d.default", [](const Args& a) {
             if (a.size() > 4 && B(a[4])) throw GraphError("pooling with ceil_mode");
             const bool countPad = a.size() <= 5 || a[5].none() || B(a[5]);
             return V(pool(T(a[0]), ints(a[1]), has(a, 2) ? ints(a[2]) : Shape(), has(a, 3) ? ints(a[3]) : Shape{0}, Shape{1},
                           false, countPad, has(a, 6) ? N(a[6]) : 0));
         }},
        {"_adaptive_avg_pool2d.default", [](const Args& a) {
             const Shape o = ints(a[1]);
             if (o.size() != 2 || o[0] != 1 || o[1] != 1) throw GraphError("adaptive pooling to " + str(o));
             const Tensor x = T(a[0]);
             return V(reduce(x, {x.rank() - 2, x.rank() - 1}, true, MEAN));
         }},
    };
    return ops;
}

// ---------------------------------------------------------------------------
// The graph
// ---------------------------------------------------------------------------

/** What a run did: its rows, the chunks and threads it took, the memory it was held to, held at most, put in files. */
struct RunInfo {
    I rows = 0, chunk = 0, chunks = 0;
    int threads = 1;
    double memory = 0, peak = 0, spilled = 0;
};

class Graph {
public:
    explicit Graph(const std::string& nameOrPath) {
        std::string path = nameOrPath;
        if (path.find('/') == std::string::npos && path.find('\\') == std::string::npos && path.find(".spx") == std::string::npos)
            path = spx::bridge_folder() + "/models/" + path + ".sng.spx";
        file_.open(path);
        auto meta = file_.meta();
        if (meta["Kind"] != "sphere-graph") throw GraphError(path + " is not a graph exported by sphere_graph");
        name_ = meta["Name"];
        feeds_ = meta["Feeds"];
        framework_ = meta["Framework"];
        precision_ = meta.count("Precision") ? meta["Precision"] : "float64";
        rowsIndependent_ = meta["RowsIndependent"] == "1";
        const std::string ex = meta["ExampleShapes"];
        size_t at = 0;
        while (!ex.empty() && at <= ex.size()) {
            const size_t semi = ex.find(';', at);
            example_.push_back(shape(ex.substr(at, semi == std::string::npos ? std::string::npos : semi - at)));
            if (semi == std::string::npos) break;
            at = semi + 1;
        }
        parse(file_.text("graph"));
    }

    const std::string& name() const { return name_; }
    const std::string& feeds() const { return feeds_; }
    const std::string& framework() const { return framework_; }
    const std::string& precision() const { return precision_; }
    const std::vector<std::string>& inputs() const { return inputNames_; }
    size_t outputs() const { return outputSlots_.size(); }
    size_t nodes() const { return nodes_.size(); }
    bool rows_independent() const { return rowsIndependent_; }
    const std::vector<Shape>& example_shapes() const { return example_; }

    /** Values from this size up (1 MB) may go to temporary files, in folder ("": $SPHERE_GRAPH_TMP, else the system's). */
    void spill(I bytes, const std::string& folder = "") {
        spillAt_ = bytes;
        spillFolder_ = folder;
    }

    I weights() const {
        I n = 0;
        for (int s : constSlots_) n += consts_[s].t.numel();
        return n;
    }

    /** The outputs for these inputs (in the graph's order), held to what the machine spares now. */
    std::vector<Tensor> run(const std::vector<Tensor>& in) const { return run(in, budget(), 0, nullptr); }

    /** The outputs, the run held to memory bytes on up to threads threads (0: as many as the machine has). */
    std::vector<Tensor> run(const std::vector<Tensor>& in, double memory, int threads = 0, RunInfo* info = nullptr) const {
        const I n = rowsOf(in);
        std::vector<Tensor> outs;
        std::vector<double*> dest;
        if (rowsIndependent_ && planned()) {   // a plan only to chunk by rows
            for (size_t i = 0; i < outRow_.size(); ++i) {
                Shape s = {n};
                s.insert(s.end(), outRow_[i].begin(), outRow_[i].end());
                outs.push_back(fresh(s));
                dest.push_back(out(outs.back()));
            }
            runInto(in, n, dest, memory - outBytes(n), threads, info);
            return outs;
        }
        return whole(in, memory, info);
    }

    /** The largest batch whose run stays within memory bytes (its weights apart); -1 where the plan is not linear. */
    I batch_for(double memory) const {
        if (!planned() || !linear_) return -1;
        double best = std::numeric_limits<double>::infinity();
        for (size_t t = 0; t < base_.size(); ++t)
            if (slope_[t] > 0) best = std::min(best, std::floor((memory - base_[t]) / slope_[t]));
        return std::isinf(best) ? (I(1) << 40) : static_cast<I>(std::max(0.0, best));
    }

    /** The bytes one row holds at the peak of a run. */
    double per_row_peak() const {
        if (!planned()) return 0;
        double p = 0;
        for (size_t t = 0; t < base_.size(); ++t) p = std::max(p, base_[t] + slope_[t]);
        return p;
    }

    /** The largest difference between this header's outputs and numpy's, on the inputs of a reference sphere_graph wrote. */
    double check(const std::string& reference, double memory = -1, int threads = 0) const {
        spx::File f(reference);
        auto meta = f.meta();
        std::vector<Tensor> in;
        for (size_t i = 0; i < inputSlots_.size(); ++i) {
            const std::string k = std::to_string(i);
            in.push_back(Tensor::wrap(f.f64("in." + k), shape(meta["Shape.in." + k])));
        }
        const std::vector<Tensor> got = run(in, memory < 0 ? budget() : memory, threads);
        double worst = 0;
        for (size_t i = 0; i < got.size(); ++i) {
            I count = 0;
            const double* ref = f.f64("out." + std::to_string(i), &count);
            if (count != got[i].numel()) throw GraphError("output " + std::to_string(i) + " has " + std::to_string(got[i].numel())
                                                          + " values, the reference " + std::to_string(count));
            for (I k = 0; k < count; ++k) worst = std::max(worst, std::fabs(got[i].p[k] - ref[k]));
        }
        return worst;
    }

    /**
     * The graph over a dataset of any size: its rows read in place from the mapped file (sections named as the
     * graph's inputs -- x, mask, input2 -- or in.0, in.1; for a graph that feeds on particles, ':ml jets'
     * datasets' x, mask and hl; on high-level features, hl), the outputs written in place into the mapped file
     * of the result (out.0, out.1..., the labels y copied), never whole in memory.
     */
    RunInfo run_file(const std::string& input, const std::string& output, double memory = -1, int threads = 0) const {
        spx::File f(spx::resolve(input));
        auto meta = f.meta();
        std::vector<Tensor> in;
        for (size_t k = 0; k < inputSlots_.size(); ++k) in.push_back(datasetInput(f, meta, k));
        const I n = rowsOf(in);
        if (!planned()) throw GraphError(name_ + " has no example shapes to plan its memory: run it with run()");
        // the result's layout: meta, then each output, then the labels
        std::vector<std::pair<std::string, I>> sections;
        std::string text = "Kind: sphere-graph-output\nGraph: " + name_ + "\nRows: " + std::to_string(n) + "\nSource: " + input + "\n";
        for (size_t i = 0; i < outRow_.size(); ++i) {
            Shape s = {n};
            s.insert(s.end(), outRow_[i].begin(), outRow_[i].end());
            text += "Shape.out." + std::to_string(i) + ": " + csv(s) + "\n";
            sections.push_back({"out." + std::to_string(i), numel(s)});
        }
        const bool labels = f.has("y");
        if (labels) sections.push_back({"y", f.count("y")});
        Mapped dst(output, text, sections);
        std::vector<double*> dest;
        for (size_t i = 0; i < outRow_.size(); ++i) dest.push_back(dst.f64(i + 1));
        if (labels) {
            double* y = dst.f64(sections.size());
            const auto& s = f.sections().at("y");
            if (s.type == spx::I64) {
                const std::int64_t* v = f.i64("y");
                for (I k = 0; k < s.count; ++k) y[k] = static_cast<double>(v[k]);
            } else {
                const double* v = f.f64("y");
                std::copy(v, v + s.count, y);
            }
        }
        RunInfo info;
        if (rowsIndependent_) {
            runInto(in, n, dest, memory < 0 ? budget() : memory, threads, &info);
        } else {
            const std::vector<Tensor> got = whole(in, memory < 0 ? budget() : memory, &info);
            for (size_t i = 0; i < got.size(); ++i) std::copy(got[i].p, got[i].p + got[i].numel(), dest[i]);
        }
        dst.commit();
        return info;
    }

    std::string describe() const {
        std::map<std::string, int> ops;
        for (const Node& nd : nodes_) ++ops[nd.op];
        std::ostringstream o;
        o << name_ << ": " << nodes_.size() << " operations, " << weights() << " weights, inputs";
        for (size_t i = 0; i < inputNames_.size(); ++i) o << (i ? ", " : " ") << inputNames_[i];
        if (!feeds_.empty()) o << ", feeds " << feeds_;
        o << ", " << precision_ << ", from " << framework_;
        if (planned()) o << "; " << per_row_peak() / 1024 << " kB a row at its peak" << (linear_ ? "" : " (not linear)");
        return o.str();
    }

private:
    struct Node {
        std::string name, op;
        Op fn = nullptr;
        std::vector<Value> args;
        std::vector<int> refs;   // distinct
        int slot = -1;
    };

    static Shape shape(const std::string& s) {
        Shape out;
        std::stringstream ss(s);
        std::string tok;
        while (std::getline(ss, tok, ','))
            if (!tok.empty() && tok.find_first_not_of(" \t") != std::string::npos) out.push_back(std::stoll(tok));
        return out;
    }

    static std::string csv(const Shape& s) {
        std::string o;
        for (size_t i = 0; i < s.size(); ++i) o += (i ? "," : "") + std::to_string(s[i]);
        return o;
    }

    int slot(const std::string& name) {   // in the scope being read: the graph's, or a block's
        auto it = scope_->find(name);
        if (it != scope_->end()) return it->second;
        const int s = static_cast<int>(scope_->size());
        (*scope_)[name] = s;
        return s;
    }

    static bool control(const std::string& op) { return op == "sphere.while" || op == "sphere.case"; }

    Value token(const std::string& t, std::vector<int>& refs) {
        Value v;
        if (t == "none") return v;
        const std::string kind = t.substr(0, 1), body = t.size() > 2 ? t.substr(2) : "";
        if (kind == "n") {
            v.kind = Value::REF;
            v.slot = slot(body);
            refs.push_back(v.slot);
        } else if (kind == "i") {
            v.kind = Value::INT;
            v.i = std::stoll(body);
        } else if (kind == "f") {
            v.kind = Value::FLOAT;
            v.f = body == "inf" ? INFINITY : body == "-inf" ? -INFINITY : body == "nan" ? NAN : std::stod(body);
        } else if (kind == "b") {
            v.kind = Value::BOOL;
            v.b = body == "1";
        } else if (kind == "s") {
            v.kind = Value::STR;
            v.s = body;
        } else if (kind == "T" || kind == "L") {
            v.kind = Value::LIST;
            std::stringstream ss(body);
            std::string e;
            while (std::getline(ss, e, ',')) v.list.push_back(kind == "T" ? token("n:" + e, refs) : token(e, refs));
        } else {
            throw GraphError("a graph token " + t);
        }
        return v;
    }

    void parse(const std::string& text) {
        std::set<std::string> missing;
        std::stringstream lines(text);
        std::string line;
        std::vector<std::pair<int, Tensor>> consts;
        Block* current = nullptr;   // the block being read, between begin and end
        while (std::getline(lines, line)) {
            if (!line.empty() && line.back() == '\r') line.pop_back();
            if (line.empty() || line[0] == '#') continue;
            std::vector<std::string> p;
            size_t at = 0;
            while (true) {
                const size_t tab = line.find('\t', at);
                p.push_back(line.substr(at, tab == std::string::npos ? std::string::npos : tab - at));
                if (tab == std::string::npos) break;
                at = tab + 1;
            }
            if (p[0] == "begin") {
                current = &blocks_[p.at(1)];
                scope_ = &current->slots;
            } else if (p[0] == "end") {
                current = nullptr;
                scope_ = &slots_;
            } else if (p[0] == "input" && current) {
                current->inputs.push_back(slot(p.at(1)));
            } else if (p[0] == "output" && current) {
                current->outputs.push_back(slot(p.at(1)));
            } else if (p[0] == "input") {
                inputSlots_.push_back(slot(p.at(1)));
                inputNames_.push_back(p.at(2));
            } else if (p[0] == "const") {
                I count = 0;
                const double* w = file_.f64("w." + p.at(1), &count);
                const Shape s = shape(p.at(2));
                if (count != numel(s)) throw GraphError("weight " + p[1] + " has " + std::to_string(count) + " values for " + str(s));
                consts.push_back({slot(p[1]), Tensor::wrap(w, s)});
            } else if (p[0] == "node") {
                Node nd;
                nd.name = p.at(1);
                nd.op = p.at(2);
                auto it = operations().find(nd.op);
                if (it != operations().end()) nd.fn = it->second;
                else if (!control(nd.op)) missing.insert(nd.op);
                std::vector<int> refs;
                if (p.size() > 3 && !p[3].empty()) {
                    std::stringstream ss(p[3]);
                    std::string tok;
                    while (std::getline(ss, tok, ' '))
                        if (!tok.empty()) nd.args.push_back(token(tok, refs));
                }
                std::sort(refs.begin(), refs.end());
                refs.erase(std::unique(refs.begin(), refs.end()), refs.end());
                nd.refs = refs;
                nd.slot = slot(nd.name);
                (current ? current->nodes : nodes_).push_back(std::move(nd));
            } else if (p[0] == "output") {
                outputSlots_.push_back(slot(p.at(1)));
            } else {
                throw GraphError("a graph line of kind " + p[0]);
            }
        }
        if (!missing.empty()) {
            std::string m;
            for (const auto& s : missing) m += (m.empty() ? "" : ", ") + s;
            throw GraphError(name_ + " uses operations this header has not: " + m);
        }
        consts_.assign(slots_.size(), Value());
        isConst_.assign(slots_.size(), false);
        isOutput_.assign(slots_.size(), false);
        last_.assign(slots_.size(), -1);
        for (auto& c : consts) {
            consts_[c.first] = Value::of(c.second);
            isConst_[c.first] = true;
            constSlots_.push_back(c.first);
        }
        for (int o : outputSlots_) isOutput_[o] = true;
        for (size_t at = 0; at < nodes_.size(); ++at)
            for (int r : nodes_[at].refs) last_[r] = static_cast<int>(at);
        for (auto& kv : blocks_)   // a block's names for weights, bound to the weights' values when it runs
            for (const auto& s : kv.second.slots) {
                auto g = slots_.find(s.first);
                if (g != slots_.end() && g->second < static_cast<int>(isConst_.size()) && isConst_[g->second])
                    kv.second.consts.push_back({s.second, g->second});
            }
    }

    /** A block run on its inputs' values: its outputs. */
    std::vector<Value> runBlock(const std::string& name, const std::vector<Value>& vals) const {
        auto it = blocks_.find(name);
        if (it == blocks_.end()) throw GraphError(name_ + " has no block " + name);
        const Block& b = it->second;
        if (vals.size() != b.inputs.size())
            throw GraphError("block " + name + " takes " + std::to_string(b.inputs.size()) + " inputs, not " + std::to_string(vals.size()));
        std::vector<Value> env(b.slots.size());
        for (const auto& c : b.consts) env[c.first] = consts_[c.second];
        for (size_t i = 0; i < vals.size(); ++i) env[b.inputs[i]] = vals[i];
        Args a;
        for (const Node& nd : b.nodes) {
            a.clear();
            for (const Value& v : nd.args) a.push_back(resolve(v, env));
            try {
                env[nd.slot] = control(nd.op) ? controlOp(nd.op, a) : nd.fn(a);
            } catch (const GraphError&) {
                throw;
            } catch (const std::exception& e) {
                throw GraphError(nd.name + " (" + nd.op + "): " + e.what());
            }
        }
        std::vector<Value> out;
        for (int o : b.outputs) out.push_back(env[o]);
        return out;
    }

    static bool truth(const Value& v) {
        const Tensor t = T(v);
        if (t.numel() != 1) throw GraphError("a condition of " + std::to_string(t.numel()) + " values, not one");
        return t.p[0] != 0;
    }

    /** sphere.while runs its body while its condition holds; sphere.case the branch its index picks (the last if out of range). */
    Value controlOp(const std::string& op, const Args& a) const {
        if (op == "sphere.while") {
            const std::string cond = a.at(0).s, body = a.at(1).s;
            std::vector<Value> vals = a.at(2).list;
            const I limit = has(a, 3) ? L(a[3]) : I(10000000);
            for (I n = 0; truth(runBlock(cond, vals).at(0));) {
                vals = runBlock(body, vals);
                if (++n >= limit) throw GraphError("a loop past " + std::to_string(limit) + " iterations");
            }
            return Value::tuple(vals);
        }
        const I k = static_cast<I>(N(a.at(0)));
        const std::vector<Value>& br = a.at(1).list;
        if (br.empty()) throw GraphError("sphere.case without branches");
        const size_t pick = k >= 0 && k < static_cast<I>(br.size()) ? static_cast<size_t>(k) : br.size() - 1;
        return Value::tuple(runBlock(br[pick].s, a.at(2).list));
    }

    static Value resolve(const Value& a, const std::vector<Value>& env) {
        if (a.kind == Value::REF) return env[a.slot];
        if (a.kind == Value::LIST) {
            Value v;
            v.kind = Value::LIST;
            v.list.reserve(a.list.size());
            for (const Value& e : a.list) v.list.push_back(resolve(e, env));
            return v;
        }
        return a;
    }

    /** One pass over the nodes, each value freed after its last use; live gets the bytes held after every node. */
    std::vector<Tensor> execute(const std::vector<Tensor>& in, std::vector<double>* live, const std::shared_ptr<Arena>& arena) const {
        ArenaScope scope(arena);
        std::vector<Value> env = consts_;
        for (size_t i = 0; i < inputSlots_.size(); ++i) env[inputSlots_[i]] = Value::of(in[i]);
        Args a;
        for (size_t at = 0; at < nodes_.size(); ++at) {
            const Node& nd = nodes_[at];
            a.clear();
            for (const Value& v : nd.args) a.push_back(resolve(v, env));
            arena->mark = arena->live.load();
            try {
                env[nd.slot] = control(nd.op) ? controlOp(nd.op, a) : nd.fn(a);
            } catch (const GraphError&) {
                throw;
            } catch (const std::exception& e) {
                throw GraphError(nd.name + " (" + nd.op + "): " + e.what());
            }
            a.clear();
            // the most the node held -- its temporaries with its output -- not only what it leaves
            if (live) live->push_back(static_cast<double>(std::max<I>(arena->mark, arena->live.load())));
            for (int r : nd.refs)
                if (last_[r] == static_cast<int>(at) && !isOutput_[r] && !isConst_[r]) env[r] = Value();
        }
        std::vector<Tensor> outs;
        for (int o : outputSlots_) outs.push_back(T(env[o]));
        return outs;
    }

    I rowsOf(const std::vector<Tensor>& in) const {
        if (in.size() != inputSlots_.size()) {
            std::string names;
            for (const auto& s : inputNames_) names += (names.empty() ? "" : ", ") + s;
            throw GraphError(name_ + " takes " + std::to_string(inputSlots_.size()) + " inputs (" + names + "), not "
                             + std::to_string(in.size()));
        }
        I n = -1;
        for (size_t k = 0; k < in.size(); ++k) {
            if (k < example_.size() && in[k].rank() == static_cast<int>(example_[k].size()) && in[k].rank() > 0) {
                const Shape a(in[k].shape.begin() + 1, in[k].shape.end()), b(example_[k].begin() + 1, example_[k].end());
                if (a != b)
                    throw GraphError("input " + inputNames_[k] + ": " + name_ + " was traced on rows of " + str(b) + ", not " + str(a));
            }
            const I r = in[k].rank() > 0 ? in[k].shape[0] : 1;
            if (rowsIndependent_ && n >= 0 && r != n && in[k].rank() > 0)   // rows only where the graph has a batch
                throw GraphError("inputs of " + std::to_string(n) + " and " + std::to_string(r) + " rows");
            n = std::max(n, r);
        }
        return std::max<I>(n, 0);
    }

    bool planned() const {
        std::call_once(planOnce_, [this] {
            try {
                plan();
            } catch (const std::exception&) {   // the graph's sizes do not follow its first dimension: it runs whole
                base_.clear();
                slope_.clear();
                outRow_.clear();
            }
        });
        return !base_.empty();
    }

    /** The bytes held after each node as a_t + b_t B: measured on batches of 1 and 2 (inputs of the traced shapes), checked at 4. */
    void plan() const {
        if (example_.size() != inputSlots_.size() || example_.empty()) return;
        std::vector<double> live[3];
        const I batches[3] = {1, 2, 4};
        for (int k = 0; k < 3; ++k) {
            std::vector<Tensor> in;
            for (const Shape& s0 : example_) {
                Shape s = s0;
                if (!s.empty()) s[0] = batches[k];
                in.push_back(full(s, 1.0));
            }
            auto arena = std::make_shared<Arena>();
            const std::vector<Tensor> outs = execute(in, &live[k], arena);
            if (k == 0)
                for (const Tensor& o : outs) {
                    Shape row = o.rank() > 0 ? Shape(o.shape.begin() + 1, o.shape.end()) : Shape();
                    outRow_.push_back(row);
                }
        }
        const size_t n = live[0].size();
        base_.assign(n, 0);
        slope_.assign(n, 0);
        double predicted = 0, actual = 0;
        for (size_t t = 0; t < n; ++t) {
            slope_[t] = live[1][t] - live[0][t];
            base_[t] = live[0][t] - slope_[t];
            predicted = std::max(predicted, base_[t] + 4 * slope_[t]);
            actual = std::max(actual, live[2][t]);
        }
        linear_ = std::fabs(predicted - actual) <= 0.05 * std::max(actual, 1.0);
        if (n == 0) {
            base_.assign(1, 0);
            slope_.assign(1, 0);
        }
    }

    double outBytes(I n) const {
        double b = 0;
        for (const Shape& r : outRow_) b += 8.0 * static_cast<double>(n) * static_cast<double>(numel(r));
        return b;
    }

    std::shared_ptr<Arena> arena(double limit) const {
        auto a = std::make_shared<Arena>();
        a->limit = limit;
        a->spill_at = spillAt_;
        a->folder = spillFolder_;
        return a;
    }

    std::vector<Tensor> whole(const std::vector<Tensor>& in, double memory, RunInfo* info) const {
        auto arena = this->arena(memory);
        std::vector<Tensor> outs = execute(in, nullptr, arena);
        if (info) {
            info->rows = rowsOf(in);
            info->chunk = info->rows;
            info->chunks = 1;
            info->threads = 1;
            info->memory = memory;
            info->peak = static_cast<double>(arena->peak);
            info->spilled = static_cast<double>(arena->spilled);
        }
        return outs;
    }

    /** The rows in chunks the memory affords, on as many threads as it affords, the outputs written at dest. */
    void runInto(const std::vector<Tensor>& in, I n, const std::vector<double*>& dest, double memory, int threads, RunInfo* info) const {
        memory = std::max(memory, 0.0);
        int t = threads > 0 ? threads : static_cast<int>(std::max(1u, std::thread::hardware_concurrency()));
        t = static_cast<int>(std::max<I>(1, std::min<I>(t, n)));
        I c = 0;
        while (true) {
            c = batch_for(memory / t);
            if (c >= 1 || t == 1) break;
            t = std::max(1, t / 2);
        }
        if (c < 0) c = n;                     // a plan that is not linear: the batch whole, the arena spilling what will not fit
        const bool spill = c < 1;             // not even one row fits: rows one by one, values beyond the budget in files
        if (spill) c = 1;
        c = std::min(c, std::max<I>(1, (n + t - 1) / t));
        const I chunks = n == 0 ? 0 : (n + c - 1) / c;
        t = static_cast<int>(std::max<I>(1, std::min<I>(t, chunks)));
        std::vector<I> rowSize;
        for (const Shape& r : outRow_) rowSize.push_back(numel(r));
        std::atomic<I> next{0};
        std::vector<std::exception_ptr> errors(t);
        std::vector<double> peaks(t, 0), spilled(t, 0);
        auto work = [&](int me) {
            try {
                for (I k; (k = next++) < chunks;) {
                    const I lo = k * c, hi = std::min(n, lo + c);
                    std::vector<Tensor> part;
                    for (const Tensor& x : in) part.push_back(rows(x, lo, hi));
                    auto arena = this->arena(memory / t);
                    const std::vector<Tensor> got = execute(part, nullptr, arena);
                    for (size_t i = 0; i < got.size(); ++i) {
                        if (got[i].numel() != (hi - lo) * rowSize[i]) throw GraphError("output " + std::to_string(i) + " of a chunk is " + str(got[i].shape));
                        std::copy(got[i].p, got[i].p + got[i].numel(), dest[i] + lo * rowSize[i]);
                    }
                    peaks[me] = std::max(peaks[me], static_cast<double>(arena->peak));
                    spilled[me] += static_cast<double>(arena->spilled);
                }
            } catch (...) {
                errors[me] = std::current_exception();
                next = chunks;
            }
        };
        std::vector<std::thread> pool;
        for (int i = 1; i < t; ++i) {
            try {
                pool.emplace_back(work, i);
            } catch (const std::exception&) {
                break;   // no more threads to be had: the others share the work
            }
        }
        work(0);
        for (auto& th : pool) th.join();
        for (auto& e : errors)
            if (e) std::rethrow_exception(e);
        if (info) {
            info->rows = n;
            info->chunk = c;
            info->chunks = chunks;
            info->threads = static_cast<int>(pool.size()) + 1;
            info->memory = memory;
            info->peak = std::accumulate(peaks.begin(), peaks.end(), 0.0);
            info->spilled = std::accumulate(spilled.begin(), spilled.end(), 0.0);
        }
    }

    Tensor datasetInput(const spx::File& f, std::map<std::string, std::string>& meta, size_t k) const {
        std::vector<std::string> candidates = {inputNames_[k], "in." + std::to_string(k)};
        if (feeds_ == "high_level" && k == 0) candidates.push_back("hl");
        if (feeds_ == "particles" && k == 2) candidates.push_back("hl");
        for (const std::string& c : candidates) {
            if (!f.has(c)) continue;
            I count = 0;
            const double* v = f.f64(c, &count);
            Shape s;
            if (meta.count("Shape." + c)) {
                s = shape(meta["Shape." + c]);
            } else if (k < example_.size() && !example_[k].empty()) {
                Shape row(example_[k].begin() + 1, example_[k].end());
                const I per = numel(row);
                if (per == 0 || count % per != 0)
                    throw GraphError(f.path() + ": section " + c + " holds " + std::to_string(count) + " values, not rows of " + str(row));
                s = {count / per};
                s.insert(s.end(), row.begin(), row.end());
            } else {
                s = {count};
            }
            return Tensor::wrap(v, s);
        }
        throw GraphError(f.path() + " has no section for the input " + inputNames_[k] + " of " + name_);
    }

    /** An SPX file written in place: its layout made first, its sections mapped for the run to fill. */
    class Mapped {
    public:
        Mapped(const std::string& name, const std::string& meta, const std::vector<std::pair<std::string, I>>& f64s) {
            path_ = name.find('/') == std::string::npos && name.find('\\') == std::string::npos
                        ? spx::bridge_folder() + "/" + (name.size() > 4 && name.compare(name.size() - 4, 4, ".spx") == 0 ? name : name + ".spx")
                        : name;
            part_ = path_ + ".part";
            const I entries = 1 + static_cast<I>(f64s.size());
            auto aligned = [](I v) { return (v + 63) / 64 * 64; };
            I at = aligned(64 + 64 * entries);
            offsets_.push_back(at);
            at = aligned(at + static_cast<I>(meta.size()));
            for (const auto& s : f64s) {
                offsets_.push_back(at);
                at = aligned(at + 8 * s.second);
            }
            size_ = at;
            {
                std::ofstream o(part_, std::ios::binary | std::ios::trunc);
                if (!o) throw GraphError("cannot write " + part_);
                std::vector<char> head(static_cast<size_t>(aligned(64 + 64 * entries)), 0);
                std::memcpy(head.data(), "SPHRSPX1", 8);
                put32(head, 8, 1);
                put32(head, 12, 3);
                put32(head, 16, static_cast<std::int32_t>(entries));
                put64(head, 24, 64);
                put64(head, 32, size_);
                std::string title = "graph output";
                title.resize(24, ' ');
                std::memcpy(head.data() + 40, title.data(), 24);
                for (I k = 0; k < entries; ++k) {
                    std::string nm = k == 0 ? "meta" : f64s[k - 1].first;
                    nm.resize(24, ' ');
                    const size_t d = static_cast<size_t>(64 + 64 * k);
                    std::memcpy(head.data() + d, nm.data(), 24);
                    put32(head, d + 24, k == 0 ? spx::TEXT : spx::F64);
                    put64(head, d + 32, offsets_[k]);
                    put64(head, d + 40, k == 0 ? static_cast<I>(meta.size()) : f64s[k - 1].second);
                }
                o.write(head.data(), static_cast<std::streamsize>(head.size()));
                o.write(meta.data(), static_cast<std::streamsize>(meta.size()));
            }
            std::filesystem::resize_file(part_, static_cast<std::uintmax_t>(size_));
            map();
        }
        ~Mapped() {
            unmap();
            if (!committed_) {
                std::error_code ec;
                std::filesystem::remove(part_, ec);
            }
        }
        Mapped(const Mapped&) = delete;
        Mapped& operator=(const Mapped&) = delete;

        double* f64(size_t k) { return reinterpret_cast<double*>(data_ + offsets_[k]); }

        void commit() {
            unmap();
            std::error_code ec;
            std::filesystem::rename(part_, path_, ec);
            if (ec) {
                std::filesystem::remove(path_, ec);
                std::filesystem::rename(part_, path_);
            }
            committed_ = true;
        }

    private:
        static void put32(std::vector<char>& h, size_t at, std::int32_t v) { std::memcpy(h.data() + at, &v, 4); }
        static void put64(std::vector<char>& h, size_t at, std::int64_t v) { std::memcpy(h.data() + at, &v, 8); }

        void map() {
#if defined(_WIN32)
            file_ = CreateFileA(part_.c_str(), GENERIC_READ | GENERIC_WRITE, 0, nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
            if (file_ == INVALID_HANDLE_VALUE) throw GraphError("cannot open " + part_);
            view_ = CreateFileMappingA(file_, nullptr, PAGE_READWRITE, 0, 0, nullptr);
            if (!view_) throw GraphError("cannot map " + part_);
            data_ = static_cast<unsigned char*>(MapViewOfFile(view_, FILE_MAP_ALL_ACCESS, 0, 0, 0));
#else
            fd_ = ::open(part_.c_str(), O_RDWR);
            if (fd_ < 0) throw GraphError("cannot open " + part_);
            void* p = mmap(nullptr, static_cast<size_t>(size_), PROT_READ | PROT_WRITE, MAP_SHARED, fd_, 0);
            data_ = p == MAP_FAILED ? nullptr : static_cast<unsigned char*>(p);
#endif
            if (!data_) throw GraphError("cannot map " + part_);
        }
        void unmap() {
            if (!data_) return;
#if defined(_WIN32)
            FlushViewOfFile(data_, 0);
            UnmapViewOfFile(data_);
            CloseHandle(view_);
            CloseHandle(file_);
            view_ = nullptr;
            file_ = INVALID_HANDLE_VALUE;
#else
            msync(data_, static_cast<size_t>(size_), MS_SYNC);
            munmap(data_, static_cast<size_t>(size_));
            ::close(fd_);
            fd_ = -1;
#endif
            data_ = nullptr;
        }

        std::string path_, part_;
        std::vector<I> offsets_;
        I size_ = 0;
        unsigned char* data_ = nullptr;
        bool committed_ = false;
#if defined(_WIN32)
        HANDLE file_ = INVALID_HANDLE_VALUE, view_ = nullptr;
#else
        int fd_ = -1;
#endif
    };

    spx::File file_;
    std::string name_, feeds_, framework_, precision_;
    bool rowsIndependent_ = false;
    std::vector<Shape> example_;
    std::map<std::string, int> slots_;
    std::vector<int> inputSlots_, outputSlots_, constSlots_;
    std::vector<std::string> inputNames_;
    std::vector<Node> nodes_;
    std::vector<Value> consts_;
    std::vector<bool> isConst_, isOutput_;
    std::vector<int> last_;
    /** A block: a loop's condition or body, a branch -- its names its own, the weights' bound when it runs. */
    struct Block {
        std::vector<int> inputs, outputs;
        std::vector<Node> nodes;
        std::map<std::string, int> slots;
        std::vector<std::pair<int, int>> consts;   // a block's slot for a weight, and the weight's in the graph
    };
    std::map<std::string, Block> blocks_;
    std::map<std::string, int>* scope_ = &slots_;
    I spillAt_ = I(1) << 20;
    std::string spillFolder_;
    mutable std::once_flag planOnce_;
    mutable std::vector<double> base_, slope_;
    mutable std::vector<Shape> outRow_;
    mutable bool linear_ = false;
};

}  // namespace graph
}  // namespace sphere

#if defined(__clang__)
#pragma float_control(pop)
#elif defined(__GNUC__)
#pragma GCC pop_options
#elif defined(_MSC_VER)
#pragma float_control(pop)
#endif

#endif  // SPHERE_GRAPH_HPP
