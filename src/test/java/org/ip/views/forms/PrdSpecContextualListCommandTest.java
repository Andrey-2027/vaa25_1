package org.ip.views.forms;

import org.ip.form.builtin.ListForm;
import org.ip.form.coordinator.FormCoordinator;
import org.ip.form.registry.ListCommandContext;
import org.ip.model.Journal;
import org.ip.model.Nomenclature;
import org.ip.model.PrdSpec;
import org.ipro.crud.LookupService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrdSpecContextualListCommandTest {

    @Test
    @SuppressWarnings("unchecked")
    void selectedSpecificationOpensContextualViewWithTwoLinkFilters() {
        Journal journal = new Journal();
        journal.setId(7L);

        Nomenclature nomenclature = new Nomenclature();
        nomenclature.setId(9L);
        nomenclature.setTypeNom("Узел");

        PrdSpec selected = new PrdSpec();
        selected.setId(101L);
        selected.setJournal(journal);
        selected.setNomenclature(nomenclature);

        LookupService lookupService = mock(LookupService.class);
        when(lookupService.findById(Nomenclature.class, 9L))
            .thenReturn(Optional.of(nomenclature));

        ListForm<PrdSpec, Long> listForm = mock(ListForm.class);
        when(listForm.getSelectedItem()).thenReturn(selected);
        FormCoordinator coordinator = mock(FormCoordinator.class);

        new PrdSpecContextualListCommand(lookupService)
            .execute(new ListCommandContext<>(listForm, coordinator));

        org.mockito.ArgumentCaptor<Map<String, Object>> parametersCaptor =
            org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(coordinator).openListForm(eq(PrdSpec.class), eq("contextual"), parametersCaptor.capture());

        Map<String, Object> parameters = parametersCaptor.getValue();
        assertThat(parameters)
            .containsEntry("sourceId", 101L)
            .containsEntry("journalId", 7L)
            .containsEntry("typeNom", "Узел")
            .containsEntry("suppressListCommands", true)
            .containsEntry("contextView", "prdSpec-contextual");

        List<Map<String, Object>> filters = (List<Map<String, Object>>) parameters.get("seedFilters");
        assertThat(filters).containsExactlyInAnyOrder(
            Map.of("path", "journal.id", "value", 7L),
            Map.of("path", "nomenclature.typeNom", "value", "Узел"));
    }

    @Test
    void noSelectionDoesNotOpenView() {
        LookupService lookupService = mock(LookupService.class);
        ListForm<PrdSpec, Long> listForm = mock(ListForm.class);
        FormCoordinator coordinator = mock(FormCoordinator.class);
        when(listForm.getSelectedItem()).thenReturn(null);

        new PrdSpecContextualListCommand(lookupService)
            .execute(new ListCommandContext<>(listForm, coordinator));

        org.mockito.Mockito.verifyNoInteractions(coordinator);
    }
}
