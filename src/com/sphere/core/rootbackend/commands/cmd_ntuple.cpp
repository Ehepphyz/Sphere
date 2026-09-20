// commands/cmd_ntuple.cpp

// RNTuple is the columnar format that replaces TTree. The engine reads it the
// same way it reads a tree: a reader bound to a job id, a schema on demand, and
// one field written straight into the shared region for whoever asked.

#include "cmd_ntuple.h"
#include "command_registry.h"
#include "isa_allocator.h"
#include "lockfree_ring.h"

// The RNTuple API moved between 6.34 and 6.36 and will move again, so the whole
// family is compiled only when the header is there and every accessor is asked
// for rather than assumed. Build with -DSPHERE_NO_RNTUPLE_DETAIL to drop it.
#if !defined(SPHERE_NO_RNTUPLE_DETAIL) && __has_include(<ROOT/RNTupleReader.hxx>)
#include <ROOT/RNTuple.hxx>
#include <ROOT/RNTupleReader.hxx>
#define SPHERE_NTUPLE_COMMANDS 1
#else
#define SPHERE_NTUPLE_COMMANDS 0
#endif

// Writing is a second, narrower dependency: a build can read the format without
// carrying the writer.
#if SPHERE_NTUPLE_COMMANDS && __has_include(<ROOT/RNTupleWriter.hxx>)
#include <ROOT/RNTupleModel.hxx>
#include <ROOT/RNTupleWriter.hxx>
#define SPHERE_NTUPLE_WRITE 1
#else
#define SPHERE_NTUPLE_WRITE 0
#endif

#include <TBranch.h>
#include <TFile.h>
#include <TKey.h>
#include <TLeaf.h>
#include <TList.h>
#include <TObjArray.h>
#include <TTree.h>

#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <string_view>
#include <thread>
#include <unordered_map>
#include <vector>

#if SPHERE_NTUPLE_COMMANDS
// The namespace the reader lives in moved with the API.
#if __has_include(<ROOT/RNTupleReader.hxx>)
namespace NTupleNS = ROOT;
#endif
#endif

namespace Sphere::cmd::ntuple {

namespace {

// -----------------------------------------------------------------------------
// Answering
// -----------------------------------------------------------------------------

void send_text(ShmLayout &shm, const Proto::PacketHeader &pkt,
               const std::string &answer) {
  if (shm.evt_ring == nullptr) {
    return;
  }
  ScopedChunkWriter writer(shm, answer.size() + 1);
  if (!writer) {
    return;
  }
  std::memcpy(writer.data(), answer.data(), answer.size() + 1);
  writer.commit();

  BridgeMessage msg{};
  msg.type = MsgType::SHM_REF;
  msg.cmd = static_cast<std::uint16_t>(Proto::PacketType::EVT_OK);
  msg.job_id = static_cast<std::uint32_t>(pkt.job_id);
  msg.req_id = static_cast<std::uint32_t>(pkt.req_id);
  shm_ref_publish(msg.shm_ref, shm, writer.offset());
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
}

[[nodiscard]] std::string read_request(const ShmLayout &shm,
                                       const Proto::PacketHeader &pkt) {
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

/// Splits "<file>\t<name>", tolerating a space where the tab should be.
void split_request(const std::string &request, std::string &file,
                   std::string &name) {
  std::size_t at = request.find('\t');
  if (at == std::string::npos) {
    at = request.rfind(' ');
  }
  if (at == std::string::npos) {
    file = request;
    name.clear();
    return;
  }
  file = request.substr(0, at);
  name = request.substr(at + 1);
  while (!file.empty() && file.back() == ' ') {
    file.pop_back();
  }
  while (!name.empty() && name.front() == ' ') {
    name.erase(name.begin());
  }
}

} // namespace

#if SPHERE_NTUPLE_COMMANDS

namespace {

// -----------------------------------------------------------------------------
// Accessors the release may or may not have
// -----------------------------------------------------------------------------

template <typename T> const auto &unwrap(const T &value) {
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

/// "name  type" for every top level field, in the order the schema declares.
template <typename D> std::string schema_of(const D &descriptor) {
  std::string out;
  if constexpr (requires { descriptor.GetTopLevelFields(); }) {
    for (const auto &field : descriptor.GetTopLevelFields()) {
      if constexpr (requires { field.GetFieldName(); field.GetTypeName(); }) {
        std::string name(field.GetFieldName());
        out += name;
        out += std::string(name.size() < 28 ? 28 - name.size() : 1, ' ');
        out += std::string(field.GetTypeName());
        out += "\n";
      }
    }
  }
  return out;
}

/// The type a field declares, empty when the release will not say.
template <typename D>
std::string type_of_field(const D &descriptor, const std::string &wanted) {
  if constexpr (requires { descriptor.GetTopLevelFields(); }) {
    for (const auto &field : descriptor.GetTopLevelFields()) {
      if constexpr (requires { field.GetFieldName(); field.GetTypeName(); }) {
        if (std::string(field.GetFieldName()) == wanted) {
          return std::string(field.GetTypeName());
        }
      }
    }
  }
  return {};
}

// -----------------------------------------------------------------------------
// The readers that are open
// -----------------------------------------------------------------------------

using Reader = NTupleNS::RNTupleReader;

std::mutex &readers_mutex() {
  static std::mutex guard;
  return guard;
}

std::unordered_map<std::uint32_t, std::shared_ptr<Reader>> &readers() {
  static std::unordered_map<std::uint32_t, std::shared_ptr<Reader>> open;
  return open;
}

[[nodiscard]] std::shared_ptr<Reader> reader_for(std::uint32_t job_id) {
  const std::lock_guard<std::mutex> hold(readers_mutex());
  const auto found = readers().find(job_id);
  return (found == readers().end()) ? nullptr : found->second;
}

// -----------------------------------------------------------------------------
// One field into the region
// -----------------------------------------------------------------------------

struct FieldType {
  ShmDType dtype{ShmDType::Float64};
  std::size_t elem_size{sizeof(double)};
  bool known{false};
};

/// Maps the type an RNTuple field declares onto what the region carries.
[[nodiscard]] FieldType map_field_type(std::string_view declared) {
  // The spellings differ between writers: "std::int32_t", "int32_t", "int".
  auto is = [declared](std::initializer_list<const char *> names) {
    for (const char *name : names) {
      if (declared == name) {
        return true;
      }
    }
    return false;
  };
  if (is({"float", "Float_t"})) {
    return {ShmDType::Float32, sizeof(float), true};
  }
  if (is({"double", "Double_t"})) {
    return {ShmDType::Float64, sizeof(double), true};
  }
  if (is({"std::int32_t", "int32_t", "int", "Int_t"})) {
    return {ShmDType::Int32, sizeof(std::int32_t), true};
  }
  if (is({"std::uint32_t", "uint32_t", "unsigned int", "UInt_t"})) {
    return {ShmDType::UInt32, sizeof(std::uint32_t), true};
  }
  if (is({"std::int64_t", "int64_t", "long", "long long", "Long64_t"})) {
    return {ShmDType::Int64, sizeof(std::int64_t), true};
  }
  if (is({"std::uint64_t", "uint64_t", "unsigned long",
          "unsigned long long", "ULong64_t"})) {
    return {ShmDType::UInt64, sizeof(std::uint64_t), true};
  }
  if (is({"std::int16_t", "int16_t", "short", "Short_t"})) {
    return {ShmDType::Int16, sizeof(std::int16_t), true};
  }
  if (is({"std::uint16_t", "uint16_t", "unsigned short", "UShort_t"})) {
    return {ShmDType::UInt16, sizeof(std::uint16_t), true};
  }
  if (is({"std::int8_t", "int8_t", "char", "Char_t"})) {
    return {ShmDType::Int8, sizeof(std::int8_t), true};
  }
  if (is({"std::uint8_t", "uint8_t", "unsigned char", "UChar_t", "bool"})) {
    return {ShmDType::UInt8, sizeof(std::uint8_t), true};
  }
  return {};
}

/// Copies one scalar field into `dest`. False when the release has no view.
template <typename T>
bool copy_field(Reader &reader, const std::string &field, std::uint64_t count,
                void *dest) {
  if constexpr (requires { reader.GetView<T>(field); }) {
    auto view = reader.GetView<T>(field);
    auto *out = static_cast<T *>(dest);
    for (std::uint64_t i = 0; i < count; ++i) {
      out[i] = view(i);
    }
    return true;
  } else {
    (void)reader;
    (void)field;
    (void)count;
    (void)dest;
    return false;
  }
}

[[nodiscard]] bool copy_by_type(Reader &reader, const std::string &field,
                                ShmDType dtype, std::uint64_t count,
                                void *dest) {
  switch (dtype) {
  case ShmDType::Float32: return copy_field<float>(reader, field, count, dest);
  case ShmDType::Float64: return copy_field<double>(reader, field, count, dest);
  case ShmDType::Int32:   return copy_field<std::int32_t>(reader, field, count, dest);
  case ShmDType::UInt32:  return copy_field<std::uint32_t>(reader, field, count, dest);
  case ShmDType::Int64:   return copy_field<std::int64_t>(reader, field, count, dest);
  case ShmDType::UInt64:  return copy_field<std::uint64_t>(reader, field, count, dest);
  case ShmDType::Int16:   return copy_field<std::int16_t>(reader, field, count, dest);
  case ShmDType::UInt16:  return copy_field<std::uint16_t>(reader, field, count, dest);
  case ShmDType::Int8:    return copy_field<std::int8_t>(reader, field, count, dest);
  case ShmDType::UInt8:   return copy_field<std::uint8_t>(reader, field, count, dest);
  default:                return false;
  }
}

} // namespace

// -----------------------------------------------------------------------------

bool attach(std::uint32_t job_id, std::string_view request) {
  std::string file;
  std::string name;
  split_request(std::string(request), file, name);
  if (file.empty() || name.empty()) {
    return false;
  }
  try {
    auto reader = Reader::Open(name, file);
    if (!reader) {
      return false;
    }
    const std::lock_guard<std::mutex> hold(readers_mutex());
    readers()[job_id] = std::shared_ptr<Reader>(std::move(reader));
    return true;
  } catch (const std::exception &) {
    return false;
  } catch (...) {
    return false;
  }
}

bool has_reader(std::uint32_t job_id) { return reader_for(job_id) != nullptr; }

void shutdown() {
  const std::lock_guard<std::mutex> hold(readers_mutex());
  readers().clear();
}

void handle_list(ShmLayout &shm, const Proto::PacketHeader &pkt,
                 void *context) {
  (void)context;
  const std::string path = read_request(shm, pkt);
  if (path.empty()) {
    send_text(shm, pkt, "ERROR: no file given. Usage: :root ntuple list <file>");
    return;
  }

  std::unique_ptr<TFile> file(TFile::Open(path.c_str(), "READ"));
  if (!file || file->IsZombie()) {
    send_text(shm, pkt, "ERROR: cannot open as a ROOT file: " + path);
    return;
  }

  std::string names;
  TList *keys = file->GetListOfKeys();
  const int count = (keys != nullptr) ? keys->GetEntries() : 0;
  for (int i = 0; i < count; ++i) {
    auto *key = dynamic_cast<TKey *>(keys->At(i));
    if (key == nullptr) {
      continue;
    }
    const std::string cls = key->GetClassName();
    if (cls.find("RNTuple") != std::string::npos) {
      names += key->GetName();
      names += "\n";
    }
  }
  send_text(shm, pkt, names.empty() ? "this file holds no RNTuple" : names);
}

void handle_attach(ShmLayout &shm, const Proto::PacketHeader &pkt,
                   void *context) {
  (void)context;
  const std::string request = read_request(shm, pkt);
  if (request.empty()) {
    send_text(shm, pkt,
              "ERROR: usage is :root ntuple attach <id> <file> <ntuple>");
    return;
  }
  if (!attach(static_cast<std::uint32_t>(pkt.job_id), request)) {
    std::string file;
    std::string name;
    split_request(request, file, name);
    send_text(shm, pkt,
              "ERROR: no RNTuple called '" + name + "' in " + file +
                  ". :root ntuple list " + file + " shows what is there.");
    return;
  }
  send_text(shm, pkt, "NTUPLE_ATTACHED  ID: " +
                          std::to_string(pkt.job_id));
}

void handle_info(ShmLayout &shm, const Proto::PacketHeader &pkt,
                 void *context) {
  (void)context;
  auto reader = reader_for(static_cast<std::uint32_t>(pkt.job_id));
  if (!reader) {
    send_text(shm, pkt, "ERROR: no RNTuple bound to that id. Use :root ntuple "
                        "attach first.");
    return;
  }
  auto &&handle = reader->GetDescriptor();
  const auto &descriptor = unwrap(handle);

  std::uint64_t entries = entries_of(*reader);
  if (const std::uint64_t counted = entries_of(descriptor); counted > 0) {
    entries = counted;
  }
  send_text(shm, pkt,
            "entries = " + std::to_string(entries) + "\n" +
                "fields = " + std::to_string(fields_of(descriptor)) + "\n" +
                "clusters = " + std::to_string(clusters_of(descriptor)));
}

void handle_fields(ShmLayout &shm, const Proto::PacketHeader &pkt,
                   void *context) {
  (void)context;
  auto reader = reader_for(static_cast<std::uint32_t>(pkt.job_id));
  if (!reader) {
    send_text(shm, pkt, "ERROR: no RNTuple bound to that id. Use :root ntuple "
                        "attach first.");
    return;
  }
  auto &&handle = reader->GetDescriptor();
  const std::string schema = schema_of(unwrap(handle));
  send_text(shm, pkt,
            schema.empty() ? "this ROOT build does not report the schema"
                           : schema);
}

// -----------------------------------------------------------------------------
// Writing: a TTree turned into an RNTuple
// -----------------------------------------------------------------------------

#if SPHERE_NTUPLE_WRITE
namespace {

/// One tree column feeding one RNTuple field, each entry.
struct FieldCopier {
  virtual ~FieldCopier() = default;
  virtual void pull() = 0;
};

template <typename T> struct TypedCopier final : FieldCopier {
  std::shared_ptr<T> field;
  TLeaf *leaf{nullptr};
  void pull() override {
    const void *source = leaf->GetValuePointer();
    if (source != nullptr) {
      *field = *static_cast<const T *>(source);
    }
  }
};

/// Asks for the field rather than assuming the factory is still called this.
template <typename T, typename M>
std::shared_ptr<T> make_field(M &model, const std::string &name) {
  if constexpr (requires { model.template MakeField<T>(name); }) {
    return model.template MakeField<T>(name);
  } else {
    (void)model;
    (void)name;
    return nullptr;
  }
}

template <typename T, typename M>
std::unique_ptr<FieldCopier> copier_for(M &model, const std::string &name,
                                        TLeaf *leaf) {
  auto typed = std::make_unique<TypedCopier<T>>();
  typed->field = make_field<T>(model, name);
  typed->leaf = leaf;
  return typed->field ? std::unique_ptr<FieldCopier>(std::move(typed)) : nullptr;
}

/// A copier for the type the leaf declares, or nothing when it is not a number.
template <typename M>
std::unique_ptr<FieldCopier> copier_by_leaf(M &model, const std::string &name,
                                            TLeaf *leaf) {
  const std::string type = leaf->GetTypeName();
  if (type == "Float_t" || type == "float")    { return copier_for<float>(model, name, leaf); }
  if (type == "Double_t" || type == "double")  { return copier_for<double>(model, name, leaf); }
  if (type == "Int_t" || type == "int")        { return copier_for<std::int32_t>(model, name, leaf); }
  if (type == "UInt_t" || type == "unsigned int") { return copier_for<std::uint32_t>(model, name, leaf); }
  if (type == "Long64_t" || type == "long long")  { return copier_for<std::int64_t>(model, name, leaf); }
  if (type == "ULong64_t")                     { return copier_for<std::uint64_t>(model, name, leaf); }
  if (type == "Short_t" || type == "short")    { return copier_for<std::int16_t>(model, name, leaf); }
  if (type == "UShort_t")                      { return copier_for<std::uint16_t>(model, name, leaf); }
  if (type == "Char_t" || type == "signed char") { return copier_for<std::int8_t>(model, name, leaf); }
  if (type == "UChar_t" || type == "unsigned char") { return copier_for<std::uint8_t>(model, name, leaf); }
  if (type == "Bool_t" || type == "bool")      { return copier_for<bool>(model, name, leaf); }
  return nullptr;
}

} // namespace
#endif // SPHERE_NTUPLE_WRITE

void handle_write(ShmLayout &shm, const Proto::PacketHeader &pkt,
                  void *context) {
  (void)context;
#if !SPHERE_NTUPLE_WRITE
  send_text(shm, pkt,
            "ERROR: this ROOT build carries no RNTuple writer, so a tree "
            "cannot be converted here.");
#else
  const std::string request = read_request(shm, pkt);
  std::vector<std::string> part;
  std::size_t at = 0;
  while (at <= request.size()) {
    const std::size_t end = request.find('\t', at);
    part.push_back(request.substr(
        at, (end == std::string::npos) ? std::string::npos : end - at));
    if (end == std::string::npos) {
      break;
    }
    at = end + 1;
  }
  if (part.size() < 4 || part[0].empty() || part[1].empty() ||
      part[2].empty() || part[3].empty()) {
    send_text(shm, pkt,
              "ERROR: usage is :root ntuple from-tree <file> <tree> <output> "
              "<ntuple>");
    return;
  }

  std::unique_ptr<TFile> source(TFile::Open(part[0].c_str(), "READ"));
  if (!source || source->IsZombie()) {
    send_text(shm, pkt, "ERROR: cannot open as a ROOT file: " + part[0]);
    return;
  }
  auto *tree = dynamic_cast<TTree *>(source->Get(part[1].c_str()));
  if (tree == nullptr) {
    send_text(shm, pkt,
              "ERROR: no TTree called '" + part[1] + "' in " + part[0]);
    return;
  }

  try {
    auto model = NTupleNS::RNTupleModel::Create();
    std::vector<std::unique_ptr<FieldCopier>> copiers;
    std::vector<std::string> skipped;

    TObjArray *branches = tree->GetListOfBranches();
    const int count = (branches != nullptr) ? branches->GetEntriesFast() : 0;
    for (int i = 0; i < count; ++i) {
      auto *branch = dynamic_cast<TBranch *>(branches->At(i));
      if (branch == nullptr) {
        continue;
      }
      auto *leaves = branch->GetListOfLeaves();
      auto *leaf = (leaves != nullptr && leaves->GetEntries() > 0)
                       ? dynamic_cast<TLeaf *>(leaves->At(0))
                       : nullptr;
      // A leaf that counts another one describes an array, which has no single
      // value per entry to copy.
      if (leaf == nullptr || leaf->GetLeafCount() != nullptr ||
          leaf->GetLen() != 1) {
        skipped.push_back(branch->GetName());
        continue;
      }
      auto copier = copier_by_leaf(*model, branch->GetName(), leaf);
      if (!copier) {
        skipped.push_back(branch->GetName());
        continue;
      }
      copiers.push_back(std::move(copier));
    }

    if (copiers.empty()) {
      send_text(shm, pkt,
                "ERROR: none of the branches of '" + part[1] +
                    "' is a scalar number this command can convert.");
      return;
    }

    auto writer = NTupleNS::RNTupleWriter::Recreate(std::move(model), part[3],
                                                    part[2]);
    if (!writer) {
      send_text(shm, pkt, "ERROR: cannot write " + part[2]);
      return;
    }

    const std::int64_t entries = tree->GetEntries();
    for (std::int64_t entry = 0; entry < entries; ++entry) {
      if (tree->GetEntry(entry) <= 0) {
        break;
      }
      for (auto &copier : copiers) {
        copier->pull();
      }
      writer->Fill();
    }
    writer.reset(); // closes the file before it is reported as written

    std::string answer = "wrote " + std::to_string(entries) + " entries and " +
                         std::to_string(copiers.size()) + " fields to " +
                         part[2] + ":" + part[3];
    if (!skipped.empty()) {
      answer += "\nleft behind, not scalar numbers:";
      for (const std::string &name : skipped) {
        answer += " " + name;
      }
    }
    send_text(shm, pkt, answer);
  } catch (const std::exception &failure) {
    send_text(shm, pkt, std::string("ERROR: the conversion failed: ") +
                            failure.what());
  } catch (...) {
    send_text(shm, pkt, "ERROR: the conversion failed");
  }
#endif
}

void handle_column(ShmLayout &shm, const Proto::PacketHeader &pkt,
                   void *context) {
  (void)context;
  auto reader = reader_for(static_cast<std::uint32_t>(pkt.job_id));
  if (!reader) {
    send_text(shm, pkt, "ERROR: no RNTuple bound to that id. Use :root ntuple "
                        "attach first.");
    return;
  }
  const std::string field = read_request(shm, pkt);
  if (field.empty()) {
    send_text(shm, pkt,
              "ERROR: no field given. Usage: :root ntuple column <id> <field>");
    return;
  }

  auto &&handle = reader->GetDescriptor();
  const auto &descriptor = unwrap(handle);

  const std::string declared = type_of_field(descriptor, field);
  if (declared.empty()) {
    send_text(shm, pkt, "ERROR: no top level field called '" + field +
                            "'. :root ntuple fields lists them.");
    return;
  }
  const FieldType type = map_field_type(declared);
  if (!type.known) {
    send_text(shm, pkt, "ERROR: field '" + field + "' holds a " + declared +
                            ", which is not a number this command can copy.");
    return;
  }

  std::uint64_t entries = entries_of(*reader);
  if (const std::uint64_t counted = entries_of(descriptor); counted > 0) {
    entries = counted;
  }
  if (entries == 0) {
    send_text(shm, pkt, "ERROR: that RNTuple holds no entry");
    return;
  }
  if (entries > 0xFFFFFFFFULL / type.elem_size) {
    send_text(shm, pkt, "ERROR: that field is larger than one block can carry");
    return;
  }

  const std::size_t bytes = static_cast<std::size_t>(entries) * type.elem_size;
  const BulkBlock block = shm_bulk_acquire(shm, bytes);
  if (!block) {
    send_text(shm, pkt, "ERROR: no room in the shared region for that field");
    return;
  }

  bool copied = false;
  try {
    copied = copy_by_type(*reader, field, type.dtype, entries, block.data);
  } catch (const std::exception &) {
    copied = false;
  } catch (...) {
    copied = false;
  }

  if (!copied) {
    shm_bulk_abort(shm, block);
    send_text(shm, pkt, "ERROR: this ROOT build cannot read '" + field +
                            "' as a flat column");
    return;
  }

  shm_bulk_commit(shm, block);
  if (shm.evt_ring != nullptr) {
    BridgeMessage msg{};
    msg.job_id = static_cast<std::uint32_t>(pkt.job_id);
    msg.req_id = static_cast<std::uint32_t>(pkt.req_id);
    shm_bulk_describe(msg, shm, block, bytes, type.dtype,
                      static_cast<std::uint32_t>(entries));
    shm.evt_ring->push(msg);
  }
}

#else // SPHERE_NTUPLE_COMMANDS

// This ROOT build carries no RNTuple header. The commands stay registered so
// that they answer why, rather than looking like commands that do not exist.

namespace {
void refuse(ShmLayout &shm, const Proto::PacketHeader &pkt) {
  send_text(shm, pkt,
            "ERROR: this ROOT build carries no RNTuple reader, so the "
            "ntuple commands are not available.");
}
} // namespace

bool attach(std::uint32_t, std::string_view) { return false; }
bool has_reader(std::uint32_t) { return false; }
void shutdown() {}

void handle_list(ShmLayout &shm, const Proto::PacketHeader &pkt, void *) { refuse(shm, pkt); }
void handle_attach(ShmLayout &shm, const Proto::PacketHeader &pkt, void *) { refuse(shm, pkt); }
void handle_info(ShmLayout &shm, const Proto::PacketHeader &pkt, void *) { refuse(shm, pkt); }
void handle_fields(ShmLayout &shm, const Proto::PacketHeader &pkt, void *) { refuse(shm, pkt); }
void handle_column(ShmLayout &shm, const Proto::PacketHeader &pkt, void *) { refuse(shm, pkt); }
void handle_write(ShmLayout &shm, const Proto::PacketHeader &pkt, void *) { refuse(shm, pkt); }

#endif // SPHERE_NTUPLE_COMMANDS

void register_all() {
  auto &registry = CommandRegistry::instance();
  registry.register_command(Proto::PacketType::CMD_NTUPLE_LIST, &handle_list);
  registry.register_command(Proto::PacketType::CMD_NTUPLE_ATTACH, &handle_attach);
  registry.register_command(Proto::PacketType::CMD_NTUPLE_INFO, &handle_info);
  registry.register_command(Proto::PacketType::CMD_NTUPLE_FIELDS, &handle_fields);
  registry.register_command(Proto::PacketType::CMD_NTUPLE_COLUMN, &handle_column);
  registry.register_command(Proto::PacketType::CMD_NTUPLE_WRITE, &handle_write);
}

} // namespace Sphere::cmd::ntuple
