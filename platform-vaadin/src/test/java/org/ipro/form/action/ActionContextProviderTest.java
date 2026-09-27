package org.ipro.form.action;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.ipro.data.DataOperation;
import org.ipro.data.EntityCapabilities;
import org.ipro.data.EntityCapabilityOverride;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.form.action.ActionDecision.Reason;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * E1.2a: провайдер входов решения.
 *
 * <p>Тест держит каталог дескрипторов <b>настоящий</b> ({@link EntityDescriptorCatalog} с реальными
 * правилами классификации), а не заглушку: смысл среза в том, что UI получает capabilities из той же
 * policy, что и canonical path, поэтому подменять её моком значило бы проверять собственный stub.
 * Каталог собирается из моков только по «сырым» границам (metamodel и реестр секций) — как это
 * делает auto-configuration в production.</p>
 *
 * <p>Классы-фикстуры намеренно повторяют предметные запреты {@code EntityClassificationConfig}:
 * полный CRUD и «создание без изменения». Это те же сочетания, на которых E1 будет пилотироваться,
 * только без приложения.</p>
 *
 * <p>{@link RlsUiGate} — мок: настоящий гейт требует AccessService, реестр измерений и текущего
 * пользователя, то есть проверял бы RLS, а не провайдер. Провайдер обязан лишь <b>переносить</b>
 * ответ гейта без изменения — это и проверяется (в том числе точной строкой причины).</p>
 */
class ActionContextProviderTest {

    /** Полный generic CRUD: обычная справочная сущность. */
    @Entity
    static class Nomenclature {
        @Id
        Long id;
    }

    /** Создание без изменения: носитель действия {@code Open} (как AttributeValue). */
    @Entity
    static class AttributeValue {
        @Id
        Long id;
    }

    /** Запрещённых generic-записей нет вообще (как SklNomOpa). */
    @Entity
    static class ImmutableSet {
        @Id
        Long id;
    }

    /** Тип вне persistence unit приложения: descriptor у него отсутствует. */
    @Entity
    static class ForeignType {
        @Id
        Long id;
    }

    private static EntityDescriptorCatalog catalog() {
        ManagedEntityCatalog managed = mock(ManagedEntityCatalog.class);
        when(managed.managedEntityClasses())
            .thenReturn(Set.of(Nomenclature.class, AttributeValue.class, ImmutableSet.class));

        SectionMetadataRegistry sections = mock(SectionMetadataRegistry.class);
        when(sections.findByRow(any())).thenReturn(Optional.empty());

        return new EntityDescriptorCatalog(managed, sections, new MetadataResolver(), List.of(),
            List.of(
                new EntityCapabilityOverride(Nomenclature.class,
                    Set.of(FetchScenario.LIST, FetchScenario.DETAIL, FetchScenario.LOOKUP),
                    Set.of(DataOperation.CREATE, DataOperation.UPDATE, DataOperation.DELETE),
                    "standard root with canonical data handle"),
                new EntityCapabilityOverride(AttributeValue.class,
                    Set.of(FetchScenario.LIST, FetchScenario.DETAIL, FetchScenario.LOOKUP),
                    Set.of(DataOperation.CREATE),
                    "значение атрибута бессмертно"),
                new EntityCapabilityOverride(ImmutableSet.class,
                    Set.of(FetchScenario.LIST, FetchScenario.DETAIL, FetchScenario.LOOKUP),
                    Set.of(),
                    "набор immutable, состав ведёт канонизация findOrCreate")));
    }

    /** Гейт без запретов: различие в тестах делает только тот, кто его переопределил. */
    private static RlsUiGate allowedGate() {
        RlsUiGate gate = mock(RlsUiGate.class);
        when(gate.canCreate(any())).thenReturn(AccessDecision.ALLOWED);
        when(gate.canUpdate(any())).thenReturn(AccessDecision.ALLOWED);
        when(gate.canDelete(any())).thenReturn(AccessDecision.ALLOWED);
        return gate;
    }

    // --- capability: из policy типа, а не из UI ------------------------------------------------

    @Test
    void capabilitiesComeFromDescriptorPolicyNotFromUiTable() {
        ActionContextProvider provider = new ActionContextProvider(catalog(), allowedGate());

        EntityCapabilities standardRoot = provider.capabilitiesOf(Nomenclature.class);
        assertThat(standardRoot.allows(DataOperation.CREATE)).isTrue();
        assertThat(standardRoot.allows(DataOperation.UPDATE)).isTrue();
        assertThat(standardRoot.allows(DataOperation.DELETE)).isTrue();

        EntityCapabilities createOnly = provider.capabilitiesOf(AttributeValue.class);
        assertThat(createOnly.allows(DataOperation.CREATE)).isTrue();
        assertThat(createOnly.allows(DataOperation.UPDATE)).isFalse();
        assertThat(createOnly.allows(DataOperation.DELETE)).isFalse();

        EntityCapabilities immutable = provider.capabilitiesOf(ImmutableSet.class);
        assertThat(immutable.writes()).isEmpty();
        assertThat(immutable.readScenarios()).contains(FetchScenario.DETAIL);
    }

    /**
     * Тип без descriptor — названный отказ, а не молчаливое разрешение. Capability каталога для
     * такого типа пусты, поэтому generic действия скрываются, и причина содержит формулировку
     * каталога, а не «кнопки нет».
     */
    @Test
    void typeWithoutDescriptorGetsNamedDenialInsteadOfPermissiveRoot() {
        ActionContextProvider provider = new ActionContextProvider(catalog(), allowedGate());

        EntityCapabilities foreign = provider.capabilitiesOf(ForeignType.class);
        assertThat(foreign.writes()).isEmpty();
        assertThat(foreign.readScenarios()).isEmpty();
        assertThat(foreign.reason()).contains("ManagedEntityCatalog");

        ActionDecision create = ActionPolicy.decide(definition(CrudAction.CREATE),
            provider.listContext(ForeignType.class, null, true));

        assertThat(create.visible()).isFalse();
        assertThat(create.reason()).isEqualTo(Reason.TYPE_NOT_SUPPORTED);
        assertThat(create.message()).contains("ManagedEntityCatalog");
    }

    // --- права: переносятся без изменения -------------------------------------------------------

    @Test
    void createDenialKeepsGateReasonAndStaysVisible() {
        RlsUiGate gate = allowedGate();
        when(gate.canCreate(Nomenclature.class)).thenReturn(new AccessDecision(false,
            "Нет прав на создание (измерение ENTITY:PrdSpec)"));
        ActionContextProvider provider = new ActionContextProvider(catalog(), gate);

        ActionDecision decision = ActionPolicy.decide(definition(CrudAction.CREATE),
            provider.listContext(Nomenclature.class, null, true));

        assertThat(decision.visible()).isTrue();
        assertThat(decision.enabled()).isFalse();
        assertThat(decision.reason()).isEqualTo(Reason.ACCESS_DENIED);
        assertThat(decision.message()).isEqualTo("Нет прав на создание (измерение ENTITY:PrdSpec)");
    }

    /** Capability первична: тип без операции не «показывает серую кнопку из-за прав». */
    @Test
    void capabilityDenialOutweighsRlsDenial() {
        RlsUiGate gate = allowedGate();
        when(gate.canCreate(ForeignType.class)).thenReturn(new AccessDecision(false, "нет прав"));
        ActionContextProvider provider = new ActionContextProvider(catalog(), gate);

        ActionDecision decision = ActionPolicy.decide(definition(CrudAction.CREATE),
            provider.listContext(ForeignType.class, null, true));

        assertThat(decision.visible()).isFalse();
        assertThat(decision.reason()).isEqualTo(Reason.TYPE_NOT_SUPPORTED);
    }

    @Test
    void rowRightsAreTakenFromRowAndCarryGateReason() {
        RlsUiGate gate = allowedGate();
        Nomenclature row = new Nomenclature();
        when(gate.canUpdate(row)).thenReturn(new AccessDecision(false,
            "Нет прав на изменение (измерение JOURNAL, id=7)"));
        ActionContextProvider provider = new ActionContextProvider(catalog(), gate);

        ActionContext context = provider.rowContext(Nomenclature.class, null, row, true);
        assertThat(context.hasSelection()).isTrue();

        ActionDecision edit = ActionPolicy.decide(definition(CrudAction.EDIT), context);
        assertThat(edit.visible()).isTrue();
        assertThat(edit.reason()).isEqualTo(Reason.ACCESS_DENIED);
        assertThat(edit.message()).isEqualTo("Нет прав на изменение (измерение JOURNAL, id=7)");

        assertThat(ActionPolicy.decide(definition(CrudAction.DELETE), context))
            .isEqualTo(ActionDecision.allowed());
    }

    // --- выделение: строковые права не вычисляются без строки -----------------------------------

    /**
     * Без выделения строковые проверки RLS не выполняются вообще, а решение называет
     * {@code NO_SELECTION}. Это паритет с {@code ListForm.configureGridSelection}, где и включение
     * кнопок, и проверка строки происходят только при непустом выделении.
     */
    @Test
    void listContextDoesNotEvaluateRowRightsAndReportsNoSelection() {
        RlsUiGate gate = allowedGate();
        ActionContextProvider provider = new ActionContextProvider(catalog(), gate);

        ActionContext context = provider.listContext(Nomenclature.class, null, true);

        assertThat(context.hasSelection()).isFalse();
        assertThat(context.permission().update()).isFalse();
        assertThat(context.permission().updateReason()).isEqualTo(ActionPermission.NO_ROW_REASON);
        verify(gate, never()).canUpdate(any());
        verify(gate, never()).canDelete(any());

        assertThat(ActionPolicy.decide(definition(CrudAction.EDIT), context).reason())
            .isEqualTo(Reason.NO_SELECTION);
        assertThat(ActionPolicy.decide(definition(CrudAction.DELETE), context).reason())
            .isEqualTo(Reason.NO_SELECTION);
    }

    /** Строка без прав — это отказ в правах, а не отсутствие строки. */
    @Test
    void selectedRowIsReportedAsAccessDeniedNotAsNoSelection() {
        RlsUiGate gate = allowedGate();
        Nomenclature row = new Nomenclature();
        when(gate.canUpdate(row)).thenReturn(new AccessDecision(false, "нет прав на изменение"));
        ActionContextProvider provider = new ActionContextProvider(catalog(), gate);

        ActionDecision edit = ActionPolicy.decide(definition(CrudAction.EDIT),
            provider.rowContext(Nomenclature.class, null, row, true));

        assertThat(edit.reason()).isEqualTo(Reason.ACCESS_DENIED);
    }

    @Test
    void rowContextWithoutRowDegradesToSelectionlessContext() {
        ActionContextProvider provider = new ActionContextProvider(catalog(), allowedGate());

        ActionContext context = provider.rowContext(Nomenclature.class, "archived", null, true);

        assertThat(context.hasSelection()).isFalse();
        assertThat(context.variant()).isEqualTo("archived");
        assertThat(ActionPolicy.decide(definition(CrudAction.EDIT), context).reason())
            .isEqualTo(Reason.NO_SELECTION);
    }

    // --- сочетание состояний: пилотный случай ---------------------------------------------------

    /**
     * Тип с {@code DETAIL} без {@code UPDATE} и запретом на создание: {@code Edit} неприменим
     * структурно, {@code Create} недоступен по правам, а {@code Open} доступен. Право на изменение
     * строки при этом гейт отдаёт разрешающим — значит решение опирается на capability, а не на
     * право, и «просмотр вместо изменения» не подменяется правом.
     */
    @Test
    void detailWithoutUpdateKeepsOpenAvailableWhileCreateIsDeniedByRights() {
        RlsUiGate gate = allowedGate();
        when(gate.canCreate(AttributeValue.class))
            .thenReturn(new AccessDecision(false, "Нет прав на создание (измерение ENTITY:Attribute)"));
        ActionContextProvider provider = new ActionContextProvider(catalog(), gate);

        AttributeValue row = new AttributeValue();
        ActionContext context = provider.rowContext(AttributeValue.class, null, row, false);

        assertThat(ActionPolicy.decide(definition(CrudAction.EDIT), context).reason())
            .isEqualTo(Reason.TYPE_NOT_SUPPORTED);
        assertThat(ActionPolicy.decide(definition(CrudAction.OPEN), context))
            .isEqualTo(ActionDecision.allowed());
        assertThat(ActionPolicy.decide(definition(CrudAction.CREATE), context).reason())
            .isEqualTo(Reason.ACCESS_DENIED);
    }

    /** Тип без generic записи: ни создать, ни изменить — и причина у обоих структурная. */
    @Test
    void immutableTypeOffersNeitherCreateNorEdit() {
        ActionContextProvider provider = new ActionContextProvider(catalog(), allowedGate());
        ImmutableSet row = new ImmutableSet();

        ActionContext context = provider.rowContext(ImmutableSet.class, null, row, false);

        assertThat(ActionPolicy.decide(definition(CrudAction.CREATE), context).reason())
            .isEqualTo(Reason.TYPE_NOT_SUPPORTED);
        assertThat(ActionPolicy.decide(definition(CrudAction.EDIT), context).reason())
            .isEqualTo(Reason.TYPE_NOT_SUPPORTED);
        assertThat(ActionPolicy.decide(definition(CrudAction.DELETE), context).reason())
            .isEqualTo(Reason.TYPE_NOT_SUPPORTED);
        assertThat(ActionPolicy.decide(definition(CrudAction.OPEN), context))
            .isEqualTo(ActionDecision.allowed());
        assertThat(ActionPolicy.decide(definition(CrudAction.REFRESH), context))
            .isEqualTo(ActionDecision.allowed());
    }

    // --- контракт коллабораторов ----------------------------------------------------------------

    @Test
    void missingCollaboratorsAndMissingRowFailWithNamedReason() {
        RlsUiGate gate = allowedGate();
        ActionContextProvider provider = new ActionContextProvider(catalog(), gate);

        assertThatThrownBy(() -> new ActionContextProvider(null, gate))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("descriptorCatalog");
        assertThatThrownBy(() -> new ActionContextProvider(catalog(), null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("rlsUiGate");
        assertThatThrownBy(() -> provider.rowPermissionOf(Nomenclature.class, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("row must not be null");
    }

    private static ActionDefinition definition(CrudAction action) {
        return ActionDefinition.platformDefault(action, ActionSurface.LIST_TOOLBAR,
            action.name(), null, 0);
    }
}
