package com.sphere.components.fileexplorerincludes;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;

public class FavoritesManager {

    private static final Set<File> favorites = new LinkedHashSet<>();

    public static void addFavorite(File file) {
        favorites.add(file.getAbsoluteFile());
    }

    public static void removeFavorite(File file) {
        favorites.remove(file.getAbsoluteFile());
    }

    public static boolean isFavorite(File file) {
        return favorites.contains(file.getAbsoluteFile());
    }

    public static File[] getFavorites() {
        return favorites.toArray(new File[0]);
    }
}

