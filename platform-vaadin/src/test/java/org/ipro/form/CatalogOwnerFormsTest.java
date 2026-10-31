package org.ipro.form;

import com.vaadin.flow.component.HasValue;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import org.ipro.crud.*;
import org.ipro.form.builtin.ItemForm;
import org.ipro.metadata.*;
import org.ipro.metadata.annotation.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

public class CatalogOwnerFormsTest {
    @Entity @EntityMetadata(listColumns={"code","name"},selectColumns={"code","name"})
    public static class Party extends StandardCatalogEntity {}
    @Entity @CatalogOwner(property="counterparty")
    @EntityMetadata(listColumns={"code","name","counterparty"},selectColumns={"code","name","counterparty"})
    public static class Contract extends StandardCatalogEntity {
        @NotNull @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(nullable=false)
        @FieldMetadata(label="Контрагент",required=RequiredMode.REQUIRED)
        Party counterparty;
        public Party getCounterparty(){return counterparty;}
        public void setCounterparty(Party value){counterparty=value;}
    }
    @Entity @EntityMetadata
    public static class Ordinary extends StandardCatalogEntity {
        @ManyToOne @FieldMetadata Party owner;
    }
    public static Party party(long id){var p=new Party();p.setId(id);p.setCode("P"+id);p.setName("Владелец "+id);return p;}
    ItemForm<Contract> form(){
        var metadata=new MetadataResolver();
        return new ItemForm<>(metadata.resolve(Contract.class),new FieldFactory(mock(LookupService.class),new SelectionFormAssembler(metadata,null)));
    }
    @Test void createAllowsOwnerChoiceThenPersistedEntityLocksOnlyOwner() {
        var f=form();var value=f.initializeNewEntity();
        var field=(EntityField<Party>)f.getField("counterparty");assertThat(field.isReadOnly()).isFalse();
        field.setValue(party(1));assertThat(f.getEntity().counterparty.getId()).isEqualTo(1L);
        value.setId(10L);f.applyPersistedEntity(value);assertThat(field.isReadOnly()).isTrue();
        f.setReadOnly(true);f.setReadOnly(false);assertThat(field.isReadOnly()).isTrue();
        field.setValue(party(2));assertThat(f.getEntity().counterparty.getId()).isEqualTo(1L);
        assertThat(((HasValue<?,?>)f.getField("name")).isReadOnly()).isFalse();
    }
    @Test void existingEntityStartsLockedAndNewFormStartsEditable() {
        var f=form();var existing=new Contract();existing.setId(10L);existing.counterparty=party(1);f.setEntity(existing);
        assertThat(((EntityField<?>)f.getField("counterparty")).isReadOnly()).isTrue();
        f.setEntity(new Contract());assertThat(((EntityField<?>)f.getField("counterparty")).isReadOnly()).isFalse();
    }
    @Test void ownerContextIsPresetLockedAndScopedAroundSave() {
        var f=form();var owner=party(1);
        f.initializeNewEntity(Map.of("counterparty",owner,"name","Договор","code","C1"));
        var field=(EntityField<Party>)f.getField("counterparty");assertThat(field.isReadOnly()).isTrue();assertThat(field.getValue()).isSameAs(owner);
        assertThat(f.isDirty()).isFalse();
        f.setSaveHandler(form->{
            assertThat(org.ipro.data.CatalogOwnerContext.current().orElseThrow().ownerId()).isEqualTo(1L);
            return new FormSaveResult.Failure<>(List.of("fixture"),null);
        });
        assertThat(f.save()).isInstanceOf(FormSaveResult.Failure.class);
        assertThat(org.ipro.data.CatalogOwnerContext.current()).isEmpty();
    }
    @Test void ordinaryReferenceDoesNotBecomeImmutableOwner() {
        var metadata=new MetadataResolver();var f=new ItemForm<Ordinary>(metadata.resolve(Ordinary.class),new FieldFactory(mock(LookupService.class),new SelectionFormAssembler(metadata,null)));
        var value=new Ordinary();value.setId(1L);value.owner=party(1);f.setEntity(value);
        assertThat(CatalogOwnerForms.ownerOf(Ordinary.class)).isNull();
        assertThat(((EntityField<?>)f.getField("owner")).isReadOnly()).isFalse();
    }
    @Test void ownerContextRejectsUnsavedAndWrongTypeTargets() {
        assertThatThrownBy(()->CatalogOwnerForms.openingOwner(Contract.class,Map.of("counterparty",new Party()))).isInstanceOf(IllegalArgumentException.class);
        var wrong=new Contract();wrong.setId(1L);
        assertThatThrownBy(()->CatalogOwnerForms.openingOwner(Contract.class,Map.of("counterparty",wrong))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void openingOwnerCannotBeReplacedClearedOrLostByReopeningAnotherEntity() {
        var f=form();f.initializeNewEntity(Map.of("counterparty",party(1)));
        assertThatThrownBy(()->f.initializeNewEntity(Map.of("counterparty",party(2)))).isInstanceOf(IllegalArgumentException.class);
        var clear=new HashMap<String,Object>();clear.put("counterparty",null);
        assertThatThrownBy(()->f.initializeNewEntity(clear)).isInstanceOf(IllegalArgumentException.class);
        var other=new Contract();other.setId(2L);other.counterparty=party(2);
        assertThatThrownBy(()->f.setEntity(other)).isInstanceOf(IllegalArgumentException.class);
        assertThat(f.peekEntity().counterparty.getId()).isEqualTo(1L);
    }
}
