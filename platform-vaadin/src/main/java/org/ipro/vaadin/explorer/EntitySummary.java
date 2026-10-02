package org.ipro.vaadin.explorer;

import org.ipro.form.action.ActionSurface;
import org.ipro.rls.RlsDimensionKind;
import org.ipro.metadata.facet.FacetKey;
import org.ipro.metadata.facet.FacetKind;
import org.ipro.metadata.facet.ResolvedValue;
import org.ipro.metadata.MetadataDiagnostic;
import org.ipro.metadata.FactOrigin;

import java.util.List;

/**
 * Сводка о сущности для Entity Explorer — immutable, замкнутый словарь (П1): только
 * готовые резолвнутые факты, никаких {@code Object payload}, Vaadin-типов, callback'ов
 * и строкового SQL внутри.
 *
 * <p>Собирается {@link EntitySummaryAssembler} из уже резолвнутых реестров
 * (MetadataResolver, FormRegistry, ReferenceIndex, NumberingMetadataRegistry,
 * SubsystemRegistry, FacetResolver) — сам сводку нигде не резолвит и не хранит.</p>
 *
 * <p>Каждая показываемая строка несёт {@link FacetKey} (вид грани + стабильный адрес)
 * и {@link ResolvedValue} (значение + источник «код» на срезе 1; «переопределение»
 * появится со store-слоем роли 3). Ключ осмыслен для переопределяемых граней
 * ({@link FacetKind#overridable()}); структурные строки несут ключ для единообразия
 * поверхности чтения.</p>
 *
 * <p><b>Доступность инспекции чтения — отдельный факт.</b>
 * {@code readPlanInspectionAvailable} говорит, подключён ли владелец сценариев и путей. Без него
 * пустой список планов смешивал бы «действительный план без путей» и «инспекция не подключена»:
 * карточка не выдаёт второе за первое и не рисует нулевые счётчики там, где факт не спрошен.</p>
 */
public record EntitySummary(
        Class<?> entityClass,
        String simpleName,
        ResolvedValue displayName,
        List<OverviewRow> overview,
        List<FieldRow> fieldsForm,
        List<FieldRow> fieldsGrid,
        List<ColumnRow> listColumns,
        List<ColumnRow> selectColumns,
        List<SectionRow> tableSections,
        List<FormRow> forms,
        List<FilterRow> contextFilters,
        List<SelectionRow> selections,
        List<ReferenceRow> references,
        List<NumberingRow> numbering,
        List<LifecycleRow> lifecycle,
        List<DiagnosticRow> diagnostics,
        List<ActionRow> actions,
        List<ReadPlanRow> readPlans,
        List<AccessRow> accessRows,
        List<LookupRow> lookupTargets,
        /**
         * Подключён ли владелец сценариев и путей чтения. {@code false} — инспекция недоступна:
         * «путей нет» тогда не утверждается, и карточка показывает само состояние.
         */
        boolean readPlanInspectionAvailable) {

    /**
     * Совместимый конструктор сводки до появления явной доступности инспекции чтения: сводка
     * без флага читается как «инспекция подключена». Так её строят фикстуры и старые вызовы;
     * настоящий ответ даёт только {@link EntitySummaryAssembler}.
     */
    public EntitySummary(Class<?> entityClass, String simpleName, ResolvedValue displayName,
                         List<OverviewRow> overview, List<FieldRow> fieldsForm,
                         List<FieldRow> fieldsGrid, List<ColumnRow> listColumns,
                         List<ColumnRow> selectColumns, List<SectionRow> tableSections,
                         List<FormRow> forms, List<FilterRow> contextFilters,
                         List<SelectionRow> selections, List<ReferenceRow> references,
                         List<NumberingRow> numbering, List<LifecycleRow> lifecycle,
                         List<DiagnosticRow> diagnostics, List<ActionRow> actions,
                         List<ReadPlanRow> readPlans, List<AccessRow> accessRows,
                         List<LookupRow> lookupTargets) {
        this(entityClass, simpleName, displayName, overview, fieldsForm, fieldsGrid,
            listColumns, selectColumns, tableSections, forms, contextFilters, selections,
            references, numbering, lifecycle, diagnostics, actions, readPlans, accessRows,
            lookupTargets, true);
    }

    /** Совместимый конструктор сводки до появления аспекта связи (E3.2.0 шаг 4). */
    public EntitySummary(Class<?> entityClass, String simpleName, ResolvedValue displayName,
                         List<OverviewRow> overview, List<FieldRow> fieldsForm,
                         List<FieldRow> fieldsGrid, List<ColumnRow> listColumns,
                         List<ColumnRow> selectColumns, List<SectionRow> tableSections,
                         List<FormRow> forms, List<FilterRow> contextFilters,
                         List<SelectionRow> selections, List<ReferenceRow> references,
                         List<NumberingRow> numbering, List<LifecycleRow> lifecycle,
                         List<DiagnosticRow> diagnostics, List<ActionRow> actions,
                         List<ReadPlanRow> readPlans, List<AccessRow> accessRows) {
        this(entityClass, simpleName, displayName, overview, fieldsForm, fieldsGrid,
            listColumns, selectColumns, tableSections, forms, contextFilters, selections,
            references, numbering, lifecycle, diagnostics, actions, readPlans, accessRows,
            List.of());
    }

    /** Совместимый конструктор сводки до появления аспекта доступа (E3.2.0 шаг 3). */
    public EntitySummary(Class<?> entityClass, String simpleName, ResolvedValue displayName,
                         List<OverviewRow> overview, List<FieldRow> fieldsForm,
                         List<FieldRow> fieldsGrid, List<ColumnRow> listColumns,
                         List<ColumnRow> selectColumns, List<SectionRow> tableSections,
                         List<FormRow> forms, List<FilterRow> contextFilters,
                         List<SelectionRow> selections, List<ReferenceRow> references,
                         List<NumberingRow> numbering, List<LifecycleRow> lifecycle,
                         List<DiagnosticRow> diagnostics, List<ActionRow> actions,
                         List<ReadPlanRow> readPlans) {
        this(entityClass, simpleName, displayName, overview, fieldsForm, fieldsGrid,
            listColumns, selectColumns, tableSections, forms, contextFilters, selections,
            references, numbering, lifecycle, diagnostics, actions, readPlans, List.of(),
            List.of());
    }

    /** Совместимый конструктор сводки до появления сценариев чтения (E3.2.0 шаг 2). */
    public EntitySummary(Class<?> entityClass, String simpleName, ResolvedValue displayName,
                         List<OverviewRow> overview, List<FieldRow> fieldsForm,
                         List<FieldRow> fieldsGrid, List<ColumnRow> listColumns,
                         List<ColumnRow> selectColumns, List<SectionRow> tableSections,
                         List<FormRow> forms, List<FilterRow> contextFilters,
                         List<SelectionRow> selections, List<ReferenceRow> references,
                         List<NumberingRow> numbering, List<LifecycleRow> lifecycle,
                         List<DiagnosticRow> diagnostics, List<ActionRow> actions) {
        this(entityClass, simpleName, displayName, overview, fieldsForm, fieldsGrid,
            listColumns, selectColumns, tableSections, forms, contextFilters, selections,
            references, numbering, lifecycle, diagnostics, actions, List.of(), List.of(),
            List.of());
    }

    /** Совместимый конструктор сводки до появления действий (E3.2.0). */
    public EntitySummary(Class<?> entityClass, String simpleName, ResolvedValue displayName,
                         List<OverviewRow> overview, List<FieldRow> fieldsForm,
                         List<FieldRow> fieldsGrid, List<ColumnRow> listColumns,
                         List<ColumnRow> selectColumns, List<SectionRow> tableSections,
                         List<FormRow> forms, List<FilterRow> contextFilters,
                         List<SelectionRow> selections, List<ReferenceRow> references,
                         List<NumberingRow> numbering, List<LifecycleRow> lifecycle,
                         List<DiagnosticRow> diagnostics) {
        this(entityClass, simpleName, displayName, overview, fieldsForm, fieldsGrid,
            listColumns, selectColumns, tableSections, forms, contextFilters, selections,
            references, numbering, lifecycle, diagnostics, List.of(), List.of(), List.of(),
            List.of());
    }

    /** Совместимый конструктор сводки до появления lifecycle и диагностики. */
    public EntitySummary(Class<?> entityClass, String simpleName, ResolvedValue displayName,
                         List<OverviewRow> overview, List<FieldRow> fieldsForm,
                         List<FieldRow> fieldsGrid, List<ColumnRow> listColumns,
                         List<ColumnRow> selectColumns, List<SectionRow> tableSections,
                         List<FormRow> forms, List<FilterRow> contextFilters,
                         List<SelectionRow> selections, List<ReferenceRow> references,
                         List<NumberingRow> numbering) {
        this(entityClass, simpleName, displayName, overview, fieldsForm, fieldsGrid,
            listColumns, selectColumns, tableSections, forms, contextFilters, selections,
            references, numbering, List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of());
    }

    public EntitySummary {
        overview = List.copyOf(overview);
        fieldsForm = List.copyOf(fieldsForm);
        fieldsGrid = List.copyOf(fieldsGrid);
        listColumns = List.copyOf(listColumns);
        selectColumns = List.copyOf(selectColumns);
        tableSections = List.copyOf(tableSections);
        forms = List.copyOf(forms);
        contextFilters = List.copyOf(contextFilters);
        selections = List.copyOf(selections);
        references = List.copyOf(references);
        numbering = List.copyOf(numbering);
        lifecycle = List.copyOf(lifecycle);
        diagnostics = List.copyOf(diagnostics);
        actions = List.copyOf(actions);
        readPlans = List.copyOf(readPlans);
        accessRows = List.copyOf(accessRows);
        lookupTargets = List.copyOf(lookupTargets);
    }

    /** Общая поверхность любой показываемой строки: вид грани + ключ + эффективное значение. */
    public interface FacetRow {
        FacetKind kind();

        FacetKey key();

        ResolvedValue value();
    }

    /** Строка «Обзора»: подсистема, заголовки форм. caption — стабильная подпись строки. */
    public record OverviewRow(String caption, FacetKey key, ResolvedValue value,
                              String detail) implements FacetRow {
        public OverviewRow(String caption, FacetKey key, ResolvedValue value) {
            this(caption, key, value, "");
        }

        public OverviewRow {
            detail = detail == null ? "" : detail;
        }

        @Override
        public FacetKind kind() {
            return key.kind();
        }
    }

    /**
     * Поле сущности в проекции формы ({@code formFields}) или грида ({@code fieldsGrid}).
     * {@code value} — эффективная подпись (для формы — поле {@code FIELD_LABEL},
     * для грида — {@code GRID_COLUMN_HEADER}); код-дефолт: {@code @FieldMetadata.label()},
     * при отсутствии — имя поля.
     *
     * <p><b>Цели выбора здесь нет.</b> Она — факт грани {@link FacetKind#LOOKUP_TARGET} и живёт в
     * {@link LookupRow}, включая происхождение. Держать её ещё и текстом строки поля значило бы
     * иметь два представления одного факта, и второе — без происхождения.</p>
     */
    public record FieldRow(
            FacetKey key,
            String name,
            String typeLabel,
            ResolvedValue value,
            boolean required,
            boolean readOnly,
            FactOrigin requiredOrigin,
            FactOrigin typeOrigin) implements FacetRow {

        @Override
        public FacetKind kind() {
            return key.kind();
        }
    }

    /**
     * Колонка списка/выбора ({@code ColumnPath}): путь (возможно, через точку) +
     * эффективный заголовок (код-дефолт — заголовок пути из метаданных последнего сегмента).
     */
    public record ColumnRow(
            FacetKey key,
            String path,
            ResolvedValue value,
            String typeLabel,
            boolean nested,
            String note) implements FacetRow {
        public ColumnRow(FacetKey key, String path, ResolvedValue value,
                         String typeLabel, boolean nested) {
            this(key, path, value, typeLabel, nested, "");
        }

        public ColumnRow {
            note = note == null ? "" : note;
        }

        @Override
        public FacetKind kind() {
            return key.kind();
        }
    }

    /** Табличная часть документа (@TableSectionMetadata). */
    public record SectionRow(
            FacetKey key,
            ResolvedValue value,
            String rowClass,
            int order,
            int minRows,
            int formFieldCount,
            int gridFieldCount) implements FacetRow {
        @Override
        public FacetKind kind() {
            return key.kind();
        }
    }

    /**
     * Зарегистрированная форма/вариант (custom-фабрика из реестра или платформенный дефолт).
     * {@code source} — пути к .java-файлам классов-декларантов («где явно»): конфиг, View или
     * набор колонок, например {@code org/ip/views/forms/ReceivingDocumentFormConfig.java};
     * пусто для платформенных дефолтов и регистраций без указанного источника.
     */
    public record FormRow(
            org.ipro.form.registry.FormType formType,
            String variant,
            String registrationKind,
            boolean platformDefault,
            String source,
            FactOrigin origin,
            String symbol) {
        public FormRow(org.ipro.form.registry.FormType formType, String variant,
                       String registrationKind, boolean platformDefault, String source) {
            this(formType, variant, registrationKind, platformDefault, source,
                platformDefault ? FactOrigin.PLATFORM_DEFAULT : FactOrigin.REGISTRATION, "");
        }

        public FormRow {
            symbol = symbol == null ? "" : symbol;
        }
    }

    /**
     * Декларированный контекст-фильтр: ряд списка/выбора или конкретного варианта.
     * {@code source} — путь к .java-файлу конфига-декларанта (например
     * {@code org/ip/views/forms/PrdSpecListFormConfig.java}); пусто — не указан.
     */
    public record FilterRow(
            FacetKey key,
            String path,
            ResolvedValue value,
            String control,
            boolean required,
            boolean allListVariants,
            String scope,
            String source) implements FacetRow {
        @Override
        public FacetKind kind() {
            return key.kind();
        }
    }

    /** Именованный набор колонок Формы Выбора (data-вариант) или метаданные selectColumns. */
    public record SelectionRow(
            String variant,
            ResolvedValue value,
            List<String> columns,
            boolean registered) {
    }

    /** Обратная ссылка: {@code referencingClass} (поле {@code fieldName}) ссылается на сущность. */
    public record ReferenceRow(
            FacetKey key,
            Class<?> referencingClass,
            String fieldName,
            boolean columnRef,
            ResolvedValue value) implements FacetRow {
        @Override
        public FacetKind kind() {
            return key.kind();
        }
    }

    /** Декларация нумеруемого поля (@Numbered). */
    public record NumberingRow(
            FacetKey key,
            String fieldName,
            ResolvedValue value,
            String scope,
            String period,
            boolean manualAllowed) implements FacetRow {
        @Override
        public FacetKind kind() {
            return key.kind();
        }
    }

    /**
     * Срез прикладного lifecycle handler'а: строка handler'а плюс строка на каждый хук
     * контракта. {@code hook} пуст у handler-строки; {@code declared} осмыслен только у строки
     * хука — переопределён ли хук handler'ом или остался default-методом контракта. Ключ есть
     * у каждой строки, как и обещает {@link EntitySummary}.
     *
     * <p>Строки хуков публикуются только тогда, когда переопределение доказуемо: «хук не
     * переопределён» и «переопределение не подтверждено» — разные факты, и второй не выдаётся
     * за первый.</p>
     */
    public record LifecycleRow(FacetKey key, String hook, boolean declared,
                               ResolvedValue value, String note) {
        public LifecycleRow {
            hook = hook == null ? "" : hook;
            note = note == null ? "" : note;
        }
    }

    /**
     * Строка действия — объявление ({@link FacetKind#ACTION}) или исполнитель
     * ({@link FacetKind#ACTION_HANDLER}). Граней две, факт один, поэтому и запись одна: строки
     * обоих видов идут в одном списке и различаются {@code key().kind()}.
     *
     * <p>Какие поля осмысленны, зависит от грани, и это записано явно, чтобы карточка не читала
     * чужое:</p>
     * <ul>
     *   <li>обе строки — {@code key}, {@code surface}, {@code actionId}, {@code value}, {@code note};
     *       ключ обеих — {@code "<surface>/<actionId>"} + вариант формы;</li>
     *   <li>строка объявления — {@code title} (пусто у иконочного действия), {@code order},
     *       {@code visible} ({@code false} — действие подавлено приложением) и
     *       {@code applicableToAnyType} (объявлено без типа, то есть применимо к любому);</li>
     *   <li>строка исполнителя — {@code executorFound}; поля объявления у неё нейтральны
     *       ({@code title} пусто, {@code order} = 0) и карточкой не показываются.</li>
     * </ul>
     *
     * <p>Подавление — это UI-policy типа, а не отнятое право: {@code visible = false} идёт вместе
     * с примечанием о capability типа, иначе строка читалась бы как «право отнято». Права
     * пользователя и решение по конкретной строке в сводку не попадают — это не конфигурация
     * типа.</p>
     */
    public record ActionRow(
            FacetKey key,
            ActionSurface surface,
            String actionId,
            String title,
            int order,
            boolean visible,
            boolean applicableToAnyType,
            boolean executorFound,
            ResolvedValue value,
            String note) implements FacetRow {
        public ActionRow {
            title = title == null ? "" : title;
            note = note == null ? "" : note;
        }

        @Override
        public FacetKind kind() {
            return key.kind();
        }
    }

    /**
     * Строка сценария чтения (E3.2.0 шаг 2): сценарий, допущен ли он canonical path, сколько
     * путей несёт его план и откуда пришёл набор сценариев.
     *
     * <p>{@code value} несёт <b>признак допуска</b>, а имя сценария уже в {@code scenario}:
     * происхождение набора читается колонкой «Источник» ({@link ResolvedValue#origin()}),
     * а происхождение пути — причиной самого пути, поэтому в значении строки сценария
     * повторять код сценария нечего.</p>
     *
     * <p>{@code pathCount} и {@code paths} не заменяют друг друга: сценарий, допущенный с пустым
     * планом, — это «допущен, путей нет», а не «сценарий недоступен», и в карточке это читается
     * счётчиком, а не пустой ячейкой.</p>
     */
    public record ReadPlanRow(
            FacetKey key,
            String scenario,
            boolean allowed,
            int pathCount,
            ResolvedValue value,
            String note,
            List<PathRow> paths) implements FacetRow {

        public ReadPlanRow {
            scenario = scenario == null ? "" : scenario;
            note = note == null ? "" : note;
            paths = List.copyOf(paths);
        }

        @Override
        public FacetKind kind() {
            return key.kind();
        }
    }

    /**
     * Путь плана сценария (E3.2.0 шаг 2): адрес — «сценарий/путь», причина — из плана
     * ({@code metadata:<SCENARIO>}, {@code instance-name}, {@code lookup:<Owner.field>},
     * {@code reference-name…}).
     */
    public record PathRow(FacetKey key, String scenario, String attributePath, String reason,
                          ResolvedValue value) implements FacetRow {

        public PathRow {
            scenario = scenario == null ? "" : scenario;
            attributePath = attributePath == null ? "" : attributePath;
            reason = reason == null ? "" : reason;
        }

        @Override
        public FacetKind kind() {
            return key.kind();
        }
    }

    /**
     * Строка измерения RLS (E3.2.0 шаг 3): род измерения, служит ли оно каталогом грантов и где
     * объявлено; правила значения вложены списком, как пути плана в строке сценария.
     *
     * <p>Происхождение строки — {@code EXPLICIT}, символ — каноническое имя класса-носителя
     * аннотации, полученное у владельца ({@code RlsDimensionRegistry}). Носителем бывает не только
     * сущность: у измерений-ворот это вложенный интерфейс-маркер, и такой факт карточке сущности
     * не принадлежит.</p>
     *
     * <p>Значения грантов и результат проверки по конкретной записи в строку не попадают: это
     * данные и решения, а не конфигурация типа. {@code grantCatalog} говорит только, что измерение
     * служит источником значений грантов.</p>
     */
    public record AccessRow(
            FacetKey key,
            String dimension,
            RlsDimensionKind dimensionKind,
            boolean grantCatalog,
            ResolvedValue value,
            String note,
            List<AccessRuleRow> rules) implements FacetRow {

        public AccessRow {
            dimension = dimension == null ? "" : dimension;
            note = note == null ? "" : note;
            rules = List.copyOf(rules);
        }

        @Override
        public FacetKind kind() {
            return key.kind();
        }
    }

    /**
     * Правило значения измерения (E3.2.0 шаг 3): объявленный путь и то, что null в пути означает
     * «измерение к записи не применимо».
     *
     * <p>У сложной политики ({@code custom = true}) строк правила нет вовсе: записанный в
     * объявлении {@code valuePaths} там не действует — фильтрация идёт по {@code readCondition},
     * а значения поставляет запись. Пустой список правил у такой строки — факт, а не потеря.
     */
    public record AccessRuleRow(
            FacetKey key,
            String dimension,
            String path,
            boolean nullsNotApplicable,
            ResolvedValue value) implements FacetRow {

        public AccessRuleRow {
            dimension = dimension == null ? "" : dimension;
            path = path == null ? "" : path;
        }

        @Override
        public FacetKind kind() {
            return key.kind();
        }
    }

    /**
     * Цель выбора поля ({@code @Lookup}): куда ведёт ссылка и откуда это известно (E3.2.0 шаг 4).
     *
     * <p>Строка появляется только у поля, у которого цель есть ({@code hasLookup()}): ссылка без
     * цели — это отсутствие факта, а не «цель неизвестна», и подставлять туда «?» значило бы
     * догадываться за владельца.</p>
     *
     * <p>Происхождение — словарь владельца ({@code FieldMetadataInfo.getReferenceOrigin()}):
     * {@link FactOrigin#EXPLICIT} — цель объявлена в {@code @Lookup.entity},
     * {@link FactOrigin#JPA_MAPPING} — выведена из типа ассоциации, {@link FactOrigin#JAVA_TYPE} —
     * из обычного типа Java. Символ — место объявления <b>поля</b>: выведенная цель объявлена там
     * же, где и поле, и «нигде» не бывает.</p>
     *
     * <p>{@code targetType} — класс цели, а не текст: по нему карточка открывает структуру. Наружу
     * цель уходит как конфигурация типа; значения по конкретной записи (гранты, права) в строку не
     * попадают.</p>
     */
    public record LookupRow(
            FacetKey key,
            String fieldName,
            ResolvedValue value,
            Class<?> targetType,
            String variant,
            String note) implements FacetRow {

        public LookupRow {
            fieldName = fieldName == null ? "" : fieldName;
            variant = variant == null ? "" : variant;
            note = note == null ? "" : note;
        }

        @Override
        public FacetKind kind() {
            return key.kind();
        }
    }

    /**
     * Диагностическая запись с исходными entity/field. {@code key} пуст, когда диагностика
     * относится к уровню сущности/системы либо её код ещё не сопоставлен с известной гранью.
     */
    public record DiagnosticRow(MetadataDiagnostic.Severity severity, String code,
                                String entityFqn, String fieldName, String caption,
                                FacetKey key, ResolvedValue value, String source) {
        public DiagnosticRow {
            entityFqn = entityFqn == null ? "" : entityFqn;
            fieldName = fieldName == null ? "" : fieldName;
            caption = caption == null ? "" : caption;
            source = source == null ? "" : source;
        }
    }
}
