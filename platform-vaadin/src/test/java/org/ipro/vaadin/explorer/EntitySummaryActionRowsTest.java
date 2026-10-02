package org.ipro.vaadin.explorer;

import org.ipro.data.DataOperation;
import org.ipro.data.EntityCapabilityOverride;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.form.action.ActionDefinition;
import org.ipro.form.action.ActionHandler;
import org.ipro.form.action.ActionHandlerRegistry;
import org.ipro.form.action.ActionId;
import org.ipro.form.action.ActionInvocation;
import org.ipro.form.action.ActionProvenanceCatalog;
import org.ipro.form.action.ActionRegistry;
import org.ipro.form.action.ActionRequirement;
import org.ipro.form.action.ActionSurface;
import org.ipro.form.action.CrudAction;
import org.ipro.form.registry.FormFactory;
import org.ipro.form.registry.FormRegistry;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.metadata.FactOrigin;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.ReferenceIndex;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.SubsystemRegistry;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;
import org.ipro.metadata.facet.FacetKey;
import org.ipro.metadata.facet.FacetKind;
import org.ipro.metadata.facet.FacetResolver;
import org.ipro.numbering.NumberingMetadataRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E3.2.0 шаг 1.3: строки действий в сводке сущности — объявление и исполнитель отдельными
 * гранями, с происхождением от владельца факта.
 *
 * <p><b>Почему на платформенных носителях, а не на типах приложения.</b> Здесь проверяются
 * ветви таблицы §2.1 плана шага: платформенный default, подавление определением-бином
 * приложения, прикладной исполнитель, отсутствие исполнителя и вариант без собственной формы.
 * Прикладные пилоты ({@code AttributeValue}, {@code SklNomOpa}, {@code PrdSpec}) — предмет
 * шага 1.4, и они проверяют не ветви модели, а факт на реальном типе.</p>
 *
 * <p><b>Почему носители — пробы, а не {@code org.ip}.</b> Платформенный модуль не знает
 * приложения: тест, знающий {@code org.ip}, был бы проверкой приложения внутри платформы.
 * Пробы объявляют ровно те три источника, которые различает каталог происхождения.</p>
 */
class EntitySummaryActionRowsTest {

    /** Пакет, в котором лежат пробы: тот же, что сканирует сборщик. */
    private static final String BASE_PACKAGE = "org.ipro.vaadin.explorer";

    private AnnotationConfigApplicationContext context;
    private FormRegistry formRegistry;
    private ActionRegistry actionRegistry;
    private ActionHandlerRegistry actionHandlers;
    private ActionProvenanceCatalog catalog;
    private MetadataResolver metadataResolver;
    private SectionMetadataRegistry sections;
    private EntitySummaryAssembler assembler;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(AppContributions.class);
        actionHandlers = new ActionHandlerRegistry(
            List.copyOf(context.getBeansOfType(ActionHandler.class).values()));
        List<ActionDefinition> declared = new ArrayList<>(CrudAction.platformDefaults());
        declared.addAll(actionHandlers.definitions());
        Map<String, ActionDefinition> beans =
            new LinkedHashMap<>(context.getBeansOfType(ActionDefinition.class));
        actionRegistry = new ActionRegistry(declared, List.copyOf(beans.values()));
        catalog = ActionProvenanceCatalog.ofBeans(actionRegistry, beans, actionHandlers,
            context.getBeanFactory());

        metadataResolver = new MetadataResolver();
        formRegistry = new FormRegistry();
        formRegistry.registerListForm(ProbeEntity.class, "materials", Mockito.mock(FormFactory.class));

        ReferenceIndex referenceIndex = new ReferenceIndex(BASE_PACKAGE);
        referenceIndex.afterPropertiesSet();
        NumberingMetadataRegistry numbering = new NumberingMetadataRegistry(BASE_PACKAGE);
        numbering.afterPropertiesSet();
        SubsystemRegistry subsystems = new SubsystemRegistry(BASE_PACKAGE);
        subsystems.afterPropertiesSet();
        sections = new SectionMetadataRegistry(BASE_PACKAGE, metadataResolver);
        sections.afterPropertiesSet();

        assembler = withDescriptors(descriptors(List.of()));
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    // ---------------------------------------------------------------- ветви §2.1

    @Test
    void platformDefaultsNameThePlatformAndSayTheyApplyToAnyType() {
        EntitySummary summary = assembler.summarize(ProbeEntity.class);

        // crud.delete у пробы не подавлен: победившая регистрация — платформенный default.
        EntitySummary.ActionRow delete =
            single(summary, FacetKind.ACTION, "LIST_TOOLBAR/" + CrudAction.DELETE.id().value());
        assertThat(delete.surface()).isEqualTo(ActionSurface.LIST_TOOLBAR);
        assertThat(delete.actionId()).isEqualTo(CrudAction.DELETE.id().value());
        assertThat(delete.title()).isEqualTo("Удалить");
        assertThat(delete.visible()).isTrue();
        assertThat(delete.applicableToAnyType()).isTrue();
        assertThat(delete.value().origin()).isEqualTo(FactOrigin.PLATFORM_DEFAULT);
        assertThat(delete.value().symbol()).isEqualTo(CrudAction.class.getName());
        assertThat(delete.note()).isEqualTo("платформенное действие, применимо к любому типу");
    }

    @Test
    void suppressionNamesTheDeclaringConfigurationAndDoesNotReadAsALostRight() {
        EntitySummary summary = assembler.summarize(ProbeEntity.class);

        EntitySummary.ActionRow suppressed = single(summary, FacetKind.ACTION,
            "LIST_TOOLBAR/crud.create");
        assertThat(suppressed.visible()).isFalse();
        assertThat(suppressed.value().origin()).isEqualTo(FactOrigin.REGISTRATION);
        assertThat(suppressed.value().symbol()).isEqualTo(AppContributions.class.getName());
        assertThat(suppressed.note())
            .as("подавление — политика типа, а не отнятое право: capability читается у типа")
            .isEqualTo("подавлено приложением; capability типа запись допускает");
    }

    @Test
    void applicationHandlerNamesTheExecutorClassForBothFacts() {
        EntitySummary summary = assembler.summarize(ProbeEntity.class);

        EntitySummary.ActionRow declaration = single(summary, FacetKind.ACTION,
            "LIST_TOOLBAR/" + ProbeAction.ID.value());
        EntitySummary.ActionRow executor = single(summary, FacetKind.ACTION_HANDLER,
            "LIST_TOOLBAR/" + ProbeAction.ID.value());

        assertThat(declaration.applicableToAnyType()).isFalse();
        assertThat(declaration.value().origin()).isEqualTo(FactOrigin.REGISTRATION);
        assertThat(declaration.value().symbol()).isEqualTo(ProbeAction.class.getName());
        assertThat(executor.executorFound()).isTrue();
        assertThat(executor.value().value()).isEqualTo("найден");
        assertThat(executor.value().symbol()).isEqualTo(ProbeAction.class.getName());
        assertThat(executor.note()).isEmpty();
    }

    @Test
    void platformActionWithoutExecutorSaysSoInsteadOfShowingAnEmptyCell() {
        EntitySummary summary = assembler.summarize(ProbeEntity.class);

        EntitySummary.ActionRow refresh = single(summary, FacetKind.ACTION_HANDLER,
            "LIST_TOOLBAR/crud.refresh");
        assertThat(refresh.executorFound()).isFalse();
        assertThat(refresh.value().value()).isEqualTo("не найден");
        assertThat(refresh.value().symbol()).isEmpty();
        assertThat(refresh.note())
            .as("«исполнителя нет» и «действие не работает» — разные факты")
            .isEqualTo("исполнитель не зарегистрирован");
    }

    // ---------------------------------------------------------------- ключ и состав

    @Test
    void bothFacetsOfOneActionShareTheAddressAndDifferOnlyByKind() {
        EntitySummary summary = assembler.summarize(ProbeEntity.class);

        List<EntitySummary.ActionRow> rows = summary.actions();
        assertThat(rows).hasSizeGreaterThan(2);
        for (EntitySummary.ActionRow row : rows) {
            assertThat(row.kind()).isIn(FacetKind.ACTION, FacetKind.ACTION_HANDLER);
            assertThat(row.key().kind()).isEqualTo(row.kind());
            assertThat(row.key().entityClass()).isEqualTo(ProbeEntity.class);
            assertThat(row.key().fieldName())
                .isEqualTo(row.surface().name() + "/" + row.actionId());
            assertThat(row.key().variant())
                .as("вариант строки — вариант формы или default, без выдуманных")
                .isIn(null, "materials", "special");
        }

        for (EntitySummary.ActionRow row : rows) {
            if (row.kind() != FacetKind.ACTION) {
                continue;
            }
            assertThat(rows)
                .as("у каждого объявления есть строка исполнителя по тому же адресу: %s", row.key())
                .anyMatch(candidate -> candidate.kind() == FacetKind.ACTION_HANDLER
                    && sameAddress(candidate.key(), row.key()));
        }
    }

    @Test
    void actionDeclaredForVariantWhoseFormIsUnknownToTheRegistryIsStillShown() {
        EntitySummary summary = assembler.summarize(ProbeEntity.class);

        EntitySummary.ActionRow special = summary.actions().stream()
            .filter(row -> "special".equals(row.key().variant()))
            .filter(row -> row.kind() == FacetKind.ACTION)
            .filter(row -> VariantAction.ID.value().equals(row.actionId()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("объявленное действие варианта не показано: "
                + summary.actions().stream().map(r -> r.key().toString()).toList()));

        assertThat(special.actionId()).isEqualTo(VariantAction.ID.value());
        assertThat(special.note()).isEqualTo("объявлено для варианта «special»");

        assertThat(summary.actions())
            .as("варианты, которых не знает ни форма, ни действие, не спрашиваются")
            .noneMatch(row -> "invented".equals(row.key().variant()));
    }

    @Test
    void actionsOfAnotherTypeAreNotShown() {
        EntitySummary other = assembler.summarize(OtherEntity.class);

        assertThat(other.actions())
            .as("прикладное действие объявлено для ProbeEntity")
            .noneMatch(row -> row.actionId().startsWith("probe."));
        assertThat(other.actions()).isNotEmpty();
    }

    @Test
    void rowsAreDeterministicAndOrderedBySurfaceVariantThenOrderThenId() {
        List<EntitySummary.ActionRow> first = assembler.summarize(ProbeEntity.class).actions();
        List<EntitySummary.ActionRow> second = assembler.summarize(ProbeEntity.class).actions();

        assertThat(second).isEqualTo(first);

        List<Integer> surfaces = first.stream()
            .map(EntitySummary.ActionRow::surface)
            .map(Enum::ordinal)
            .toList();
        assertThat(surfaces)
            .as("поверхности идут в порядке перечисления и не перемешиваются: "
                + "сортировка по поверхности \u2192 варианту \u2192 порядку \u2192 id")
            .isSorted();

        List<Integer> orders = first.stream()
            .filter(row -> row.kind() == FacetKind.ACTION)
            .filter(row -> row.surface() == ActionSurface.LIST_TOOLBAR)
            .filter(row -> row.key().variant() == null)
            .map(EntitySummary.ActionRow::order)
            .toList();
        assertThat(orders).as("внутри поверхности-варианта — по порядку отображения")
            .isSorted();
    }

    // ---------------------------------------------------------------- честность источника

    @Test
    void noSourceCellEverPrintsAJavaFilePath() {
        for (Class<?> type : List.of(ProbeEntity.class, OtherEntity.class)) {
            for (EntitySummary.ActionRow row : assembler.summarize(type).actions()) {
                assertThat(row.value().symbol())
                    .as("символ — FQN класса или пусто, но не путь: %s", row.key())
                    .doesNotContain(".java")
                    .doesNotContain("/")
                    .doesNotContain("\\");
            }
        }
    }

    @Test
    void suppressionWithoutDescriptorSaysNothingAboutCapability() {
        EntitySummaryAssembler withoutDescriptors = withDescriptors(null);

        EntitySummary.ActionRow suppressed = single(withoutDescriptors.summarize(ProbeEntity.class),
            FacetKind.ACTION, "LIST_TOOLBAR/crud.create");

        assertThat(suppressed.visible()).isFalse();
        assertThat(suppressed.note())
            .as("без дескриптора capability неизвестна — она не выводится из отсутствия определения")
            .isEqualTo("подавлено приложением");
    }

    @Test
    void capabilitySentenceFollowsTheDescriptorNotTheSuppression() {
        // Тот же тип и то же подавление, но capability типа запись не допускает.
        EntitySummaryAssembler withoutWrite = withDescriptors(descriptors(
            List.of(new EntityCapabilityOverride(ProbeEntity.class, Set.of(FetchScenario.DETAIL),
                Set.of(), "тип обслуживается своим путём"))));

        EntitySummary.ActionRow suppressed = single(withoutWrite.summarize(ProbeEntity.class),
            FacetKind.ACTION, "LIST_TOOLBAR/crud.create");

        assertThat(suppressed.visible()).isFalse();
        assertThat(suppressed.note()).isEqualTo("подавлено приложением; capability типа запись не допускает");
    }

    @Test
    void assemblerWithoutActionCollaboratorsShowsNoActionRows() {
        EntitySummaryAssembler bare = new EntitySummaryAssembler(BASE_PACKAGE, metadataResolver,
            formRegistry, new ReferenceIndex(BASE_PACKAGE), new NumberingMetadataRegistry(BASE_PACKAGE),
            new SubsystemRegistry(BASE_PACKAGE), FacetResolver.none());

        assertThat(bare.summarize(ProbeEntity.class).actions())
            .as("аспект не подключён — это не «действий нет», но и не выдуманные строки")
            .isEmpty();
    }

    // ---------------------------------------------------------------- носители

    /** JPA-тип: policy типа описывает именно persistence type, поэтому проба — настоящая сущность. */
    @jakarta.persistence.Entity
    @EntityMetadata(listFormTitle = "Пробная сущность")
    static class ProbeEntity {

        @jakarta.persistence.Id
        private Long id;

        @FieldMetadata(label = "Код", grid = @GridColumn(order = 1))
        String code;
    }

    @EntityMetadata(listFormTitle = "Другая сущность")
    static class OtherEntity {
    }

    /** Прикладное действие объявляет исполнитель: другого места объявления у него нет. */
    static class ProbeAction implements ActionHandler {

        static final ActionId ID = ActionId.of("probe.act");

        @Override
        public ActionDefinition definition() {
            return ActionDefinition.forEntity(ID, ActionSurface.LIST_TOOLBAR, ProbeEntity.class,
                "Обработать", null, 7, ActionRequirement.none());
        }

        @Override
        public void execute(ActionInvocation invocation) {
            throw new UnsupportedOperationException("заглушка измерения");
        }
    }

    /** Действие конкретного варианта, которого нет среди зарегистрированных форм. */
    static class VariantAction implements ActionHandler {

        static final ActionId ID = ActionId.of("probe.special");

        @Override
        public ActionDefinition definition() {
            return ActionDefinition.forEntity(ID, ActionSurface.LIST_TOOLBAR, ProbeEntity.class,
                "Особый вариант", null, 90, ActionRequirement.none())
                .withVariant("special");
        }

        @Override
        public void execute(ActionInvocation invocation) {
            throw new UnsupportedOperationException("заглушка измерения");
        }
    }

    /**
     * Три источника сборки, ровно как у приложения: исполнители-бины и определение-бин подавления.
     * Подавление объявлено бином, потому что только так площадка сборки знает класс-объявление.
     */
    @Configuration(proxyBeanMethods = false)
    static class AppContributions {

        @Bean
        ActionHandler probeAction() {
            return new ProbeAction();
        }

        @Bean
        ActionHandler variantAction() {
            return new VariantAction();
        }

        @Bean
        ActionDefinition probeSuppression() {
            return ActionDefinition.suppress(CrudAction.CREATE, ActionSurface.LIST_TOOLBAR,
                ProbeEntity.class);
        }
    }

    // ---------------------------------------------------------------- вспомогательное

    /** Сборщик с тем же набором коллабораторов и заданным дескриптором типа ({@code null} — нет). */
    private EntitySummaryAssembler withDescriptors(EntityDescriptorCatalog descriptors) {
        return new EntitySummaryAssembler(BASE_PACKAGE, metadataResolver, formRegistry,
            referenceIndex(), numbering(), subsystems(), FacetResolver.none(),
            descriptors, null, null, sections, null,
            actionRegistry, actionHandlers, catalog);
    }

    /** Дескрипторы с явной policy типа: capability читается у типа, а не у подавления. */
    private static EntityDescriptorCatalog descriptors(
            List<EntityCapabilityOverride> capabilityOverrides) {
        ManagedEntityCatalog managed = Mockito.mock(ManagedEntityCatalog.class);
        Mockito.when(managed.managedEntityClasses())
            .thenReturn(Set.of(ProbeEntity.class, OtherEntity.class));
        MetadataResolver resolver = new MetadataResolver();
        SectionMetadataRegistry sectionRegistry =
            new SectionMetadataRegistry(BASE_PACKAGE, resolver);
        sectionRegistry.afterPropertiesSet();
        return new EntityDescriptorCatalog(managed, sectionRegistry, resolver, List.of(),
            capabilityOverrides);
    }

    private ReferenceIndex referenceIndex() {
        ReferenceIndex index = new ReferenceIndex(BASE_PACKAGE);
        index.afterPropertiesSet();
        return index;
    }

    private NumberingMetadataRegistry numbering() {
        NumberingMetadataRegistry registry = new NumberingMetadataRegistry(BASE_PACKAGE);
        registry.afterPropertiesSet();
        return registry;
    }

    private SubsystemRegistry subsystems() {
        SubsystemRegistry registry = new SubsystemRegistry(BASE_PACKAGE);
        registry.afterPropertiesSet();
        return registry;
    }

    private static EntitySummary.ActionRow single(EntitySummary summary, FacetKind kind,
                                                  String fieldName) {
        List<EntitySummary.ActionRow> matches = summary.actions().stream()
            .filter(row -> row.kind() == kind)
            .filter(row -> row.key().fieldName().equals(fieldName))
            .filter(row -> row.key().variant() == null)
            .toList();
        assertThat(matches).as("ожидалась одна строка %s %s", kind, fieldName).hasSize(1);
        return matches.get(0);
    }

    private static boolean sameAddress(FacetKey left, FacetKey right) {
        return left.entityClass().equals(right.entityClass())
            && left.fieldName().equals(right.fieldName())
            && java.util.Objects.equals(left.variant(), right.variant());
    }

}
