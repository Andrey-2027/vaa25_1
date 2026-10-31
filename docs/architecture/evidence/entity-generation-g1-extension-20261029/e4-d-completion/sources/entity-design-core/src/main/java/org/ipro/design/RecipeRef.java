package org.ipro.design;

import java.util.Objects;

public record RecipeRef(String id, int version) {
    public RecipeRef { Objects.requireNonNull(id); }
}
