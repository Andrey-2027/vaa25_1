package org.ipro.data.ownerfixture;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.validation.constraints.NotNull;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.metadata.annotation.CatalogOwner;
import org.ipro.metadata.annotation.EntityKind;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;

/** Подчинённый справочник: обязательный и неизменяемый владелец. */
@Entity(name = "OwnerContract")
@EntityMetadata(kind = EntityKind.CATALOG, listFormTitle = "Договоры")
@CatalogOwner(property = "counterparty")
public class OwnerContract implements IdentifiableEntity<Long> {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @FieldMetadata(label = "Номер", order = 10)
    private String number;

    @ManyToOne(optional = false)
    @JoinColumn(name = "counterparty_id", nullable = false)
    @NotNull
    @FieldMetadata(label = "Контрагент", order = 30)
    private OwnerContractor counterparty;

    public OwnerContract() {
    }

    public OwnerContract(String number, OwnerContractor counterparty) {
        this.number = number;
        this.counterparty = counterparty;
    }

    @Override
    public Long getId() {
        return id;
    }

    @Override
    public void setId(Long id) {
        this.id = id;
    }

    public String getNumber() {
        return number;
    }

    public void setNumber(String number) {
        this.number = number;
    }

    public OwnerContractor getCounterparty() {
        return counterparty;
    }

    public void setCounterparty(OwnerContractor counterparty) {
        this.counterparty = counterparty;
    }
}
