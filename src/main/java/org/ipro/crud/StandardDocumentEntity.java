package org.ipro.crud;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.ipro.metadata.HasDisplayName;
import org.ipro.metadata.annotation.RequiredMode;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;
import org.ipro.numbering.annotation.Numbered;
import org.ipro.numbering.annotation.NumberingRole;

import java.time.LocalDate;

/**
 * Opinionated golden path обычного документа.
 *
 * <p>Класс задаёт общую структуру {@code number + date} и безопасный default нумерации.
 * Scope, период и формат конкретный документ меняет через class-level
 * {@code @NumberingPolicy}; database uniqueness номера здесь не фиксируется. Наличие
 * табличных секций также не является частью наследования: owned sections объявляются
 * отдельной metadata capability на конкретной сущности.</p>
 */
@MappedSuperclass
public abstract class StandardDocumentEntity extends BaseEntity implements HasDisplayName {

    @NotBlank
    @Size(max = 50)
    @Column(name = "number", nullable = false, length = 50)
    @FieldMetadata(
        label = "Номер", required = RequiredMode.REQUIRED, order = 10,
        grid = @GridColumn(order = 10, width = "150px")
    )
    @Numbered(role = NumberingRole.DOCUMENT_NUMBER, dateField = "date")
    private String number;

    @NotNull
    @Column(name = "date", nullable = false)
    @FieldMetadata(
        label = "Дата", required = RequiredMode.REQUIRED, order = 20,
        grid = @GridColumn(order = 20, width = "150px")
    )
    private LocalDate date;

    public String getNumber() {
        return number;
    }

    public void setNumber(String number) {
        this.number = number;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    @Override
    public String getDisplayName() {
        if (number == null || number.isBlank()) {
            return date != null ? date.toString() : "";
        }
        return date != null ? number + " от " + date : number;
    }

    @Override
    public String toString() {
        return getDisplayName();
    }
}
