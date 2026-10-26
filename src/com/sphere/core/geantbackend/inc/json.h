// json.h
//
// A small JSON value, parser and writer: the request and reply language of
// geant4-bridge. Self-contained, as the rest of the bridge: no dependency
// beyond the C++ standard library.

#pragma once

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <map>
#include <memory>
#include <stdexcept>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace g4b {

class Json {
public:
  enum class Type { Null, Bool, Number, String, Array, Object };

  Json() = default;
  Json(std::nullptr_t) {}
  Json(bool b) : type_(Type::Bool), b_(b) {}
  Json(int v) : type_(Type::Number), n_(v) {}
  Json(long v) : type_(Type::Number), n_(static_cast<double>(v)) {}
  Json(long long v) : type_(Type::Number), n_(static_cast<double>(v)) {}
  Json(unsigned long v) : type_(Type::Number), n_(static_cast<double>(v)) {}
  Json(unsigned long long v) : type_(Type::Number), n_(static_cast<double>(v)) {}
  Json(double v) : type_(Type::Number), n_(v) {}
  Json(const char *s) : type_(Type::String), s_(s) {}
  Json(std::string s) : type_(Type::String), s_(std::move(s)) {}

  static Json array() {
    Json j;
    j.type_ = Type::Array;
    return j;
  }
  static Json object() {
    Json j;
    j.type_ = Type::Object;
    return j;
  }

  Type type() const { return type_; }
  bool isNull() const { return type_ == Type::Null; }
  bool isObject() const { return type_ == Type::Object; }
  bool isArray() const { return type_ == Type::Array; }
  bool isString() const { return type_ == Type::String; }
  bool isNumber() const { return type_ == Type::Number; }

  double num(double fallback = 0) const { return type_ == Type::Number ? n_ : fallback; }
  bool boolean(bool fallback = false) const { return type_ == Type::Bool ? b_ : fallback; }
  const std::string &str() const {
    static const std::string empty;
    return type_ == Type::String ? s_ : empty;
  }

  // Object access
  bool has(const std::string &k) const { return type_ == Type::Object && o_.count(k) > 0; }
  const Json &operator[](const std::string &k) const {
    static const Json null;
    if (type_ != Type::Object) {
      return null;
    }
    auto it = o_.find(k);
    return it == o_.end() ? null : it->second;
  }
  Json &set(const std::string &k, Json v) {
    if (type_ != Type::Object) {
      type_ = Type::Object;
      o_.clear();
    }
    o_[k] = std::move(v);
    return *this;
  }
  const std::map<std::string, Json> &items() const { return o_; }

  // Array access
  std::size_t size() const { return type_ == Type::Array ? a_.size() : 0; }
  const Json &at(std::size_t i) const {
    static const Json null;
    return (type_ == Type::Array && i < a_.size()) ? a_[i] : null;
  }
  Json &push(Json v) {
    if (type_ != Type::Array) {
      type_ = Type::Array;
      a_.clear();
    }
    a_.push_back(std::move(v));
    return *this;
  }

  std::string str(const std::string &k, const std::string &fallback) const {
    const Json &v = (*this)[k];
    return v.isString() ? v.s_ : fallback;
  }
  double num(const std::string &k, double fallback) const {
    const Json &v = (*this)[k];
    return v.isNumber() ? v.n_ : fallback;
  }

  std::string dump() const {
    std::string out;
    write(out);
    return out;
  }

  static Json parse(std::string_view text) {
    std::size_t pos = 0;
    Json j = parseValue(text, pos);
    skip(text, pos);
    if (pos != text.size()) {
      throw std::runtime_error("trailing characters in JSON");
    }
    return j;
  }

private:
  Type type_{Type::Null};
  bool b_{false};
  double n_{0};
  std::string s_;
  std::vector<Json> a_;
  std::map<std::string, Json> o_;

  void write(std::string &out) const {
    switch (type_) {
    case Type::Null:
      out += "null";
      break;
    case Type::Bool:
      out += b_ ? "true" : "false";
      break;
    case Type::Number: {
      if (!std::isfinite(n_)) {
        out += "null";
        break;
      }
      char buf[32];
      if (n_ == std::floor(n_) && std::fabs(n_) < 1e15) {
        std::snprintf(buf, sizeof(buf), "%.0f", n_);
      } else {
        std::snprintf(buf, sizeof(buf), "%.10g", n_);
      }
      out += buf;
      break;
    }
    case Type::String:
      quote(out, s_);
      break;
    case Type::Array:
      out += '[';
      for (std::size_t i = 0; i < a_.size(); ++i) {
        if (i > 0) {
          out += ',';
        }
        a_[i].write(out);
      }
      out += ']';
      break;
    case Type::Object: {
      out += '{';
      bool first = true;
      for (const auto &[k, v] : o_) {
        if (!first) {
          out += ',';
        }
        first = false;
        quote(out, k);
        out += ':';
        v.write(out);
      }
      out += '}';
      break;
    }
    }
  }

  static void quote(std::string &out, const std::string &s) {
    out += '"';
    for (unsigned char c : s) {
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
        if (c < 0x20) {
          char buf[8];
          std::snprintf(buf, sizeof(buf), "\\u%04x", c);
          out += buf;
        } else {
          out += static_cast<char>(c);
        }
      }
    }
    out += '"';
  }

  static void skip(std::string_view t, std::size_t &p) {
    while (p < t.size() && (t[p] == ' ' || t[p] == '\t' || t[p] == '\n' || t[p] == '\r')) {
      ++p;
    }
  }

  static Json parseValue(std::string_view t, std::size_t &p) {
    skip(t, p);
    if (p >= t.size()) {
      throw std::runtime_error("unexpected end of JSON");
    }
    const char c = t[p];
    if (c == '{') {
      ++p;
      Json o = object();
      skip(t, p);
      if (p < t.size() && t[p] == '}') {
        ++p;
        return o;
      }
      for (;;) {
        skip(t, p);
        Json key = parseValue(t, p);
        if (!key.isString()) {
          throw std::runtime_error("JSON object key is not a string");
        }
        skip(t, p);
        if (p >= t.size() || t[p] != ':') {
          throw std::runtime_error("expected ':' in JSON object");
        }
        ++p;
        o.o_[key.s_] = parseValue(t, p);
        skip(t, p);
        if (p < t.size() && t[p] == ',') {
          ++p;
          continue;
        }
        if (p < t.size() && t[p] == '}') {
          ++p;
          return o;
        }
        throw std::runtime_error("expected ',' or '}' in JSON object");
      }
    }
    if (c == '[') {
      ++p;
      Json a = array();
      skip(t, p);
      if (p < t.size() && t[p] == ']') {
        ++p;
        return a;
      }
      for (;;) {
        a.a_.push_back(parseValue(t, p));
        skip(t, p);
        if (p < t.size() && t[p] == ',') {
          ++p;
          continue;
        }
        if (p < t.size() && t[p] == ']') {
          ++p;
          return a;
        }
        throw std::runtime_error("expected ',' or ']' in JSON array");
      }
    }
    if (c == '"') {
      ++p;
      std::string s;
      while (p < t.size() && t[p] != '"') {
        char ch = t[p++];
        if (ch == '\\' && p < t.size()) {
          const char e = t[p++];
          switch (e) {
          case 'n':
            s += '\n';
            break;
          case 't':
            s += '\t';
            break;
          case 'r':
            s += '\r';
            break;
          case 'b':
            s += '\b';
            break;
          case 'f':
            s += '\f';
            break;
          case 'u': {
            if (p + 4 > t.size()) {
              throw std::runtime_error("bad \\u escape");
            }
            const unsigned code = std::stoul(std::string(t.substr(p, 4)), nullptr, 16);
            p += 4;
            if (code < 0x80) {
              s += static_cast<char>(code);
            } else if (code < 0x800) {
              s += static_cast<char>(0xC0 | (code >> 6));
              s += static_cast<char>(0x80 | (code & 0x3F));
            } else {
              s += static_cast<char>(0xE0 | (code >> 12));
              s += static_cast<char>(0x80 | ((code >> 6) & 0x3F));
              s += static_cast<char>(0x80 | (code & 0x3F));
            }
            break;
          }
          default:
            s += e;
          }
        } else {
          s += ch;
        }
      }
      if (p >= t.size()) {
        throw std::runtime_error("unterminated JSON string");
      }
      ++p;
      return Json(std::move(s));
    }
    if (t.substr(p, 4) == "true") {
      p += 4;
      return Json(true);
    }
    if (t.substr(p, 5) == "false") {
      p += 5;
      return Json(false);
    }
    if (t.substr(p, 4) == "null") {
      p += 4;
      return Json();
    }
    const std::size_t start = p;
    while (p < t.size() && (std::string_view("+-.0123456789eE").find(t[p]) != std::string_view::npos)) {
      ++p;
    }
    if (p == start) {
      throw std::runtime_error("unexpected character in JSON");
    }
    return Json(std::stod(std::string(t.substr(start, p - start))));
  }
};

} // namespace g4b
