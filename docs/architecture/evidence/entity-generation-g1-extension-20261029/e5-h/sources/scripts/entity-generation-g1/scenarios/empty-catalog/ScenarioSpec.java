package com.example.generated.harness;

import java.util.List;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.metadata.annotation.RequiredMode;

/** Independent expectations for the empty standard catalog, authored from {@code emptyDraft()}. */
public final class ScenarioSpec {

    public enum Base { CATALOG, DOCUMENT }

    public static final Base BASE = Base.CATALOG;
    public static final String SCENARIO = "empty-catalog";
    public static final String CLASS_NAME = "com.example.generated.EmptyCatalog";
    public static final String RELATIVE_PATH = "com/example/generated/EmptyCatalog.java";
    public static final String TABLE_NAME = "empty_catalog";

    public static final String LIST_TITLE = "Пустые справочники";
    public static final String ITEM_TITLE = "Пустой справочник";
    public static final String SELECTION_TITLE = "Выбор пустого справочника";
    public static final String ICON = "FILE";
    public static final String NAME_SAMPLE = "Пустой справочник G1";

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

    public static final List<FieldSpec> FIELDS = List.of();

    public static final List<String> LIST_COLUMNS = List.of("code", "name");
    public static final List<String> SELECT_COLUMNS = List.of("code", "name");

    private ScenarioSpec() {
    }
}
