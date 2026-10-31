package com.example.generated.harness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.metadata.annotation.RequiredMode;

/** Independent expectations for the combined catalog, authored from {@code fullDraft()}. */
public final class ScenarioSpec {

    public enum Base { CATALOG, DOCUMENT }

    public static final Base BASE = Base.CATALOG;
    public static final String SCENARIO = "future-catalog";
    public static final String CLASS_NAME = "com.example.generated.FutureCatalog";
    public static final String RELATIVE_PATH = "com/example/generated/FutureCatalog.java";
    public static final String TABLE_NAME = "future_catalog";

    public static final String LIST_TITLE = "Будущие справочники";
    public static final String ITEM_TITLE = "Будущий справочник";
    public static final String SELECTION_TITLE = "Выбор справочника";
    public static final String ICON = "BOOK";
    public static final String NAME_SAMPLE = "Будущий справочник G1";

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
            new BaseFieldSpec("code", "code", FieldType.TEXT, String.class, "Код", 10,
                    true, RequiredMode.REQUIRED, true, 50, null, "CATALOG_CODE", null),
            new BaseFieldSpec("name", "name", FieldType.TEXT, String.class, "Наименование", 20,
                    true, RequiredMode.REQUIRED, true, 200, NAME_SAMPLE, null, null));

    public static final List<FieldSpec> FIELDS = List.of(
            new FieldSpec("article", "article", FieldType.TEXT, String.class, "Артикул", 30,
                    true, RequiredMode.AUTO, true, 100, null, null, "ART-001"),
            new FieldSpec("amount", "amount", FieldType.INTEGER, Integer.class, "Количество", 40,
                    false, RequiredMode.OPTIONAL, false, null, null, null, null),
            new FieldSpec("total", "total", FieldType.INTEGER, Long.class, "Итого", 50,
                    false, RequiredMode.REQUIRED, true, null, null, null, 42L),
            new FieldSpec("price", "price", FieldType.DECIMAL, BigDecimal.class, "Цена", 60,
                    true, RequiredMode.AUTO, true, null, 19, 4, new BigDecimal("123.4500")),
            new FieldSpec("active", "active", FieldType.BOOLEAN, Boolean.class,
                    "Активен \"да\" \\ и\nперенос", 70, false, RequiredMode.AUTO, false,
                    null, null, null, true),
            new FieldSpec("startDate", "start_date", FieldType.DATE, LocalDate.class,
                    "Дата начала", 80, false, RequiredMode.AUTO, true, null, null, null,
                    LocalDate.of(2026, 10, 28)),
            new FieldSpec("startedAt", "started_at", FieldType.DATETIME, LocalDateTime.class,
                    "Момент начала", 90, false, RequiredMode.AUTO, true, null, null, null,
                    LocalDateTime.of(2026, 10, 28, 12, 34, 56)),
            new FieldSpec("listOnly", "list_only", FieldType.TEXT, String.class,
                    "Только в списке", 100, false, RequiredMode.AUTO, false, 40, null, null,
                    "list-only"),
            new FieldSpec("selectionOnly", "selection_only", FieldType.TEXT, String.class,
                    "Только в выборе", 110, false, RequiredMode.AUTO, false, 40, null, null,
                    "selection-only"),
            new FieldSpec("serviceCode", "service_code", FieldType.TEXT, String.class,
                    "Служебный код", 120, false, RequiredMode.AUTO, false, 20, null, null,
                    "SVC-1"));

    public static final List<String> LIST_COLUMNS =
            List.of("code", "name", "article", "price", "active", "startDate", "listOnly");
    public static final List<String> SELECT_COLUMNS =
            List.of("code", "name", "article", "price", "active", "startedAt", "selectionOnly");

    private ScenarioSpec() {
    }
}
