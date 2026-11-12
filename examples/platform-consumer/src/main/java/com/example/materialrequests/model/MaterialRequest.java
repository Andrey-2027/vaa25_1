package com.example.materialrequests.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.ipro.crud.BaseEntity;
import org.ipro.fetch.instance.InstanceName;
import org.ipro.metadata.annotation.*;
import org.ipro.rls.RlsDimension;
import org.ipro.numbering.annotation.Numbered;
import java.time.LocalDate;

@Entity
@Table(name = "consumer_material_request", uniqueConstraints = @UniqueConstraint(columnNames = "number"))
@EntityMetadata(kind = EntityKind.DOCUMENT, listFormTitle = "Заявки", itemFormTitle = "Заявка на материалы", selectionFormTitle = "Выбор заявки", subsystem = MaterialsSubsystem.class, order = 200)
@InstanceName(value = {"number"})
@TableSections({MaterialRequestLine.class})
@RlsDimension(value = MaterialRequest.DEPARTMENT, valuePaths = {"departmentId"})
@FilterDef(name = MaterialRequest.DEPARTMENT, parameters = @ParamDef(name = "allowedIds", type = Long.class),
        applyToLoadByKey = true)
@Filter(name = MaterialRequest.DEPARTMENT, condition = "department_id in (:allowedIds)")
public class MaterialRequest extends BaseEntity {
    public static final String DEPARTMENT = "CONSUMER_DEPARTMENT";

    @NotBlank @Column(nullable = false, length = 40)
    @Numbered(prefix = "ЗМ-", pattern = "{prefix}{seq:000000}")
    @FieldMetadata(label = "Номер", order = 10)
    private String number;

    @NotNull @Column(nullable = false)
    @FieldMetadata(label = "Дата", order = 20)
    private LocalDate date = LocalDate.now();

    @NotNull @Min(1) @Max(2) @Column(name = "department_id", nullable = false)
    @FieldMetadata(label = "Подразделение (1 или 2)", order = 30)
    private Long departmentId;

    @NotNull @Enumerated(EnumType.STRING) @Column(nullable = false)
    @FieldMetadata(label = "Статус", order = 40)
    private RequestStatus status = RequestStatus.DRAFT;

    @FieldMetadata(label = "Комментарий", order = 50)
    private String comment;

    public String getNumber() { return number; }
    public void setNumber(String number) { this.number = number; }
    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }
    public Long getDepartmentId() { return departmentId; }
    public void setDepartmentId(Long departmentId) { this.departmentId = departmentId; }
    public RequestStatus getStatus() { return status; }
    public void setStatus(RequestStatus status) { this.status = status; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
}
