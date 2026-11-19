package org.ipro.report.ui.print;


import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import jakarta.persistence.Entity;
import org.ipro.crud.BaseEntity;
import org.ipro.crud.EntityLookup;
import org.ipro.form.FieldFactory;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.registry.FormContext;
import org.ipro.jr.run.JrxmlExecutionService;
import org.ipro.jr.service.JrxmlTemplateService;
import org.ipro.report.ui.ReportContextFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Общая кастомизация форм: команда «Отчёты» появляется на панели карточки без ручного вызова
 * {@link ItemFormReportActions} и не дублируется там, где форма подключила её сама.
 */
class ReportCardPrintCustomizationTest {

    @Test
    void persistedEntityCardGetsExactlyOneReportsCommandOnThePanel() {
        ItemForm<?> form = form();
        customization().customizeItem(form, FormContext.builder(Printable.class).id(7L).build());

        // Панель — потомок карточки: кнопка команды находится обходом дерева, как у любого компонента.
        assertThat(reportsButtons(form)).singleElement()
            .satisfies(button -> assertThat(button.getText()).isEqualTo("Отчёты"));
        assertThat(ItemFormReportActions.isInstalled(form)).isTrue();
        // В подвале карточки отчётной кнопки больше нет.
        assertThat(form.getFooter().getComponentCount()).isZero();
    }

    @Test
    void newRecordAndNonEntityFormsAreSkipped() {
        ItemForm<?> form = form();
        customization().customizeItem(form, FormContext.builder(Printable.class).build());

        assertThat(reportsButtons(form)).isEmpty();
        assertThat(ItemFormReportActions.isInstalled(form)).isFalse();
        assertThat(customization().supports(Printable.class)).isTrue();
        assertThat(customization().supports(Plain.class)).isFalse();
        assertThat(customization().supports(NoIdentityEntity.class)).isFalse();
    }

    @Test
    void explicitlyWiredCommandIsNotDuplicated() {
        ItemForm<?> form = form();
        ItemFormReportActions.addContextualCommand(form, () -> null, null, null, null, null);

        customization().customizeItem(form, FormContext.builder(Printable.class).id(7L).build());

        assertThat(reportsButtons(form)).hasSize(1);
    }

    @Test
    void legacyLauncherStillAddsItsButtonWithoutAnAutomaticDuplicate() {
        ItemForm<?> form = form();
        ContextualReportLauncher launcher =
            ItemFormReportActions.addContextualLauncher(form, () -> null, null, null, null, null);

        customization().customizeItem(form, FormContext.builder(Printable.class).id(7L).build());

        assertThat(form.getFooter().getChildren()).containsExactly(launcher);
        assertThat(reportsButtons(form)).containsExactly(launcher);
    }

    private static List<Button> reportsButtons(Component root) {
        List<Button> found = new ArrayList<>();
        collect(root, found);
        return found.stream().filter(button -> "Отчёты".equals(button.getText())).toList();
    }

    private static void collect(Component component, List<Button> found) {
        if (component instanceof Button button) {
            found.add(button);
        }
        component.getChildren().forEach(child -> collect(child, found));
    }

    private static ReportCardPrintCustomization customization() {
        return new ReportCardPrintCustomization(new ItemFormReportActions.CardLauncher(
            mock(JrxmlTemplateService.class), null, null, null),
            mock(ReportContextFactory.class));
    }

    private static ItemForm<?> form() {
        EntityMetadataInfo metadata = mock(EntityMetadataInfo.class);
        doReturn(Printable.class).when(metadata).getEntityClass();
        when(metadata.getFormFields()).thenReturn(List.of());
        return new ItemForm<>(metadata, mock(FieldFactory.class));
    }

    /** JPA-сущность: только такие карточки получают кнопку. */
    @Entity
    static final class Printable extends BaseEntity {
    }

    /** Не сущность: служебная форма без печати по ID. */
    static final class Plain {
    }

    /** JPA-класс без идентификатора: печать по ID к нему не адресуется. */
    @Entity
    static final class NoIdentityEntity {
    }
}
