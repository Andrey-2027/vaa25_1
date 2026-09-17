package org.ipro.metadata;

import org.ipro.metadata.annotation.SectionPersistenceMode;
import org.ipro.metadata.annotation.TableSectionMetadata;
import org.ipro.metadata.annotation.TableSections;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SectionDescriptorResolutionTest {

    private final MetadataResolver resolver = new MetadataResolver();

    @Test
    void resolvesOwnedSectionContractFromBothSides() {
        TableSectionMetadataInfo descriptor = resolver.resolveTableSections(Owner.class).getFirst();

        assertThat(descriptor.getKey()).isEqualTo("Owner.Row");
        assertThat(descriptor.getOwnerClass()).isEqualTo(Owner.class);
        assertThat(descriptor.getRowClass()).isEqualTo(Row.class);
        assertThat(descriptor.getParentFieldName()).isEqualTo("owner");
        assertThat(descriptor.getLineNumberFieldName()).isEqualTo("lineNumber");
        assertThat(descriptor.getPersistenceMode())
            .isEqualTo(SectionPersistenceMode.MUTABLE_REPLACE_ALL);
    }

    @Test
    void rejectsParentFieldWithIncompatibleType() {
        assertThatThrownBy(() -> resolver.resolveTableSections(WrongOwner.class))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot reference owner")
            .hasMessageContaining(WrongRow.class.getName());
    }

    @Test
    void rejectsUnsupportedLineNumberFieldType() {
        assertThatThrownBy(() -> resolver.resolveTableSections(WrongLineOwner.class))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("lineNumberField")
            .hasMessageContaining("java.lang.String");
    }

    @Test
    void rejectsDuplicateRowDeclarationOnOwner() {
        assertThatThrownBy(() -> resolver.resolveTableSections(DuplicateOwner.class))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Duplicate row class");
    }

    @TableSections(Row.class)
    static class Owner {
    }

    @TableSectionMetadata(
        parentEntity = Owner.class,
        parentField = "owner",
        lineNumberField = "lineNumber"
    )
    static class Row {
        Owner owner;
        Integer lineNumber;
    }

    @TableSections(WrongRow.class)
    static class WrongOwner {
    }

    @TableSectionMetadata(parentEntity = WrongOwner.class, parentField = "owner")
    static class WrongRow {
        String owner;
    }

    @TableSections(WrongLineRow.class)
    static class WrongLineOwner {
    }

    @TableSectionMetadata(
        parentEntity = WrongLineOwner.class,
        parentField = "owner",
        lineNumberField = "lineNumber"
    )
    static class WrongLineRow {
        WrongLineOwner owner;
        String lineNumber;
    }

    @TableSections({DuplicateRow.class, DuplicateRow.class})
    static class DuplicateOwner {
    }

    @TableSectionMetadata(parentEntity = DuplicateOwner.class, parentField = "owner")
    static class DuplicateRow {
        DuplicateOwner owner;
    }
}
