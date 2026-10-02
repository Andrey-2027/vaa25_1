package org.ip.vaadin.explorer;

import org.ip.config.ActionPolicyConfig;
import org.ip.config.EntityClassificationConfig;
import org.ip.model.AttributeValue;
import org.ip.model.Nomenclature;
import org.ip.model.PrdSpec;
import org.ip.model.ReceivingDocument;
import org.ip.model.SklNomOpa;
import org.ip.views.forms.PrdSpecMaterialsOnlyAction;
import org.ip.views.reportstudio.ReportPrintAction;
import org.ipro.crud.EntityLookup;
import org.ipro.data.DataOperation;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.form.action.ActionDefinition;
import org.ipro.form.action.ActionHandler;
import org.ipro.form.action.ActionHandlerRegistry;
import org.ipro.form.action.ActionId;
import org.ipro.form.action.ActionProvenanceCatalog;
import org.ipro.form.action.ActionRegistry;
import org.ipro.form.action.ActionSurface;
import org.ipro.form.action.CrudAction;
import org.ipro.form.registry.FormRegistry;
import org.ipro.jr.run.JrxmlExecutionService;
import org.ipro.jr.service.JrxmlTemplateService;
import org.ipro.metadata.AnnotationClassScanner;
import org.ipro.metadata.FactOrigin;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.ReferenceIndex;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.SubsystemRegistry;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.facet.FacetKind;
import org.ipro.metadata.facet.FacetResolver;
import org.ipro.metadata.facet.ResolvedValue;
import org.ipro.numbering.NumberingMetadataRegistry;
import org.ipro.reportstudio.param.ReportContextFactory;
import org.ipro.reportstudio.run.ReportExecutionService;
import org.ipro.reportstudio.service.ReportTemplateService;
import org.ipro.ureport.service.UreportTemplateService;
import org.ipro.vaadin.explorer.EntitySummary;
import org.ipro.vaadin.explorer.EntitySummaryAssembler;
import com.vaadin.flow.component.Component;
import org.ip.views.admin.CardSections;
import org.ip.views.admin.EntitySummaryPanel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * E3.2.0 шаг 1.4: аспект действий на <b>прикладных</b> типах — то, что карточка типа показывает про
 * действия этого приложения, а не про модельные ветви.
 *
 * <p><b>Почему на настоящих политиках.</b> Подавления берутся у {@link ActionPolicyConfig}, а
 * capability — у {@link EntityClassificationConfig}: если бы тест объявлял их сам, он проверял бы
 * свою фикстуру, а не приложение. Имена классов-объявления разрешает настоящая фабрика бинов
 * ({@code @Bean}-метод), поэтому символ подавления — {@code ActionPolicyConfig}, а не пусто.</p>
 *
 * <p><b>Что именно закреплено.</b> Подавление значения атрибута — политика приложения, а не отнятое
 * право (capability типа {@code CREATE} по-прежнему допускает); исполнитель спецификации назван
 * классом {@code PrdSpecMaterialsOnlyAction}; «Печать» объявлена на любой тип и исполняется
 * {@code ReportPrintAction}; у {@code SklNomOpa} прикладных объявлений нет вовсе, зато рядом с
 * платформенным действием сказано, какой операции тип не умеет.</p>
 *
 * <p><b>Чего приложение не даёт проверить.</b> Объявление, привязанного к варианту формы: у
 * {@code PrdSpecMaterialsOnlyAction} и {@code ReportPrintAction} вариант {@code null} (предметный
 * вариант {@code materials-only} — параметр открытия карточки, а не применимость действия), а
 * подавления адресованы типу. Эта ветвь проверена платформенным
 * {@code EntitySummaryActionRowsTest} на пробе, здесь дублировать её нечем.</p>
 */
class EntitySummaryActionPilotTest {

    private static final String BASE_PACKAGE = "org.ip";

    private final EntityClassificationConfig classification = new EntityClassificationConfig();

    private AnnotationConfigApplicationContext context;
    private EntityDescriptorCatalog descriptors;
    private EntitySummaryAssembler assembler;
    private Set<Class<?>> managedTypes;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(ActionPolicyConfig.class);
        ActionHandlerRegistry handlers = new ActionHandlerRegistry(
            List.of(new PrdSpecMaterialsOnlyAction(), printAction()));
        List<ActionDefinition> declared = new ArrayList<>(CrudAction.platformDefaults());
        declared.addAll(handlers.definitions());
        Map<String, ActionDefinition> beans =
            new LinkedHashMap<>(context.getBeansOfType(ActionDefinition.class));
        ActionRegistry registry = new ActionRegistry(declared, List.copyOf(beans.values()));
        ActionProvenanceCatalog catalog = ActionProvenanceCatalog.ofBeans(
            registry, beans, handlers, context.getBeanFactory());

        MetadataResolver metadataResolver = new MetadataResolver();
        ReferenceIndex referenceIndex = new ReferenceIndex(BASE_PACKAGE);
        referenceIndex.afterPropertiesSet();
        NumberingMetadataRegistry numbering = new NumberingMetadataRegistry(BASE_PACKAGE);
        numbering.afterPropertiesSet();
        SubsystemRegistry subsystems = new SubsystemRegistry(BASE_PACKAGE);
        subsystems.afterPropertiesSet();
        SectionMetadataRegistry sections =
            new SectionMetadataRegistry(BASE_PACKAGE, metadataResolver);
        sections.afterPropertiesSet();

        managedTypes = new LinkedHashSet<>(
            AnnotationClassScanner.scanAnnotated(BASE_PACKAGE, EntityMetadata.class));
        sections.all().forEach(section -> managedTypes.add(section.getRowClass()));
        ManagedEntityCatalog managed = mock(ManagedEntityCatalog.class);
        when(managed.managedEntityClasses()).thenReturn(managedTypes);
        descriptors = new EntityDescriptorCatalog(managed, sections, metadataResolver,
            List.of(classification.sklNomOpaValueIsAnOwnedRow()),
            List.of(classification.attributeValueIsCreateOnly(),
                classification.sklNomOpaHasNoGenericWrites()));

        assembler = new EntitySummaryAssembler(BASE_PACKAGE, metadataResolver, new FormRegistry(),
            referenceIndex, numbering, subsystems, FacetResolver.none(), descriptors, null, null,
            sections, null, registry, handlers, catalog);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    // ---------------------------------------------------------------- пилоты

    @Test
    void attributeValueSuppressionNamesTheDeclaringConfigurationAndIsNotALostRight() {
        EntitySummary.ActionRow create = row(AttributeValue.class, FacetKind.ACTION,
            ActionSurface.LIST_TOOLBAR, CrudAction.CREATE.id());

        assertThat(create.visible()).isFalse();
        assertThat(create.applicableToAnyType())
            .as("подавление адресовано типу, а не действию вообще")
            .isFalse();
        assertThat(create.value().origin()).isEqualTo(FactOrigin.REGISTRATION);
        assertThat(create.value().symbol()).isEqualTo(ActionPolicyConfig.class.getName());
        assertThat(create.note())
            .as("«подавлено» и «право отнято» — разные факты, и карточка их не смешивает")
            .isEqualTo("подавлено приложением; capability типа запись допускает");

        assertThat(descriptors.descriptorOf(AttributeValue.class).capabilities()
            .allows(DataOperation.CREATE))
            .as("одно и то же утверждение читается из обоих владельцев факта")
            .isTrue();

        EntitySummary.ActionRow executor = row(AttributeValue.class, FacetKind.ACTION_HANDLER,
            ActionSurface.LIST_TOOLBAR, CrudAction.CREATE.id());
        assertThat(executor.executorFound()).isFalse();
        assertThat(executor.value().value()).isEqualTo("не найден");
        assertThat(executor.note()).isEqualTo("исполнитель не зарегистрирован");
    }

    @Test
    void attributeValueHasExactlyItsTwoSuppressionsAndNoOtherTypeDeclaration() {
        List<String> applicationDeclarations = rows(AttributeValue.class, FacetKind.ACTION).stream()
            .filter(row -> row.value().origin() == FactOrigin.REGISTRATION)
            .map(row -> row.actionId() + "@" + row.surface())
            .toList();

        assertThat(applicationDeclarations)
            .as("«Печать» объявлена на любой тип, поэтому она присутствует и здесь: "
                + "это не объявление про значение атрибута")
            .containsExactlyInAnyOrder(
                CrudAction.CREATE.id().value() + "@" + ActionSurface.LIST_TOOLBAR,
                CrudAction.COPY.id().value() + "@" + ActionSurface.LIST_TOOLBAR,
                ReportPrintAction.ID.value() + "@" + ActionSurface.LIST_TOOLBAR);

        EntitySummary.ActionRow copy = row(AttributeValue.class, FacetKind.ACTION,
            ActionSurface.LIST_TOOLBAR, CrudAction.COPY.id());
        assertThat(copy.visible()).isFalse();
        assertThat(copy.note()).isEqualTo("подавлено приложением; capability типа запись допускает");
    }

    @Test
    void specificationNamesItsApplicationExecutorForBothFacts() {
        EntitySummary.ActionRow declaration = row(PrdSpec.class, FacetKind.ACTION,
            ActionSurface.LIST_TOOLBAR, PrdSpecMaterialsOnlyAction.ID);

        assertThat(declaration.title()).isEqualTo("Только материалы");
        assertThat(declaration.visible()).isTrue();
        assertThat(declaration.applicableToAnyType()).isFalse();
        assertThat(declaration.value().origin()).isEqualTo(FactOrigin.REGISTRATION);
        assertThat(declaration.value().symbol())
            .isEqualTo(PrdSpecMaterialsOnlyAction.class.getName());
        assertThat(declaration.note()).isEmpty();

        EntitySummary.ActionRow executor = row(PrdSpec.class, FacetKind.ACTION_HANDLER,
            ActionSurface.LIST_TOOLBAR, PrdSpecMaterialsOnlyAction.ID);
        assertThat(executor.executorFound()).isTrue();
        assertThat(executor.value().value()).isEqualTo("найден");
        assertThat(executor.value().symbol())
            .isEqualTo(PrdSpecMaterialsOnlyAction.class.getName());

        assertThat(rows(Nomenclature.class, FacetKind.ACTION))
            .as("предметное действие спецификации не должно появляться у чужого типа")
            .noneMatch(row -> row.actionId().equals(PrdSpecMaterialsOnlyAction.ID.value()));
    }

    @Test
    void printIsDeclaredForEveryTypeAndItsExecutorIsNamed() {
        for (Class<?> type : List.of(Nomenclature.class, ReceivingDocument.class,
                AttributeValue.class)) {
            String name = type.getSimpleName();

            EntitySummary.ActionRow declaration = row(type, FacetKind.ACTION,
                ActionSurface.LIST_TOOLBAR, ReportPrintAction.ID);
            assertThat(declaration.applicableToAnyType()).as("%s", name).isTrue();
            assertThat(declaration.title()).as("%s", name).isEqualTo("Печать");
            assertThat(declaration.value().origin()).as("%s", name)
                .isEqualTo(FactOrigin.REGISTRATION);
            assertThat(declaration.value().symbol()).as("%s", name)
                .isEqualTo(ReportPrintAction.class.getName());

            EntitySummary.ActionRow executor = row(type, FacetKind.ACTION_HANDLER,
                ActionSurface.LIST_TOOLBAR, ReportPrintAction.ID);
            assertThat(executor.executorFound()).as("%s", name).isTrue();
            assertThat(executor.value().symbol()).as("%s", name)
                .isEqualTo(ReportPrintAction.class.getName());
        }
    }

    @Test
    void sklNomOpaDeclaresNothingOfItsOwnAndExplainsWhatTheTypeCannotDo() {
        assertThat(rows(SklNomOpa.class, FacetKind.ACTION).stream()
            .filter(row -> row.actionId().startsWith("crud."))
            .map(EntitySummary.ActionRow::value)
            .map(ResolvedValue::origin)
            .toList())
            .as("у типа нет ни одного прикладного объявления: состав — платформенный")
            .containsOnly(FactOrigin.PLATFORM_DEFAULT);

        EntitySummary.ActionRow create = row(SklNomOpa.class, FacetKind.ACTION,
            ActionSurface.LIST_TOOLBAR, CrudAction.CREATE.id());
        assertThat(create.visible())
            .as("тип не умеет создавать: это capability, а не подавление приложением")
            .isTrue();
        assertThat(create.note()).isEqualTo("платформенное действие, применимо к любому типу;"
            + " capability типа запись не допускает");
        assertThat(descriptors.descriptorOf(SklNomOpa.class).capabilities()
            .allows(DataOperation.CREATE))
            .as("второй владелец того же факта подтверждает примечание")
            .isFalse();

        assertThat(row(SklNomOpa.class, FacetKind.ACTION, ActionSurface.LIST_TOOLBAR,
            CrudAction.OPEN.id()).note())
            .as("чтение у типа есть — объяснять нечего")
            .isEqualTo("платформенное действие, применимо к любому типу");

        assertThat(row(SklNomOpa.class, FacetKind.ACTION, ActionSurface.ITEM_FOOTER,
            CrudAction.SAVE.id()).note())
            .as("у crud.save операция выбирается состоянием записи: одна строка-объявление "
                + "не может сказать, о какой из них речь, и карточка не выбирает за неё")
            .isEqualTo("платформенное действие, применимо к любому типу");
    }

    // ---------------------------------------------------------------- инварианты §2.2

    @Test
    void noSymbolIsInventedAndNoCellPrintsAJavaFilePath() throws Exception {
        for (Class<?> type : pilots()) {
            for (EntitySummary.ActionRow row : assembler.summarize(type).actions()) {
                String symbol = row.value().symbol();
                if (symbol.isEmpty()) {
                    continue;
                }
                assertThat(Class.forName(symbol))
                    .as("символ %s — класс-объявление или класс-исполнитель, а не догадка",
                        symbol)
                    .isNotNull();
                assertThat(symbol).doesNotContain(".java").doesNotContain("/").doesNotContain("\\");
            }
        }
    }

    @Test
    void everyDeclarationHasAnExecutorRowAndRowsAreDeterministic() {
        for (Class<?> type : pilots()) {
            List<EntitySummary.ActionRow> rows = assembler.summarize(type).actions();

            assertThat(assembler.summarize(type).actions()).as("%s", type.getSimpleName())
                .isEqualTo(rows);
            assertThat(rows).as("%s: строки не выдуманы", type.getSimpleName())
                .allMatch(row -> row.key().entityClass().equals(type));
            assertThat(rows).as("%s", type.getSimpleName())
                .allMatch(row -> row.key().fieldName()
                    .equals(row.surface().name() + "/" + row.actionId()));
            assertThat(rows).as("%s: готовые резолвнутые факты, без payload",
                type.getSimpleName())
                .allMatch(row -> row.value().source() != null);

            for (EntitySummary.ActionRow declaration : rows) {
                if (declaration.kind() != FacetKind.ACTION) {
                    continue;
                }
                assertThat(rows)
                    .as("у каждого объявления есть строка исполнителя по тому же адресу: %s",
                        declaration.key())
                    .anyMatch(candidate -> candidate.kind() == FacetKind.ACTION_HANDLER
                        && candidate.key().fieldName().equals(declaration.key().fieldName())
                        && Objects.equals(candidate.key().variant(),
                            declaration.key().variant()));
            }
        }
    }

    @Test
    void pilotsCoverTheTypesThePlanNamesAndTheCatalogKnowsAboutThem() {
        assertThat(managedTypes)
            .as("забор не должен быть вакуумным: дескрипторы пилотов обязаны существовать")
            .contains(AttributeValue.class, SklNomOpa.class, PrdSpec.class, Nomenclature.class,
                ReceivingDocument.class);
        assertThat(pilots()).allMatch(type -> !assembler.summarize(type).actions().isEmpty());
    }

    /**
     * E3.2.0 шаг 5.1: аспект действий виден в карточке двумя разделами. Пилот — {@code AttributeValue}:
     * у него подавлены {@code CREATE}/{@code COPY}, и карточка обязана показать это как регистрацию, а
     * не как отсутствие действия (приёмка §6 п.1).
     *
     * <p><b>Что проверено здесь и что — не здесь.</b> Здесь — что сводка приложения действительно
     * доходит до карточки и даёт оба раздела (объявление и исполнитель), а аспекты без владельца
     * разделов не рисуют. Текст ячеек проверяется помощниками панели в
     * {@code EntitySummaryPanelTextTest}: ячейки рендерит клиент, и «текст на экране» здесь
     * подменить нечем. Привязка «колонка → помощник» тестом пока не покрыта — это предмет забора
     * на состав разделов карточки.</p>
     */
    @Test
    void theCardDrawsBothActionSectionsForTheSuppressedCreatePilot() {
        EntitySummaryPanel panel = new EntitySummaryPanel(null);

        panel.show(assembler.summarize(AttributeValue.class));

        assertThat(sectionTitles(panel))
            .as("сводка приложения доходит до карточки: у объявления и исполнителя свои разделы")
            .contains("Действия", "Действия — исполнители");
        assertThat(sectionTitles(panel))
            .as("аспекты без владельца факта разделов не рисуют")
            .doesNotContain("Сценарии чтения", "Доступ");
    }

    private static List<String> sectionTitles(Component root) {
        return CardSections.titles(root);
    }

    // ---------------------------------------------------------------- фикстуры

    private List<Class<?>> pilots() {
        return List.of(AttributeValue.class, SklNomOpa.class, PrdSpec.class, Nomenclature.class,
            ReceivingDocument.class);
    }

    private List<EntitySummary.ActionRow> rows(Class<?> type, FacetKind kind) {
        return assembler.summarize(type).actions().stream()
            .filter(row -> row.kind() == kind)
            .toList();
    }

    private EntitySummary.ActionRow row(Class<?> type, FacetKind kind, ActionSurface surface,
                                        ActionId id) {
        List<EntitySummary.ActionRow> matches = rows(type, kind).stream()
            .filter(row -> row.surface() == surface)
            .filter(row -> row.actionId().equals(id.value()))
            .filter(row -> row.key().variant() == null)
            .toList();
        assertThat(matches).as("%s: одна строка %s %s", type.getSimpleName(), kind, id.value())
            .hasSize(1);
        return matches.get(0);
    }

    /** Настоящее прикладное объявление «Печать»: моки — только её сервисы, не действие. */
    private static ActionHandler printAction() {
        return new ReportPrintAction(mock(ReportTemplateService.class),
            mock(ReportExecutionService.class), mock(EntityLookup.class),
            mock(SelectionFormAssembler.class), mock(UreportTemplateService.class),
            mock(JrxmlTemplateService.class), mock(JrxmlExecutionService.class),
            new ReportContextFactory(() -> "e3-2-test", Clock.systemDefaultZone()));
    }
}
