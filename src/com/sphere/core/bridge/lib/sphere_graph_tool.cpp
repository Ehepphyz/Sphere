// sphere_graph_tool.cpp
//
// Sphere's C++ engine for graphs: sphere_graph.hpp, compiled once by Sphere (':torch check --cpp', ':torch run
// --engine c++') with the flags the machine's free memory allows, and kept until the header changes. It answers
// in lines of "key value", which Sphere reads.
//
//     sphere_graph_tool check <graph.sng.spx> <reference.graphref.spx>
//         its outputs against numpy's on the reference's inputs; against itself in chunks and on threads
//     sphere_graph_tool run <graph.sng.spx> <dataset.spx> <out.spx> [memory MB] [threads]
//         the dataset streamed through the graph, file to file

#include "sphere_graph.hpp"

#include <chrono>
#include <cstdio>
#include <cstdlib>

using namespace sphere::graph;

static double gap(const std::vector<Tensor>& a, const std::vector<Tensor>& b) {
    double d = 0;
    for (size_t i = 0; i < a.size(); ++i) {
        if (a[i].numel() != b[i].numel()) return INFINITY;
        for (I k = 0; k < a[i].numel(); ++k) d = std::max(d, std::fabs(a[i].p[k] - b[i].p[k]));
    }
    return d;
}

static Shape shapeOf(const std::string& s) {
    Shape out;
    std::stringstream ss(s);
    std::string t;
    while (std::getline(ss, t, ','))
        if (!t.empty()) out.push_back(std::stoll(t));
    return out;
}

static int check(const std::string& graph, const std::string& reference) {
    Graph g(graph);
    spx::File f(reference);
    auto meta = f.meta();
    std::vector<Tensor> in;
    for (size_t i = 0; i < g.inputs().size(); ++i) {
        const std::string k = std::to_string(i);
        in.push_back(Tensor::wrap(f.f64("in." + k), shapeOf(meta["Shape.in." + k])));
    }
    const auto t0 = std::chrono::steady_clock::now();
    RunInfo a, b, c;
    const auto whole = g.run(in, INFINITY, 1, &a);
    const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    double vs = 0;
    for (size_t i = 0; i < whole.size(); ++i) {
        I count = 0;
        const double* want = f.f64("out." + std::to_string(i), &count);
        if (count != whole[i].numel()) {
            vs = INFINITY;
            break;
        }
        for (I k = 0; k < count; ++k) vs = std::max(vs, std::fabs(whole[i].p[k] - want[k]));
    }
    const auto chunked = g.run(in, 3.5 * g.per_row_peak(), 1, &b);
    const auto threaded = g.run(in, INFINITY, 4, &c);
    std::printf("vs_numpy %.17g\nmillis %.6g\nchunked %.17g\nchunk %lld\nthreaded %.17g\nthreads %d\nper_row %.17g\n"
                "rows_independent %d\nnodes %zu\n",
                vs, ms, gap(whole, chunked), static_cast<long long>(b.chunk), gap(whole, threaded), c.threads, g.per_row_peak(),
                g.rows_independent() ? 1 : 0, g.nodes());
    return 0;
}

static int run(const std::string& graph, const std::string& data, const std::string& out, double memory, int threads) {
    Graph g(graph);
    const auto t0 = std::chrono::steady_clock::now();
    const RunInfo r = g.run_file(data, out, memory, threads);
    const double s = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    std::printf("rows %lld\nchunk %lld\nchunks %lld\nthreads %d\nmemory %.17g\npeak %.17g\nspilled %.17g\nseconds %.6g\n",
                static_cast<long long>(r.rows), static_cast<long long>(r.chunk), static_cast<long long>(r.chunks), r.threads,
                r.memory, r.peak, r.spilled, s);
    return 0;
}

int main(int argc, char** argv) {
    try {
        const std::string what = argc > 1 ? argv[1] : "";
        if (what == "check" && argc >= 4) return check(argv[2], argv[3]);
        if (what == "run" && argc >= 5)
            return run(argv[2], argv[3], argv[4], argc > 5 ? std::atof(argv[5]) * 1048576.0 : -1.0, argc > 6 ? std::atoi(argv[6]) : 0);
        std::fprintf(stderr, "usage: sphere_graph_tool check <graph> <reference> | run <graph> <dataset> <out> [memory MB] [threads]\n");
        return 2;
    } catch (const std::exception& e) {
        std::fprintf(stderr, "%s\n", e.what());
        return 1;
    }
}
