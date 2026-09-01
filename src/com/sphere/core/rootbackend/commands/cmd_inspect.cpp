// commands/cmd_inspect.cpp

#include "cmd_inspect.h"
#include "command_registry.h"
#include "lockfree_ring.h"
#include "RootBatchLoader.h"

// RNTuple's C++ API moved between 6.34 and 6.36 and will move again. The deep
// pass is compiled only when the header is there, and every accessor below is
// feature-detected rather than assumed. Build with -DSPHERE_NO_RNTUPLE_DETAIL to
// drop it entirely; the key-level report stays.
#if !defined(SPHERE_NO_RNTUPLE_DETAIL) && __has_include(<ROOT/RNTupleReader.hxx>)
#include <ROOT/RNTuple.hxx>
#include <ROOT/RNTupleReader.hxx>
#define SPHERE_RNTUPLE_DETAIL 1
#else
#define SPHERE_RNTUPLE_DETAIL 0
#endif

#include <TBranch.h>
#include <TDatime.h>
#include <TDirectory.h>
#include <TFile.h>
#include <TKey.h>
#include <TLeaf.h>
#include <TList.h>
#include <TObjArray.h>
#include <TTree.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <iomanip>
#include <iostream>
#include <memory>
#include <set>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

namespace Sphere::cmd::inspect {

namespace {

// The gap the loader tolerates before it splits one read in two.
constexpr std::uint64_t kMaxGapBytes = 64 * 1024;
// Below this a basket costs more in seeks than it carries in data.
constexpr std::uint64_t kSmallBasketBytes = 32 * 1024;
constexpr int kTopBranches = 5;
constexpr int kMaxKeysPerDirectory = 200;

enum class Severity { Note, Warning, Error };

struct Finding {
  Severity severity;
  std::string code;
  std::string subject;
  std::string detail;
};

struct BranchStat {
  std::string name;
  std::string type;
  std::uint64_t zip_bytes{0};
  std::uint64_t tot_bytes{0};
  std::uint64_t baskets{0};
};

struct NTupleStat {
  std::string path;
  std::string class_name;
  std::uint64_t entries{0};
  std::uint64_t fields{0};
  std::uint64_t clusters{0};
  std::uint64_t pages{0};
  std::uint64_t page_bytes{0};
  std::string format_version;   // read from the anchor, whoever wrote the file
  bool detailed{false};
  bool unreadable{false};
};

struct TreeStat {
  std::string path;
  std::uint64_t entries{0};
  std::uint64_t zip_bytes{0};
  std::uint64_t tot_bytes{0};
  int branch_count{0};
  std::uint64_t basket_count{0};
  std::uint64_t basket_bytes{0};
  std::uint64_t physical_reads{0};
  std::vector<BranchStat> branches;
};

const char *severity_name(Severity severity) {
  switch (severity) {
  case Severity::Error:
    return "ERROR";
  case Severity::Warning:
    return "WARNING";
  case Severity::Note:
  default:
    return "NOTE";
  }
}

std::string escape_json(std::string_view input) {
  std::string out;
  out.reserve(input.size() + 8);
  for (const char c : input) {
    switch (c) {
    case '"':
      out += "\\\"";
      break;
    case '\\':
      out += "\\\\";
      break;
    case '\n':
      out += "\\n";
      break;
    case '\r':
      out += "\\r";
      break;
    case '\t':
      out += "\\t";
      break;
    default:
      if (static_cast<unsigned char>(c) < 0x20) {
        char buffer[7];
        std::snprintf(buffer, sizeof(buffer), "\\u%04x", c);
        out += buffer;
      } else {
        out += c;
      }
      break;
    }
  }
  return out;
}

std::string human_bytes(std::uint64_t bytes) {
  static const char *units[] = {"B", "KiB", "MiB", "GiB", "TiB"};
  double value = static_cast<double>(bytes);
  int unit = 0;
  while (value >= 1024.0 && unit < 4) {
    value /= 1024.0;
    ++unit;
  }
  std::ostringstream out;
  out << std::fixed << std::setprecision(unit == 0 ? 0 : 1) << value << ' '
      << units[unit];
  return out.str();
}

double compression_factor(const TreeStat &tree) {
  return (tree.zip_bytes > 0)
             ? static_cast<double>(tree.tot_bytes) /
                   static_cast<double>(tree.zip_bytes)
             : 0.0;
}

/**
* Counts the reads the loader would actually issue: baskets closer than
* kMaxGapBytes are merged into one.
*/
void measure_baskets(TTree *tree, const char *branch_name, TreeStat &stat,
                     BranchStat &branch_stat) {
  auto chunks = IO::inspect_branch_chunks(tree, branch_name);
  if (chunks.empty()) {
    return;
  }

  branch_stat.baskets = chunks.size();
  stat.basket_count += chunks.size();

  std::uint64_t end_of_previous = 0;
  bool first = true;
  for (const auto &chunk : chunks) {
    stat.basket_bytes += chunk.compressed_bytes;
    if (first || chunk.file_offset > end_of_previous + kMaxGapBytes) {
      ++stat.physical_reads;
    }
    end_of_previous = chunk.file_offset + chunk.compressed_bytes;
    first = false;
  }
}

#if SPHERE_RNTUPLE_DETAIL
// Each accessor is asked for, not assumed: the checks live in templates so a
// missing member is a false answer instead of a compile error.

/// Some releases hand back the descriptor itself, others a guard around it.
template <typename T> const auto &unwrap_descriptor(const T &value) {
  if constexpr (requires { value.GetRef(); }) {
    return value.GetRef();
  } else {
    return value;
  }
}

template <typename T> std::uint64_t entries_of(const T &source) {
  if constexpr (requires { source.GetNEntries(); }) {
    return static_cast<std::uint64_t>(source.GetNEntries());
  } else {
    return 0;
  }
}

template <typename T> std::uint64_t fields_of(const T &source) {
  if constexpr (requires { source.GetNFields(); }) {
    return static_cast<std::uint64_t>(source.GetNFields());
  } else {
    return 0;
  }
}

template <typename T> std::uint64_t clusters_of(const T &source) {
  if constexpr (requires { source.GetNClusters(); }) {
    return static_cast<std::uint64_t>(source.GetNClusters());
  } else {
    return 0;
  }
}

/// Page locators, through the helper the batch loader already uses.
template <typename D> void pages_of(const D &descriptor, NTupleStat &stat) {
  if constexpr (requires { descriptor.GetTopLevelFields(); }) {
    for (const auto &field : descriptor.GetTopLevelFields()) {
      if constexpr (requires { field.GetFieldName(); }) {
        auto pages = IO::inspect_field_pages(descriptor, field.GetFieldName());
        stat.pages += pages.size();
        for (const auto &page : pages) {
          stat.page_bytes += page.compressed_bytes;
        }
      }
    }
  }
}

/// The on-disk format version lives in the anchor, not in the reader: a file
/// written years ago still answers even when the reader refuses to open it.
template <typename A> std::string format_version_of(const A &anchor) {
  if constexpr (requires { anchor.GetVersionEpoch(); anchor.GetVersionMajor();
                           anchor.GetVersionMinor(); anchor.GetVersionPatch(); }) {
    return std::to_string(anchor.GetVersionEpoch()) + "." +
           std::to_string(anchor.GetVersionMajor()) + "." +
           std::to_string(anchor.GetVersionMinor()) + "." +
           std::to_string(anchor.GetVersionPatch());
  } else if constexpr (requires { anchor.GetVersionEpoch(); }) {
    return "epoch " + std::to_string(anchor.GetVersionEpoch());
  } else {
    return {};
  }
}

template <typename R> void read_with(R &reader, NTupleStat &stat) {
  stat.entries = entries_of(reader);

  auto &&handle = reader.GetDescriptor();
  const auto &descriptor = unwrap_descriptor(handle);

  if (const std::uint64_t counted = entries_of(descriptor); counted > 0) {
    stat.entries = counted;
  }
  stat.fields = fields_of(descriptor);
  stat.clusters = clusters_of(descriptor);
  pages_of(descriptor, stat);
  stat.detailed = true;
}

void read_ntuple(const std::string &file_path, const std::string &name,
                 NTupleStat &stat) {
  try {
    auto reader = RNTupleNS::RNTupleReader::Open(name, file_path);
    if (!reader) {
      stat.unreadable = true;
      return;
    }
    read_with(*reader, stat);
  } catch (const std::exception &) {
    stat.unreadable = true;
  } catch (...) {
    stat.unreadable = true;
  }
}
#endif

void collect_tree(TTree *tree, const std::string &path,
                  std::vector<TreeStat> &trees) {
  TreeStat stat;
  stat.path = path;
  stat.entries = static_cast<std::uint64_t>(std::max<Long64_t>(0, tree->GetEntries()));
  stat.zip_bytes = static_cast<std::uint64_t>(std::max<Long64_t>(0, tree->GetZipBytes()));
  stat.tot_bytes = static_cast<std::uint64_t>(std::max<Long64_t>(0, tree->GetTotBytes()));

  TObjArray *branches = tree->GetListOfBranches();
  stat.branch_count = (branches != nullptr) ? branches->GetEntriesFast() : 0;

  for (int i = 0; i < stat.branch_count; ++i) {
    auto *branch = dynamic_cast<TBranch *>(branches->At(i));
    if (branch == nullptr) {
      continue;
    }

    BranchStat branch_stat;
    branch_stat.name = branch->GetName();
    branch_stat.zip_bytes =
        static_cast<std::uint64_t>(std::max<Long64_t>(0, branch->GetZipBytes()));
    branch_stat.tot_bytes =
        static_cast<std::uint64_t>(std::max<Long64_t>(0, branch->GetTotBytes()));

    TObjArray *leaves = branch->GetListOfLeaves();
    if (leaves != nullptr && leaves->GetEntriesFast() > 0) {
      if (auto *leaf = dynamic_cast<TLeaf *>(leaves->At(0)); leaf != nullptr) {
        branch_stat.type = leaf->GetTypeName();
      }
    }

    measure_baskets(tree, branch_stat.name.c_str(), stat, branch_stat);
    stat.branches.push_back(std::move(branch_stat));
  }

  std::sort(stat.branches.begin(), stat.branches.end(),
            [](const BranchStat &a, const BranchStat &b) {
              return a.zip_bytes > b.zip_bytes;
            });

  trees.push_back(std::move(stat));
}

struct DirectoryEntry {
  std::string name;
  std::string class_name;
  short cycle{0};
  int bytes{0};
  std::string date;
  int depth{0};
};

void walk(TDirectory *dir, const std::string &file_path,
          const std::string &path, int depth,
          std::vector<DirectoryEntry> &entries, std::vector<TreeStat> &trees,
          std::vector<NTupleStat> &ntuples, std::vector<Finding> &findings,
          bool &saw_ttree) {
  TList *keys = dir->GetListOfKeys();
  if (keys == nullptr) {
    return;
  }

  std::set<std::string> seen;
  int visited = 0;

  TIter next(keys);
  while (TObject *object = next()) {
    auto *key = dynamic_cast<TKey *>(object);
    if (key == nullptr) {
      continue;
    }
    if (++visited > kMaxKeysPerDirectory) {
      findings.push_back({Severity::Note, "TOO_MANY_KEYS", path,
                          "more than " + std::to_string(kMaxKeysPerDirectory) +
                              " keys, the listing was cut short"});
      break;
    }

    const std::string name = key->GetName();
    const std::string cls = key->GetClassName();
    const std::string full = path.empty() ? name : path + "/" + name;

    if (!seen.insert(name).second) {
      findings.push_back({Severity::Warning, "DUPLICATE_CYCLE", full,
                          "several cycles of the same name; a read without an "
                          "explicit cycle takes the newest and the others stay "
                          "on disk"});
    }

    DirectoryEntry entry;
    entry.name = name;
    entry.class_name = cls;
    entry.cycle = key->GetCycle();
    entry.bytes = key->GetNbytes();
    entry.date = key->GetDatime().AsSQLString();
    entry.depth = depth;
    entries.push_back(std::move(entry));

    if (cls == "TDirectoryFile" || cls == "TDirectory") {
      if (auto *sub = dynamic_cast<TDirectory *>(key->ReadObj());
          sub != nullptr) {
        walk(sub, file_path, full, depth + 1, entries, trees, ntuples, findings,
             saw_ttree);
      }
      continue;
    }

    if (cls == "TTree" || cls == "TNtuple" || cls == "TNtupleD" ||
        cls == "TChain") {
      if (auto *tree = dynamic_cast<TTree *>(key->ReadObj()); tree != nullptr) {
        saw_ttree = true;
        collect_tree(tree, full, trees);
      }
      continue;
    }

    if (cls.find("RNTuple") != std::string::npos) {
      NTupleStat ntuple;
      ntuple.path = full;
      ntuple.class_name = cls;
#if SPHERE_RNTUPLE_DETAIL
      if (auto *anchor = dynamic_cast<RNTupleNS::RNTuple *>(key->ReadObj());
          anchor != nullptr) {
        ntuple.format_version = format_version_of(*anchor);
      }
      read_ntuple(file_path, name, ntuple);
#endif
      ntuples.push_back(std::move(ntuple));
    }
  }
}

void judge_ntuples(const std::vector<NTupleStat> &ntuples,
                   std::vector<Finding> &findings) {
  for (const auto &ntuple : ntuples) {
    if (ntuple.class_name.find("Experimental") != std::string::npos) {
      findings.push_back({Severity::Warning, "RNTUPLE_EXPERIMENTAL", ntuple.path,
                          "written as " + ntuple.class_name +
                              "; ROOT 6.36 and later moved RNTuple out of "
                              "Experimental and may refuse this anchor"});
    }
    if (ntuple.unreadable) {
      findings.push_back(
          {Severity::Error, "RNTUPLE_UNREADABLE", ntuple.path,
           std::string("the anchor is there") +
               (ntuple.format_version.empty()
                    ? std::string()
                    : " and says format " + ntuple.format_version) +
               ", but this ROOT's reader refused it; the binary format changed "
               "before it froze at 6.36, so a file written earlier needs the "
               "ROOT that wrote it, or a conversion"});
      continue;
    }
    if (!ntuple.detailed) {
      findings.push_back({Severity::Note, "RNTUPLE_NO_DETAIL", ntuple.path,
                          "this build reports the anchor only; rebuild against "
                          "a ROOT that ships RNTupleReader for entries, fields "
                          "and pages"});
      continue;
    }
    if (ntuple.entries == 0) {
      findings.push_back({Severity::Error, "EMPTY_NTUPLE", ntuple.path,
                          "no entries; the job that wrote it produced nothing"});
    }
    if (ntuple.pages > 0) {
      const std::uint64_t average = ntuple.page_bytes / ntuple.pages;
      if (average < kSmallBasketBytes) {
        findings.push_back({Severity::Warning, "SMALL_PAGES", ntuple.path,
                            "average page " + human_bytes(average) +
                                "; the same seek cost a TTree pays with small "
                                "baskets"});
      }
    }
  }
}

void judge(const std::vector<TreeStat> &trees, std::vector<Finding> &findings) {
  for (const auto &tree : trees) {
    if (tree.entries == 0) {
      findings.push_back({Severity::Error, "EMPTY_TREE", tree.path,
                          "no entries; the job that wrote it produced nothing"});
    }
    if (tree.branch_count == 0) {
      findings.push_back({Severity::Error, "NO_BRANCH", tree.path,
                          "no branch at all"});
      continue;
    }

    const double factor = compression_factor(tree);
    if (tree.zip_bytes > 0 && factor < 1.05) {
      findings.push_back({Severity::Warning, "NOT_COMPRESSED", tree.path,
                          "compression factor " +
                              std::to_string(factor).substr(0, 4) +
                              "; the tree was written uncompressed"});
    }

    int empty_branches = 0;
    for (const auto &branch : tree.branches) {
      if (branch.zip_bytes == 0) {
        ++empty_branches;
      }
    }
    if (empty_branches > 0) {
      findings.push_back({Severity::Warning, "EMPTY_BRANCH", tree.path,
                          std::to_string(empty_branches) +
                              " branch(es) hold no data on disk"});
    }

    if (tree.basket_count > 0) {
      const std::uint64_t average = tree.basket_bytes / tree.basket_count;
      if (average < kSmallBasketBytes) {
        findings.push_back(
            {Severity::Warning, "SMALL_BASKETS", tree.path,
             "average basket " + human_bytes(average) +
                 "; reading costs more in seeks than in data. TTree::"
                 "OptimizeBaskets() at write time fixes this"});
      }
      if (tree.physical_reads > 0) {
        const double per_read =
            static_cast<double>(tree.basket_count) /
            static_cast<double>(tree.physical_reads);
        if (per_read < 2.0 && tree.basket_count > 8) {
          findings.push_back(
              {Severity::Warning, "SCATTERED_BASKETS", tree.path,
               std::to_string(tree.physical_reads) + " reads for " +
                   std::to_string(tree.basket_count) +
                   " baskets; they are spread out, so merging gains almost "
                   "nothing"});
        }
      }
    }
  }
}

std::string render_text(const std::string &path, TFile &file,
                        const std::vector<Finding> &findings,
                        const std::vector<DirectoryEntry> &entries,
                        const std::vector<TreeStat> &trees,
                        const std::vector<NTupleStat> &ntuples) {
  std::ostringstream out;

  int errors = 0;
  int warnings = 0;
  for (const auto &finding : findings) {
    if (finding.severity == Severity::Error) {
      ++errors;
    } else if (finding.severity == Severity::Warning) {
      ++warnings;
    }
  }

  out << path << "\n";
  if (errors == 0 && warnings == 0) {
    out << "  sound: nothing to report\n";
  } else {
    out << "  " << errors << " error(s), " << warnings << " warning(s)\n";
  }

  const Long64_t size = file.GetSize();
  out << "\n"
      << "  written by ROOT : " << file.GetVersion() << "\n"
      << "  size            : " << human_bytes(static_cast<std::uint64_t>(std::max<Long64_t>(0, size)))
      << "\n"
      << "  compression     : algorithm " << file.GetCompressionAlgorithm()
      << ", level " << file.GetCompressionLevel() << "\n"
      << "  keys            : " << entries.size() << "\n";

  if (!findings.empty()) {
    out << "\nfindings\n";
    for (const auto &finding : findings) {
      out << "  [" << severity_name(finding.severity) << "] " << finding.code
          << "  " << finding.subject << "\n"
          << "      " << finding.detail << "\n";
    }
  }

  out << "\nstructure\n";
  for (const auto &entry : entries) {
    out << std::string(2 + 2 * static_cast<std::size_t>(entry.depth), ' ')
        << entry.name << ";" << entry.cycle << "  " << entry.class_name << "  "
        << human_bytes(static_cast<std::uint64_t>(std::max(0, entry.bytes)))
        << "  " << entry.date << "\n";
  }

  for (const auto &tree : trees) {
    out << "\n" << tree.path << "\n"
        << "  entries       : " << tree.entries << "\n"
        << "  branches      : " << tree.branch_count << "\n"
        << "  on disk       : " << human_bytes(tree.zip_bytes) << " for "
        << human_bytes(tree.tot_bytes) << " in memory";
    if (tree.zip_bytes > 0) {
      out << "  (x" << std::fixed << std::setprecision(2)
          << compression_factor(tree) << ")";
    }
    out << "\n";

    if (tree.basket_count > 0) {
      out << "  baskets       : " << tree.basket_count << ", average "
          << human_bytes(tree.basket_bytes / tree.basket_count) << "\n"
          << "  reads needed  : " << tree.physical_reads
          << " after merging gaps under " << human_bytes(kMaxGapBytes) << "\n";
    }

    const int shown =
        std::min<int>(kTopBranches, static_cast<int>(tree.branches.size()));
    if (shown > 0) {
      out << "  heaviest branches\n";
      for (int i = 0; i < shown; ++i) {
        const auto &branch = tree.branches[static_cast<std::size_t>(i)];
        out << "    " << branch.name << "  " << branch.type << "  "
            << human_bytes(branch.zip_bytes) << " on disk, " << branch.baskets
            << " basket(s)\n";
      }
    }
  }

  for (const auto &ntuple : ntuples) {
    out << "\n" << ntuple.path << "  (" << ntuple.class_name;
    if (!ntuple.format_version.empty()) {
      out << ", format " << ntuple.format_version;
    }
    out << ")\n";
    if (!ntuple.detailed) {
      out << "  anchor only on this build\n";
      continue;
    }
    out << "  entries       : " << ntuple.entries << "\n"
        << "  fields        : " << ntuple.fields << "\n"
        << "  clusters      : " << ntuple.clusters << "\n";
    if (ntuple.pages > 0) {
      out << "  pages         : " << ntuple.pages << ", average "
          << human_bytes(ntuple.page_bytes / ntuple.pages) << "\n";
    }
  }

  return out.str();
}

std::string render_json(const std::string &path, TFile &file,
                        const std::vector<Finding> &findings,
                        const std::vector<DirectoryEntry> &entries,
                        const std::vector<TreeStat> &trees,
                        const std::vector<NTupleStat> &ntuples) {
  std::ostringstream out;
  out << "{\"path\":\"" << escape_json(path) << "\",";
  out << "\"root_version\":" << file.GetVersion() << ",";
  out << "\"size_bytes\":" << file.GetSize() << ",";
  out << "\"end_of_data\":" << file.GetEND() << ",";
  out << "\"compression_algorithm\":" << file.GetCompressionAlgorithm() << ",";
  out << "\"compression_level\":" << file.GetCompressionLevel() << ",";
  out << "\"recovered\":"
      << (file.TestBit(TFile::kRecovered) ? "true" : "false") << ",";

  out << "\"findings\":[";
  for (std::size_t i = 0; i < findings.size(); ++i) {
    const auto &finding = findings[i];
    out << (i == 0 ? "" : ",") << "{\"severity\":\""
        << severity_name(finding.severity) << "\",\"code\":\"" << finding.code
        << "\",\"subject\":\"" << escape_json(finding.subject)
        << "\",\"detail\":\"" << escape_json(finding.detail) << "\"}";
  }
  out << "],";

  out << "\"keys\":[";
  for (std::size_t i = 0; i < entries.size(); ++i) {
    const auto &entry = entries[i];
    out << (i == 0 ? "" : ",") << "{\"name\":\"" << escape_json(entry.name)
        << "\",\"class\":\"" << escape_json(entry.class_name)
        << "\",\"cycle\":" << entry.cycle << ",\"bytes\":" << entry.bytes
        << ",\"depth\":" << entry.depth << ",\"date\":\""
        << escape_json(entry.date) << "\"}";
  }
  out << "],";

  out << "\"trees\":[";
  for (std::size_t i = 0; i < trees.size(); ++i) {
    const auto &tree = trees[i];
    out << (i == 0 ? "" : ",") << "{\"path\":\"" << escape_json(tree.path)
        << "\",\"entries\":" << tree.entries
        << ",\"branch_count\":" << tree.branch_count
        << ",\"zip_bytes\":" << tree.zip_bytes
        << ",\"tot_bytes\":" << tree.tot_bytes
        << ",\"basket_count\":" << tree.basket_count
        << ",\"basket_bytes\":" << tree.basket_bytes
        << ",\"physical_reads\":" << tree.physical_reads << ",\"branches\":[";
    for (std::size_t b = 0; b < tree.branches.size(); ++b) {
      const auto &branch = tree.branches[b];
      out << (b == 0 ? "" : ",") << "{\"name\":\"" << escape_json(branch.name)
          << "\",\"type\":\"" << escape_json(branch.type)
          << "\",\"zip_bytes\":" << branch.zip_bytes
          << ",\"tot_bytes\":" << branch.tot_bytes
          << ",\"baskets\":" << branch.baskets << "}";
    }
    out << "]}";
  }
  out << "],";

  out << "\"ntuples\":[";
  for (std::size_t i = 0; i < ntuples.size(); ++i) {
    const auto &ntuple = ntuples[i];
    out << (i == 0 ? "" : ",") << "{\"path\":\"" << escape_json(ntuple.path)
        << "\",\"class\":\"" << escape_json(ntuple.class_name)
        << "\",\"format_version\":\"" << escape_json(ntuple.format_version)
        << "\",\"detailed\":" << (ntuple.detailed ? "true" : "false")
        << ",\"unreadable\":" << (ntuple.unreadable ? "true" : "false")
        << ",\"entries\":" << ntuple.entries
        << ",\"fields\":" << ntuple.fields
        << ",\"clusters\":" << ntuple.clusters
        << ",\"pages\":" << ntuple.pages
        << ",\"page_bytes\":" << ntuple.page_bytes << "}";
  }
  out << "]}";
  return out.str();
}

std::string read_request(const ShmLayout &shm, const Proto::PacketHeader &pkt) {
  constexpr std::size_t kMaxRequest = 64 * 1024;
  if (shm.base == nullptr || shm.header == nullptr || pkt.payload_size == 0 ||
      pkt.payload_size > kMaxRequest) {
    return {};
  }
  const std::uint64_t total = shm.header->total_size;
  if (pkt.payload_offset == 0 || pkt.payload_offset >= total ||
      pkt.payload_size > total - pkt.payload_offset) {
    return {};
  }
  const auto *bytes =
      reinterpret_cast<const char *>(shm.base + pkt.payload_offset);
  std::size_t length = 0;
  while (length < pkt.payload_size && bytes[length] != '\0') {
    ++length;
  }
  return std::string(bytes, length);
}

void send_answer(ShmLayout &shm, const Proto::PacketHeader &pkt,
                 const std::string &answer) {
  if (shm.evt_ring == nullptr) {
    return;
  }

  ScopedChunkWriter writer(shm, answer.size() + 1);
  if (!writer) {
    std::cerr << "[CmdInspect] Error: no room in the shared heap for the "
                 "report.\n";
    return;
  }
  std::memcpy(writer.data(), answer.data(), answer.size() + 1);
  writer.commit();

  BridgeMessage msg{};
  msg.type = MsgType::SHM_REF;
  msg.cmd = static_cast<std::uint16_t>(Proto::PacketType::EVT_OK);
  msg.job_id = pkt.job_id;
  msg.req_id = pkt.req_id;
  msg.shm_ref.offset = static_cast<std::uint32_t>(writer.offset());
  msg.shm_ref.total_bytes = static_cast<std::uint32_t>(answer.size() + 1);
  msg.shm_ref.dtype = ShmDType::UInt8;
  msg.shm_ref.ndim = 1;
  msg.shm_ref.shape[0] = static_cast<std::uint32_t>(answer.size() + 1);

  for (int retry = 0; retry < 100; ++retry) {
    if (shm.evt_ring->push(msg)) {
      return;
    }
    std::this_thread::yield();
  }
  std::cerr << "[CmdInspect] Error: event ring full, report dropped for req_id "
            << pkt.req_id << ".\n";
}

} // anonymous namespace

void handle_file_scan(ShmLayout &shm, const Proto::PacketHeader &pkt,
                      void *context) {
  (void)context;

  std::string request = read_request(shm, pkt);
  if (request.empty()) {
    send_answer(shm, pkt, "ERROR: no path given. Usage: :root file scan <path> "
                          "[--json]");
    return;
  }

  bool as_json = false;
  const std::string flag = " --json";
  if (request.size() > flag.size() &&
      request.compare(request.size() - flag.size(), flag.size(), flag) == 0) {
    as_json = true;
    request.resize(request.size() - flag.size());
  }
  while (!request.empty() && request.back() == ' ') {
    request.pop_back();
  }

  std::unique_ptr<TFile> file(TFile::Open(request.c_str(), "READ"));
  if (!file || file->IsZombie()) {
    send_answer(shm, pkt,
                "ERROR: cannot read as a ROOT file: " + request);
    return;
  }

  std::vector<Finding> findings;
  std::vector<DirectoryEntry> entries;
  std::vector<TreeStat> trees;
  std::vector<NTupleStat> ntuples;
  bool saw_ttree = false;

  if (file->TestBit(TFile::kRecovered)) {
    findings.push_back({Severity::Error, "NOT_CLOSED", request,
                        "the file was not closed properly and ROOT rebuilt its "
                        "directory; anything written after the last checkpoint "
                        "is gone"});
  }

  const Long64_t size = file->GetSize();
  const Long64_t end = file->GetEND();
  if (end > size) {
    findings.push_back({Severity::Error, "TRUNCATED", request,
                        "the header says the data ends at " +
                            std::to_string(end) + " but the file stops at " +
                            std::to_string(size)});
  }

  walk(file.get(), request, "", 0, entries, trees, ntuples, findings, saw_ttree);
  judge(trees, findings);
  judge_ntuples(ntuples, findings);

  if (!ntuples.empty() && saw_ttree) {
    findings.push_back({Severity::Note, "MIXED_FORMATS", request,
                        "the file holds both TTree and RNTuple objects; they "
                        "do not take the same read path"});
  }

  std::sort(findings.begin(), findings.end(),
            [](const Finding &a, const Finding &b) {
              return static_cast<int>(a.severity) > static_cast<int>(b.severity);
            });

  send_answer(shm, pkt,
              as_json
                  ? render_json(request, *file, findings, entries, trees, ntuples)
                  : render_text(request, *file, findings, entries, trees, ntuples));
}

void register_all() {
  CommandRegistry::instance().register_command(
      Proto::PacketType::CMD_FILE_SCAN, &handle_file_scan);
}

} // namespace Sphere::cmd::inspect
