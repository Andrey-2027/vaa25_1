package org.ipro.reportstudio.query;

import org.ipro.reportstudio.param.EntityParamRefresher;
import org.ipro.reportstudio.param.ReportContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Служебные параметры запуска отчёта — доступны в JPQL без объявления в
 * шаблоне и без участия в форме параметров:
 * <ul>
 * <li>{@code parEntity} — экземпляр выбранной сущности (перезапрошен через
 *     {@link EntityParamRefresher} под RLS, тот же путь, что CONTEXT-параметры);
 *     в JPQL работает напрямую: {@code where uom = :parEntity};</li>
 * <li>{@code parEntityId} — идентификатор выбранной сущности (числовой);
 *     нотация {@code :parEntity.id} раскрывается в него до парсинга
 *     ({@link #expand}) — property-path на параметре не является стандартным
 *     JPQL и может не пройти парсер Hibernate;</li>
 * <li>{@code parEntityIds} — идентификаторы выделенных строк (для
 *     {@code IN (:parEntityIds)} при запуске из реестра).</li>
 * </ul>
 * Контекст запуска (выбранные записи) формируется кнопками печати ListForm/
 * ItemForm; из каталога отчётов контекст пустой, и служебные параметры будут
 * «не заданы» — так же, как CONTEXT.
 */
public final class ServiceParams {

    public static final String ENTITY = "parEntity";
    public static final String ENTITY_ID = "parEntityId";
    public static final String ENTITY_IDS = "parEntityIds";

    public static final Set<String> NAMES = Set.of(ENTITY, ENTITY_ID, ENTITY_IDS);

    private static final Pattern ENTITY_ID_PATH =
        Pattern.compile(":" + ENTITY + "\\s*\\.\\s*id\\b");

    private ServiceParams() {
    }

    public static boolean isServiceName(String name) {
        return name != null && NAMES.contains(name);
    }

    /**
     * Раскрывает {@code :parEntity.id} в {@code :parEntityId}. Точечная нотация
     * применена только к служебному имени; остальной текст не изменяется.
     * Вызывается до всех парсящих точек: guard, executor, редакторский анализ.
     */
    public static String expand(String jpql) {
        if (jpql == null || jpql.isBlank()) {
            return jpql;
        }
        return ENTITY_ID_PATH.matcher(jpql).replaceAll(":" + ENTITY_ID);
    }

    /**
     * Служебные биндинги из контекста запуска. Сущность биндится только если
     * перезапрос под RLS её вернул (не найдена/недоступна — биндинга нет, и
     * запрос со ссылкой на параметр остановится с понятной ошибкой). Список
     * {@code parEntityIds} — как есть из контекста: построчные RLS-фильтры
     * применятся к самому запросу при выполнении.
     *
     * @return пустая карта, если контекст не предоставляет сущность
     */
    public static Map<String, Object> bindings(ReportContext context,
                                               EntityParamRefresher refresher) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (context != null && context.entityClass() != null && context.entityId() != null) {
            Object entity = refresher.refresh(context.entityClass(), context.entityId());
            if (entity != null) {
                result.put(ENTITY, entity);
                result.put(ENTITY_ID, context.entityId());
            }
        }
        if (context != null && context.selectedIds() != null && !context.selectedIds().isEmpty()) {
            result.put(ENTITY_IDS, List.copyOf(context.selectedIds()));
        }
        return result;
    }
}