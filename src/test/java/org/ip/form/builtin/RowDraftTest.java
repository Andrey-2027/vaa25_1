package org.ip.form.builtin;

import org.ipro.metadata.FieldMetadataInfo;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ip.service.LookupService;
import org.ipro.crud.IdentifiableEntity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RowDraftTest {

    @Test
    void restoreReturnsScalarAndEntityReferenceToCapturedValues() throws Exception {
        IdentifiableEntity originalReference = mock(IdentifiableEntity.class);
        IdentifiableEntity changedReference = mock(IdentifiableEntity.class);
        when(originalReference.getId()).thenReturn(7L);

        Row row = new Row("before", originalReference);
        RowDraft<Row> draft = RowDraft.capture(row, fields());
        row.name = "after";
        row.reference = changedReference;

        LookupService lookupService = mock(LookupService.class);
        when(lookupService.findById(IdentifiableEntity.class, 7L))
                .thenReturn(Optional.of(originalReference));

        draft.restore(row, lookupService);

        assertThat(row.name).isEqualTo("before");
        assertThat(row.reference).isSameAs(originalReference);
    }

    private static List<FieldMetadataInfo> fields() throws NoSuchFieldException {
        return List.of(field("name"), field("reference"));
    }

    private static FieldMetadataInfo field(String name) throws NoSuchFieldException {
        Field field = Row.class.getDeclaredField(name);
        return new FieldMetadataInfo(field, field.getAnnotation(FieldMetadata.class));
    }

    private static class Row {
        @FieldMetadata
        private String name;

        @FieldMetadata
        private IdentifiableEntity reference;

        private Row(String name, IdentifiableEntity reference) {
            this.name = name;
            this.reference = reference;
        }
}
}
