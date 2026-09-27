package org.ipro.form.action;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.ipro.data.DataOperation;
import org.ipro.data.EntityCapabilityOverride;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.fetch.plan.FetchScenario;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * E1.3: резолвер как единственная точка, из которой host получает решение по действию.
 *
 * <p>Проверяется композиция: действие берётся из реестра по ключу, входы — из настоящего каталога
 * дескрипторов (заглушки только на «сырых» границах), решение — из чистой политики. Отдельно
 * закреплены случаи, ради которых резолвер и появился: незарегистрированное действие обязано быть
 * скрыто с названной причиной, а вариант формы меняет решение через тот же ключ.</p>
 */
class ActionResolverTest {

    /** Полный generic CRUD. */
    @Entity
    static class Nomenclature {
        @Id
        Long id;
    }

    /** Создание без изменения: носитель действия {@code Open}. */
    @Entity
    static class AttributeValue {
        @Id
        Long id;
    }

    private static final Set<FetchScenario> READS =
        Set.of(FetchScenario.LIST, FetchScenario.DETAIL, FetchScenario.LOOKUP);

    @Test
    void createWaitsForRequiredContextAndThenBecomesActionable() {
        ActionResolver resolver = resolver(Nomenclature.class, null,
            writes(DataOperation.CREATE, DataOperation.UPDATE, DataOperation.DELETE), allowedGate());

        assertThat(resolver.decide(CrudAction.CREATE, null, false).reason())
            .isEqualTo(ActionDecision.Reason.CONTEXT_INCOMPLETE);
        assertThat(resolver.decide(CrudAction.CREATE, null, true))
            .isEqualTo(ActionDecision.allowed());
    }

    @Test
    void rowActionsWaitForSelection() {
        ActionResolver resolver = resolver(Nomenclature.class, null,
            writes(DataOperation.CREATE, DataOperation.UPDATE, DataOperation.DELETE), allowedGate());

        Nomenclature row = new Nomenclature();

        assertThat(resolver.decide(CrudAction.EDIT, null, true).reason())
            .isEqualTo(ActionDecision.Reason.NO_SELECTION);
        assertThat(resolver.decide(CrudAction.DELETE, null, true).reason())
            .isEqualTo(ActionDecision.Reason.NO_SELECTION);
        assertThat(resolver.decide(CrudAction.EDIT, row, true)).isEqualTo(ActionDecision.allowed());
        assertThat(resolver.decide(CrudAction.DELETE, row, true)).isEqualTo(ActionDecision.allowed());
    }

    @Test
    void capabilityRemovesEditAndOffersOpenInstead() {
        ActionResolver resolver = resolver(AttributeValue.class, null,
            writes(DataOperation.CREATE), allowedGate());
        AttributeValue row = new AttributeValue();

        ActionDecision edit = resolver.decide(CrudAction.EDIT, row, true);
        assertThat(edit.visible()).isFalse();
        assertThat(edit.reason()).isEqualTo(ActionDecision.Reason.TYPE_NOT_SUPPORTED);

        assertThat(resolver.decide(CrudAction.OPEN, row, true)).isEqualTo(ActionDecision.allowed());
        assertThat(resolver.decide(CrudAction.CREATE, null, true))
            .isEqualTo(ActionDecision.allowed());
    }

    @Test
    void rlsDenialOnTheRowKeepsGateReason() {
        RlsUiGate gate = allowedGate();
        Nomenclature row = new Nomenclature();
        when(gate.canUpdate(row)).thenReturn(new AccessDecision(false,
            "Нет прав на изменение (измерение JOURNAL, id=7)"));
        ActionResolver resolver = resolver(Nomenclature.class, null,
            writes(DataOperation.CREATE, DataOperation.UPDATE, DataOperation.DELETE), gate);

        ActionDecision edit = resolver.decide(CrudAction.EDIT, row, true);

        assertThat(edit.visible()).isTrue();
        assertThat(edit.reason()).isEqualTo(ActionDecision.Reason.ACCESS_DENIED);
        assertThat(edit.message()).isEqualTo("Нет прав на изменение (измерение JOURNAL, id=7)");
    }

    /** Действия нет в реестре — оно скрыто и причина названа, а не молча разрешена. */
    @Test
    void unregisteredActionIsHiddenWithNamedReason() {
        ActionResolver resolver = new ActionResolver(new ActionRegistry(List.of(), List.of()),
            provider(Nomenclature.class,
                writes(DataOperation.CREATE, DataOperation.UPDATE, DataOperation.DELETE),
                allowedGate()),
            ActionHandlerRegistry.empty(), ActionSurface.LIST_TOOLBAR, Nomenclature.class, null);

        ActionDecision decision = resolver.decide(CrudAction.CREATE, null, true);

        assertThat(decision.visible()).isFalse();
        assertThat(decision.reason()).isEqualTo(ActionDecision.Reason.NOT_APPLICABLE);
        assertThat(decision.message()).contains("crud.create").contains("Nomenclature");
    }

    /** Вариант формы меняет решение через ключ реестра, а не через ветвление в UI. */
    @Test
    void variantScopedOverrideChangesTheDecision() {
        ActionDefinition suppressed = CrudAction.listToolbarDefaults().get(0)
            .withVariant("archived")
            .withVisible(false);
        ActionRegistry registry = new ActionRegistry(CrudAction.listToolbarDefaults(),
            List.of(overrideTarget(suppressed)));

        ActionResolver archived = new ActionResolver(registry,
            provider(Nomenclature.class, writes(DataOperation.CREATE), allowedGate()),
            ActionHandlerRegistry.empty(), ActionSurface.LIST_TOOLBAR, Nomenclature.class,
            "archived");
        ActionResolver defaultVariant = new ActionResolver(registry,
            provider(Nomenclature.class, writes(DataOperation.CREATE), allowedGate()),
            ActionHandlerRegistry.empty(), ActionSurface.LIST_TOOLBAR, Nomenclature.class, null);

        assertThat(archived.decide(CrudAction.CREATE, null, true).visible()).isFalse();
        assertThat(defaultVariant.decide(CrudAction.CREATE, null, true))
            .isEqualTo(ActionDecision.allowed());
    }

    @Test
    void refreshNeedsNeitherSelectionNorRights() {
        RlsUiGate gate = allowedGate();
        when(gate.canCreate(any())).thenReturn(new AccessDecision(false, "нет прав"));
        ActionResolver resolver = resolver(Nomenclature.class, null,
            writes(DataOperation.CREATE, DataOperation.UPDATE), gate);

        assertThat(resolver.decide(CrudAction.REFRESH, null, false))
            .isEqualTo(ActionDecision.allowed());
    }

    /**
     * Подавление объявляется на конкретном ключе, поэтому у него обязана быть цель: default
     * зарегистрирован типом {@code null}, а override называет тип. Здесь это и проверяется — без
     * «выдуманной» цели реестр падал бы, что и есть защита от опечатки.
     */
    private static ActionDefinition overrideTarget(ActionDefinition suppressed) {
        return new ActionDefinition(suppressed.id(), suppressed.surface(), suppressed.title(),
            suppressed.iconName(), Nomenclature.class, suppressed.variant(), suppressed.order(),
            suppressed.requirement(), suppressed.visible());
    }

    private static ActionResolver resolver(Class<?> type, String variant,
                                           Set<DataOperation> writes, RlsUiGate gate) {
        return new ActionResolver(new ActionRegistry(CrudAction.listToolbarDefaults(), List.of()),
            provider(type, writes, gate), ActionHandlerRegistry.empty(), ActionSurface.LIST_TOOLBAR,
            type, variant);
    }

    private static ActionContextProvider provider(Class<?> type, Set<DataOperation> writes,
                                                  RlsUiGate gate) {
        ManagedEntityCatalog managed = mock(ManagedEntityCatalog.class);
        when(managed.managedEntityClasses()).thenReturn(Set.of(type));

        SectionMetadataRegistry sections = mock(SectionMetadataRegistry.class);
        when(sections.findByRow(any())).thenReturn(Optional.empty());

        EntityDescriptorCatalog catalog = new EntityDescriptorCatalog(managed, sections,
            new MetadataResolver(), List.of(),
            List.of(new EntityCapabilityOverride(type, READS, writes, "policy фикстуры E1.3")));
        return new ActionContextProvider(catalog, gate);
    }

    private static RlsUiGate allowedGate() {
        RlsUiGate gate = mock(RlsUiGate.class);
        when(gate.canCreate(any())).thenReturn(AccessDecision.ALLOWED);
        when(gate.canUpdate(any())).thenReturn(AccessDecision.ALLOWED);
        when(gate.canDelete(any())).thenReturn(AccessDecision.ALLOWED);
        return gate;
    }

    private static Set<DataOperation> writes(DataOperation... operations) {
        return Set.of(operations);
    }
}
