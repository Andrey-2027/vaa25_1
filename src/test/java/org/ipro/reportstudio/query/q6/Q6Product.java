package org.ipro.reportstudio.query.q6;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;

import java.math.BigDecimal;

/**
 * Тестовая сущность для интеграционных проверок визуального построителя.
 * Каталог {@link org.ipro.reportstudio.query.QueryBuilderMetadataCatalog} пропускает
 * только сущности с {@code @EntityMetadata} и полями {@code @FieldMetadata}.
 */
@Entity(name = "Q6Product")
@EntityMetadata(listFormTitle = "Тестовые продукты")
public class Q6Product {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @FieldMetadata(label = "Код", order = 1)
    private String code;

    @FieldMetadata(label = "Количество", order = 2, grid = @GridColumn(order = 2))
    private BigDecimal amount;

    public Long getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
}
