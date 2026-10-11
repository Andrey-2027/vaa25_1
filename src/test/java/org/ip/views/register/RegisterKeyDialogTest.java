package org.ip.views.register;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.datetimepicker.DateTimePicker;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.ip.model.Nomenclature;
import org.ip.model.NomenclaturePrice;
import org.ipro.crud.LookupService;
import org.ipro.form.EntityField;
import org.ipro.form.FieldFactory;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.form.registry.FormContext;
import org.ipro.metadata.MetadataResolver;
import org.ipro.register.RegisterDescriptor;
import org.ipro.register.RegisterKeyChanges;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class RegisterKeyDialogTest {
    @Test void separateDraftPassesOnlyKeyIdsAndVersionWithoutMutatingTheSelection() {
        var oldNom = new Nomenclature(); oldNom.setId(1L);
        var newNom = new Nomenclature(); newNom.setId(2L);
        LocalDateTime oldPeriod = LocalDateTime.of(2026, 10, 9, 0, 0);
        LocalDateTime period = oldPeriod.plusDays(1).plusNanos(123000000);
        var row = new NomenclaturePrice(oldNom, oldPeriod, new BigDecimal("150")); row.setId(10L); row.setVersion(7L);
        var changes = mock(RegisterKeyChanges.class);
        AtomicBoolean saved = new AtomicBoolean();
        var dialog = new RegisterKeyDialog(RegisterDescriptor.describe(NomenclaturePrice.class), row, context(), changes, () -> saved.set(true));
        var fields = (VerticalLayout) dialog.getChildren().filter(VerticalLayout.class::isInstance).findFirst().orElseThrow();
        assertThat(fields.getComponentCount()).isEqualTo(2);
        ((EntityField<Nomenclature>) fields.getComponentAt(0)).setValue(newNom);
        ((DateTimePicker) fields.getComponentAt(1)).setValue(period);
        button(dialog, "Сохранить").click();
        verify(changes).change(NomenclaturePrice.class, 10L, 7L, Map.of("nomenclature", 2L), period);
        assertThat(saved.get()).isTrue();
        assertThat(row.getNomenclature()).isSameAs(oldNom);
        assertThat(row.getPeriod()).isEqualTo(oldPeriod);
        assertThat(row.getPrice()).isEqualByComparingTo("150");
    }
    @Test void cancellationDoesNotExecuteTheCommand() {
        var row = new NomenclaturePrice(new Nomenclature(), LocalDateTime.now(), BigDecimal.ONE);
        var changes = mock(RegisterKeyChanges.class);
        var dialog = new RegisterKeyDialog(RegisterDescriptor.describe(NomenclaturePrice.class), row, context(), changes, () -> { });
        button(dialog, "Отмена").click();
        verifyNoInteractions(changes);
    }
    private static FormContext context() {
        var metadata = new MetadataResolver();
        var selections = mock(SelectionFormAssembler.class);
        org.mockito.Mockito.when(selections.resolveColumns(Nomenclature.class)).thenReturn(
            new SelectionFormAssembler.ResolvedSelection(java.util.List.of(
                org.ipro.metadata.ColumnPath.resolve(Nomenclature.class, "name")), "Номенклатура"));
        var factory = new FieldFactory(mock(LookupService.class), selections);
        return FormContext.builder(NomenclaturePrice.class).metadataResolver(metadata).fieldFactory(factory).build();
    }
    private static Button button(RegisterKeyDialog dialog, String text) {
        return dialog.getFooter().getElement().getChildren().map(element -> element.getComponent().orElse(null))
            .filter(Button.class::isInstance).map(Button.class::cast)
            .filter(button -> text.equals(button.getText())).findFirst().orElseThrow();
    }
}
