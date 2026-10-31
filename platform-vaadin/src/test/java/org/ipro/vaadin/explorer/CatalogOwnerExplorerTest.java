package org.ipro.vaadin.explorer;

import org.ipro.form.CatalogOwnerFormsTest;
import org.ipro.metadata.CatalogOwnerDescriptor;
import org.ipro.metadata.MetadataResolver;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class CatalogOwnerExplorerTest {
    @Test void structureCarriesResolvedOwnerSeparatelyFromSectionParentAndOrdinaryReference() {
        var assembler=new EntityStructureFactsAssembler();var metadata=new MetadataResolver();
        var type=CatalogOwnerFormsTest.Contract.class;
        var input=new EntityStructureFactsAssembler.FieldInput(type,List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of());
        var structure=assembler.assemble(type,input,List.of(),metadata.resolve(type));
        assertThat(structure.catalogOwner()).isEqualTo(new CatalogOwnerDescriptor("counterparty",CatalogOwnerFormsTest.Party.class.getName()));
        assertThat(structure.sections()).isEmpty();
        var ordinary=CatalogOwnerFormsTest.Ordinary.class;
        var plain=new EntityStructureFactsAssembler.FieldInput(ordinary,List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of());
        assertThat(assembler.assemble(ordinary,plain,List.of(),metadata.resolve(ordinary)).catalogOwner()).isNull();
    }
}
