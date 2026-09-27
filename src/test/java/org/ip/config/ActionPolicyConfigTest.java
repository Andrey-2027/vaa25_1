package org.ip.config;

import org.ip.model.AttributeValue;
import org.ip.model.SklNomOpa;
import org.ipro.crud.BaseEntity;
import org.ipro.data.DataOperation;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.form.FieldFactory;
import org.ipro.form.action.ActionContextProvider;
import org.ipro.form.action.ActionDefinition;
import org.ipro.form.action.ActionRegistry;
import org.ipro.form.action.ActionResolver;
import org.ipro.form.action.ActionSurface;
import org.ipro.form.action.CrudAction;
import org.ipro.form.link.FormRouteCatalog;
import org.ipro.form.registry.FormRegistry;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.coordinator.ItemFormAccessBinder;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.rls.RlsUiGate;
import org.ipro.rls.RlsUiGate.AccessDecision;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * E1.4: состав действий для интернированных типов — «две разные capability-матрицы» (§2.3 плана E1).
 *
 * <p>Тест держит <b>настоящие</b> прикладные политики: capabilities берутся из бобов
 * {@link EntityClassificationConfig}, объявления действий — из бобов {@link ActionPolicyConfig}
 * (читаются рефлексией по {@code @Bean}, чтобы новая декларация не могла остаться незамеченной).
 * Моками закрыты только «сырые» границы каталога дескрипторов (persistence unit и реестр секций) и
 * {@link RlsUiGate}: эти типы не защищены RLS, поэтому шлюз здесь не решает ничего, а каталог
 * дескрипторов — тот же, что в приложении.</p>
 *
 * <p>Что именно закреплено:</p>
 * <ul>
 *   <li>{@code SklNomOpa} (generic writes нет) — список предлагает ровно {@code Open} и
 *       {@code Refresh}, и это следствие capability, а не скрытия кнопок: объявлений про этот тип
 *       нет вовсе;</li>
 *   <li>{@code AttributeValue} (generic {@code CREATE} разрешён, {@code UPDATE}/{@code DELETE} нет) —
 *       generic создание и копирование подавлены прикладной политикой, остальное скрыто по
 *       capability;</li>
 *   <li>подавление — решение UI: backend {@code CREATE} не сужен (§2.3), серверные запреты не
 *       меняются;</li>
 *   <li>карточки обоих типов открываются просмотром <b>без</b> сообщения о правах, а generic
 *       создание недоступно с нейтральной причиной.</li>
 * </ul>
 *
 * <p>Предметный путь создания значения («Добавить значение» → интернирование) проверен отдельно и
 * действием не является: {@code AttributeTypeForm} открывает свой диалог и вызывает
 * {@code AttributeValueService.createEnumValue}, а дедупликация по каноническому ключу — в
 * {@code AttributeValueServiceTest} ({@code stringDedupIsCaseInsensitiveAndTrims},
 * {@code numberCanonicalFormDedupsCommaAndTrailingZeros}, {@code refDedupsByRefIdNotByName}).</p>
 */
class ActionPolicyConfigTest {

    private final EntityClassificationConfig classification = new EntityClassificationConfig();
    private final ActionPolicyConfig actionPolicy = new ActionPolicyConfig();

    @Test
    void sklNomOpaListOffersOnlyReadActionsAndNeedsNoDeclaration() {
        assertThat(appOverrides())
            .as("состав списка выводится из capability: ручных объявлений про этот тип быть не должно")
            .noneMatch(definition -> SklNomOpa.class.equals(definition.entityType()));

        assertThat(offeredListActions(SklNomOpa.class))
            .as("ссылка на список адресуема (тип опубликован): affordance E2.1 не требует ни прав,"
                + " ни записи, поэтому он видим")
            .containsExactlyInAnyOrder("crud.open", "crud.refresh", "crud.copy_link");
    }

    @Test
    void attributeValueListLosesGenericCreateAndCopyOnly() {
        assertThat(offeredListActions(AttributeValue.class))
            .as("generic создание и копию значения атрибута заменяет предметное «Добавить значение»,"
                + " а ссылка на список остаётся: адрес — не операция над данными (E2.1)")
            .containsExactlyInAnyOrder("crud.open", "crud.refresh", "crud.copy_link");
    }

    @Test
    void declaredSuppressionsTargetStandardActionsOfThatType() {
        assertThat(appOverrides())
            .extracting(definition -> definition.id().value() + "@" + definition.entityType().getSimpleName())
            .containsExactlyInAnyOrder("crud.create@AttributeValue", "crud.copy@AttributeValue");
        assertThat(appOverrides())
            .as("подавление — про тип: оно не должно скрывать действие у всех сущностей")
            .allMatch(definition -> AttributeValue.class.equals(definition.entityType()));
    }

    @Test
    void uiPolicyDoesNotNarrowBackendCapabilities() {
        EntityDescriptorCatalog catalog = catalog();

        assertThat(catalog.descriptorOf(AttributeValue.class).capabilities()
            .allows(DataOperation.CREATE))
            .as("§2.3: backend CREATE не сужается в E1 — подавление живёт только в UI")
            .isTrue();
        assertThat(catalog.descriptorOf(AttributeValue.class).capabilities()
            .allows(DataOperation.UPDATE))
            .isFalse();
        assertThat(catalog.descriptorOf(SklNomOpa.class).capabilities()
            .allows(DataOperation.CREATE))
            .isFalse();
        assertThat(catalog.descriptorOf(SklNomOpa.class).capabilities()
            .allows(DataOperation.DELETE))
            .isFalse();
    }

    @Test
    void cardsOfInternedTypesOpenReadOnlyWithoutRightsMessage() {
        ItemFormAccessBinder binder = new ItemFormAccessBinder();

        for (BaseEntity saved : savedRows()) {
            ItemForm<?> form = formWith(saved);
            String name = saved.getClass().getSimpleName();

            binder.applyReadOnlyIfCannotSave(form, itemResolver(saved.getClass()));

            assertThat(form.isReadOnly()).as("%s", name).isTrue();
            assertThat(form.readOnlyReason().showsNotice())
                .as("%s: права не отказывали — тип просто не умеет менять запись", name)
                .isFalse();
            assertThat(form.readOnlyReason().noticeText()).isEmpty();
        }
    }

    @Test
    void genericCreateIsRefusedWithNeutralReasonForBothMatrices() {
        ItemFormAccessBinder binder = new ItemFormAccessBinder();

        String noWrites = binder.blockReasonIfCannotCreate(listResolver(SklNomOpa.class));
        String suppressed = binder.blockReasonIfCannotCreate(listResolver(AttributeValue.class));

        assertThat(noWrites).contains("CREATE");
        assertThat(suppressed)
            .as("подавленное действие не должно объясняться «нехваткой прав»")
            .isNotBlank()
            .doesNotContain("прав");
    }

    @Test
    void typedCreateOfValueRemainsPossibleWhereItBelongsTo() {
        ItemFormAccessBinder binder = new ItemFormAccessBinder();

        assertThat(binder.readOnlyReason(itemResolver(AttributeValue.class), null))
            .as("карточка новой строки сохраняется: создание значения не запрещено, ему лишь не"
                + " соответствует generic действие списка")
            .isNull();
    }

    // --- фикстуры -------------------------------------------------------------------------------

    /** Прикладные объявления действий: читаются рефлексией, чтобы состав не разошёлся с тестом. */
    private List<ActionDefinition> appOverrides() {
        return Arrays.stream(ActionPolicyConfig.class.getDeclaredMethods())
            .filter(method -> method.isAnnotationPresent(Bean.class))
            .map(method -> {
                try {
                    return (ActionDefinition) method.invoke(actionPolicy);
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException("не удалось прочитать объявление "
                        + method.getName(), ex);
                }
            })
            .toList();
    }

    private ActionRegistry registry() {
        return new ActionRegistry(CrudAction.platformDefaults(), appOverrides());
    }

    /**
     * Состав тулбара списка: не «что зарегистрировано», а «что предлагается решением» —
     * capability, подавление и права считает та же функция, что и в UI. Строка не выбрана,
     * поэтому строковые действия остаются видимыми, но недоступными — это и есть вопрос состава.
     */
    private List<String> offeredListActions(Class<?> type) {
        ActionResolver resolver = listResolver(type);
        return Arrays.stream(CrudAction.values())
            .filter(action -> resolver.decide(action, null, true).visible())
            .map(action -> action.id().value())
            .toList();
    }

    private ActionResolver listResolver(Class<?> type) {
        return new ActionResolver(registry(), provider(),
            org.ipro.form.action.ActionHandlerRegistry.empty(),
            ActionSurface.LIST_TOOLBAR, type, null);
    }

    private ActionResolver itemResolver(Class<?> type) {
        return new ActionResolver(registry(), provider(),
            org.ipro.form.action.ActionHandlerRegistry.empty(),
            ActionSurface.ITEM_FOOTER, type, null);
    }

    /**
     * Входы решения: capability типа, права и адресуемость формы (E2.1).
     *
     * <p>Каталог адресов настоящий, а не заглушка: без него действие, требующее ссылку
     * ({@code crud.copy_link}), падает с ошибкой композиции — и это правильно, потому что
     * «адреса спрашивать не у кого» не равно «адреса нет».</p>
     */
    private ActionContextProvider provider() {
        EntityDescriptorCatalog descriptors = catalog();
        return new ActionContextProvider(descriptors, allowedGate(),
            FormRouteCatalog.build(descriptors, new FormRegistry(), List.of()));
    }

    private EntityDescriptorCatalog catalog() {
        ManagedEntityCatalog managed = mock(ManagedEntityCatalog.class);
        when(managed.managedEntityClasses()).thenReturn(Set.of(AttributeValue.class, SklNomOpa.class));

        SectionMetadataRegistry sections = mock(SectionMetadataRegistry.class);
        when(sections.findByRow(any())).thenReturn(Optional.empty());

        return new EntityDescriptorCatalog(managed, sections, new MetadataResolver(),
            List.of(classification.sklNomOpaValueIsAnOwnedRow()),
            List.of(classification.attributeValueIsCreateOnly(),
                classification.sklNomOpaHasNoGenericWrites()));
    }

    private static RlsUiGate allowedGate() {
        RlsUiGate gate = mock(RlsUiGate.class);
        when(gate.canCreate(any())).thenReturn(AccessDecision.ALLOWED);
        when(gate.canUpdate(any())).thenReturn(AccessDecision.ALLOWED);
        when(gate.canDelete(any())).thenReturn(AccessDecision.ALLOWED);
        return gate;
    }

    /**
     * Сохранённые строки обоих типов: решение карточки зависит от состояния объекта (наличие
     * {@code id}), а не от класса. Фикстуры строятся штатными конструкторами самих сущностей.
     */
    private static List<BaseEntity> savedRows() {
        AttributeValue value = new AttributeValue();
        value.setId(42L);

        SklNomOpa set = new SklNomOpa(null, "canon", "Canon");
        set.setId(42L);

        return List.of(value, set);
    }

    @SuppressWarnings("unchecked")
    private static <T extends BaseEntity> ItemForm<T> formWith(T entity) {
        EntityMetadataInfo metadata = mock(EntityMetadataInfo.class);
        doReturn(entity.getClass()).when(metadata).getEntityClass();
        when(metadata.getFormFields()).thenReturn(List.of());
        ItemForm<T> form = new ItemForm<>(metadata, mock(FieldFactory.class));
        form.setEntity(entity);
        return form;
    }
}
