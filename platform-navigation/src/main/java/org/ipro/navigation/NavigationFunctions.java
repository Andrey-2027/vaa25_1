package org.ipro.navigation;

import java.util.List;
import java.util.Optional;

/** Application source of available functions, independent of any menu structure. */
public interface NavigationFunctions {
    /** Functions that can be indexed once after application startup. */
    List<NavigationFunction> all();

    Optional<NavigationFunction> find(String key);

    /** Functions supplied by a catalog that can change while the application is running. */
    default List<NavigationFunction> dynamicFunctions() { return List.of(); }
}
