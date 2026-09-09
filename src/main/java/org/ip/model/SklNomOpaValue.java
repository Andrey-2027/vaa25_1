package org.ip.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import org.ipro.crud.BaseEntity;

/**
 * Строка набора {@link SklNomOpa}: конкретная пара «тип → значение» внутри комбинации
 * (аналог {@code TSklOpaDetail}; ранее в черновых проходах — AttributeSetItem).
 *
 * <p>Unique — {@code (set, attrType)}: тип встречается в наборе один раз.
 * Индекс {@code (attrType, value)} — обратный поиск («все наборы, где цвет = красный»).
 * Сеттеров нет: набор и его строки неизменяемы.
 */
@Entity
@Table(name = "skl_nom_opa_value",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_skl_nom_opa_value_set_type", columnNames = {"set_id", "attr_type_id"})
    },
    indexes = {
        @Index(name = "ix_skl_nom_opa_value_type_value", columnList = "attr_type_id, attr_value_id")
    })
public class SklNomOpaValue extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "set_id", nullable = false)
    private SklNomOpa set;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attr_type_id", nullable = false)
    private AttributeType attrType;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attr_value_id", nullable = false)
    private AttributeValue value;

    protected SklNomOpaValue() {
    }

    public SklNomOpaValue(SklNomOpa set, AttributeType attrType, AttributeValue value) {
        this.set = set;
        this.attrType = attrType;
        this.value = value;
    }

    public SklNomOpa getSet() {
        return set;
    }

    public AttributeType getAttrType() {
        return attrType;
    }

    public AttributeValue getValue() {
        return value;
    }
}
