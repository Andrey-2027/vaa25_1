package com.example.generated.harness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.metadata.annotation.RequiredMode;

/** Independent expectations for the combined scalar document, authored from the E1 input draft. */
public final class ScenarioSpec {

    public enum Base { CATALOG, DOCUMENT }

    public static final Base BASE = Base.DOCUMENT;
    public static final String SCENARIO = "scalar-document";
    public static final String CLASS_NAME = "com.example.generated.ScalarDocument";
    public static final String RELATIVE_PATH = "com/example/generated/ScalarDocument.java";
    public static final String TABLE_NAME = "scalar_document";

    public static final String LIST_TITLE = "Документ";
    public static final String ITEM_TITLE = "Документ";
    public static final String SELECTION_TITLE = "Документ";
    public static final String ICON = "FILE";
    public static final String NAME_SAMPLE = "Документ G1";
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

    public static final List<FieldSpec> FIELDS = List.of(
            new FieldSpec("text", "text", FieldType.TEXT, String.class,
                    "Подпись \"текст\" \\ и\nстрока", 30, true, RequiredMode.AUTO, true,
                    255, null, null, "ART-001"),
            new FieldSpec("quantity", "quantity", FieldType.INTEGER, Integer.class,
                    "Подпись \"текст\" \\ и\nстрока", 40, false, RequiredMode.REQUIRED, true,
                    null, null, null, 7),
            new FieldSpec("count", "count", FieldType.INTEGER, Long.class,
                    "Подпись \"текст\" \\ и\nстрока", 50, false, RequiredMode.OPTIONAL, true,
                    null, null, null, 42L),
            new FieldSpec("amount", "amount", FieldType.DECIMAL, BigDecimal.class,
                    "Подпись \"текст\" \\ и\nстрока", 60, true, RequiredMode.REQUIRED, true,
                    null, 19, 2, new BigDecimal("123.45")),
            new FieldSpec("active", "active", FieldType.BOOLEAN, Boolean.class,
                    "Подпись \"текст\" \\ и\nстрока", 70, false, RequiredMode.AUTO, true,
                    null, null, null, true),
            new FieldSpec("dueDate", "due_date", FieldType.DATE, LocalDate.class,
                    "Подпись \"текст\" \\ и\nстрока", 80, false, RequiredMode.AUTO, true,
                    null, null, null, LocalDate.of(2026, 10, 28)),
            new FieldSpec("timestamp", "timestamp", FieldType.DATETIME, LocalDateTime.class,
                    "Подпись \"текст\" \\ и\nстрока", 90, false, RequiredMode.AUTO, true,
                    null, null, null, LocalDateTime.of(2026, 10, 28, 12, 34, 56)));

    public static final List<String> LIST_COLUMNS = List.of(
            "number", "date", "text", "quantity", "count", "amount", "active", "dueDate", "timestamp");
    public static final List<String> SELECT_COLUMNS = List.of(
            "number", "date", "text", "quantity", "count", "amount", "active", "dueDate", "timestamp");

    private ScenarioSpec() {
    }
}
