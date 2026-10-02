package org.ipro.metadata.facet;

import java.util.Objects;

/**
 * Стабильный адрес факта метаданных — ключ, по которому будущее хранилище переопределений
 * (роль 3) сможет адресовать переопределение без перестройки модели.
 *
 * <p>Состав зависит от грани:</p>
 * <ul>
 *   <li>entity-уровень ({@link FacetKind#ENTITY_LIST_TITLE} и др.) — {@code entityClass},
 *       {@code fieldName = null}, {@code variant = null};</li>
 *   <li>поле ({@link FacetKind#FIELD_LABEL}, {@code FIELD_STRUCTURE}) — {@code entityClass}
 *       + {@code fieldName};</li>
 *   <li>колонка ({@link FacetKind#GRID_COLUMN_HEADER}) — {@code entityClass} + {@code fieldName}
 *       = путь колонки (имя поля или путь через точку);</li>
 *   <li>вариант-специфичная грань ({@link FacetKind#CONTEXT_FILTER_LABEL}) — дополнительно
 *       {@code variant} (null = общий ряд сущности).</li>
 * </ul>
 *
 * <p>Грани, добавленные срезом E3.1, не вводят нового состава: они берут тот же ключ, но
 * занимают ту его часть, которая соответствует природе факта. Это записано явно, чтобы
 * следующее хранилище не угадывало состав по аналогии.</p>
 * <ul>
 *   <li>entity-уровень — {@link FacetKind#ENTITY_KIND}, {@link FacetKind#ENTITY_EXPOSURE},
 *       {@link FacetKind#ENTITY_KEY}, {@link FacetKind#ENTITY_LIFECYCLE_HANDLER}:
 *       {@code entityClass}, {@code fieldName = null}, {@code variant = null};</li>
 *   <li>несколько экземпляров грани у одной сущности — {@link FacetKind#ENTITY_LIFECYCLE_HOOK}
 *       ({@code fieldName} = имя хука контракта) и {@link FacetKind#LINKABILITY}
 *       ({@code fieldName} = {@code ITEM[:вариант]} / {@code LIST[:вариант]}):
 *       {@code entityClass} + {@code fieldName}, {@code variant = null}.</li>
 * </ul>
 *
 * <p>Грани действий (E3.2.0) занимают <b>обе</b> необязательные части ключа сразу: действие
 * адресуется парой «поверхность + id» и работает в контексте варианта формы.</p>
 * <ul>
 *   <li>{@link FacetKind#ACTION} и {@link FacetKind#ACTION_HANDLER}:
 *       {@code entityClass} + {@code fieldName} = {@code "<ActionSurface>/<actionId>"} +
 *       {@code variant} = имя варианта формы ({@code null} = default-вариант). Поверхность входит
 *       в имя поля, а не в {@code variant}, потому что одно и то же действие может существовать на
 *       разных поверхностях с разными условиями, и это разные факты.</li>
 * </ul>
 *
 * <p>Грани сценариев чтения (E3.2.0 шаг 2) занимают только {@code fieldName}, и вариант
 * остаётся пустым — это отличие от действий, и оно измерено, а не выбрано: ключ плана — пара
 * «класс + сценарий» ({@code FetchPlanRegistry.plan}), варианта формы в нём нет.</p>
 * <ul>
 *   <li>{@link FacetKind#FETCH_PLAN}: {@code entityClass} + {@code fieldName} =
 *       {@code "<SCENARIO>"}, {@code variant = null};</li>
 *   <li>{@link FacetKind#FETCH_PLAN_PATH}: {@code entityClass} + {@code fieldName} =
 *       {@code "<SCENARIO>/<attributePath>"} — сценарий входит в имя поля, потому что один и тот
 *       же путь может быть в планах разных сценариев, и это разные факты с разными причинами.</li>
 * </ul>
 *
 * <p>Грани доступа (E3.2.0 шаг 3) занимают только {@code fieldName}, вариант остаётся пустым —
 * как у сценариев чтения: измерение не зависит от варианта формы.</p>
 * <ul>
 *   <li>{@link FacetKind#RLS_DIMENSION}: {@code entityClass} + {@code fieldName} =
 *       {@code "<dimension>"}, {@code variant = null};</li>
 *   <li>{@link FacetKind#RLS_VALUE_RULE}: {@code entityClass} + {@code fieldName} =
 *       {@code "<dimension>/<valuePath>"} — измерение входит в имя поля, потому что одно и то же
 *       измерение объявляют несколько типов (BRANCH — {@code Branch}, {@code ReceivingDocument},
 *       {@code Workshop}), и это разные правила с разными путями. У сложной политики
 *       ({@code custom = true}) строк правила нет вовсе: записанный {@code valuePaths} не действует
 *       (фильтрация идёт по {@code readCondition}), а для недействующего факта адрес не заводится.</li>
 * </ul>
 *
 * @param kind        вид грани (несёт и флаг переопределяемости)
 * @param entityClass сущность, которой принадлежит факт
 * @param fieldName   имя поля/путь колонки; null для entity-уровневых граней
 * @param variant     имя варианта (формы/ряда); null = default/общий
 */
public record FacetKey(FacetKind kind, Class<?> entityClass, String fieldName, String variant) {

    public FacetKey {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(entityClass, "entityClass");
    }

    /** Ключ entity-уровневой грани (без поля и варианта). */
    public static FacetKey of(FacetKind kind, Class<?> entityClass) {
        return new FacetKey(kind, entityClass, null, null);
    }

    /** Ключ грани поля (без варианта). */
    public static FacetKey of(FacetKind kind, Class<?> entityClass, String fieldName) {
        return new FacetKey(kind, entityClass, fieldName, null);
    }

    /** Полный ключ: грань + поле + вариант (для вариант-специфичных граней). */
    public static FacetKey of(FacetKind kind, Class<?> entityClass, String fieldName, String variant) {
        return new FacetKey(kind, entityClass, fieldName, variant);
    }
}
