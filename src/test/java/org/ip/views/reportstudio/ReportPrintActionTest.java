package org.ip.views.reportstudio;

import jakarta.persistence.Entity;
import org.ipro.crud.BaseEntity;
import org.ipro.crud.EntityLookup;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.form.action.ActionDefinition;
import org.ipro.form.action.ActionHandlerRegistry;
import org.ipro.form.action.ActionInvocation;
import org.ipro.form.action.ActionRegistry;
import org.ipro.form.action.ActionSurface;
import org.ipro.form.action.CrudAction;
import org.ipro.jr.run.JrxmlExecutionService;
import org.ipro.jr.service.JrxmlTemplateService;
import org.ipro.reportstudio.param.ReportContext;
import org.ipro.reportstudio.param.ReportContextFactory;
import org.ipro.reportstudio.run.ReportExecutionService;
import org.ipro.reportstudio.service.ReportTemplateService;
import org.ipro.ureport.service.UreportTemplateService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * E1.6b: «Печать» — объявленное действие списка, а не сквозной шов тулбара.
 *
 * <p>Проверяется то, чем этот срез отличается от прежней кнопки-контрибьютора: объявление живёт в
 * приложении и применимо к любому типу (печатные формы привязаны к реестру, а не к сущности), а
 * исполнение работает со снимком списка, а не с формой.</p>
 *
 * <p><b>Что здесь сознательно не проверяется.</b> Семантика решения (недоступное действие не
 * исполняется, в том числе программным кликом; требование по выделению даёт {@code NO_SELECTION})
 * уже закреплена платформенным {@code ListFormDeclaredActionTest} на том же контракте — здесь она
 * не дублируется, потому что проверяла бы платформу, а не прикладное объявление. Входы решения
 * (capabilities типа и права) пришлось бы собирать через MODULE_API типы, и это была бы вторая
 * проверка одного правила в другом модуле.</p>
 */
class ReportPrintActionTest {

    @Entity
    static class Printable extends BaseEntity {
    }

    /** Объявление читается как данные: поверхность, тип, требование и представление. */
    @Test
    void declarationIsATypeWideListActionWaitingForSelection() {
        ActionDefinition definition = action().definition();

        assertThat(definition.id()).isEqualTo(ReportPrintAction.ID);
        assertThat(definition.surface()).isEqualTo(ActionSurface.LIST_TOOLBAR);
        assertThat(definition.entityType()).as("печать не про один тип: форма привязана к реестру")
            .isNull();
        assertThat(definition.variant()).isNull();
        assertThat(definition.visible()).isTrue();
        assertThat(definition.title()).isEqualTo("Печать");
        assertThat(definition.iconName()).isEqualTo(ReportPrintAction.ICON);
        assertThat(definition.requirement().requiresSelection()).isTrue();
        assertThat(definition.requirement().requiresRequiredContext()).isFalse();
    }

    /**
     * Регистрация идёт через тот же реестр, что собирает платформа: объявление исполнителя
     * становится регистрацией, поэтому печать есть у любой сущности без объявления под тип.
     */
    @Test
    void declarationResolvesForAnyEntityTypeThroughTheSharedRegistry() {
        ActionHandlerRegistry handlers = new ActionHandlerRegistry(List.of(action()));
        ActionRegistry registry = new ActionRegistry(
            Stream.concat(CrudAction.platformDefaults().stream(),
                handlers.definitions().stream()).toList(),
            List.of());

        assertThat(registry.resolve(ActionSurface.LIST_TOOLBAR, Printable.class, null,
            ReportPrintAction.ID)).isPresent();
        assertThat(registry.resolve(ActionSurface.ITEM_FOOTER, Printable.class, null,
            ReportPrintAction.ID))
            .as("поверхность — часть ключа: подвал карточки печать не получает")
            .isEmpty();
    }

    /** Исполнение строит контекст запуска по выделенной строке — тот же вид, что у кнопки карточки. */
    @Test
    void executionBuildsTheLaunchContextForTheSelectedRow() {
        RecordingPrintAction recording = new RecordingPrintAction();
        Printable row = row(7L);

        recording.execute(new ActionInvocation(Printable.class, null, row, null, null,
            Map.of("target", "42"), Map.of()));

        assertThat(recording.calls).isEqualTo(1);
        assertThat(recording.presented.entityClass()).isEqualTo(Printable.class);
        assertThat(recording.presented.entityId()).isEqualTo(7L);
        assertThat(recording.presented.selectedIds()).isEqualTo(List.of(7L));
        assertThat(recording.presented.viewId()).isEqualTo(Printable.class.getName() + "-list");
    }

    /**
     * Строка, исчезнувшая между решением и кликом, — штатный случай, а не исключение: печатать
     * нечего, и диалог не открывается.
     */
    @Test
    void executionDoesNothingWhenTheRowDisappeared() {
        RecordingPrintAction recording = new RecordingPrintAction();

        recording.execute(new ActionInvocation(Printable.class, null, null, null, null,
            Map.of(), Map.of()));

        assertThat(recording.calls).isZero();
        assertThat(recording.presented).isNull();
    }

    // --- фикстуры -------------------------------------------------------------------------------

    /** Точка расширения: тест проверяет, что открывается и с каким контекстом, а не UI диалога. */
    private static final class RecordingPrintAction extends ReportPrintAction {

        private ReportContext presented;
        private int calls;

        private RecordingPrintAction() {
            super(mock(ReportTemplateService.class), mock(ReportExecutionService.class),
                mock(EntityLookup.class), mock(SelectionFormAssembler.class),
                mock(UreportTemplateService.class), mock(JrxmlTemplateService.class),
                mock(JrxmlExecutionService.class),
                new ReportContextFactory(() -> "test-user", Clock.systemDefaultZone()));
        }

        @Override
        protected void present(ReportContext context, ActionInvocation invocation) {
            calls++;
            presented = context;
        }
    }

    private static ReportPrintAction action() {
        return new ReportPrintAction(mock(ReportTemplateService.class),
            mock(ReportExecutionService.class), mock(EntityLookup.class),
            mock(SelectionFormAssembler.class), mock(UreportTemplateService.class),
            mock(JrxmlTemplateService.class), mock(JrxmlExecutionService.class),
            new ReportContextFactory(() -> "test-user", Clock.systemDefaultZone()));
    }

    private static Printable row(long id) {
        Printable row = new Printable();
        row.setId(id);
        return row;
    }
}
