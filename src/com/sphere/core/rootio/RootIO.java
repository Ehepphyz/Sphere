package com.sphere.core.rootio;

import com.sphere.components.rootview.RootFile;
import com.sphere.components.rootview.RootKey;
import com.sphere.components.rootview.RootNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A ROOT file read in Java, objects and trees included: the keys of its
 * directories, its class descriptions, and any object or TTree in it, read
 * by following those descriptions. Nothing of ROOT is needed.
 */
public final class RootIO implements AutoCloseable {

    private final RootFile file;
    private Streamers streamers;
    private ObjectReader reader;

    private RootIO(RootFile file) {
        this.file = file;
    }

    public static RootIO open(Path path) throws IOException {
        return new RootIO(new RootFile(path));
    }

    public RootFile file() {
        return file;
    }

    @Override
    public void close() throws IOException {
        file.close();
    }

    /** The class descriptions, read the first time they are needed. */
    public synchronized Streamers streamers() throws IOException {
        if (streamers == null) {
            final RootKey k = file.streamerInfoKey();
            if (k == null) {
                streamers = new Streamers();
            } else {
                streamers = Streamers.read(file.payload(k), k.keylen);
            }
            reader = new ObjectReader(streamers);
        }
        return streamers;
    }

    public ObjectReader reader() throws IOException {
        streamers();
        return reader;
    }

    /** The keys of the top directory, in the order the file lists them. */
    public List<RootKey> keys() throws IOException {
        final List<RootKey> out = new ArrayList<>();
        for (RootNode n : file.tree().children) if (n.key != null) out.add(n.key);
        return out;
    }

    /** Every key, subdirectories included, with its path ("dir/name"). */
    public List<String> paths() throws IOException {
        final List<String> out = new ArrayList<>();
        collect(file.tree(), "", out);
        return out;
    }

    private static void collect(RootNode node, String prefix, List<String> out) {
        for (RootNode c : node.children) {
            final String p = prefix.isEmpty() ? c.name : prefix + "/" + c.name;
            out.add(p);
            if (c.directory) collect(c, p, out);
        }
    }

    /** The key of that name ("dir/name" for a subdirectory), the highest cycle; null when absent. */
    public RootKey key(String path) throws IOException {
        RootNode node = file.tree();
        final String[] parts = path.split("/");
        for (int i = 0; i < parts.length; i++) {
            String want = parts[i];
            int cycle = -1;
            final int semi = want.indexOf(';');
            if (semi >= 0) {
                cycle = Integer.parseInt(want.substring(semi + 1));
                want = want.substring(0, semi);
            }
            RootNode best = null;
            for (RootNode c : node.children) {
                if (!c.name.equals(want)) continue;
                if (cycle >= 0 && c.key != null && c.key.cycle != cycle) continue;
                if (best == null || (c.key != null && best.key != null && c.key.cycle > best.key.cycle)) best = c;
            }
            if (best == null) return null;
            if (i == parts.length - 1) return best.key;
            node = best;
        }
        return null;
    }

    /** The object behind a key, read by its class description. */
    public Object read(RootKey k) throws IOException {
        final byte[] payload = file.payload(k);
        return reader().readClass(new RBuffer(payload, k.keylen), k.className);
    }

    public Object read(String path) throws IOException {
        final RootKey k = key(path);
        if (k == null) throw new IOException("no object " + path + " in " + file.getPath().getFileName());
        return read(k);
    }

    /** The tree of that name, ready to read. */
    public RTree tree(String path) throws IOException {
        final RootKey k = key(path);
        if (k == null) throw new IOException("no tree " + path + " in " + file.getPath().getFileName());
        if (!k.className.equals("TTree") && !k.className.equals("TNtuple") && !k.className.equals("TNtupleD")) {
            throw new IOException(path + " is a " + k.className + ", not a TTree");
        }
        final Object o = read(k);
        if (!(o instanceof RObject t)) throw new IOException(path + " could not be read");
        return new RTree(this, t);
    }

    /** The trees in the top directory. */
    public List<String> trees() throws IOException {
        final List<String> out = new ArrayList<>();
        for (RootKey k : keys()) {
            if (k.className.equals("TTree") || k.className.startsWith("TNtuple")) {
                if (!out.contains(k.name)) out.add(k.name);
            }
        }
        return out;
    }
}
