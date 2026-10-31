package org.ipro.report.ui.print;

import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import jakarta.persistence.Entity;
import org.ipro.crud.BaseEntity;
import org.ipro.crud.EntityLookup;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.registry.FormContext;
import org.ipro.jr.run.JrxmlExecutionService;
import org.ipro.jr.service.JrxmlTemplateService;
import org.ipro.report.ui.ReportContextFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Общая кастомизация форм: кнопка «Отчёты» появляется в карточке без ручного вызова
 * {@link ItemFormReportActions} и не дублируется там, где форма подключила её сама.
 */
class ReportCardPrintCustomizationTest {

    @Test
    void persistedEntityCardGetsExactlyOneReportsButton() {
        ItemForm<?> form = form();
        customization().customizeItem(form, FormContext.builder(Printable.class).id(7L).build());

        List<ContextualReportLauncher> launchers = footer(form).getChildren()
            .filter(ContextualReportLauncher.class::isInstance)
            .map(ContextualReportLauncher.class::cast)
            .toList();
        assertThat(launchers).singleElement()
            .satisfies(launcher -> assertThat(launcher.getText()).isEqualTo("Отчёты"));
    }

    @Test
    void newRecordAndNonEntityFormsAreSkipped() {
        ItemForm<?> form = form();
        customization().customizeItem(form, FormContext.builder(Printable.class).build());

        assertThat(footer(form).getChildren()).isEmpty();
        assertThat(customization().supports(Printable.class)).isTrue();
        assertThat(customization().supports(Plain.class)).isFalse();
        assertThat(customization().supports(NoIdentityEntity.class)).isFalse();
    }

    @Test
    void explicitlyWiredLauncherIsNotDuplicated() {
        ItemForm<?> form = form();
        ContextualReportLauncher explicit = new ContextualReportLauncher(
            () -> null, null, null, null, null);
        footer(form).add(explicit);

        customization().customizeItem(form, FormContext.builder(Printable.class).id(7L).build());

        assertThat(footer(form).getChildren()
            .filter(ContextualReportLauncher.class::isInstance)).hasSize(1);
    }

    private static ReportCardPrintCustomization customization() {
        return new ReportCardPrintCustomization(new ItemFormReportActions.CardLauncher(
            mock(JrxmlTemplateService.class), null, null, null),
            mock(ReportContextFactory.class));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ItemForm<?> form() {
        ItemForm form = mock(ItemForm.class);
        when(form.getFooter()).thenReturn(new HorizontalLayout());
        return form;
    }

    private static HorizontalLayout footer(ItemForm<?> form) {
        return form.getFooter();
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
