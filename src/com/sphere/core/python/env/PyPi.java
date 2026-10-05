package com.sphere.core.python.env;

import com.sphere.components.rootview.Json;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.zip.GZIPInputStream;

/**
 * What PyPI knows about a package, fetched many at a time and kept on disk,
 * so that the next opening of the manager asks nothing.
 *
 * pip list --outdated asks PyPI for each package in turn: sixteen seconds for
 * ninety packages. Here a package costs its releases feed (the last forty
 * versions with their dates, ten kilobytes) and the small JSON of the
 * versions that matter (requires_python, yanked, the known vulnerabilities
 * of the installed one), all fetched in parallel batches.
 *
 * A firewall often lets python.exe out (pip works) and not java.exe. When
 * Java cannot connect, the batch is fetched by the environment's own
 * interpreter, in one run of twenty-four threads, into the same cache.
 */
public final class PyPi {

    /** The versions published, newest first as PyPI lists them. */
    public static final class Project {
        public String key = "";
        public boolean found = true;
        public String summary = "";
        public final List<Published> recent = new ArrayList<>();
        public boolean stale;

        /** The newest version that is not a pre-release (or the newest of all when allowed). */
        public Published newest(boolean prereleases) {
            Published best = null;
            for (Published p : recent) {
                if (p.parsed == null || (!prereleases && p.parsed.isPrerelease())) continue;
                if (best == null || p.parsed.compareTo(best.parsed) > 0) best = p;
            }
            return best;
        }

        /** The versions, newest first. */
        public List<Published> sorted(boolean prereleases) {
            final List<Published> out = new ArrayList<>();
            for (Published p : recent) if (p.parsed != null && (prereleases || !p.parsed.isPrerelease())) out.add(p);
            out.sort((a, b) -> b.parsed.compareTo(a.parsed));
            return out;
        }

        public Published find(String version) {
            final Pep440.Version v = Pep440.parse(version);
            for (Published p : recent) if (p.parsed != null && p.parsed.equals(v)) return p;
            return null;
        }
    }

    /** One version in the feed: its number and when it was published (read when asked: thousands are never shown). */
    public record Published(String version, Pep440.Version parsed, String rawDate) {
        public Instant date() {
            if (rawDate == null || rawDate.isBlank()) return null;
            try {
                return ZonedDateTime.parse(rawDate.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /** What PyPI says of one version. */
    public static final class Release {
        public String key = "";
        public String version = "";
        public boolean found = true;
        public String requiresPython = "";
        public boolean yanked;
        public String yankedReason = "";
        public String summary = "";
        public String license = "";
        public String home = "";
        public Instant uploaded;
        public final List<String> requiresDist = new ArrayList<>();
        public final List<Vulnerability> vulnerabilities = new ArrayList<>();
        public boolean stale;
    }

    /** A known vulnerability of a version, from the OSV database PyPI publishes. */
    public record Vulnerability(String id, List<String> aliases, String summary, List<String> fixedIn, String link) {
        /** The CVE number when there is one, else the advisory's own id. */
        public String label() {
            for (String a : aliases) if (a.startsWith("CVE-")) return a;
            return id;
        }
    }

    /** One page to have in the cache. */
    public record Job(String url, String file, Duration ttl) {
    }

    private static final Duration PROJECT_TTL = Duration.ofHours(6);
    private static final Duration RELEASE_TTL = Duration.ofHours(24);
    private static final int PARALLEL = 32;

    private static PyPi shared;

    private final Path cache;
    private final HttpClient http;
    private final Semaphore permits = new Semaphore(PARALLEL);
    private final Map<String, Project> projects = new ConcurrentHashMap<>();
    private final Map<String, Release> releases = new ConcurrentHashMap<>();
    private volatile String python;
    private volatile boolean javaBlocked;
    private volatile boolean offline;
    private volatile int requests;
    private volatile int cacheHits;
    private volatile String transport = "";

    private PyPi(Path cache) {
        this.cache = cache;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL).version(HttpClient.Version.HTTP_2).build();
    }

    /** The client of the session, with its cache in config/pypi-cache. */
    public static synchronized PyPi get() {
        if (shared == null) shared = new PyPi(Path.of("config", "pypi-cache").toAbsolutePath());
        return shared;
    }

    /** The interpreter that fetches when Java cannot reach PyPI. */
    public void setPython(String executable) {
        this.python = executable;
    }

    public boolean offline() {
        return offline;
    }

    public int requests() {
        return requests;
    }

    public int cacheHits() {
        return cacheHits;
    }

    /** How the last pages came: "Java", "Python (Java is blocked by a firewall)", "cache". */
    public String transport() {
        return transport;
    }

    /** Forgets what was read in this session; with disk, the cache too, so that PyPI is asked again. */
    public void forget(boolean disk) {
        projects.clear();
        releases.clear();
        if (disk) {
            try (var files = Files.list(cache)) {
                files.forEach(f -> {
                    try {
                        Files.deleteIfExists(f);
                    } catch (IOException ignored) {
                        // in use
                    }
                });
            } catch (IOException ignored) {
                // no cache yet
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Jobs                                                                */
    /* ------------------------------------------------------------------ */

    public static Job projectJob(String name) {
        final String key = Pep440.normalize(name);
        return new Job("https://pypi.org/rss/project/" + enc(key) + "/releases.xml", key + ".rss", PROJECT_TTL);
    }

    public static Job releaseJob(String name, String version) {
        final String key = Pep440.normalize(name);
        return new Job("https://pypi.org/pypi/" + enc(key) + "/" + enc(version) + "/json",
            key + "@" + version.replaceAll("[^A-Za-z0-9.+_-]", "_") + ".json", RELEASE_TTL);
    }

    /** Whether a page is in the cache, young enough (a recent 404 counts). */
    private boolean cached(Job j) {
        try {
            final Path f = cache.resolve(j.file());
            final Path missing = cache.resolve(j.file() + ".404");
            return Files.isRegularFile(missing) && young(missing, j.ttl()) || Files.isRegularFile(f) && young(f, j.ttl());
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Brings the pages into the cache, those not already there, in parallel:
     * by Java, or by the interpreter when Java is blocked.
     */
    public void prefetch(Collection<Job> jobs) {
        final Map<String, Job> todo = new LinkedHashMap<>();
        for (Job j : jobs) {
            if (cached(j)) cacheHits++;
            else todo.putIfAbsent(j.file(), j);
        }
        if (todo.isEmpty()) {
            if (transport.isEmpty()) transport = "cache";
            return;
        }
        List<Job> failed = new ArrayList<>(todo.values());
        if (!javaBlocked) failed = fetchByJava(failed);
        if (!failed.isEmpty() && python != null) failed = fetchByPython(failed);
        offline = !failed.isEmpty() && failed.size() == todo.size();
    }

    private List<Job> fetchByJava(List<Job> jobs) {
        final List<Job> failed = java.util.Collections.synchronizedList(new ArrayList<>());
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            final List<Future<?>> all = new ArrayList<>();
            for (Job j : jobs) {
                all.add(pool.submit(() -> {
                    if (javaBlocked) {
                        failed.add(j);
                        return;
                    }
                    try {
                        permits.acquire();
                        try {
                            fetchOne(j);
                        } finally {
                            permits.release();
                        }
                    } catch (ConnectException | java.net.http.HttpConnectTimeoutException e) {
                        javaBlocked = true;
                        failed.add(j);
                    } catch (IOException | InterruptedException e) {
                        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                        if (String.valueOf(e.getMessage()).contains("Permission denied")) javaBlocked = true;
                        failed.add(j);
                    }
                }));
            }
            for (Future<?> f : all) {
                try {
                    f.get();
                } catch (Exception ignored) {
                    // counted in failed
                }
            }
        }
        if (failed.size() < jobs.size()) transport = "Java";
        return failed;
    }

    private void fetchOne(Job j) throws IOException, InterruptedException {
        requests++;
        final HttpRequest req = HttpRequest.newBuilder(URI.create(j.url())).timeout(Duration.ofSeconds(20))
            .header("User-Agent", "Sphere-PyEnvManager/2 (+https://pypi.org)")
            .header("Accept-Encoding", "gzip").GET().build();
        final HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() == 404) {
            write(cache.resolve(j.file() + ".404"), "");
            return;
        }
        if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode());
        byte[] body = res.body();
        if (res.headers().firstValue("Content-Encoding").orElse("").contains("gzip")) {
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
                body = in.readAllBytes();
            }
        }
        write(cache.resolve(j.file()), new String(body, StandardCharsets.UTF_8));
    }

    /** The fetcher the interpreter runs: the jobs from a JSON file, twenty-four threads. No backslash: a text block. */
    static final String FETCHER = """
        import sys, os, json, gzip, concurrent.futures, urllib.request, urllib.error
        with open(sys.argv[1], encoding="utf-8") as f:
            jobs = json.load(f)

        def get(job):
            url, path = job
            req = urllib.request.Request(url, headers={"User-Agent": "Sphere-PyEnvManager/2 (python)", "Accept-Encoding": "gzip"})
            try:
                with urllib.request.urlopen(req, timeout=20) as r:
                    data = r.read()
                    if (r.headers.get("Content-Encoding") or "") == "gzip":
                        data = gzip.decompress(data)
                tmp = path + ".part"
                with open(tmp, "wb") as f:
                    f.write(data)
                os.replace(tmp, path)
                return 200
            except urllib.error.HTTPError as e:
                if e.code == 404:
                    open(path + ".404", "w").close()
                return e.code
            except Exception:
                return 0

        with concurrent.futures.ThreadPoolExecutor(24) as ex:
            codes = list(ex.map(get, jobs))
        sys.stdout.write(json.dumps(codes))
        """;

    private List<Job> fetchByPython(List<Job> jobs) {
        try {
            Files.createDirectories(cache);
            final Path dir = Path.of(System.getProperty("java.io.tmpdir"), "sphere-python");
            Files.createDirectories(dir);
            final Path script = dir.resolve("sphere_pypi_fetch_" + Integer.toHexString(FETCHER.hashCode()) + ".py");
            if (!Files.isRegularFile(script)) Files.writeString(script, FETCHER, StandardCharsets.UTF_8);
            final StringBuilder list = new StringBuilder("[");
            for (int i = 0; i < jobs.size(); i++) {
                list.append(i == 0 ? "" : ",").append('[').append(json(jobs.get(i).url())).append(',')
                    .append(json(cache.resolve(jobs.get(i).file()).toString())).append(']');
            }
            final Path file = Files.createTempFile(dir, "jobs", ".json");
            Files.writeString(file, list.append(']').toString(), StandardCharsets.UTF_8);
            requests += jobs.size();
            try {
                final String out = PyProbe.runScript(python, script, 120);
                final List<Job> failed = new ArrayList<>();
                final Object codes = Json.parse(out.substring(Math.max(0, out.indexOf('['))));
                if (codes instanceof List<?> l) {
                    for (int i = 0; i < jobs.size(); i++) {
                        final Object c = i < l.size() ? l.get(i) : null;
                        final long code = c instanceof Number n ? n.longValue() : 0;
                        if (code != 200 && code != 404) failed.add(jobs.get(i));
                    }
                }
                if (failed.size() < jobs.size()) {
                    transport = javaBlocked ? "Python (a firewall blocks Java)" : "Python";
                }
                return failed;
            } finally {
                Files.deleteIfExists(file);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return jobs;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Reading                                                             */
    /* ------------------------------------------------------------------ */

    /** The versions of a project, from the cache (fetched first if it is not there). */
    public Project project(String name) {
        final String key = Pep440.normalize(name);
        return projects.computeIfAbsent(key, k -> {
            final Job j = projectJob(k);
            if (!cached(j)) prefetch(List.of(j));
            return parseProject(k, j);
        });
    }

    /** One version of a project, from the cache (fetched first if it is not there). */
    public Release release(String name, String version) {
        final String key = Pep440.normalize(name);
        return releases.computeIfAbsent(key + "@" + version, k -> {
            final Job j = releaseJob(key, version);
            if (!cached(j)) prefetch(List.of(j));
            return parseRelease(key, version, j);
        });
    }


    private Project parseProject(String key, Job j) {
        final Project p = new Project();
        p.key = key;
        final String body = read(j);
        if (body == null) {
            p.found = false;
            p.stale = !Files.isRegularFile(cache.resolve(j.file() + ".404"));
            return p;
        }
        p.stale = !cached(j);
        // Item by item with indexOf: a regular expression with lazy spans backtracks over the whole feed.
        int at = body.indexOf("<item>");
        while (at >= 0) {
            final int end = body.indexOf("</item>", at);
            if (end < 0) break;
            final String item = body.substring(at, end);
            final String v = unescape(between(item, "<title>", "</title>").strip());
            if (p.summary.isEmpty()) p.summary = unescape(between(item, "<description>", "</description>").strip());
            if (!v.isEmpty()) p.recent.add(new Published(v, Pep440.parse(v), between(item, "<pubDate>", "</pubDate>")));
            at = body.indexOf("<item>", end);
        }
        return p;
    }

    private static String between(String s, String open, String close) {
        final int a = s.indexOf(open);
        if (a < 0) return "";
        final int b = s.indexOf(close, a + open.length());
        return b < 0 ? "" : s.substring(a + open.length(), b);
    }

    /**
     * A release from its compact extract when it is there (a page of PyPI's
     * JSON carries the whole README; the extract only what the manager reads),
     * else from the page, the extract written for next time.
     */
    private Release parseRelease(String key, String version, Job j) {
        final Path full = cache.resolve(j.file());
        final Path lite = cache.resolve(j.file() + ".lite");
        try {
            if (Files.isRegularFile(lite) && Files.isRegularFile(full)
                && Files.getLastModifiedTime(lite).compareTo(Files.getLastModifiedTime(full)) >= 0) {
                final Release r = fromLite(key, version, Files.readString(lite, StandardCharsets.UTF_8));
                r.stale = !cached(j);
                return r;
            }
        } catch (IOException | RuntimeException ignored) {
            // read the page
        }
        final Release r = parseReleaseFull(key, version, j);
        if (r.found) write(lite, toLite(r));
        return r;
    }

    private static String toLite(Release r) {
        final StringBuilder b = new StringBuilder("{\"rp\":").append(json(r.requiresPython)).append(",\"y\":").append(r.yanked)
            .append(",\"yr\":").append(json(r.yankedReason)).append(",\"s\":").append(json(r.summary))
            .append(",\"l\":").append(json(r.license)).append(",\"h\":").append(json(r.home))
            .append(",\"u\":").append(json(r.uploaded == null ? "" : r.uploaded.toString())).append(",\"rd\":[");
        for (int i = 0; i < r.requiresDist.size(); i++) b.append(i == 0 ? "" : ",").append(json(r.requiresDist.get(i)));
        b.append("],\"v\":[");
        for (int i = 0; i < r.vulnerabilities.size(); i++) {
            final Vulnerability v = r.vulnerabilities.get(i);
            b.append(i == 0 ? "" : ",").append("{\"id\":").append(json(v.id())).append(",\"s\":").append(json(v.summary()))
                .append(",\"k\":").append(json(v.link())).append(",\"a\":[");
            for (int k = 0; k < v.aliases().size(); k++) b.append(k == 0 ? "" : ",").append(json(v.aliases().get(k)));
            b.append("],\"f\":[");
            for (int k = 0; k < v.fixedIn().size(); k++) b.append(k == 0 ? "" : ",").append(json(v.fixedIn().get(k)));
            b.append("]}");
        }
        return b.append("]}").toString();
    }

    private static Release fromLite(String key, String version, String text) {
        final Object o = Json.parse(text);
        final Release r = new Release();
        r.key = key;
        r.version = version;
        r.requiresPython = text(o, "rp");
        r.yanked = Boolean.TRUE.equals(Json.get(o, "y"));
        r.yankedReason = text(o, "yr");
        r.summary = text(o, "s");
        r.license = text(o, "l");
        r.home = text(o, "h");
        final String u = text(o, "u");
        if (!u.isEmpty()) r.uploaded = Instant.parse(u);
        for (Object d : Json.list(o, "rd")) r.requiresDist.add(String.valueOf(d));
        for (Object v : Json.list(o, "v")) {
            final List<String> aliases = new ArrayList<>();
            for (Object a : Json.list(v, "a")) aliases.add(String.valueOf(a));
            final List<String> fixed = new ArrayList<>();
            for (Object f : Json.list(v, "f")) fixed.add(String.valueOf(f));
            r.vulnerabilities.add(new Vulnerability(text(v, "id"), aliases, text(v, "s"), fixed, text(v, "k")));
        }
        return r;
    }

    private Release parseReleaseFull(String key, String version, Job j) {
        final Release r = new Release();
        r.key = key;
        r.version = version;
        final String body = read(j);
        if (body == null) {
            r.found = false;
            r.stale = true;
            return r;
        }
        r.stale = !cached(j);
        final Object root = Json.parse(body);
        final Object info = Json.get(root, "info");
        r.requiresPython = text(info, "requires_python");
        r.yanked = Boolean.TRUE.equals(Json.get(info, "yanked"));
        r.yankedReason = text(info, "yanked_reason");
        r.summary = text(info, "summary");
        final String le = text(info, "license_expression");
        r.license = !le.isEmpty() ? le : firstLine(text(info, "license"));
        r.home = text(info, "home_page");
        if (Json.get(info, "project_urls") instanceof Map<?, ?> urls) {
            for (Map.Entry<?, ?> e : urls.entrySet()) {
                final String label = String.valueOf(e.getKey()).toLowerCase(java.util.Locale.ROOT);
                if (r.home.isEmpty() || label.equals("homepage") || label.equals("home")) r.home = String.valueOf(e.getValue());
            }
        }
        for (Object d : Json.list(info, "requires_dist")) r.requiresDist.add(String.valueOf(d));
        final List<Object> files = Json.list(root, "urls");
        if (!files.isEmpty()) {
            try {
                r.uploaded = Instant.parse(Json.text(files.get(0), "upload_time_iso_8601", ""));
            } catch (RuntimeException ignored) {
                // no date
            }
        }
        for (Object v : Json.list(root, "vulnerabilities")) {
            final Object withdrawn = Json.get(v, "withdrawn");
            if (Boolean.TRUE.equals(withdrawn) || withdrawn instanceof String) continue;
            final List<String> aliases = new ArrayList<>();
            for (Object a : Json.list(v, "aliases")) aliases.add(String.valueOf(a));
            final List<String> fixed = new ArrayList<>();
            for (Object x : Json.list(v, "fixed_in")) fixed.add(String.valueOf(x));
            String summary = text(v, "summary");
            if (summary.isEmpty()) summary = firstLine(text(v, "details"));
            final Vulnerability vuln = new Vulnerability(Json.text(v, "id", "?"), aliases, summary, fixed, text(v, "link"));
            // The same flaw is published by several databases (PYSEC, GHSA) under one CVE: kept once.
            Vulnerability same = null;
            for (Vulnerability known : r.vulnerabilities) if (known.label().equals(vuln.label())) same = known;
            if (same == null) {
                r.vulnerabilities.add(vuln);
            } else {
                for (String f : fixed) if (!same.fixedIn().contains(f)) same.fixedIn().add(f);
                if (same.summary().isEmpty() && !summary.isEmpty()) {
                    r.vulnerabilities.set(r.vulnerabilities.indexOf(same), new Vulnerability(same.id(), same.aliases(), summary,
                        same.fixedIn(), same.link()));
                }
            }
        }
        return r;
    }

    /** The cached page, young or old; null when there is none (or a 404). */
    private String read(Job j) {
        try {
            final Path f = cache.resolve(j.file());
            if (Files.isRegularFile(cache.resolve(j.file() + ".404")) && !Files.isRegularFile(f)) return null;
            return Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String text(Object o, String key) {
        final Object v = Json.get(o, key);
        return v == null ? "" : String.valueOf(v);
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        final String line = s.strip().split("\\R", 2)[0];
        return line.length() > 160 ? line.substring(0, 157) + "..." : line;
    }

    private static boolean young(Path p, Duration ttl) throws IOException {
        return Files.getLastModifiedTime(p).toInstant().plus(ttl).isAfter(Instant.now());
    }

    private void write(Path p, String text) {
        try {
            Files.createDirectories(p.getParent());
            final Path tmp = p.resolveSibling(p.getFileName() + "." + Thread.currentThread().threadId() + ".part");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            // the cache is only a cache
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String json(String s) {
        final StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
    }
}
