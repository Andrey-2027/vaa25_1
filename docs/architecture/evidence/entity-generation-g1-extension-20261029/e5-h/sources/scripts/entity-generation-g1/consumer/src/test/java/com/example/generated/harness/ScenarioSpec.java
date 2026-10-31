package com.example.generated.harness;

import java.time.LocalDate;
import java.util.List;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.metadata.annotation.RequiredMode;

/**
 * Independent expectations for the harness control source. Values are authored from the input
 * contract, never read back from the generated class or its annotations.
 */
public final class ScenarioSpec {

    public enum Base { CATALOG, DOCUMENT }

    public static final Base BASE = Base.CATALOG;
    public static final String SCENARIO = "control";
    public static final String CLASS_NAME = "com.example.generated.ControlCatalog";
    public static final String RELATIVE_PATH = "com/example/generated/ControlCatalog.java";
    public static final String TABLE_NAME = "control_catalog";

    public static final String LIST_TITLE = "Контрольные справочники";
    public static final String ITEM_TITLE = "Контрольный справочник";
    public static final String SELECTION_TITLE = "Выбор контрольного справочника";
    public static final String ICON = "FILE";
    public static final String NAME_SAMPLE = "Контрольный справочник G1";
    public static final LocalDate DATE_SAMPLE = LocalDate.of(2026, 10, 29);
    public static final LocalDate DATE_UPDATED = LocalDate.of(2026, 10, 30);

    /**
     * Expectations for the fields inherited from the standard base. The sample is the value
     * written before create; a null sample means the platform numbering service must assign it.
     */
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
            new FieldSpec("note", "note", FieldType.TEXT, String.class, "Заметка", 30,
                    false, RequiredMode.AUTO, true, 30, null, null, "control"));

    public static final List<String> LIST_COLUMNS = List.of("code", "name", "note");
    public static final List<String> SELECT_COLUMNS = List.of("code", "name", "note");

    private ScenarioSpec() {
    }
}
