package com.example.generated.harness;

import java.util.List;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.metadata.annotation.RequiredMode;

/** Independent E3.C owner input; OwnerScenarioTest checks the capability separately from subject fields. */
public final class ScenarioSpec {

    public enum Base { CATALOG, DOCUMENT }

    public static final String REQUIRED_SUITE = "com.example.generated.harness.OwnerScenarioTest";
    public static final Base BASE = Base.CATALOG;
    public static final String SCENARIO = "owned-catalog";
    public static final String CLASS_NAME = "com.example.generated.OwnedCatalog";
    public static final String RELATIVE_PATH = "com/example/generated/OwnedCatalog.java";
    public static final String TABLE_NAME = "owned_catalog";

    public static final String LIST_TITLE = "Договоры";
    public static final String ITEM_TITLE = "Договоры";
    public static final String SELECTION_TITLE = "Договоры";
    public static final String ICON = "FILE";
    public static final String NAME_SAMPLE = "Договоры G1";
    public static final java.time.LocalDate DATE_SAMPLE = java.time.LocalDate.of(2026, 10, 29);
    public static final java.time.LocalDate DATE_UPDATED = java.time.LocalDate.of(2026, 10, 30);

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

    public static final List<FieldSpec> FIELDS = List.of(new FieldSpec("description", "description", FieldType.TEXT,
            String.class, "Описание", 40, false, RequiredMode.AUTO, true, 200, null, null, "Описание договора"));

    public static final List<String> LIST_COLUMNS = List.of("code", "name", "counterparty", "description");
    public static final List<String> SELECT_COLUMNS = List.of("code", "name", "counterparty", "description");

    private ScenarioSpec() {
    }
}
