package com.example.generated.harness;

import java.time.LocalDate;
import java.util.List;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.metadata.annotation.RequiredMode;

/** Independent expectations for the empty standard document, authored from the E1 input contract. */
public final class ScenarioSpec {

    public enum Base { CATALOG, DOCUMENT }

    public static final Base BASE = Base.DOCUMENT;
    public static final String SCENARIO = "owner-document-v1";
    public static final String CLASS_NAME = "com.example.generated.OwnerEnvelopeDocument";
    public static final String RELATIVE_PATH = "com/example/generated/OwnerEnvelopeDocument.java";
    public static final String TABLE_NAME = "owner_envelope_document";

    public static final String LIST_TITLE = "Документ envelope E3";
    public static final String ITEM_TITLE = "Документ envelope E3";
    public static final String SELECTION_TITLE = "Документ envelope E3";
    public static final String ICON = "FILE";
    public static final String NAME_SAMPLE = "Пустой документ G1";
    public static final LocalDate DATE_SAMPLE = LocalDate.of(2026, 10, 29);
    public static final LocalDate DATE_UPDATED = LocalDate.of(2026, 10, 30);

    public record BaseFieldSpec(String name, String column, FieldType type, Class<?> javaType,
                                String label, int order, boolean serverRequired,
                                RequiredMode requiredMode, boolean notBlank, Integer length,
                                Object sample, String numberingRole, String dateField) {
    }

    public record FieldSpec(String name, String column, FieldType type, Class<?> javaType,
                            String label, int order, boolean serverRequired, RequiredMode requiredMode,
                            boolean form, Integer length, Integer precision, Integer scale,
                            Object sample) {
    }

    public static final List<BaseFieldSpec> BASE_FIELDS = List.of(
            new BaseFieldSpec("number", "number", FieldType.TEXT, String.class, "Номер", 10,
                    true, RequiredMode.REQUIRED, true, 50, null, "DOCUMENT_NUMBER", "date"),
            new BaseFieldSpec("date", "date", FieldType.DATE, LocalDate.class, "Дата", 20,
                    true, RequiredMode.REQUIRED, false, null, DATE_SAMPLE, null, null));

    public static final List<FieldSpec> FIELDS = List.of();

    public static final List<String> LIST_COLUMNS = List.of("number", "date");
    public static final List<String> SELECT_COLUMNS = List.of("number", "date");

    private ScenarioSpec() {
    }
}
