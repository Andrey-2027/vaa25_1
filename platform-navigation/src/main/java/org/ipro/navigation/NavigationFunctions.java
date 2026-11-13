package org.ipro.navigation;

import java.util.List;
import java.util.Optional;

/** Application source of available functions, independent of any menu structure. */
public interface NavigationFunctions {
    List<NavigationFunction> all();

    Optional<NavigationFunction> find(String key);
}
