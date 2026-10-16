package com.sphere.core.rootio;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Where a ROOT file is, written the way ROOT accepts it in TFile::Open and
 * TChain::Add:
 * <ul>
 * <li>a local path, or a file: URL;</li>
 * <li>an http(s):// URL, read with range requests; a root:// URL of an EOS
 *     server is tried over HTTPS, which EOS serves on the same path;</li>
 * <li>"file.root/T": a tree name after the file name (TChain's slash-suffix style);</li>
 * <li>"?query": kept apart, and "zip=N" in it picks the Nth member of an archive;</li>
 * <li>"#fragment": a member of a zip archive ("multi.zip#Event.root") or a tree name.</li>
 * </ul>
 * This is what stressIOPlugins' "Filename formats when adding files to TChain"
 * checks, ported to Sphere.
 */
public final class RootLocation {

    /** The file itself: a path or a URL, without the tree suffix, the query or the fragment. */
    public final String file;
    /** The tree named in the location, or null. */
    public final String tree;
    public final String query;
    public final String fragment;
    /** The member of a zip archive to open, by name, or null. */
    public final String zipMember;
    /** The member of a zip archive to open, by index, or -1. */
    public final int zipIndex;
    public final boolean remote;

    private RootLocation(String file, String tree, String query, String fragment, String zipMember,
                         int zipIndex, boolean remote) {
        this.file = file;
        this.tree = tree;
        this.query = query;
        this.fragment = fragment;
        this.zipMember = zipMember;
        this.zipIndex = zipIndex;
        this.remote = remote;
    }

    public static RootLocation parse(String spec) {
        String s = spec.strip();
        String fragment = null;
        final int hash = s.indexOf('#');
        if (hash >= 0) {
            fragment = s.substring(hash + 1);
            s = s.substring(0, hash);
        }
        String query = null;
        final boolean remote = isRemote(s);
        final int q = s.indexOf('?');
        if (q >= 0 && (remote || q > 1)) {
            query = s.substring(q + 1);
            s = s.substring(0, q);
        }
        if (s.toLowerCase(Locale.ROOT).startsWith("root://") || s.toLowerCase(Locale.ROOT).startsWith("xroot://")) {
            final String rest = s.substring(s.indexOf("://") + 3);
            final int slash = rest.indexOf('/');
            final String host = slash < 0 ? rest : rest.substring(0, slash);
            String path = slash < 0 ? "/" : rest.substring(slash);
            while (path.startsWith("//")) {
                path = path.substring(1);
            }
            if (host.toLowerCase(Locale.ROOT).startsWith("eospublic.cern.ch") && path.startsWith("/eos/opendata/")) {
                // The CERN Open Data portal serves its EOS area over HTTPS, with ranges;
                // the xrootd door of eospublic does not answer HTTPS itself.
                s = "https://opendata.cern.ch" + path;
            } else {
                // other EOS instances answer HTTPS on the same host and path
                s = "https://" + host + path;
            }
        }
        String tree = null;
        final String lower = s.toLowerCase(Locale.ROOT);
        final int dotRoot = lower.lastIndexOf(".root/");
        if (dotRoot >= 0 && dotRoot + 6 < s.length()) {
            tree = s.substring(dotRoot + 6);
            s = s.substring(0, dotRoot + 5);
        }
        String member = null;
        int index = -1;
        final boolean zip = s.toLowerCase(Locale.ROOT).endsWith(".zip");
        if (zip && fragment != null && !fragment.isEmpty()) {
            member = fragment;
        } else if (fragment != null && !fragment.isEmpty()) {
            tree = fragment;
        }
        if (zip && query != null) {
            for (String part : query.split("&")) {
                if (part.startsWith("zip=")) {
                    try {
                        index = Integer.parseInt(part.substring(4));
                    } catch (NumberFormatException notAnIndex) {
                        member = part.substring(4);
                    }
                }
            }
        }
        return new RootLocation(s, tree, query, fragment, member, index, isRemote(s));
    }

    private static boolean isRemote(String s) {
        final String l = s.toLowerCase(Locale.ROOT);
        return l.startsWith("http://") || l.startsWith("https://") || l.startsWith("root://")
            || l.startsWith("xroot://");
    }

    public boolean isZip() {
        return file.toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    /** The last part of the location, for titles. */
    public String displayName() {
        String base = file;
        final int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        if (zipMember != null) {
            return base + "#" + zipMember;
        }
        return base;
    }

    /** Opens the file this location names, remote, local, or inside an archive. */
    public com.sphere.components.rootview.RootFile openFile() throws IOException {
        if (!remote && !isZip()) {
            final Path path = file.toLowerCase(Locale.ROOT).startsWith("file:")
                ? Path.of(URI.create(file)) : Path.of(file);
            return new com.sphere.components.rootview.RootFile(path);
        }
        SeekableByteChannel channel;
        long size;
        if (remote) {
            final HttpRangeChannel http = HttpRangeChannel.open(URI.create(file));
            channel = http;
            size = http.size();
        } else {
            final Path path = Path.of(file);
            channel = Files.newByteChannel(path);
            size = Files.size(path);
        }
        String name = displayName();
        if (isZip()) {
            try {
                final ZipArchive.Member m = ZipArchive.find(channel, size, zipMember, zipIndex);
                channel = new SliceChannel(channel, m.dataStart(), m.size());
                size = m.size();
                name = displayName().contains("#") ? displayName() : displayName() + "#" + m.name();
            } catch (IOException | RuntimeException notFound) {
                channel.close();
                throw notFound;
            }
        }
        final String safe = name.replaceAll("[\\\\/:*?\"<>|#]", "_");
        return new com.sphere.components.rootview.RootFile(channel, size, Path.of(safe), toString());
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder(file);
        if (tree != null && (fragment == null || !fragment.equals(tree))) {
            b.append('/').append(tree);
        }
        if (query != null) {
            b.append('?').append(query);
        }
        if (fragment != null) {
            b.append('#').append(fragment);
        }
        return b.toString();
    }
}
