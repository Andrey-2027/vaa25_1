package org.ip.views.reportstudio;

import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import org.ip.form.SelectionFormAssembler;
import org.ip.form.builtin.ListForm;
import org.ip.service.LookupService;
import org.ipro.crud.BaseEntity;
import org.ipro.reportstudio.param.ReportContext;
import org.ipro.reportstudio.run.ReportExecutionService;
import org.ipro.reportstudio.service.ReportTemplateService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ListFormReportActionsTest {

    @Test
    void reportContextContainsCurrentAndAllSelectedIdentifiers() {
        ReportContext context = ListFormReportActions.reportContext(
                ReportableEntity.class,
                List.of(entity(8L), entity(13L)));

        assertEquals(ReportableEntity.class, context.entityClass());
        assertEquals(8L, context.entityId());
        assertEquals(List.of(8L, 13L), context.selectedIds());
        assertEquals(ReportableEntity.class.getName() + "-list", context.viewId());
    }

    @Test
    void printIsEnabledUnlessEntityExplicitlyOptsOut() {
        assertTrue(ListFormReportActions.isEnabled(ReportableEntity.class));
        assertFalse(ListFormReportActions.isEnabled(WithoutPrintEntity.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void actionIsAddedToListToolbarAndEnabledWhenGridHasSelection() {
        ListForm<ReportableEntity, Long> form = mock(ListForm.class);
        Grid<ReportableEntity> grid = new Grid<>(ReportableEntity.class, false);
        HorizontalLayout toolbar = new HorizontalLayout();
        when(form.getGrid()).thenReturn(grid);
        when(form.getToolbar()).thenReturn(toolbar);

        ListFormReportActions actions = new ListFormReportActions(
                mock(ReportTemplateService.class),
                mock(ReportExecutionService.class),
                mock(LookupService.class),
                mock(SelectionFormAssembler.class));

        ContextualReportLauncher launcher = actions
                .addDefaultPrintAction(form, ReportableEntity.class)
                .orElseThrow();

        assertFalse(launcher.isEnabled());
        assertSame(launcher, toolbar.getComponentAt(0));
        grid.select(entity(21L));
        assertTrue(launcher.isEnabled());
    }

    private static ReportableEntity entity(long id) {
        ReportableEntity entity = new ReportableEntity();
        entity.setId(id);
        return entity;
    }

    private static final class ReportableEntity extends BaseEntity {
    }

    @WithReportView(false)
    private static final class WithoutPrintEntity extends BaseEntity {
    }
}
