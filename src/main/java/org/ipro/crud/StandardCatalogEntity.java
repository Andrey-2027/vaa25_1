package org.ipro.crud;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.ipro.metadata.HasDisplayName;
import org.ipro.metadata.annotation.RequiredMode;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;
import org.ipro.numbering.annotation.Numbered;
import org.ipro.numbering.annotation.NumberingRole;

/**
 * Opinionated golden path обычного справочника.
 *
 * <p>Класс задаёт общую структуру {@code code + name} и безопасный default нумерации
 * кода. Конкретный справочник меняет его через class-level {@code @NumberingPolicy},
 * не переобъявляя поле. Глобальная database uniqueness здесь намеренно не задаётся.</p>
 */
@MappedSuperclass
public abstract class StandardCatalogEntity extends BaseEntity implements HasDisplayName {

    @NotBlank
    @Size(max = 50)
    @Column(name = "code", nullable = false, length = 50)
    @FieldMetadata(
        label = "Код", required = RequiredMode.REQUIRED, order = 10,
        grid = @GridColumn(order = 10, width = "150px")
    )
    @Numbered(role = NumberingRole.CATALOG_CODE)
    private String code;

    @NotBlank
    @Size(max = 200)
    @Column(name = "name", nullable = false, length = 200)
    @FieldMetadata(
        label = "Наименование", required = RequiredMode.REQUIRED, order = 20,
        grid = @GridColumn(order = 20, flexGrow = 1)
    )
    private String name;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String getDisplayName() {
        if (code == null || code.isBlank()) {
            return name != null ? name : "";
        }
        if (name == null || name.isBlank()) {
            return code;
        }
        return code + " — " + name;
    }

    @Override
    public String toString() {
        return getDisplayName();
    }
}
