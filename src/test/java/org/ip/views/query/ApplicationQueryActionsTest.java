package org.ip.views.query;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import org.ip.model.Journal;
import org.ipro.data.EntityDataAccess;
import org.ipro.filtergrid.projection.QueryProvenance;
import org.ipro.form.action.ActionDecision;
import org.ipro.form.action.ActionResolver;
import org.ipro.form.action.ActionResolvers;
import org.ipro.form.action.CrudAction;
import org.ipro.form.action.NavigationCommandAvailability;
import org.ipro.form.coordinator.FormNavigator;
import org.ipro.metadata.MetadataResolver;
import org.ipro.query.list.QueryListDefinition;
import org.ipro.query.list.QueryListDefinition.Command;
import org.ipro.query.list.QueryListRegistry;
import org.ipro.query.model.QueryDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ApplicationQueryActionsTest {
    private final EntityManagerFactory factory = mock(EntityManagerFactory.class);
    private final EntityDataAccess data = mock(EntityDataAccess.class);
    private final ActionResolvers resolvers = mock(ActionResolvers.class);
    private final ActionResolver list = mock(ActionResolver.class);
    private final FormNavigator forms = mock(FormNavigator.class);
    private final NavigationCommandAvailability availability = mock(NavigationCommandAvailability.class);
    private final QueryListRegistry registry = mock(QueryListRegistry.class);
    private final QueryWorkbenchAccess access = mock(QueryWorkbenchAccess.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<FormNavigator> navigators = mock(ObjectProvider.class);
    private final ApplicationQueryActions actions = new ApplicationQueryActions(factory, data, resolvers, navigators,
            availability, mock(MetadataResolver.class), registry, access);
    private final Runnable refresh = mock(Runnable.class);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        var metamodel = mock(Metamodel.class);
        EntityType<Journal> journal = mock(EntityType.class);
        when(factory.getMetamodel()).thenReturn(metamodel);
        when(metamodel.getEntities()).thenReturn(Set.of(journal));
        when(journal.getJavaType()).thenReturn(Journal.class);
        when(resolvers.list(Journal.class, null)).thenReturn(list);
        when(navigators.getObject()).thenReturn(forms);
        when(access.allows()).thenReturn(true);
    }

    @Test
    void createsWithOnlyTheEntityAndBlocksRowCommandsWithoutTheColumn() {
        var commands = actions.forList(entry(null, Command.CREATE, Command.EDIT));
        when(list.decide(CrudAction.CREATE, null, true)).thenReturn(ActionDecision.allowed());

        assertThat(commands.execute(Command.CREATE, null, refresh).actionable()).isTrue();
        assertThat(commands.execute(Command.EDIT, 77L, refresh).blockReason()).contains("колонка");
        assertThat(commands.execute(Command.OPEN, 77L, refresh).visible()).isFalse();
        verify(forms).openItemForm(eq(Journal.class), isNull(), isNull(), any(), isNull());
        verifyNoInteractions(data);
    }

    @Test
    void appliesTheListResolverToCanonicalDataAndRefusesAfterReadRightsAreRevoked() {
        var commands = actions.forList(entry("journalId", Command.EDIT));
        var journal = new Journal();
        when(data.detail(Journal.class, 77L)).thenReturn(Optional.of(journal), Optional.empty());
        when(list.decide(CrudAction.EDIT, journal, true)).thenReturn(ActionDecision.allowed());

        assertThat(commands.decide(Command.EDIT, 77L).actionable()).isTrue();
        assertThat(commands.execute(Command.EDIT, 77L, refresh).blockReason()).contains("Запись недоступна");
        verifyNoInteractions(forms, refresh);
    }

    @Test
    void deletesThroughTheCanonicalPathAndRefreshesAfterSuccess() {
        var commands = actions.forList(entry("journalId", Command.DELETE));
        var journal = new Journal();
        when(data.detail(Journal.class, 77L)).thenReturn(Optional.of(journal));
        when(list.decide(CrudAction.DELETE, journal, true)).thenReturn(ActionDecision.allowed());

        assertThat(commands.execute(Command.DELETE, 77L, refresh).actionable()).isTrue();

        var order = inOrder(data, list, refresh);
        order.verify(data).detail(Journal.class, 77L);
        order.verify(list).decide(CrudAction.DELETE, journal, true);
        order.verify(data).delete(Journal.class, 77L);
        order.verify(refresh).run();
    }

    @Test
    void opensAReferenceInReadOnlyModeWhenOnlyOpeningIsAllowed() {
        var journal = new Journal();
        when(data.detail(Journal.class, 77L)).thenReturn(Optional.of(journal));
        when(list.decide(CrudAction.EDIT, journal, true))
                .thenReturn(ActionDecision.blocked(ActionDecision.Reason.ACCESS_DENIED, "Нет права изменения"));
        when(list.decide(CrudAction.OPEN, journal, true)).thenReturn(ActionDecision.allowed());

        assertThat(actions.open(Journal.class, 77L)).isNull();
        verify(forms).openReadOnlyItemForm(Journal.class, 77L);
    }

    private QueryListRegistry.Entry entry(String column, Command... commands) {
        var definition = new QueryListDefinition("journals", "Журналы",
                new QueryDefinition("select j.id as journalId from Journal j", null, null, null, List.of()), null, null,
                new QueryListDefinition.CommandObject(Journal.class.getName(), column), Set.of(commands),
                QueryProvenance.DECLARED);
        var entry = new QueryListRegistry.Entry(definition.key(), definition.title(), definition, true, null, null,
                null, null, List.of("Journal"));
        when(registry.findOpenable(definition.key())).thenReturn(Optional.of(entry));
        return entry;
    }
}
