package com.example.materialrequests.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.ipro.crud.BaseEntity;
import org.ipro.metadata.annotation.*;
import java.math.BigDecimal;

@Entity
@Table(name = "consumer_material_request_line")
@TableSectionMetadata(parentEntity = MaterialRequest.class, parentField = "request", title = "Материалы",
        rowFormTitle = "Строка заявки", lineNumberField = "lineNumber", minRows = 1,
        rlsPolicy = SectionRlsPolicy.INHERIT_ROOT)
public class MaterialRequestLine extends BaseEntity {
    @NotNull @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "request_id", nullable = false)
    private MaterialRequest request;
    @Column(name = "line_number")
    private Integer lineNumber;
    @NotNull @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "material_id", nullable = false)
    @FieldMetadata(label = "Материал", order = 10, lookup = @Lookup(entity = Material.class))
    private Material material;
    @NotNull @Positive @Column(nullable = false, precision = 19, scale = 3)
    @FieldMetadata(label = "Количество", order = 20)
    private BigDecimal quantity;

    public MaterialRequest getRequest() { return request; }
    public void setRequest(MaterialRequest request) { this.request = request; }
    public Integer getLineNumber() { return lineNumber; }
    public void setLineNumber(Integer lineNumber) { this.lineNumber = lineNumber; }
    public Material getMaterial() { return material; }
    public void setMaterial(Material material) { this.material = material; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
}
