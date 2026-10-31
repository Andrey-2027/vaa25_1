package com.example.generated.harness;

import java.time.LocalDate;
import java.util.List;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.metadata.annotation.RequiredMode;

/**
 * Independent expectations for the multi-file control set. The root is authored as a
 * document; row expectations name their own parent/line-number linkage and published
 * subject field, so the acceptance test never reads those values back from the class.
 */
public final class ScenarioSpec {

    public enum Base { CATALOG, DOCUMENT }

    public static final Base BASE = Base.DOCUMENT;
    public static final String SCENARIO = "multifile";
    public static final String CLASS_NAME = "com.example.generated.MultiFileRoot";
    public static final String RELATIVE_PATH = "com/example/generated/MultiFileRoot.java";
    public static final String TABLE_NAME = "multi_file_root";

    public static final String LIST_TITLE = "Контрольные комплекты";
    public static final String ITEM_TITLE = "Контрольный комплект";
    public static final String SELECTION_TITLE = "Выбор контрольного комплекта";
    public static final String ICON = "FILE";
    public static final String NAME_SAMPLE = "";
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

    public static final List<FieldSpec> FIELDS = List.of();

    public static final List<SectionSpec> SECTIONS = List.of(
            new SectionSpec("com.example.generated.MultiFileRow", "multi_file_row", "Позиции", 10,
                    "owner", "lineNumber", "note", "Строка комплекта"),
            new SectionSpec("com.example.generated.MultiFileNote", "multi_file_note", "Примечания", 20,
                    "owner", "lineNumber", "text", "Примечание комплекта"));

    public static final List<String> LIST_COLUMNS = List.of("number", "date");
    public static final List<String> SELECT_COLUMNS = List.of("number", "date");

    private ScenarioSpec() {
    }
}
