package org.ip.g1r4fixture;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import org.ipro.crud.BaseEntity;
import org.ipro.metadata.annotation.*;
import org.ipro.register.RegisterMetadata;

/** Scoped test entity: matches the NONE generator contract, never part of the production scan. */
@Entity
@Table(name="g1r4_none_rate", uniqueConstraints=@UniqueConstraint(columnNames="region"))
@EntityMetadata(listFormTitle="Ставки", itemFormTitle="Ставка", selectionFormTitle="Ставки", listColumns={"region","rate"}, selectColumns={"region","rate"})
@RegisterMetadata(dimensions="region", resources="rate", keyWriteAllowed=true, keyChangeAllowed=false, displayName="Ставки NONE")
public class UiNoneRate extends BaseEntity {
    @NotNull @Column(name="region", nullable=false, updatable=false, length=20)
    @FieldMetadata(label="Регион", order=30)
    private String region;
    @NotNull @Column(name="rate", nullable=false, precision=19, scale=2)
    @FieldMetadata(label="Ставка", order=40)
    private BigDecimal rate;
    public String getRegion() { return region; }
    public void setRegion(String value) { region=value; }
    public BigDecimal getRate() { return rate; }
    public void setRate(BigDecimal value) { rate=value; }
}
