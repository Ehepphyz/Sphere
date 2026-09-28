package com.sphere.core.fastjet;

/** The N2MHTLazy25 strategy: tiles of size R/2 and their twenty-four neighbours. */
final class LazyTiling25 extends LazyTilingEngine {
    LazyTiling25(ClusterSequence cs) {
        super(cs, 2);
    }
}
