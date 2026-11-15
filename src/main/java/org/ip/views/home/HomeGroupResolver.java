package org.ip.views.home;

import org.ipro.form.link.FormRouteCatalog;
import org.ipro.metadata.SubsystemNode;
import org.ipro.metadata.SubsystemRegistry;
import org.ipro.navigation.NavigationEntityKeys;
import org.ipro.navigation.NavigationFunctionsProvider;
import org.ipro.navigation.NavigationMenuReader;
import org.ipro.navigation.NavigationStructure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Название группы для нового закреплённого: раздел меню пользователя (назначенного или основного), где
 * лежит функция; в нескольких разделах — первый по порядку меню. Для записи берётся раздел со списком её
 * сущности. Нет в меню — предметная область, нет области — «Прочее». Это только название для новой
 * группы: готовые группы пользователь переименовывает и переставляет сам.
 */
@Component
public class HomeGroupResolver {
    public static final String OTHER = "Прочее";
    private static final Logger log = LoggerFactory.getLogger(HomeGroupResolver.class);

    private final NavigationMenuReader menus;
    private final NavigationFunctionsProvider functions;
    private final SubsystemRegistry areas;
    private final FormRouteCatalog routes;

    public HomeGroupResolver(NavigationMenuReader menus, NavigationFunctionsProvider functions,
                             SubsystemRegistry areas, FormRouteCatalog routes) {
        this.menus = menus;
        this.functions = functions;
        this.areas = areas;
        this.routes = routes;
    }

    public String groupTitle(HomePin pin) {
        String functionKey = functionKeyOf(pin);
        if (functionKey == null) return OTHER;
        try {
            Optional<String> section = sectionTitle(menus.currentMenu(), functionKey);
            if (section.isPresent()) return section.get();
        } catch (RuntimeException failure) {
            log.warn("Не удалось прочитать меню для названия группы «Главного»", failure);
        }
        return functions.find(functionKey).map(function -> function.areaKey())
            .flatMap(this::areaTitle).orElse(OTHER);
    }

    /** Функция, по которой ищется место в меню: сама функция либо список сущности записи. */
    private String functionKeyOf(HomePin pin) {
        if (pin.isFunction()) return pin.key();
        return routes.find(pin.key()).isPresent() ? "list:" + pin.key() : null;
    }

    private static Optional<String> sectionTitle(NavigationStructure structure, String functionKey) {
        Map<String, List<NavigationStructure.Section>> byParent = new HashMap<>();
        for (var section : structure.sections()) {
            byParent.computeIfAbsent(section.parentId() == null ? "" : section.parentId(), key -> new ArrayList<>())
                .add(section);
        }
        return find(structure, byParent, "", functionKey);
    }

    private static Optional<String> find(NavigationStructure structure,
                                         Map<String, List<NavigationStructure.Section>> byParent,
                                         String parent, String functionKey) {
        var children = byParent.getOrDefault(parent, List.of()).stream()
            .sorted(Comparator.comparingInt(NavigationStructure.Section::position)
                .thenComparing(NavigationStructure.Section::id))
            .toList();
        for (var section : children) {
            boolean here = structure.entries().stream()
                .anyMatch(entry -> entry.sectionId().equals(section.id()) && entry.functionKey().equals(functionKey));
            if (here) return Optional.of(section.title());
            var nested = find(structure, byParent, section.id(), functionKey);
            if (nested.isPresent()) return nested;
        }
        return Optional.empty();
    }

    private Optional<String> areaTitle(String areaKey) {
        return areaKey == null ? Optional.empty() : areaTitle(areas.getRoots(), areaKey);
    }

    private static Optional<String> areaTitle(List<SubsystemNode> nodes, String areaKey) {
        for (SubsystemNode node : nodes) {
            if (areaKey.equals(NavigationEntityKeys.derive(node.getMarkerClass()))) return Optional.of(node.getTitle());
            var nested = areaTitle(node.getChildren(), areaKey);
            if (nested.isPresent()) return nested;
        }
        return Optional.empty();
    }
}
