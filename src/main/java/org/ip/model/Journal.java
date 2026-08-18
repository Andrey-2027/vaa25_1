package org.ip.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;
import org.ip.subsystem.Subsystems;
import org.ipro.rls.RlsDimension;
import org.ipro.rls.RlsCheckValue;
import org.ipro.rls.RlsDimensionValue;
import org.ipro.crud.BaseEntity;

/**
 * RLS: доступ к журналу — гранты AccessGrant (dimension = "JOURNAL").
 * @Filter включается через RlsFilterActivator, активируемый на каждой сессии
 * (один round-trip); UPDATE/DELETE проверяются вручную в JournalService
 * (см. AccessService.canUpdate/canDelete) — @Filter на них не действует.
 */
@Entity
@Table(name = "journal")
@RlsDimension("JOURNAL")
@FilterDef(name = "JOURNAL", parameters = @ParamDef(name = "allowedIds", type = Long.class))
@Filter(name = "JOURNAL", condition = "id in (:allowedIds)")
@EntityMetadata(
    listFormTitle = "Журналы",
    itemFormTitle = "Журнал",
    selectionFormTitle = "Выбор журнала",
    order = 10,
    icon = "BOOK",
    serviceClass = org.ip.service.JournalService.class,
    subsystem = Subsystems.Production.class,
    selectColumns = {"code", "name"},
    displaySortFields = {"code", "name"}
)
public class Journal extends BaseEntity implements HasDisplayName, RlsDimensionValue {

    @NotBlank
    @Size(max = 20)
    @Column(nullable = false, unique = true)
    @FieldMetadata(
        label = "Код", required = true, order = 1,
        grid = @GridColumn(order = 1, width = "150px")
    )
    private String code;

    @NotBlank
    @Size(max = 200)
    @Column(nullable = false)
    @FieldMetadata(
        label = "Наименование", required = true, order = 2,
        grid = @GridColumn(order = 2, flexGrow = 1)
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
        return code + " " + name;
    }

    /**
     * Journal сам является измерением "JOURNAL" — значение проверки = собственный id.
     * id == null (до insert) — см. RlsCheckValue.Check: пройдёт только у обладателя
     * wildcard-гранта, это осознанное правило ("новые журналы создаёт только полный доступ").
     */
    @Override
    public java.util.Map<String, java.util.List<RlsCheckValue>> getRlsChecks() {
        return java.util.Map.of("JOURNAL", java.util.List.of(RlsCheckValue.of(getId())));
    }
}