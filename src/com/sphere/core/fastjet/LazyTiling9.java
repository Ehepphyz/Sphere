package com.sphere.core.fastjet;

/** The N2MHTLazy9 strategy: tiles of size R and their eight neighbours. */
final class LazyTiling9 extends LazyTilingEngine {
    LazyTiling9(ClusterSequence cs) {
        super(cs, 1);
    }
}
