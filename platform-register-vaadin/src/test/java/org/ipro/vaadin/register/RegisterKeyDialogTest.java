package org.ipro.vaadin.register;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.datetimepicker.DateTimePicker;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import org.ipro.crud.BaseEntity;
import org.ipro.crud.LookupService;
import org.ipro.form.EntityField;
import org.ipro.form.FieldFactory;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.form.registry.FormContext;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.HasDisplayName;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.register.PeriodicRegisterEntryBase;
import org.ipro.register.RegisterDescriptor;
import org.ipro.register.RegisterKeyChanges;
import org.ipro.register.RegisterMetadata;
import org.ipro.register.RegisterPeriodicity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * D-R S4: диалог смены ключа правит только ключ — в отдельном черновике, не превращая выбранную
 * строку в буфер записи.
 *
 * <p>Тест переехал вместе с классом и вместе с ним сменил опоры: в приложении он проверялся на
 * прикладных сущностях ({@code Nomenclature}, {@code NomenclaturePrice}), а модульный тест обязан
 * обходиться своими фикстурами — иначе артефакт верифицируется только внутри приложения, то есть
 * ровно там, откуда срез его выносил. Смысл проверки от замены фикстур не изменился: ключ
 * (измерение и период) уходит командой с версией строки, а сама строка остаётся нетронутой.</p>
 */
class RegisterKeyDialogTest {

    @Test
    void separateDraftPassesOnlyKeyIdsAndVersionWithoutMutatingTheSelection() {
        var oldDimension = dimension(1L, "Старое измерение");
        var newDimension = dimension(2L, "Новое измерение");
        LocalDateTime oldPeriod = LocalDateTime.of(2026, 10, 9, 0, 0);
        LocalDateTime period = oldPeriod.plusDays(1).plusNanos(123000000);
        var row = new DialogRegisterFixture(oldDimension, oldPeriod, new BigDecimal("150"));
        row.setId(10L);
        row.setVersion(7L);
        var changes = mock(RegisterKeyChanges.class);
        AtomicBoolean saved = new AtomicBoolean();
        var dialog = new RegisterKeyDialog(RegisterDescriptor.describe(DialogRegisterFixture.class), row,
            context(), changes, () -> saved.set(true));
        var fields = (VerticalLayout) dialog.getChildren().filter(VerticalLayout.class::isInstance)
            .findFirst().orElseThrow();
        assertThat(fields.getComponentCount()).isEqualTo(2);
        ((EntityField<DimensionFixture>) fields.getComponentAt(0)).setValue(newDimension);
        ((DateTimePicker) fields.getComponentAt(1)).setValue(period);
        button(dialog, "Сохранить").click();
        verify(changes).change(DialogRegisterFixture.class, 10L, 7L, Map.of("dimension", 2L), period);
        assertThat(saved.get()).isTrue();
        assertThat(row.getDimension()).isSameAs(oldDimension);
        assertThat(row.getPeriod()).isEqualTo(oldPeriod);
        assertThat(row.getAmount()).isEqualByComparingTo("150");
    }

    @Test
    void cancellationDoesNotExecuteTheCommand() {
        var row = new DialogRegisterFixture(dimension(1L, "Измерение"), LocalDateTime.now(), BigDecimal.ONE);
        var changes = mock(RegisterKeyChanges.class);
        var dialog = new RegisterKeyDialog(RegisterDescriptor.describe(DialogRegisterFixture.class), row,
            context(), changes, () -> { });
        button(dialog, "Отмена").click();
        verifyNoInteractions(changes);
    }

    private static FormContext context() {
        var metadata = new MetadataResolver();
        var selections = mock(SelectionFormAssembler.class);
        org.mockito.Mockito.when(selections.resolveColumns(DimensionFixture.class)).thenReturn(
            new SelectionFormAssembler.ResolvedSelection(java.util.List.of(
                ColumnPath.resolve(DimensionFixture.class, "name")), "Измерение"));
        var factory = new FieldFactory(mock(LookupService.class), selections);
        return FormContext.builder(DialogRegisterFixture.class).metadataResolver(metadata)
            .fieldFactory(factory).build();
    }

    private static Button button(RegisterKeyDialog dialog, String text) {
        return dialog.getFooter().getElement().getChildren()
            .map(element -> element.getComponent().orElse(null))
            .filter(Button.class::isInstance).map(Button.class::cast)
            .filter(button -> text.equals(button.getText())).findFirst().orElseThrow();
    }

    private static DimensionFixture dimension(Long id, String name) {
        var dimension = new DimensionFixture();
        dimension.setId(id);
        dimension.setName(name);
        return dimension;
    }

    /**
     * Измерение регистра: обычная ссылочная сущность модульного теста. Достаточно быть
     * {@code HasDisplayName} и иметь поле, названное в колонках выбора, — этого требует поле
     * ввода ссылки, и ничего прикладного здесь нет.
     */
    @Entity
    @Table(name = "vaadin_register_dimension_fixture")
    static class DimensionFixture extends BaseEntity implements HasDisplayName {
        @Column(name = "name", length = 128)
        private String name;

        DimensionFixture() { }

        String getName() { return name; }

        void setName(String name) { this.name = name; }

        @Override
        public String getDisplayName() { return name; }
    }

    /**
     * Периодический регистр модульного теста: одно измерение, период и ресурс — тот же контракт,
     * что у настоящего регистра, поэтому валидатор декларации и метаданные проверяются настоящие,
     * а не подменённые моком.
     */
    @Entity
    @AttributeOverride(name = "period",
        column = @Column(name = "period_date", nullable = false, updatable = true))
    @Table(name = "vaadin_register_dialog_fixture", uniqueConstraints = {
        @UniqueConstraint(name = "uk_vaadin_register_dialog_fixture_key",
            columnNames = {"dimension_id", "period_date"})
    })
    @EntityMetadata(
        listFormTitle = "Тестовый регистр",
        itemFormTitle = "Запись тестового регистра",
        selectColumns = {"dimension", "period", "amount"}
    )
    @RegisterMetadata(dimensions = "dimension", period = "period", resources = "amount",
        periodicity = RegisterPeriodicity.DAY, keyChangeAllowed = true, keyWriteAllowed = true)
    static class DialogRegisterFixture extends PeriodicRegisterEntryBase {

        @NotNull
        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "dimension_id", nullable = false)
        @FieldMetadata(label = "Измерение", order = 1)
        private DimensionFixture dimension;

        @NotNull
        @Column(name = "amount", nullable = false, precision = 19, scale = 4)
        @FieldMetadata(label = "Сумма", order = 3)
        private BigDecimal amount;

        DialogRegisterFixture() { }

        DialogRegisterFixture(DimensionFixture dimension, LocalDateTime period, BigDecimal amount) {
            this.dimension = dimension;
            setPeriod(period);
            this.amount = amount;
        }

        DimensionFixture getDimension() { return dimension; }

        void setDimension(DimensionFixture dimension) { this.dimension = dimension; }

        BigDecimal getAmount() { return amount; }

        void setAmount(BigDecimal amount) { this.amount = amount; }
    }
}
