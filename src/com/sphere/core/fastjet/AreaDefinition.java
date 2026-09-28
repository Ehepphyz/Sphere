package com.sphere.core.fastjet;

/**
 * Which area to compute and how, fastjet::AreaDefinition.
 */
public final class AreaDefinition {

    /** fastjet::AreaType. */
    public enum AreaType {
        INVALID(-1),
        /** Ghosts clustered with the event, then removed from the history. */
        ACTIVE(0),
        /** Ghosts clustered with the event and left in the jets. */
        ACTIVE_EXPLICIT_GHOSTS(1),
        /** One ghost at a time. */
        ONE_GHOST_PASSIVE(10),
        /** Passive area by the fastest method for the algorithm. */
        PASSIVE(11),
        /** The Voronoi cells of the particles, intersected with a circle of radius R. */
        VORONOI(20);

        public final int id;

        AreaType(int id) {
            this.id = id;
        }

        public static AreaType parse(String text) {
            return switch (text.trim().toLowerCase(java.util.Locale.ROOT).replace("-", "_")) {
                case "active", "active_area", "0" -> ACTIVE;
                case "explicit", "active_explicit_ghosts", "active_area_explicit_ghosts", "1" -> ACTIVE_EXPLICIT_GHOSTS;
                case "one_ghost_passive", "1ghost", "one_ghost_passive_area", "10" -> ONE_GHOST_PASSIVE;
                case "passive", "passive_area", "11" -> PASSIVE;
                case "voronoi", "voronoi_area", "20" -> VORONOI;
                default -> throw new FastJetException("Unknown area type '" + text
                    + "' (active, explicit, passive, 1ghost, voronoi)");
            };
        }
    }

    /** fastjet::VoronoiAreaSpec. */
    public static final class VoronoiAreaSpec {
        private final double effectiveRfact;

        public VoronoiAreaSpec() {
            this(1.0);
        }

        public VoronoiAreaSpec(double effectiveRfact) {
            this.effectiveRfact = effectiveRfact;
        }

        public double effectiveRfact() {
            return effectiveRfact;
        }

        public String description() {
            return "Voronoi area with effective_Rfact = " + Fmt.g(effectiveRfact);
        }
    }

    private final AreaType areaType;
    private final GhostedAreaSpec ghostSpec;
    private final VoronoiAreaSpec voronoiSpec;

    /** Active area with the default ghosts. */
    public AreaDefinition() {
        this(AreaType.ACTIVE, new GhostedAreaSpec());
    }

    public AreaDefinition(AreaType type, GhostedAreaSpec spec) {
        if (type == AreaType.VORONOI) {
            throw new FastJetException("A Voronoi area takes a VoronoiAreaSpec");
        }
        this.areaType = type;
        this.ghostSpec = spec;
        this.voronoiSpec = new VoronoiAreaSpec();
    }

    public AreaDefinition(GhostedAreaSpec spec) {
        this(AreaType.ACTIVE, spec);
    }

    public AreaDefinition(VoronoiAreaSpec spec) {
        this.areaType = AreaType.VORONOI;
        this.ghostSpec = new GhostedAreaSpec();
        this.voronoiSpec = spec;
    }

    public AreaDefinition(AreaType type) {
        this.areaType = type;
        this.ghostSpec = new GhostedAreaSpec();
        this.voronoiSpec = new VoronoiAreaSpec();
    }

    public AreaType areaType() {
        return areaType;
    }

    public GhostedAreaSpec ghostSpec() {
        return ghostSpec;
    }

    public VoronoiAreaSpec voronoiSpec() {
        return voronoiSpec;
    }

    public AreaDefinition withFixedSeed(int[] seed) {
        if (areaType == AreaType.VORONOI) {
            return this;
        }
        return new AreaDefinition(areaType, ghostSpec.withFixedSeed(seed));
    }

    public String description() {
        return switch (areaType) {
            case ACTIVE -> "Active area (hidden ghosts) with " + ghostSpec.description();
            case ACTIVE_EXPLICIT_GHOSTS -> "Active area (explicit ghosts) with " + ghostSpec.description();
            case ONE_GHOST_PASSIVE -> "Passive area (one ghost at a time) with " + ghostSpec.description();
            case PASSIVE -> "Passive area (optimal alg. based on jet.def.), where relevant with " + ghostSpec.description();
            case VORONOI -> voronoiSpec.description();
            default -> throw new FastJetException("Error: unrecognized area_type in AreaDefinition::description():" + areaType.id);
        };
    }

    @Override
    public String toString() {
        return description();
    }
}
