package com.example.entitytypes.model;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import org.ipro.crud.StandardDocumentEntity;
import org.ipro.metadata.annotation.*;
import org.ipro.rls.RlsDimension;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import java.time.LocalDate;

@Entity @Table(name = "et5_order")
@TableSections(OrderLine.class)
@RlsDimension(value = Order.TEAM, valuePaths = "teamId")
@FilterDef(name = Order.TEAM, parameters = @ParamDef(name = "allowedIds", type = Long.class), applyToLoadByKey = true)
@Filter(name = Order.TEAM, condition = "team_id in (:allowedIds)")
public class Order extends StandardDocumentEntity {
    public static final String TEAM = "ET5_TEAM";
    @NotNull @Column(name = "team_id", nullable = false)
    @FieldMetadata(label = "Подразделение", order = 30)
    private Long teamId = 1L;
    public Order() { setDate(LocalDate.now()); }
    public Long getTeamId() { return teamId; }
    public void setTeamId(Long teamId) { this.teamId = teamId; }
}
