package org.ipro.form.coordinator;

import jakarta.persistence.Entity;
import org.ipro.crud.BaseEntity;
import org.ipro.data.DataOperation;
import org.ipro.data.EntityCapabilityOverride;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.form.FieldFactory;
import org.ipro.form.action.ActionContextProvider;
import org.ipro.form.action.ActionDecision;
import org.ipro.form.action.ActionRegistry;
import org.ipro.form.action.ActionResolver;
import org.ipro.form.action.ActionSurface;
import org.ipro.form.action.CrudAction;
import org.ipro.form.action.ReadOnlyReason;
import org.ipro.form.builtin.ItemForm;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.rls.RlsUiGate;
import org.ipro.rls.RlsUiGate.AccessDecision;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * E1.5: карточка получает решение по действию, а не собственную формулу прав.
 *
 * <p>Тест держит настоящие {@link EntityDescriptorCatalog} и {@link ActionRegistry}: смысл среза в
 * том, что режим карточки выводится из той же политики типа и того же действия {@code crud.save},
 * что и у canonical path. Мок — только {@link RlsUiGate} (настоящий требует пользователя и реестр
 * измерений, то есть проверял бы RLS, а не связку «решение → режим формы»).</p>
 *
 * <p>Ключевая проверка — тип с {@code DETAIL} без {@code UPDATE} (носитель {@code AttributeValue}):
 * карточка открывается в режиме просмотра <b>нейтрально</b>, без сообщения о правах. До E1.5
 * такая карточка открывалась редактируемой и предлагала сохранение, которое отклонял серверный
 * canonical write (дефект, найденный в E1.0).</p>
 */
class ItemFormAccessBinderDecisionTest {

    /** Полный generic CRUD: обычная справочная сущность. */
    @Entity
    public static class NomenclatureDoc extends BaseEntity {
    }

    /** Создание без изменения: носитель действия {@code Open} (как AttributeValue). */
    @Entity
    public static class AttributeValueDoc extends BaseEntity {
    }

    /** Generic-записей нет вообще (как SklNomOpa). */
    @Entity
    public static class ImmutableDoc extends BaseEntity {
    }

    // --- отображение решения в причину (чистое, без каталога) -----------------------------------

    @Test
    void allowedDecisionKeepsCardEditable() {
        assertThat(ItemFormAccessBinder.readOnlyReasonOf(ActionDecision.allowed())).isNull();
    }

    @Test
    void accessDeniedKeepsTheRightsMessageExactly() {
        ReadOnlyReason reason = ItemFormAccessBinder.readOnlyReasonOf(
            ActionDecision.blocked(ActionDecision.Reason.ACCESS_DENIED,
                "нет прав на изменение (измерение ENTITY:Doc)"));

        assertThat(reason.kind()).isEqualTo(ReadOnlyReason.Kind.ACCESS_DENIED);
        assertThat(reason.noticeText())
            .isEqualTo("Только просмотр: нет прав на изменение (измерение ENTITY:Doc)");
    }

    @Test
    void structuralAndContextReasonsNeverClaimRightsWereDenied() {
        List<ActionDecision> decisions = List.of(
            ActionDecision.hidden(ActionDecision.Reason.TYPE_NOT_SUPPORTED, "причина"),
            ActionDecision.hidden(ActionDecision.Reason.NOT_APPLICABLE, "причина"),
            ActionDecision.blocked(ActionDecision.Reason.NO_SELECTION, "причина"),
            ActionDecision.blocked(ActionDecision.Reason.CONTEXT_INCOMPLETE, "причина"));

        for (ActionDecision decision : decisions) {
            ReadOnlyReason mapped = ItemFormAccessBinder.readOnlyReasonOf(decision);

            assertThat(mapped.kind()).as("причина %s", decision.reason())
                .isEqualTo(ReadOnlyReason.Kind.TYPE_READ_ONLY);
            assertThat(mapped.showsNotice()).as("причина %s", decision.reason()).isFalse();
            assertThat(mapped.noticeText()).as("причина %s", decision.reason()).isEmpty();
        }
    }

    // --- решение по карточке на настоящей политике типов ----------------------------------------

    @Test
    void cardOfTypeWithoutGenericUpdateOpensReadOnlyWithoutRightsMessage() {
        ItemFormAccessBinder binder = new ItemFormAccessBinder();
        ItemForm<AttributeValueDoc> form = itemFormWith(existing(AttributeValueDoc.class));

        ReadOnlyReason applied = binder.applyReadOnlyIfCannotSave(form,
            itemResolver(AttributeValueDoc.class));

        assertThat(applied.kind()).isEqualTo(ReadOnlyReason.Kind.TYPE_READ_ONLY);
        assertThat(form.isReadOnly()).isTrue();
        assertThat(form.readOnlyReason().showsNotice())
            .as("права не отказывали — карточка просто не умеет менять запись")
            .isFalse();
    }

    @Test
    void cardOfFullyEditableTypeStaysEditable() {
        ItemFormAccessBinder binder = new ItemFormAccessBinder();
        ItemForm<NomenclatureDoc> form = itemFormWith(existing(NomenclatureDoc.class));

        ReadOnlyReason applied = binder.applyReadOnlyIfCannotSave(form,
            itemResolver(NomenclatureDoc.class));

        assertThat(applied).isNull();
        assertThat(form.isReadOnly()).isFalse();
        assertThat(form.readOnlyReason()).isNull();
    }

    @Test
    void rlsDenialTurnsIntoRightsReasonWithGateText() {
        ItemFormAccessBinder binder = new ItemFormAccessBinder();
        ItemForm<NomenclatureDoc> form = itemFormWith(existing(NomenclatureDoc.class));
        RlsUiGate gate = allowedGate();
        when(gate.canUpdate(any()))
            .thenReturn(new AccessDecision(false, "нет прав на изменение (измерение ENTITY:Doc)"));

        ReadOnlyReason applied = binder.applyReadOnlyIfCannotSave(form,
            itemResolver(NomenclatureDoc.class, gate));

        assertThat(applied.kind()).isEqualTo(ReadOnlyReason.Kind.ACCESS_DENIED);
        assertThat(form.readOnlyReason().noticeText())
            .isEqualTo("Только просмотр: нет прав на изменение (измерение ENTITY:Doc)");
    }

    @Test
    void unsavedNewCardIsNotReadOnlyEvenWhenTheTypeCannotUpdate() {
        ItemFormAccessBinder binder = new ItemFormAccessBinder();
        ItemForm<AttributeValueDoc> form = newForm(AttributeValueDoc.class);
        ActionResolver resolver = itemResolver(AttributeValueDoc.class);

        // Новая запись: действует требование CREATE, а не UPDATE, поэтому сохранение доступно.
        assertThat(binder.readOnlyReason(resolver, null)).isNull();
        assertThat(binder.applyReadOnlyIfCannotSave(form, resolver)).isNull();
        assertThat(form.isReadOnly()).isFalse();

        // Та же запись, уже сохранённая (есть id) — править нельзя.
        assertThat(binder.readOnlyReason(resolver, existing(AttributeValueDoc.class)).kind())
            .isEqualTo(ReadOnlyReason.Kind.TYPE_READ_ONLY);
    }

    @Test
    void cardOfImmutableTypeCannotBeCreatedAtAll() {
        ItemFormAccessBinder binder = new ItemFormAccessBinder();

        String reason = binder.blockReasonIfCannotCreate(listResolver(ImmutableDoc.class));

        assertThat(reason)
            .as("прямое открытие карточки создания для типа без CREATE получает отказ")
            .isNotBlank()
            .contains("CREATE");
    }

    @Test
    void createOfEditableTypeIsAllowed() {
        ItemFormAccessBinder binder = new ItemFormAccessBinder();

        assertThat(binder.blockReasonIfCannotCreate(listResolver(NomenclatureDoc.class))).isNull();
    }

    @Test
    void createDeniedByRlsKeepsGateReason() {
        ItemFormAccessBinder binder = new ItemFormAccessBinder();
        RlsUiGate gate = allowedGate();
        when(gate.canCreate(any()))
            .thenReturn(new AccessDecision(false, "нет прав на создание (измерение ENTITY:Doc)"));

        assertThat(binder.blockReasonIfCannotCreate(listResolver(NomenclatureDoc.class, gate)))
            .isEqualTo("нет прав на создание (измерение ENTITY:Doc)");
    }

    @Test
    void saveNotDeclaredForSurfaceIsNeutralReadOnlyNotRightsDenial() {
        ActionRegistry registryWithoutItemDefaults =
            new ActionRegistry(CrudAction.listToolbarDefaults(), List.of());
        ActionResolver resolver = new ActionResolver(registryWithoutItemDefaults,
            provider(allowedGate()), org.ipro.form.action.ActionHandlerRegistry.empty(),
            ActionSurface.ITEM_FOOTER, NomenclatureDoc.class, null);

        ReadOnlyReason reason = new ItemFormAccessBinder()
            .readOnlyReason(resolver, existing(NomenclatureDoc.class));

        assertThat(reason.kind()).isEqualTo(ReadOnlyReason.Kind.TYPE_READ_ONLY);
        assertThat(reason.showsNotice()).isFalse();
    }

    // --- фикстуры ------------------------------------------------------------------------------

    private static ActionResolver itemResolver(Class<?> type) {
        return itemResolver(type, allowedGate());
    }

    private static ActionResolver itemResolver(Class<?> type, RlsUiGate gate) {
        return new ActionResolver(defaultRegistry(), provider(gate),
            org.ipro.form.action.ActionHandlerRegistry.empty(),
            ActionSurface.ITEM_FOOTER, type, null);
    }

    private static ActionResolver listResolver(Class<?> type) {
        return listResolver(type, allowedGate());
    }

    private static ActionResolver listResolver(Class<?> type, RlsUiGate gate) {
        return new ActionResolver(defaultRegistry(), provider(gate),
            org.ipro.form.action.ActionHandlerRegistry.empty(),
            ActionSurface.LIST_TOOLBAR, type, null);
    }

    private static ActionRegistry defaultRegistry() {
        return new ActionRegistry(CrudAction.platformDefaults(), List.of());
    }

    private static ActionContextProvider provider(RlsUiGate gate) {
        return new ActionContextProvider(catalog(), gate);
    }

    private static EntityDescriptorCatalog catalog() {
        ManagedEntityCatalog managed = mock(ManagedEntityCatalog.class);
        when(managed.managedEntityClasses())
            .thenReturn(Set.of(NomenclatureDoc.class, AttributeValueDoc.class, ImmutableDoc.class));

        SectionMetadataRegistry sections = mock(SectionMetadataRegistry.class);
        when(sections.findByRow(any())).thenReturn(Optional.empty());

        return new EntityDescriptorCatalog(managed, sections, new MetadataResolver(), List.of(),
            List.of(
                new EntityCapabilityOverride(NomenclatureDoc.class,
                    Set.of(FetchScenario.LIST, FetchScenario.DETAIL, FetchScenario.LOOKUP),
                    Set.of(DataOperation.CREATE, DataOperation.UPDATE, DataOperation.DELETE),
                    "standard root with canonical data handle"),
                new EntityCapabilityOverride(AttributeValueDoc.class,
                    Set.of(FetchScenario.LIST, FetchScenario.DETAIL, FetchScenario.LOOKUP),
                    Set.of(DataOperation.CREATE),
                    "значение атрибута бессмертно"),
                new EntityCapabilityOverride(ImmutableDoc.class,
                    Set.of(FetchScenario.LIST, FetchScenario.DETAIL, FetchScenario.LOOKUP),
                    Set.of(),
                    "набор immutable, состав ведёт канонизация findOrCreate")));
    }

    private static RlsUiGate allowedGate() {
        RlsUiGate gate = mock(RlsUiGate.class);
        when(gate.canCreate(any())).thenReturn(AccessDecision.ALLOWED);
        when(gate.canUpdate(any())).thenReturn(AccessDecision.ALLOWED);
        when(gate.canDelete(any())).thenReturn(AccessDecision.ALLOWED);
        return gate;
    }

    @SuppressWarnings("unchecked")
    private static <T extends BaseEntity> ItemForm<T> itemFormWith(T entity) {
        ItemForm<T> form = newForm((Class<T>) entity.getClass());
        form.setEntity(entity);
        return form;
    }

    /** Сохранённая запись: решение о карточке зависит от состояния объекта, а не от класса. */
    private static <T extends BaseEntity> T existing(Class<T> type) {
        try {
            T entity = type.getDeclaredConstructor().newInstance();
            entity.setId(42L);
            return entity;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("не удалось создать фикстуру " + type, ex);
        }
    }

    private static <T extends BaseEntity> ItemForm<T> newForm(Class<T> type) {
        EntityMetadataInfo metadata = mock(EntityMetadataInfo.class);
        doReturn(type).when(metadata).getEntityClass();
        when(metadata.getFormFields()).thenReturn(List.of());
        return new ItemForm<>(metadata, mock(FieldFactory.class));
    }
}
