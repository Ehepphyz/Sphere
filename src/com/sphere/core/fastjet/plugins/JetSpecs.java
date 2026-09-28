package com.sphere.core.fastjet.plugins;

import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.JetDefinition;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Jet definitions from one line of text, for the command line and scripts:
 * the native algorithms as {@link JetDefinition#parse} reads them
 * ("antikt:0.4,scheme=pt,strategy=N2Tiled,precision=double"), and every
 * plugin as "name:R,key=value,...", for instance
 * "siscone:0.7,f=0.75,npass=0,ptmin=0,sm=pttilde",
 * "cmscone:0.5,seed=1", "pxcone:0.7,emin=5,f=0.5,mode=2,robust".
 */
public final class JetSpecs {

    private JetSpecs() {
    }

    /** The plugin names this reads, with their parameters and defaults. */
    public static Map<String, String> plugins() {
        final Map<String, String> m = new LinkedHashMap<>();
        m.put("siscone", "siscone:R,f=0.75,npass=0,ptmin=0,sm=pttilde|pt|mt|et,cache,progressive");
        m.put("sisconespherical", "sisconespherical:R,f=0.75,npass=0,emin=0,sm=etilde|e");
        m.put("cdfmidpoint", "cdfmidpoint:R,f=0.5,seed=1,area=1,pairs=2,iter=100,sm=pt|et|mt|pttilde");
        m.put("cdfjetclu", "cdfjetclu:R,f=0.75,seed=1,iratch=1");
        m.put("cmscone", "cmscone:R,seed=1");
        m.put("atlascone", "atlascone:R,seed=2,f=0.5");
        m.put("d0runii", "d0runii:R,etmin=6,f=0.5");
        m.put("d0runi", "d0runi:R,etmin=8,f=0.5,d0order");
        m.put("d0runipre96", "d0runipre96:R,etmin=8,f=0.5,d0order");
        m.put("pxcone", "pxcone:R,emin=5,f=0.5,mode=2,escheme,robust");
        m.put("trackjet", "trackjet:R");
        m.put("gridjet", "gridjet:spacing,ymax=5");
        m.put("eecambridge", "eecambridge:ycut");
        m.put("jade", "jade,strategy=nnfjn2plain|nnh");
        return m;
    }

    public static JetDefinition parse(String spec) {
        final String s = spec.trim();
        final int colon = s.indexOf(':');
        final int comma = s.indexOf(',');
        final int cut = colon >= 0 ? colon : (comma >= 0 ? comma : s.length());
        final String name = s.substring(0, cut).toLowerCase(Locale.ROOT);
        if (!plugins().containsKey(name) && !name.equals("cms") && !name.equals("atlas") && !name.equals("midpoint")
                && !name.equals("jetclu") && !name.equals("sisconespheri")) {
            return JetDefinition.parse(s);
        }
        final String rest = cut < s.length() ? s.substring(cut + 1) : "";
        final String[] parts = rest.isEmpty() ? new String[0] : rest.split(",");
        double first = Double.NaN;
        final Map<String, String> kv = new LinkedHashMap<>();
        for (String part : parts) {
            final String p = part.trim();
            if (p.isEmpty()) continue;
            final int eq = p.indexOf('=');
            if (eq < 0 && Double.isNaN(first) && isNumber(p)) first = Double.parseDouble(p);
            else if (eq < 0) kv.put(p.toLowerCase(Locale.ROOT), "true");
            else kv.put(p.substring(0, eq).trim().toLowerCase(Locale.ROOT), p.substring(eq + 1).trim());
        }
        final double r = Double.isNaN(first) ? d(kv, "r", 0.7) : first;
        final JetDefinition.Plugin plugin = switch (name) {
            case "siscone" -> {
                final SISConePlugin p = new SISConePlugin(r, d(kv, "f", 0.75), (int) d(kv, "npass", 0),
                    d(kv, "ptmin", 0.0), kv.containsKey("cache"));
                switch (kv.getOrDefault("sm", "pttilde").toLowerCase(Locale.ROOT)) {
                    case "pt" -> p.setSplitMergeScale(SISConePlugin.SplitMergeScale.SM_PT);
                    case "mt" -> p.setSplitMergeScale(SISConePlugin.SplitMergeScale.SM_MT);
                    case "et" -> p.setSplitMergeScale(SISConePlugin.SplitMergeScale.SM_ET);
                    default -> p.setSplitMergeScale(SISConePlugin.SplitMergeScale.SM_PTTILDE);
                }
                if (kv.containsKey("progressive")) p.setProgressiveRemoval(true);
                yield p;
            }
            case "sisconespherical", "sisconespheri" -> {
                final SISConeSphericalPlugin p = new SISConeSphericalPlugin(r, d(kv, "f", 0.75), (int) d(kv, "npass", 0),
                    d(kv, "emin", 0.0));
                if ("e".equalsIgnoreCase(kv.get("sm"))) p.setSplitMergeScale(SISConeSphericalPlugin.SplitMergeScale.SM_E);
                yield p;
            }
            case "cdfmidpoint", "midpoint" -> {
                final CDFMidPointPlugin.SplitMergeScale sm = switch (kv.getOrDefault("sm", "pt").toLowerCase(Locale.ROOT)) {
                    case "et" -> CDFMidPointPlugin.SplitMergeScale.SM_ET;
                    case "mt" -> CDFMidPointPlugin.SplitMergeScale.SM_MT;
                    case "pttilde" -> CDFMidPointPlugin.SplitMergeScale.SM_PTTILDE;
                    default -> CDFMidPointPlugin.SplitMergeScale.SM_PT;
                };
                yield new CDFMidPointPlugin(d(kv, "seed", 1.0), r, d(kv, "area", 1.0), (int) d(kv, "pairs", 2),
                    (int) d(kv, "iter", 100), d(kv, "f", 0.5), sm);
            }
            case "cdfjetclu", "jetclu" -> new CDFJetCluPlugin(r, d(kv, "f", 0.75), d(kv, "seed", 1.0), (int) d(kv, "iratch", 1));
            case "cmscone", "cms" -> new CMSIterativeConePlugin(r, d(kv, "seed", 1.0));
            case "atlascone", "atlas" -> new ATLASConePlugin(r, d(kv, "seed", 2.0), d(kv, "f", 0.5));
            case "d0runii" -> new D0RunIIConePlugin(r, d(kv, "etmin", 6.0), d(kv, "f", 0.5));
            case "d0runi" -> {
                final D0RunIConePlugin p = new D0RunIConePlugin(r, d(kv, "etmin", 8.0), d(kv, "f", 0.5));
                if (kv.containsKey("d0order")) p.withD0Parameters();
                yield p;
            }
            case "d0runipre96" -> {
                final D0RunIpre96ConePlugin p = new D0RunIpre96ConePlugin(r, d(kv, "etmin", 8.0), d(kv, "f", 0.5));
                if (kv.containsKey("d0order")) p.withD0Parameters();
                yield p;
            }
            case "pxcone" -> new PxConePlugin(r, d(kv, "emin", 5.0), d(kv, "f", 0.5), kv.containsKey("escheme"),
                (int) d(kv, "mode", 2)).setRobust(kv.containsKey("robust"));
            case "trackjet" -> new TrackJetPlugin(r);
            case "gridjet" -> new GridJetPlugin(d(kv, "ymax", 5.0), Double.isNaN(first) ? 1.0 : first);
            case "eecambridge" -> new EECambridgePlugin(Double.isNaN(first) ? d(kv, "ycut", 0.08) : first);
            case "jade" -> new JadePlugin("nnh".equalsIgnoreCase(kv.get("strategy"))
                ? JadePlugin.Strategy.NNH : JadePlugin.Strategy.NNFJN2PLAIN);
            default -> throw new FastJetException("Unknown plugin " + name);
        };
        final JetDefinition def = new JetDefinition(plugin);
        if (kv.containsKey("precision")) {
            def.setPrecision(com.sphere.core.fastjet.Precision.valueOf(kv.get("precision").toUpperCase(Locale.ROOT)
                .replace("DOUBLE-DOUBLE", "DD")));
        }
        return def;
    }

    private static double d(Map<String, String> kv, String key, double fallback) {
        final String v = kv.get(key);
        return v == null ? fallback : Double.parseDouble(v);
    }

    private static boolean isNumber(String s) {
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
