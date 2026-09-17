package org.ipro.data.fixture;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.validation.constraints.NotNull;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;

/**
 * Isolated fixture C4.3 (см. {@code c4-inventory.md} §9): test-only entity, доказывающая
 * artifact budget — {@code @EntityMetadata} + поля и <b>ни одного</b> repository, service,
 * {@code serviceClass} или bean-name convention.
 *
 * <p>Намеренно не наследует {@code BaseEntity}: аудит-инфраструктура относится к
 * приложению, а fixture должна работать в изолированном persistence unit, где нет
 * {@code AuditConfig}. Для canonical path это не проблема — version-нормализация просто
 * не применяется.</p>
 */
@Entity(name = "C4FixtureEntity")
@EntityMetadata(listFormTitle = "C4 fixture")
public class C4FixtureEntity implements IdentifiableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @FieldMetadata(label = "Код", order = 1, required = org.ipro.metadata.annotation.RequiredMode.REQUIRED,
        grid = @GridColumn(order = 1, width = "120px"))
    @NotNull
    private String code;

    @FieldMetadata(label = "Название", order = 2,
        grid = @GridColumn(order = 2, width = "240px"))
    private String name;

    public C4FixtureEntity() {
    }

    public C4FixtureEntity(String code, String name) {
        this.code = code;
        this.name = name;
    }

    @Override
    public Long getId() {
        return id;
    }

    @Override
    public void setId(Long id) {
        this.id = id;
    }

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
}
