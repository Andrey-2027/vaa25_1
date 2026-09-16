package org.ipro.rls;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.ipro.crud.BaseEntity;

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
@EntityListeners(AccessGrantChangeListener.class)
@Table(name = "access_grant")
public class AccessGrant extends BaseEntity {

    public enum SubjectType { USER, ROLE }

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false)
    private SubjectType subjectType;

    /** username (для USER) или Role.getName() (для ROLE) — текстовое поле, не FK: без жёсткой привязки. */
    @NotBlank
    @Column(name = "subject_key", nullable = false)
    private String subjectKey;

    /**
     * Имя измерения RLS — связано с @RlsDimension/@Filter.name() конкретной модели.
     * "*" — зарезервированное значение "любое измерение", полный доступ независимо
     * от того, какие измерения существуют сейчас или появятся в будущем (см.
     * AccessGrantRepository) — тем же принципом wildcard, что и dimensionValueId = null.
     */
    @NotBlank
    @Column(nullable = false)
    private String dimension;

    /** id записи измерения (например, Journal.id); null = не указывается, действует на всё. */
    @Column(name = "dimension_value_id")
    private Long dimensionValueId;

    @Column(nullable = false)
    private boolean canRead = true;

    @Column(nullable = false)
    private boolean canUpdate;

    @Column(nullable = false)
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