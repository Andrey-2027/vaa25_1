package org.ip.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import org.ip.metadata.annotation.EntityMetadata;
import org.ip.metadata.annotation.FieldMetadata;
import org.ip.metadata.annotation.GridColumn;

/**
 * Грант доступа — единственная запись, указывающая на право (пользователь или роль),
 * на измерение RLS (например, журнал), и на три флага прав на конкретную сущность.
 *
 * dimension — имя измерения (например, "JOURNAL"), связанное с именем Hibernate-фильтра
 * на конкретной модели (см. @RlsDimension) — один и тот же набор значений.
 *
 * dimensionValueId — id конкретной записи измерения (например, Journal.id).
 * Связь без @ManyToOne — в одном измерении могут быть разные типы записей (Journal,
 * Workshop, ...), поэтому связывать сущностью нельзя. null = "на всё измерение",
 * действует как wildcard (см. AccessService).
 *
 * Отсутствие записи = всё: если нет ни одной записи (ни для пользователя, ни для его
 * ролей) — считать права через AccessService, а не по спискам.
 */
@Entity
@Table(name = "access_grant")
@EntityMetadata(
    listFormTitle = "Гранты доступа",
    itemFormTitle = "Грант доступа",
    order = 900,
    icon = "LOCK"
)
public class AccessGrant extends BaseEntity {

    public enum SubjectType { USER, ROLE }

    @NotBlank
    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false)
    @FieldMetadata(label = "Тип субъекта", required = true, order = 1,
        grid = @GridColumn(order = 1, width = "110px"))
    private SubjectType subjectType;

    /** username (для USER) или Role.getName() (для ROLE) — текстовое поле, не FK: без жёсткой привязки. */
    @NotBlank
    @Column(name = "subject_key", nullable = false)
    @FieldMetadata(label = "Пользователь/роль", required = true, order = 2,
        grid = @GridColumn(order = 2, width = "160px"))
    private String subjectKey;

    /** Имя измерения RLS — связано с @RlsDimension/@Filter.name() конкретной модели. */
    @NotBlank
    @Column(nullable = false)
    @FieldMetadata(label = "Измерение", required = true, order = 3,
        grid = @GridColumn(order = 3, width = "120px"))
    private String dimension;

    /** id записи измерения (например, Journal.id); null = не указывается, действует на всё. */
    @Column(name = "dimension_value_id")
    @FieldMetadata(label = "Запись (пусто = всё)", order = 4,
        grid = @GridColumn(order = 4, width = "140px"))
    private Long dimensionValueId;

    @Column(nullable = false)
    @FieldMetadata(label = "Чтение", order = 5, grid = @GridColumn(order = 5, width = "90px"))
    private boolean canRead = true;

    @Column(nullable = false)
    @FieldMetadata(label = "Изменение", order = 6, grid = @GridColumn(order = 6, width = "100px"))
    private boolean canUpdate;

    @Column(nullable = false)
    @FieldMetadata(label = "Удаление", order = 7, grid = @GridColumn(order = 7, width = "100px"))
    private boolean canDelete;

    public AccessGrant() {
    }

    public SubjectType getSubjectType() {
        return subjectType;
    }

    public void setSubjectType(SubjectType subjectType) {
        this.subjectType = subjectType;
    }

    public String getSubjectKey() {
        return subjectKey;
    }

    public void setSubjectKey(String subjectKey) {
        this.subjectKey = subjectKey;
    }

    public String getDimension() {
        return dimension;
    }

    public void setDimension(String dimension) {
        this.dimension = dimension;
    }

    public Long getDimensionValueId() {
        return dimensionValueId;
    }

    public void setDimensionValueId(Long dimensionValueId) {
        this.dimensionValueId = dimensionValueId;
    }

    public boolean isCanRead() {
        return canRead;
    }

    public void setCanRead(boolean canRead) {
        this.canRead = canRead;
    }

    public boolean isCanUpdate() {
        return canUpdate;
    }

    public void setCanUpdate(boolean canUpdate) {
        this.canUpdate = canUpdate;
    }

    public boolean isCanDelete() {
        return canDelete;
    }

    public void setCanDelete(boolean canDelete) {
        this.canDelete = canDelete;
    }
}