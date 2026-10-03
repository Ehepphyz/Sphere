// sphere_spx.hpp -- Sphere Physics eXchange, the C++ and ROOT reader.
//
// One header, no library, C++17. Sphere writes PDF sets, event samples and
// jets into SPX files; this maps them and reads them in place. It is the same
// file Julia, Fortran and Python read, and the PDF interpolation below is the
// same arithmetic, in the same order, as Sphere's Java one and LHAPDF's
// log-bicubic: the answers agree to the bit, which ":bridge crosscheck" shows.
//
//   #include "sphere_spx.hpp"
//   auto pdf = spx::PDF::open("CT18NLO");          // $SPHERE_BRIDGE/CT18NLO.spx
//   double g = pdf.xfxQ2(21, 1e-3, 1e4);            // member 0
//   auto band = pdf.uncertainty(21, 1e-3, 1e4);    // Hessian or replicas
//   auto ev = spx::Events::open("events");         // particles, jets, weights
//   spx::publish("pt_spectrum", edges, counts);    // shows up in Sphere's Plots tab
//
// Under ROOT (Cling, or with SPHERE_SPX_ROOT defined) spx::root:: turns the
// same files into TH1D, TGraph and TTree objects.
//
// Format: see Spx.java in Sphere. Little-endian, 64-byte header, 64 bytes per
// directory entry, sections on 64-byte boundaries, types float64/int64/text.

#ifndef SPHERE_SPX_HPP
#define SPHERE_SPX_HPP

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <limits>
#include <map>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#if defined(_WIN32)
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#else
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#endif

namespace spx {

enum class Kind { PDF = 1, EVENTS = 2, TABLE = 3 };
enum { F64 = 1, I64 = 2, TEXT = 3 };

struct Section {
    std::string name;
    int type = 0;
    std::int64_t offset = 0;
    std::int64_t count = 0;
};

inline double now_seconds() {
    using namespace std::chrono;
    return duration<double>(steady_clock::now().time_since_epoch()).count();
}

/** Where Sphere keeps the files it hands over, from $SPHERE_BRIDGE. */
inline std::string bridge_folder() {
    const char* at = std::getenv("SPHERE_BRIDGE");
    return at ? std::string(at) : std::string("bridge");
}

/** A name, or a path: a bare name is looked for in the bridge folder. */
inline std::string resolve(const std::string& name) {
    if (name.find('/') != std::string::npos || name.find('\\') != std::string::npos) return name;
    if (name.size() > 4 && name.compare(name.size() - 4, 4, ".spx") == 0) return bridge_folder() + "/" + name;
    return bridge_folder() + "/" + name + ".spx";
}

// ---------------------------------------------------------------------------
// The mapped file
// ---------------------------------------------------------------------------

class File {
public:
    File() = default;
    explicit File(const std::string& path) { open(path); }

    void open(const std::string& path) {
        path_ = path;
        map_ = std::make_shared<Mapping>(path);
        const unsigned char* b = map_->data;
        if (map_->size < 64 || std::memcmp(b, "SPHRSPX1", 8) != 0)
            throw std::runtime_error(path + " is not an SPX file");
        if (i32(8) != 1) throw std::runtime_error(path + ": unsupported SPX version");
        kind_ = static_cast<Kind>(i32(12));
        const int n = i32(16);
        const std::int64_t dir = i64at(24);
        title_ = trim(std::string(reinterpret_cast<const char*>(b + 40), 24));
        for (int k = 0; k < n; ++k) {
            const std::int64_t at = dir + 64LL * k;
            Section s;
            s.name = trim(std::string(reinterpret_cast<const char*>(b + at), 24));
            s.type = i32(at + 24);
            s.offset = i64at(at + 32);
            s.count = i64at(at + 40);
            const std::int64_t bytes = s.type == TEXT ? s.count : s.count * 8;
            if (s.offset + bytes > static_cast<std::int64_t>(map_->size))
                throw std::runtime_error(path + ": section " + s.name + " runs past the end");
            sections_[s.name] = s;
        }
    }

    Kind kind() const { return kind_; }
    const std::string& title() const { return title_; }
    const std::string& path() const { return path_; }
    bool has(const std::string& name) const { return sections_.count(name) != 0; }
    const std::map<std::string, Section>& sections() const { return sections_; }

    std::int64_t count(const std::string& name) const { return section(name).count; }

    const double* f64(const std::string& name, std::int64_t* n = nullptr) const {
        const Section& s = typed(name, F64);
        if (n) *n = s.count;
        return reinterpret_cast<const double*>(map_->data + s.offset);
    }

    const std::int64_t* i64(const std::string& name, std::int64_t* n = nullptr) const {
        const Section& s = typed(name, I64);
        if (n) *n = s.count;
        return reinterpret_cast<const std::int64_t*>(map_->data + s.offset);
    }

    std::string text(const std::string& name) const {
        const Section& s = typed(name, TEXT);
        return std::string(reinterpret_cast<const char*>(map_->data + s.offset), static_cast<size_t>(s.count));
    }

    /** The "Key: value" lines of the meta section. */
    std::map<std::string, std::string> meta() const {
        std::map<std::string, std::string> out;
        if (!has("meta")) return out;
        const std::string all = text("meta");
        size_t start = 0;
        while (start < all.size()) {
            size_t end = all.find('\n', start);
            if (end == std::string::npos) end = all.size();
            const std::string line = all.substr(start, end - start);
            const size_t colon = line.find(':');
            if (colon != std::string::npos) out[trim(line.substr(0, colon))] = trim(line.substr(colon + 1));
            start = end + 1;
        }
        return out;
    }

private:
    // Owns the mapping; shared so that copies of a PDF or Events stay cheap.
    struct Mapping {
        const unsigned char* data = nullptr;
        size_t size = 0;
        std::vector<unsigned char> fallback;
#if defined(_WIN32)
        HANDLE file = INVALID_HANDLE_VALUE, view = nullptr;
#else
        int fd = -1;
#endif
        explicit Mapping(const std::string& path) {
#if defined(_WIN32)
            file = CreateFileA(path.c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_DELETE, nullptr,
                               OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
            if (file != INVALID_HANDLE_VALUE) {
                LARGE_INTEGER sz;
                if (GetFileSizeEx(file, &sz) && sz.QuadPart > 0) {
                    view = CreateFileMappingA(file, nullptr, PAGE_READONLY, 0, 0, nullptr);
                    if (view) {
                        data = static_cast<const unsigned char*>(MapViewOfFile(view, FILE_MAP_READ, 0, 0, 0));
                        size = static_cast<size_t>(sz.QuadPart);
                    }
                }
            }
#else
            fd = ::open(path.c_str(), O_RDONLY);
            if (fd >= 0) {
                struct stat st;
                if (fstat(fd, &st) == 0 && st.st_size > 0) {
                    void* p = mmap(nullptr, static_cast<size_t>(st.st_size), PROT_READ, MAP_SHARED, fd, 0);
                    if (p != MAP_FAILED) {
                        data = static_cast<const unsigned char*>(p);
                        size = static_cast<size_t>(st.st_size);
                    }
                }
            }
#endif
            if (!data) {  // No mapping to be had: read it whole instead.
                std::ifstream in(path, std::ios::binary);
                if (!in) throw std::runtime_error("cannot open " + path);
                fallback.assign(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
                data = fallback.data();
                size = fallback.size();
            }
        }
        ~Mapping() {
            if (!fallback.empty()) return;
#if defined(_WIN32)
            if (data) UnmapViewOfFile(data);
            if (view) CloseHandle(view);
            if (file != INVALID_HANDLE_VALUE) CloseHandle(file);
#else
            if (data) munmap(const_cast<unsigned char*>(data), size);
            if (fd >= 0) ::close(fd);
#endif
        }
        Mapping(const Mapping&) = delete;
        Mapping& operator=(const Mapping&) = delete;
    };

    static std::string trim(const std::string& s) {
        const size_t a = s.find_first_not_of(" \t\r\n");
        if (a == std::string::npos) return "";
        const size_t b = s.find_last_not_of(" \t\r\n");
        return s.substr(a, b - a + 1);
    }
    int i32(std::int64_t at) const {
        std::int32_t v;
        std::memcpy(&v, map_->data + at, 4);
        return v;
    }
    std::int64_t i64at(std::int64_t at) const {
        std::int64_t v;
        std::memcpy(&v, map_->data + at, 8);
        return v;
    }
    const Section& section(const std::string& name) const {
        auto it = sections_.find(name);
        if (it == sections_.end()) throw std::runtime_error(path_ + " has no section " + name);
        return it->second;
    }
    const Section& typed(const std::string& name, int type) const {
        const Section& s = section(name);
        if (s.type != type) throw std::runtime_error("section " + name + " has another type");
        return s;
    }

    std::string path_, title_;
    Kind kind_ = Kind::TABLE;
    std::map<std::string, Section> sections_;
    std::shared_ptr<Mapping> map_;
};

// ---------------------------------------------------------------------------
// Parton distributions
// ---------------------------------------------------------------------------

struct Uncertainty {
    double central = 0, errplus = 0, errminus = 0, errsymm = 0;
};

class PDF {
public:
    PDF() = default;
    explicit PDF(const File& f) { load(f); }

    /** A set Sphere exported, by name or by path. */
    static PDF open(const std::string& name) { return PDF(File(resolve(name))); }

    int members() const { return static_cast<int>(nm_); }
    std::string name() const { return meta_.count("SetName") ? meta_.at("SetName") : file_.title(); }
    std::string errorType() const { return meta_.count("ErrorType") ? meta_.at("ErrorType") : "none"; }
    double errorConfLevel() const {
        return meta_.count("ErrorConfLevel") ? std::atof(meta_.at("ErrorConfLevel").c_str()) : 68.268949;
    }
    const std::map<std::string, std::string>& meta() const { return meta_; }
    double xMin() const { return xs_[0]; }
    double xMax() const { return xs_[nx_ - 1]; }
    double q2Min() const { return q2s_[0]; }
    double q2Max() const { return q2s_[nq_ - 1]; }
    std::vector<int> flavors() const {
        std::vector<int> out(pids_, pids_ + nf_);
        return out;
    }
    bool hasFlavor(int pid) const { return flavor(pid) >= 0; }

    bool inRangeXQ2(double x, double q2) const {
        return x >= xs_[0] && x <= xs_[nx_ - 1] && q2 >= q2s_[0] && q2 <= q2s_[nq_ - 1];
    }

    /** x f(x, Q^2) of one member; 0 for a flavour the set does not carry. */
    double xfxQ2(int pid, double x, double q2, int member = 0) const {
        const int f = flavor(pid);
        if (f < 0 || member < 0 || member >= static_cast<int>(nm_)) return 0.0;
        if (x <= 0.0 || q2 <= 0.0) return 0.0;
        const double* v = xf_ + static_cast<std::int64_t>(member) * nx_ * nq_ * nf_;
        if (inRangeXQ2(x, q2)) return interpolate(v, f, x, q2);
        return continuation(v, f, x, q2);
    }
    double xfxQ(int pid, double x, double q, int member = 0) const { return xfxQ2(pid, x, q * q, member); }

    /** Every flavour from -6 to 6 at once, LHAPDF's xfxQ2(x, Q2, vector) order: index pid+6, gluon at 6. */
    std::vector<double> xfxQ2All(double x, double q2, int member = 0) const {
        std::vector<double> out(13);
        for (int p = -6; p <= 6; ++p) out[p + 6] = xfxQ2(p == 0 ? 21 : p, x, q2, member);
        return out;
    }

    std::vector<double> memberValues(int pid, double x, double q2) const {
        std::vector<double> out(nm_);
        for (std::int64_t m = 0; m < nm_; ++m) out[m] = xfxQ2(pid, x, q2, static_cast<int>(m));
        return out;
    }

    /** LHAPDF's PDFSet::uncertainty at the set's own confidence level. */
    Uncertainty uncertainty(int pid, double x, double q2) const { return uncertaintyOf(memberValues(pid, x, q2)); }

    Uncertainty uncertaintyOf(const std::vector<double>& v) const {
        Uncertainty u;
        const size_t n = v.size();
        u.central = n ? v[0] : 0.0;
        const std::string type = errorType();
        if (n < 2 || type == "none") return u;
        if (type == "replicas") {
            const double nrep = static_cast<double>(n - 1);
            double mean = 0, mean2 = 0;
            for (size_t m = 1; m < n; ++m) {
                mean += v[m];
                mean2 += v[m] * v[m];
            }
            mean /= nrep;
            mean2 /= nrep;
            u.central = mean;
            u.errsymm = std::sqrt(std::max(0.0, nrep / (nrep - 1.0) * (mean2 - mean * mean)));
            u.errplus = u.errminus = u.errsymm;
        } else if (type == "symmhessian") {
            double s = 0;
            for (size_t m = 1; m < n; ++m) s += (v[m] - u.central) * (v[m] - u.central);
            u.errsymm = u.errplus = u.errminus = std::sqrt(s);
        } else {
            double up = 0, down = 0, symm = 0;
            for (size_t m = 1; m + 1 < n; m += 2) {
                const double a = v[m] - u.central, b = v[m + 1] - u.central;
                const double rise = std::max(std::max(a, b), 0.0), fall = std::max(std::max(-a, -b), 0.0);
                up += rise * rise;
                down += fall * fall;
                symm += (v[m] - v[m + 1]) * (v[m] - v[m + 1]);
            }
            u.errplus = std::sqrt(up);
            u.errminus = std::sqrt(down);
            u.errsymm = 0.5 * std::sqrt(symm);
        }
        return u;
    }

    bool hasAlphaS() const { return na_ > 0; }

    /** alpha_s(Q^2), interpolated from the table the set carries, the LHAPDF way. */
    double alphasQ2(double q2) const {
        if (na_ == 0 || q2 < 0.0) return std::numeric_limits<double>::quiet_NaN();
        const double* q = asq2_;
        const double* a = asval_;
        if (q2 < q[0]) {
            std::int64_t next = 1;
            while (next < na_ && q[0] == q[next]) ++next;
            if (next >= na_ || a[0] <= 0.0 || a[next] <= 0.0) return a[0];
            const double slope = std::log10(a[next] / a[0]) / std::log10(q[next] / q[0]);
            return a[0] * std::pow(q2 / q[0], slope);
        }
        if (q2 > q[na_ - 1]) {
            if (asHold_ || na_ < 3) return a[na_ - 1];
            const double lo = a[na_ - 2], hi = a[na_ - 1];
            if (lo <= 0.0 || hi <= 0.0 || q[na_ - 1] == q[na_ - 2]) return hi;
            const double slope = std::log(hi / lo) / std::log(q[na_ - 1] / q[na_ - 2]);
            return hi * std::pow(q2 / q[na_ - 1], slope);
        }
        // The piece whose first knot is the last one at or below q2.
        size_t p = 0;
        while (p + 1 < pieces_.size() && q[pieces_[p + 1]] <= q2) ++p;
        const std::int64_t s = pieces_[p];
        const std::int64_t len = (p + 1 < pieces_.size() ? pieces_[p + 1] : na_) - s;
        const double* pq = q + s;
        const double* pl = aslog_ + s;
        const double* pa = a + s;
        const std::int64_t i = below(q2, pq, len);
        auto forward = [&](std::int64_t k) { return (pa[k + 1] - pa[k]) / (pl[k + 1] - pl[k]); };
        auto backward = [&](std::int64_t k) { return (pa[k] - pa[k - 1]) / (pl[k] - pl[k - 1]); };
        auto central = [&](std::int64_t k) { return 0.5 * (forward(k) + backward(k)); };
        double sl, sh;
        if (len == 2) {
            sl = sh = forward(0);
        } else if (i == 0) {
            sl = forward(i);
            sh = central(i + 1);
        } else if (i == len - 2) {
            sl = central(i);
            sh = backward(i + 1);
        } else {
            sl = central(i);
            sh = central(i + 1);
        }
        const double dlog = pl[i + 1] - pl[i];
        const double t = (std::log(q2) - pl[i]) / dlog;
        const double out = hermite(t, pa[i], sl * dlog, pa[i + 1], sh * dlog);
        return std::fabs(out) < 2.0 ? out : std::numeric_limits<double>::max();
    }
    double alphasQ(double q) const { return alphasQ2(q * q); }

    const File& file() const { return file_; }

private:
    void load(const File& f) {
        file_ = f;
        if (f.kind() != Kind::PDF) throw std::runtime_error(f.path() + " does not hold a PDF set");
        meta_ = f.meta();
        const std::int64_t* shape = f.i64("shape");
        nf_ = shape[0];
        nq_ = shape[1];
        nx_ = shape[2];
        nm_ = shape[3];
        pids_ = f.i64("pids");
        xs_ = f.f64("xknots");
        q2s_ = f.f64("q2knots");
        logx_ = f.f64("logx");
        logq2_ = f.f64("logq2");
        xf_ = f.f64("xf");
        weighted_ = meta_.count("Accuracy") && meta_.at("Accuracy") == "weighted";
        std::fill(std::begin(lookup_), std::end(lookup_), -1);
        for (std::int64_t k = 0; k < nf_; ++k) {
            const std::int64_t p = pids_[k];
            if (p >= -6 && p <= 6) lookup_[p + 6] = static_cast<int>(k);
            if (p == 21) lookup_[13] = static_cast<int>(k);
            if (p == 22) lookup_[14] = static_cast<int>(k);
        }
        if (f.has("as_q2")) {
            asq2_ = f.f64("as_q2", &na_);
            aslog_ = f.f64("as_logq2");
            asval_ = f.f64("as_val");
            asHold_ = meta_.count("AlphaS_Above") && meta_.at("AlphaS_Above") == "hold";
            pieces_.push_back(0);
            for (std::int64_t k = 1; k < na_; ++k)
                if (std::fabs(asq2_[k] - asq2_[k - 1]) < 2.220446049250313e-16) pieces_.push_back(k);
        }
    }

    int flavor(int pid) const {
        if (pid == 0 || pid == 21) return lookup_[13];
        if (pid == 22) return lookup_[14];
        if (pid >= -6 && pid <= 6) return lookup_[pid + 6];
        return -1;
    }

    static std::int64_t below(double value, const double* knots, std::int64_t n) {
        std::int64_t low = 0, high = n;
        while (low < high) {
            const std::int64_t mid = (low + high) >> 1;
            if (knots[mid] <= value) low = mid + 1;
            else high = mid;
        }
        if (low >= n) low = n - 1;
        return low == 0 ? 0 : low - 1;
    }

    double at(const double* v, std::int64_t ix, std::int64_t iq, int f) const {
        return v[(ix * nq_ + iq) * nf_ + f];
    }

    double slope(const double* v, std::int64_t ix, std::int64_t iq, int f) const {
        const double* axis = logx_;
        if (ix != 0 && ix != nx_ - 1) {
            const double back = axis[ix] - axis[ix - 1];
            const double ahead = axis[ix + 1] - axis[ix];
            const double left = (at(v, ix, iq, f) - at(v, ix - 1, iq, f)) / back;
            const double right = (at(v, ix + 1, iq, f) - at(v, ix, iq, f)) / ahead;
            if (weighted_) return (ahead * left + back * right) / (back + ahead);
            return (left + right) / 2.0;
        }
        if (ix == 0) return (at(v, 1, iq, f) - at(v, 0, iq, f)) / (axis[1] - axis[0]);
        return (at(v, nx_ - 1, iq, f) - at(v, nx_ - 2, iq, f)) / (axis[nx_ - 1] - axis[nx_ - 2]);
    }

    // The cubic in log x between knots ix and ix+1 on one Q row: the
    // coefficients Java computes once, computed here on demand, same order.
    double alongX(const double* v, std::int64_t ix, std::int64_t iq, int f, double t) const {
        const double dlogx = logx_[ix + 1] - logx_[ix];
        const double vl = at(v, ix, iq, f);
        const double vh = at(v, ix + 1, iq, f);
        const double vdl = slope(v, ix, iq, f) * dlogx;
        const double vdh = slope(v, ix + 1, iq, f) * dlogx;
        const double c0 = vdh + vdl - 2.0 * vh + 2.0 * vl;
        const double c1 = 3.0 * vh - 3.0 * vl - 2.0 * vdl - vdh;
        const double c2 = vdl;
        const double c3 = vl;
        const double t2 = t * t;
        return c0 * t2 * t + c1 * t2 + c2 * t + c3;
    }

    static double hermite(double t, double vl, double vdl, double vh, double vdh) {
        const double t2 = t * t;
        const double t3 = t * t2;
        return (2 * t3 - 3 * t2 + 1) * vl + (t3 - 2 * t2 + t) * vdl + (-2 * t3 + 3 * t2) * vh + (t3 - t2) * vdh;
    }

    static double linear(double x, double xl, double xh, double yl, double yh) {
        return yl + (x - xl) / (xh - xl) * (yh - yl);
    }

    double interpolate(const double* v, int f, double x, double q2) const {
        const std::int64_t ix = below(x, xs_, nx_);
        const std::int64_t iq = below(q2, q2s_, nq_);
        const double lx = std::log(x);
        const double lq = std::log(q2);
        const bool lowSeam = (iq == 0) || (q2s_[iq] == q2s_[iq - 1]);
        const bool highSeam = (iq + 1 == nq_ - 1) || (q2s_[iq + 1] == q2s_[iq + 2]);
        const double dlogx = logx_[ix + 1] - logx_[ix];
        const double tx = (lx - logx_[ix]) / dlogx;
        const double dlogq1 = logq2_[iq + 1] - logq2_[iq];
        const double tq = (lq - logq2_[iq]) / dlogq1;
        if (lowSeam && highSeam) {
            const double fl = linear(lx, logx_[ix], logx_[ix + 1], at(v, ix, iq, f), at(v, ix + 1, iq, f));
            const double fh = linear(lx, logx_[ix], logx_[ix + 1], at(v, ix, iq + 1, f), at(v, ix + 1, iq + 1, f));
            return linear(lq, logq2_[iq], logq2_[iq + 1], fl, fh);
        }
        const double vl = alongX(v, ix, iq, f, tx);
        const double vh = alongX(v, ix, iq + 1, f, tx);
        double vdl, vdh;
        if (lowSeam) {
            vdl = vh - vl;
            const double vhh = alongX(v, ix, iq + 2, f, tx);
            const double dlogq2 = 1.0 / (logq2_[iq + 2] - logq2_[iq + 1]);
            vdh = (vdl + (vhh - vh) * dlogq1 * dlogq2) * 0.5;
        } else if (highSeam) {
            vdh = vh - vl;
            const double vll = alongX(v, ix, iq - 1, f, tx);
            const double dlogq0 = 1.0 / (logq2_[iq] - logq2_[iq - 1]);
            vdl = (vdh + (vl - vll) * dlogq1 * dlogq0) * 0.5;
        } else {
            const double vll = alongX(v, ix, iq - 1, f, tx);
            const double dlogq0 = 1.0 / (logq2_[iq] - logq2_[iq - 1]);
            vdl = ((vh - vl) + (vl - vll) * dlogq1 * dlogq0) * 0.5;
            const double vhh = alongX(v, ix, iq + 2, f, tx);
            const double dlogq2 = 1.0 / (logq2_[iq + 2] - logq2_[iq + 1]);
            vdh = ((vh - vl) + (vhh - vh) * dlogq1 * dlogq2) * 0.5;
        }
        return hermite(tq, vl, vdl, vh, vdh);
    }

    static double alongLog(double x, double xa, double xb, double fa, double fb) {
        const double t = (std::log(x) - std::log(xa)) / (std::log(xb) - std::log(xa));
        if (fa > 1e-3 && fb > 1e-3) return std::exp(std::log(fa) + t * (std::log(fb) - std::log(fa)));
        return fa + t * (fb - fa);
    }

    // LHAPDF's continuation outside the grid (the MSTW rule below Q min).
    double continuation(const double* v, int f, double x, double q2) const {
        const double xMin = xs_[0], xMin1 = xs_[1], xMax = xs_[nx_ - 1];
        const double q2Min = q2s_[0], q2Max1 = q2s_[nq_ - 2], q2Max = q2s_[nq_ - 1];
        auto I = [&](double xx, double qq) { return interpolate(v, f, xx, qq); };
        auto alongXq = [&](double xx, double qq) { return alongLog(xx, xMin, xMin1, I(xMin, qq), I(xMin1, qq)); };
        if (x > xMax) return 0.0;  // LHAPDF refuses; a momentum fraction above one carries nothing.
        if (x < xMin && q2 >= q2Min && q2 <= q2Max) return alongXq(x, q2);
        if (x >= xMin && q2 > q2Max) return alongLog(q2, q2Max, q2Max1, I(x, q2Max), I(x, q2Max1));
        if (x < xMin && q2 > q2Max) {
            const double atMin = alongLog(q2, q2Max, q2Max1, I(xMin, q2Max), I(xMin, q2Max1));
            const double atMin1 = alongLog(q2, q2Max, q2Max1, I(xMin1, q2Max), I(xMin1, q2Max1));
            return alongLog(x, xMin, xMin1, atMin, atMin1);
        }
        if (q2 < q2Min) {
            double atEdge, justAbove;
            if (x < xMin) {
                atEdge = alongXq(x, q2Min);
                justAbove = alongXq(x, 1.01 * q2Min);
            } else {
                atEdge = I(x, q2Min);
                justAbove = I(x, 1.01 * q2Min);
            }
            const double anomalous =
                std::fabs(atEdge) >= 1e-5 ? std::max(-2.5, (justAbove - atEdge) / atEdge / 0.01) : 1.0;
            const double ratio = q2 / q2Min;
            return atEdge * std::pow(ratio, anomalous * ratio + 1.0 - ratio);
        }
        return I(x, q2);
    }

    File file_;
    std::map<std::string, std::string> meta_;
    std::int64_t nf_ = 0, nq_ = 0, nx_ = 0, nm_ = 0, na_ = 0;
    const std::int64_t* pids_ = nullptr;
    const double *xs_ = nullptr, *q2s_ = nullptr, *logx_ = nullptr, *logq2_ = nullptr, *xf_ = nullptr;
    const double *asq2_ = nullptr, *aslog_ = nullptr, *asval_ = nullptr;
    bool weighted_ = false, asHold_ = false;
    int lookup_[15];
    std::vector<std::int64_t> pieces_;
};

/** One member, with LHAPDF's method names, for code written against LHAPDF::PDF. */
class Member {
public:
    Member(std::shared_ptr<const PDF> set, int member) : set_(std::move(set)), m_(member) {}
    double xfxQ2(int pid, double x, double q2) const { return set_->xfxQ2(pid, x, q2, m_); }
    double xfxQ(int pid, double x, double q) const { return set_->xfxQ(pid, x, q, m_); }
    double alphasQ2(double q2) const { return set_->alphasQ2(q2); }
    double alphasQ(double q) const { return set_->alphasQ(q); }
    bool inRangeXQ2(double x, double q2) const { return set_->inRangeXQ2(x, q2); }
    int memberID() const { return m_; }
    const PDF& set() const { return *set_; }

private:
    std::shared_ptr<const PDF> set_;
    int m_;
};

/** LHAPDF::mkPDF("CT18NLO/3") or mkPDF("CT18NLO", 3), on a set Sphere exported. */
inline Member mkPDF(const std::string& name, int member = -1) {
    std::string set = name;
    if (member < 0) {
        member = 0;
        const size_t slash = name.rfind('/');
        if (slash != std::string::npos && slash + 1 < name.size() &&
            name.find_first_not_of("0123456789", slash + 1) == std::string::npos) {
            member = std::atoi(name.c_str() + slash + 1);
            set = name.substr(0, slash);
        }
    }
    return Member(std::make_shared<PDF>(PDF::open(set)), member);
}

// ---------------------------------------------------------------------------
// Events and jets
// ---------------------------------------------------------------------------

struct FourMomentum {
    double px, py, pz, E;
    double pt() const { return std::sqrt(px * px + py * py); }
    double m() const {
        const double m2 = E * E - px * px - py * py - pz * pz;
        return m2 < 0 ? -std::sqrt(-m2) : std::sqrt(m2);
    }
    double rap() const { return 0.5 * std::log((E + pz) / (E - pz)); }
    double phi() const {
        const double p = std::atan2(py, px);
        return p < 0 ? p + 2 * 3.14159265358979323846 : p;
    }
};

struct Incoming {
    int id1, id2;
    double x1, x2, scale;
};

class Events {
public:
    Events() = default;
    explicit Events(const File& f) : file_(f) {
        if (f.kind() != Kind::EVENTS) throw std::runtime_error(f.path() + " does not hold events");
        offset_ = f.i64("evt_offset", &n_);
        n_ -= 1;
        p4_ = f.f64("p4");
        pdg_ = f.i64("pdg");
        weight_ = f.f64("weight");
        if (f.has("incoming")) incoming_ = f.f64("incoming");
        if (f.has("jet_offset")) {
            jetOffset_ = f.i64("jet_offset");
            jetP4_ = f.f64("jet_p4");
            jetOf_ = f.i64("jet_of");
        }
    }
    static Events open(const std::string& name) { return Events(File(resolve(name))); }

    std::int64_t size() const { return n_; }
    std::int64_t particles(std::int64_t e) const { return offset_[e + 1] - offset_[e]; }
    FourMomentum particle(std::int64_t e, std::int64_t i) const {
        const double* p = p4_ + 4 * (offset_[e] + i);
        return {p[0], p[1], p[2], p[3]};
    }
    std::int64_t pdg(std::int64_t e, std::int64_t i) const { return pdg_[offset_[e] + i]; }
    double weight(std::int64_t e) const { return weight_[e]; }

    bool hasIncoming() const { return incoming_ != nullptr; }
    Incoming incoming(std::int64_t e) const {
        const double* v = incoming_ + 5 * e;
        return {static_cast<int>(v[0]), static_cast<int>(v[1]), v[2], v[3], v[4]};
    }

    bool hasJets() const { return jetOffset_ != nullptr; }
    std::int64_t jets(std::int64_t e) const { return jetOffset_[e + 1] - jetOffset_[e]; }
    FourMomentum jet(std::int64_t e, std::int64_t k) const {
        const double* p = jetP4_ + 4 * (jetOffset_[e] + k);
        return {p[0], p[1], p[2], p[3]};
    }
    /** The jet a particle ended up in, -1 when none. */
    std::int64_t jetOf(std::int64_t e, std::int64_t i) const { return jetOf_[offset_[e] + i]; }

    const File& file() const { return file_; }

private:
    File file_;
    std::int64_t n_ = 0;
    const std::int64_t *offset_ = nullptr, *pdg_ = nullptr, *jetOffset_ = nullptr, *jetOf_ = nullptr;
    const double *p4_ = nullptr, *weight_ = nullptr, *incoming_ = nullptr, *jetP4_ = nullptr;
};

// ---------------------------------------------------------------------------
// Writing results back
// ---------------------------------------------------------------------------

/** A table of named columns, written as SPX for Sphere (or any engine) to read. */
class Table {
public:
    explicit Table(std::string title = "") : title_(std::move(title)) {}

    Table& meta(const std::string& key, const std::string& value) {
        meta_ += key + ": " + value + "\n";
        return *this;
    }
    Table& column(const std::string& name, const std::vector<double>& values) {
        sections_.push_back({name, F64, values, {}, {}});
        return *this;
    }
    Table& column(const std::string& name, const std::vector<std::int64_t>& values) {
        sections_.push_back({name, I64, {}, values, {}});
        return *this;
    }
    /** A histogram Sphere draws: edges (n+1), values (n), and a band when given. */
    Table& histogram(const std::vector<double>& edges, const std::vector<double>& values,
                     const std::vector<double>& errPlus = {}, const std::vector<double>& errMinus = {}) {
        meta("Kind", "histogram");
        column("edges", edges);
        column("values", values);
        if (!errPlus.empty()) column("err_plus", errPlus);
        if (!errMinus.empty()) column("err_minus", errMinus);
        return *this;
    }

    void write(const std::string& path) const {
        std::vector<Entry> all;
        if (!meta_.empty() || !title_.empty()) all.push_back({"meta", TEXT, {}, {}, "Title: " + title_ + "\n" + meta_});
        all.insert(all.end(), sections_.begin(), sections_.end());
        auto aligned = [](std::int64_t v) { return (v + 63) / 64 * 64; };
        std::vector<std::int64_t> offsets;
        std::int64_t at = aligned(64 + 64 * static_cast<std::int64_t>(all.size()));
        for (const Entry& e : all) {
            offsets.push_back(at);
            at = aligned(at + e.bytes());
        }
        std::vector<unsigned char> out(static_cast<size_t>(at), 0);
        auto put32 = [&](std::int64_t p, std::int32_t v) { std::memcpy(&out[p], &v, 4); };
        auto put64 = [&](std::int64_t p, std::int64_t v) { std::memcpy(&out[p], &v, 8); };
        auto putName = [&](std::int64_t p, const std::string& s) {
            std::memset(&out[p], ' ', 24);
            std::memcpy(&out[p], s.data(), std::min<size_t>(24, s.size()));
        };
        std::memcpy(&out[0], "SPHRSPX1", 8);
        put32(8, 1);
        put32(12, static_cast<std::int32_t>(Kind::TABLE));
        put32(16, static_cast<std::int32_t>(all.size()));
        put64(24, 64);
        put64(32, at);
        putName(40, title_);
        for (size_t k = 0; k < all.size(); ++k) {
            const Entry& e = all[k];
            const std::int64_t d = 64 + 64 * static_cast<std::int64_t>(k);
            putName(d, e.name);
            put32(d + 24, e.type);
            put64(d + 32, offsets[k]);
            put64(d + 40, e.count());
            if (e.type == F64 && !e.f.empty()) std::memcpy(&out[offsets[k]], e.f.data(), e.f.size() * 8);
            if (e.type == I64 && !e.i.empty()) std::memcpy(&out[offsets[k]], e.i.data(), e.i.size() * 8);
            if (e.type == TEXT && !e.t.empty()) std::memcpy(&out[offsets[k]], e.t.data(), e.t.size());
        }
        const std::string part = path + ".part";
        {
            std::ofstream file(part, std::ios::binary | std::ios::trunc);
            if (!file) throw std::runtime_error("cannot write " + part);
            file.write(reinterpret_cast<const char*>(out.data()), static_cast<std::streamsize>(out.size()));
        }
        std::remove(path.c_str());
        if (std::rename(part.c_str(), path.c_str()) != 0) throw std::runtime_error("cannot move " + part);
    }

private:
    struct Entry {
        std::string name;
        int type;
        std::vector<double> f;
        std::vector<std::int64_t> i;
        std::string t;
        std::int64_t count() const {
            return type == F64 ? static_cast<std::int64_t>(f.size())
                 : type == I64 ? static_cast<std::int64_t>(i.size()) : static_cast<std::int64_t>(t.size());
        }
        std::int64_t bytes() const { return type == TEXT ? count() : 8 * count(); }
    };
    std::string title_, meta_;
    std::vector<Entry> sections_;
};

/** Hands a histogram to Sphere: it appears in the Plots tab by itself. */
inline std::string publish(const std::string& name, const std::vector<double>& edges,
                           const std::vector<double>& values, const std::vector<double>& errPlus = {},
                           const std::vector<double>& errMinus = {}, const std::string& xLabel = "") {
    Table t(name);
    t.meta("XLabel", xLabel.empty() ? name : xLabel).meta("Engine", "C++");
    t.histogram(edges, values, errPlus, errMinus);
    const std::string path = bridge_folder() + "/outbox/" + name + ".spx";
    t.write(path);
    return path;
}

// ---------------------------------------------------------------------------
// The cross-check Sphere runs on every engine
// ---------------------------------------------------------------------------

/**
 * Evaluates the points Sphere chose and writes the answers back, with the
 * time one evaluation took. Called by the small program Sphere compiles; the
 * same function exists in every reader.
 */
inline void crosscheck(const std::string& pdfPath, const std::string& pointsPath, const std::string& outPath,
                       const std::string& engine = "C++") {
    const PDF pdf{File(pdfPath)};
    const File points(pointsPath);
    std::int64_t n = 0, na = 0;
    const double* pid = points.f64("pid", &n);
    const double* x = points.f64("x");
    const double* q2 = points.f64("q2");
    const double* member = points.f64("member");
    const double* aq2 = points.f64("as_q2", &na);
    std::vector<double> xf(static_cast<size_t>(n)), as(static_cast<size_t>(na));
    for (std::int64_t k = 0; k < n; ++k)
        xf[k] = pdf.xfxQ2(static_cast<int>(pid[k]), x[k], q2[k], static_cast<int>(member[k]));
    for (std::int64_t k = 0; k < na; ++k) as[k] = pdf.alphasQ2(aq2[k]);
    // Timed separately, over enough rounds to be longer than the clock's grain.
    int rounds = 0;
    double sink = 0;
    const double start = now_seconds();
    double elapsed = 0;
    do {
        for (std::int64_t k = 0; k < n; ++k)
            sink += pdf.xfxQ2(static_cast<int>(pid[k]), x[k], q2[k], static_cast<int>(member[k]));
        ++rounds;
        elapsed = now_seconds() - start;
    } while (elapsed < 0.2 && rounds < 1000);
    Table t("crosscheck");
    t.meta("Engine", engine).meta("Rounds", std::to_string(rounds)).meta("Sink", std::to_string(sink));
    t.column("xf", xf).column("as", as).column("ns_per_eval", std::vector<double>{elapsed * 1e9 / (static_cast<double>(n) * rounds)});
    t.write(outPath);
}

}  // namespace spx

// ---------------------------------------------------------------------------
// ROOT
// ---------------------------------------------------------------------------

#if defined(__CLING__) || defined(__ROOTCLING__) || defined(SPHERE_SPX_ROOT)
#include "TGraph.h"
#include "TGraphAsymmErrors.h"
#include "TH1D.h"
#include "TTree.h"

namespace spx {
namespace root {

/** A histogram an engine published, as a TH1D; the band goes in the bin errors (symmetrised). */
inline TH1D* histogram(const std::string& fileOrName, const char* name = nullptr) {
    const File f(resolve(fileOrName));
    std::int64_t ne = 0, nv = 0;
    const double* edges = f.f64("edges", &ne);
    const double* values = f.f64("values", &nv);
    const double* up = f.has("err_plus") ? f.f64("err_plus") : nullptr;
    const double* down = f.has("err_minus") ? f.f64("err_minus") : nullptr;
    const std::string title = f.title();
    TH1D* h = new TH1D(name ? name : title.c_str(), title.c_str(), static_cast<int>(nv), edges);
    for (std::int64_t k = 0; k < nv; ++k) {
        h->SetBinContent(static_cast<int>(k + 1), values[k]);
        if (up) h->SetBinError(static_cast<int>(k + 1), 0.5 * (up[k] + (down ? down[k] : up[k])));
    }
    return h;
}

/** The same with the asymmetric band kept, as ROOT draws a PDF uncertainty. */
inline TGraphAsymmErrors* band(const std::string& fileOrName) {
    const File f(resolve(fileOrName));
    std::int64_t nv = 0;
    const double* edges = f.f64("edges");
    const double* values = f.f64("values", &nv);
    const double* up = f.has("err_plus") ? f.f64("err_plus") : nullptr;
    const double* down = f.has("err_minus") ? f.f64("err_minus") : up;
    auto* g = new TGraphAsymmErrors(static_cast<int>(nv));
    for (std::int64_t k = 0; k < nv; ++k) {
        const double c = 0.5 * (edges[k] + edges[k + 1]);
        const double w = 0.5 * (edges[k + 1] - edges[k]);
        g->SetPoint(static_cast<int>(k), c, values[k]);
        g->SetPointError(static_cast<int>(k), w, w, down ? down[k] : 0.0, up ? up[k] : 0.0);
    }
    g->SetTitle(f.title().c_str());
    return g;
}

/** x f(x, Q) across x at one Q, from the central member. */
inline TGraph* pdfCurve(const PDF& pdf, int pid, double q, int points = 200) {
    auto* g = new TGraph(points);
    const double lo = std::log(pdf.xMin()), hi = std::log(pdf.xMax());
    for (int k = 0; k < points; ++k) {
        const double x = std::exp(lo + (hi - lo) * k / (points - 1.0));
        g->SetPoint(k, x, pdf.xfxQ(pid, x, q));
    }
    g->SetTitle((pdf.name() + ";x;xf(x,Q)").c_str());
    return g;
}

/** An event sample as a tree of vectors, one entry per event, jets included. */
inline TTree* eventsTree(const Events& ev, const char* name = "events") {
    auto* tree = new TTree(name, "Sphere events");
    std::vector<double> px, py, pz, e, jpt, jrap, jphi, jm;
    std::vector<int> pdg, jetOf;
    double weight = 0;
    tree->Branch("px", &px);
    tree->Branch("py", &py);
    tree->Branch("pz", &pz);
    tree->Branch("E", &e);
    tree->Branch("pdg", &pdg);
    tree->Branch("weight", &weight);
    if (ev.hasJets()) {
        tree->Branch("jet_pt", &jpt);
        tree->Branch("jet_rap", &jrap);
        tree->Branch("jet_phi", &jphi);
        tree->Branch("jet_m", &jm);
        tree->Branch("jet_of", &jetOf);
    }
    for (std::int64_t k = 0; k < ev.size(); ++k) {
        px.clear(); py.clear(); pz.clear(); e.clear(); pdg.clear(); jetOf.clear();
        jpt.clear(); jrap.clear(); jphi.clear(); jm.clear();
        for (std::int64_t i = 0; i < ev.particles(k); ++i) {
            const FourMomentum p = ev.particle(k, i);
            px.push_back(p.px); py.push_back(p.py); pz.push_back(p.pz); e.push_back(p.E);
            pdg.push_back(static_cast<int>(ev.pdg(k, i)));
            if (ev.hasJets()) jetOf.push_back(static_cast<int>(ev.jetOf(k, i)));
        }
        if (ev.hasJets()) {
            for (std::int64_t j = 0; j < ev.jets(k); ++j) {
                const FourMomentum p = ev.jet(k, j);
                jpt.push_back(p.pt()); jrap.push_back(p.rap()); jphi.push_back(p.phi()); jm.push_back(p.m());
            }
        }
        weight = ev.weight(k);
        tree->Fill();
    }
    tree->ResetBranchAddresses();
    return tree;
}

/**
 * A table an engine wrote (':fjco bridge', ':fjco export x.spx') as a flat TTree: one
 * branch per float64 (/D) or int64 (/L) column of the full length, one entry per row.
 */
inline TTree* tableTree(const std::string& fileOrName, const char* name = nullptr) {
    const File f(resolve(fileOrName));
    std::int64_t rows = 0;
    for (const auto& kv : f.sections())
        if (kv.second.type != TEXT && kv.second.count > rows) rows = kv.second.count;
    std::vector<std::string> names;
    std::vector<int> types;
    for (const auto& kv : f.sections()) {
        if (kv.second.type == TEXT || kv.second.count != rows) continue;
        names.push_back(kv.first);
        types.push_back(kv.second.type);
    }
    auto* tree = new TTree(name ? name : f.title().c_str(), f.title().c_str());
    std::vector<double> d(names.size());
    std::vector<Long64_t> l(names.size());
    for (std::size_t k = 0; k < names.size(); ++k) {
        if (types[k] == F64) tree->Branch(names[k].c_str(), &d[k], (names[k] + "/D").c_str());
        else tree->Branch(names[k].c_str(), &l[k], (names[k] + "/L").c_str());
    }
    for (std::int64_t r = 0; r < rows; ++r) {
        for (std::size_t k = 0; k < names.size(); ++k) {
            if (types[k] == F64) d[k] = f.f64(names[k])[r];
            else l[k] = static_cast<Long64_t>(f.i64(names[k])[r]);
        }
        tree->Fill();
    }
    tree->ResetBranchAddresses();
    return tree;
}

}  // namespace root
}  // namespace spx
#endif

#endif  // SPHERE_SPX_HPP
