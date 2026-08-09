package org.ip.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.ip.metadata.annotation.*;

@Entity
@Table(name = "prd_spec_oper")
@TableSectionMetadata(
        parentEntity = PrdSpec.class,
        parentField = "prdSpec",
        title = "Операции спецификации",
        rowFormTitle = "Операция",
        lineNumberField = "order",
        minRows = 1,
        serviceClass = org.ip.service.PrdSpecOperService.class
)
public class PrdSpecOper extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "prd_spec_id", nullable = false)
    @NotNull
    private PrdSpec prdSpec;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "oper_id")
    @FieldMetadata(
        label = "Операция", order = 1,
        type = FieldType.ENTITY_REFERENCE,
        lookup = @Lookup(entity = Oper.class),
        grid = @GridColumn(order = 1, width = "250px")
    )
    private Oper oper;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workshop_id")
    @FieldMetadata(
        label = "Цех", order = 2,
        type = FieldType.ENTITY_REFERENCE,
        lookup = @Lookup(entity = Workshop.class),
        grid = @GridColumn(order = 2, width = "200px")
    )
    private Workshop ceh;

    @Size(max = 100)
    @Column(name = "route")
    @FieldMetadata(
        label = "Маршрут", order = 3,
        grid = @GridColumn(order = 3, width = "200px")
    )
    private String route;

    @Column(name = "order_num")
    @FieldMetadata(
        label = "Порядок", order = 4,
        type = FieldType.INTEGER,
        grid = @GridColumn(order = 4, width = "100px")
    )
    private Integer order;

    public PrdSpec getPrdSpec() {
        return prdSpec;
    }

    public void setPrdSpec(PrdSpec prdSpec) {
        this.prdSpec = prdSpec;
    }

    public Oper getOper() {
        return oper;
    }

    public void setOper(Oper oper) {
        this.oper = oper;
    }

    public Workshop getCeh() {
        return ceh;
    }

    public void setCeh(Workshop ceh) {
        this.ceh = ceh;
    }

    public String getRoute() {
        return route;
    }

    public void setRoute(String route) {
        this.route = route;
    }

    public Integer getOrder() {
        return order;
    }

    public void setOrder(Integer order) {
        this.order = order;
    }
}
