package org.ipro.metadata;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.validation.constraints.NotBlank;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.RequiredMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FieldMetadataRequiredOriginTest {
    static class Probe {
        @FieldMetadata(required = RequiredMode.REQUIRED) private String uiOnly;
        @FieldMetadata @NotBlank @Column(nullable = false) private String beanAndJpa;
        @FieldMetadata @Column(nullable = false) private String column;
        @FieldMetadata @JoinColumn(nullable = false) @ManyToOne private Probe join;
        @FieldMetadata @ManyToOne(optional = false) private Probe manyToOne;
        @FieldMetadata @OneToOne(optional = false) private Probe oneToOne;
        @FieldMetadata @Basic(optional = false) private String basic;
        @FieldMetadata @Basic(optional = false) private int primitive;
    }

    @Test
    void originBelongsToTheResolvedWriteContractRatherThanUiOverrideOrAnExplorerInference() {
        var fields = new MetadataResolver().resolveAllAnnotatedFields(Probe.class);
        assertThat(fields).filteredOn(f -> f.getName().equals("uiOnly")).singleElement().satisfies(field -> {
            assertThat(field.isRequired()).isTrue();
            assertThat(field.getRequiredOrigin()).isEqualTo(FactOrigin.EXPLICIT);
            assertThat(field.isServerRequired()).isFalse();
            assertThat(field.getServerRequiredOrigin()).isEqualTo(FactOrigin.PLATFORM_DEFAULT);
        });
        assertThat(fields).filteredOn(f -> f.getName().equals("beanAndJpa")).singleElement()
            .satisfies(field -> assertThat(field.getServerRequiredOrigin()).isEqualTo(FactOrigin.BEAN_VALIDATION));
        assertThat(fields).filteredOn(f -> !f.getName().equals("primitive") && !f.getName().equals("uiOnly")
            && !f.getName().equals("beanAndJpa")).hasSize(5).allSatisfy(field -> {
                assertThat(field.isServerRequired()).isTrue();
                assertThat(field.getServerRequiredOrigin()).isEqualTo(FactOrigin.JPA_MAPPING);
            });
        assertThat(fields).filteredOn(f -> f.getName().equals("primitive")).singleElement().satisfies(field -> {
            assertThat(field.isServerRequired()).isFalse();
            assertThat(field.getServerRequiredOrigin()).isEqualTo(FactOrigin.PLATFORM_DEFAULT);
        });
    }
}
