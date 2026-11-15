package org.ipro.query.ui.admin;

import org.ipro.query.list.QueryListRegistry;
import org.ipro.query.ui.editor.ReportQueryEditor;
import org.ipro.query.ui.grid.QueryGridEnvironment;
import org.ipro.query.ui.selection.EntitySelection;

import java.util.Objects;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Всё, что экран «Списки по запросу» берёт у приложения. Сам экран не знает ни Spring, ни форм платформы:
 * редактор запроса, названия сущностей и ссылки приходят снаружи.
 *
 * @param registry       реестр списков: перечень, проверка, запись
 * @param grid           окружение сетки для вкладки «Вид»
 * @param editors        фабрика редактора запроса; редактор создаётся на каждый открытый список
 * @param selection      выбор ссылки для значений параметров; может быть {@code null}
 * @param entityTitle    название сущности по имени из проверки запроса; для неизвестной — её имя
 * @param link           адрес списка по ключу: по нему сотрудники и администратор открывают список
 * @param inMemoryLimit  предел строк режима «Целиком в памяти»; он же верхняя граница «Первые N строк»
 * @param commandEntities классы сущностей с формами приложения
 */
public record QueryListAdminEnvironment(QueryListRegistry registry, QueryGridEnvironment grid,
                                        Supplier<ReportQueryEditor> editors, EntitySelection selection,
                                        Function<String, String> entityTitle, Function<String, String> link,
                                        int inMemoryLimit, List<String> commandEntities) {
    public QueryListAdminEnvironment(QueryListRegistry registry, QueryGridEnvironment grid,
                                     Supplier<ReportQueryEditor> editors, EntitySelection selection,
                                     Function<String, String> entityTitle, Function<String, String> link,
                                     int inMemoryLimit) {
        this(registry, grid, editors, selection, entityTitle, link, inMemoryLimit, List.of());
    }

    public QueryListAdminEnvironment {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(grid, "grid");
        Objects.requireNonNull(editors, "editors");
        Objects.requireNonNull(entityTitle, "entityTitle");
        Objects.requireNonNull(link, "link");
        commandEntities = List.copyOf(commandEntities);
        if (inMemoryLimit < 1) throw new IllegalArgumentException("inMemoryLimit");
    }
}
