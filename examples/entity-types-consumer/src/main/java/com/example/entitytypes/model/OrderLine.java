package com.example.entitytypes.model;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import org.ipro.crud.BaseEntity;
import org.ipro.metadata.annotation.*;
import java.math.BigDecimal;

@Entity
@TableSectionMetadata(parentEntity = Order.class, parentField = "owner", title = "Позиции", lineNumberField = "line", minRows = 0)
public class OrderLine extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(nullable = false) private Order owner;
    private Integer line;
    @NotNull @ManyToOne(fetch = FetchType.LAZY) @FieldMetadata(label = "Товар", order = 10)
    private Product product;
    @NotNull @Positive @FieldMetadata(label = "Количество", order = 20)
    private BigDecimal quantity;
    public Order getOwner() { return owner; }
    public void setOwner(Order owner) { this.owner = owner; }
    public Integer getLine() { return line; }
    public void setLine(Integer line) { this.line = line; }
    public Product getProduct() { return product; }
    public void setProduct(Product product) { this.product = product; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
}
