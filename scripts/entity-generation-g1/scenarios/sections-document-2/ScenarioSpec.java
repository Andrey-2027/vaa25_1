package com.example.generated.harness;

import java.time.LocalDate;
import java.util.List;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.metadata.annotation.RequiredMode;

/** Independent E4.C expectations for a document root with two owned sections. */
public final class ScenarioSpec {

    public enum Base { CATALOG, DOCUMENT }

    public static final String REQUIRED_SUITE = "com.example.generated.harness.SectionsScenarioTest";
    public static final String SUPPORT_PROFILE = "g1-sections";
    public static final boolean OWNER_ENABLED = false;
    public static final Base BASE = Base.DOCUMENT;
    public static final String SCENARIO = "sections-document-2";
    public static final String CLASS_NAME = "com.example.generated.DocumentSections2";
    public static final String RELATIVE_PATH = "com/example/generated/DocumentSections2.java";
    public static final String TABLE_NAME = "document_sections_2";

    public static final String LIST_TITLE = "Документ с двумя секциями";
    public static final String ITEM_TITLE = "Документ с двумя секциями";
    public static final String SELECTION_TITLE = "Выбор документа с двумя секциями";
    public static final String ICON = "FILE";
    public static final String NAME_SAMPLE = "Документ с двумя секциями G1";
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

    public record SectionSpec(String className, String tableName, String title, int order,
                              String parentField, String lineNumberField, String fieldName,
                              String sample) {
    }

    public static final List<BaseFieldSpec> BASE_FIELDS = List.of(
            new BaseFieldSpec("number", "number", FieldType.TEXT, String.class, "Номер", 10,
                    true, RequiredMode.REQUIRED, true, 50, null, "DOCUMENT_NUMBER", "date"),
            new BaseFieldSpec("date", "date", FieldType.DATE, LocalDate.class, "Дата", 20,
                    true, RequiredMode.REQUIRED, false, null, DATE_SAMPLE, null, null));

    public static final List<SectionSpec> SECTIONS = List.of(
            new SectionSpec("com.example.generated.rows.DocumentSections2Row1",
                    "document_sections_2_row_1", "Позиции", 10, "document",
                    "lineNumber", "note", "позиция документа"),
            new SectionSpec("com.example.generated.rows.DocumentSections2Row2",
                    "document_sections_2_row_2", "Примечания", 20, "document",
                    "lineNumber", "note", "примечание документа"));

    public static final List<FieldSpec> FIELDS = List.of();

    public static final List<String> LIST_COLUMNS = List.of("number", "date");
    public static final List<String> SELECT_COLUMNS = List.of("number", "date");

    private ScenarioSpec() {
    }
}
