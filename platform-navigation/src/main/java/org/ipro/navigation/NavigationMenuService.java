package org.ipro.navigation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Administrative operations on menus and user assignments. */
public interface NavigationMenuService {
    List<NavigationMenu> menus();
    NavigationStructure structure(long menuId);
    NavigationStructure save(long menuId, long expectedVersion, NavigationStructure draft);
    NavigationMenu importMenu(String name, NavigationStructure draft);
    NavigationStructure replaceMenu(long menuId, long expectedVersion, NavigationStructure draft);
    NavigationMenu copy(long sourceMenuId, String name);
    NavigationMenu createEmpty(String name);
    NavigationMenu rename(long menuId, long version, String name);
    void delete(long menuId, long version);
    Set<String> assignees(long menuId);
    void assign(long menuId, Set<String> add, Set<String> remove);
    Optional<Long> assignmentOf(String userKey);
    List<NavigationUserDirectory.UserOption> lookupUsers(String term, int limit);
    List<DiscoveredFunction> functions();
    Map<String, Map<Long, Long>> placements();

    record DiscoveredFunction(NavigationFunction function, Instant firstSeenAt) {}
}
