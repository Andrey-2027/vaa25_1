package com.example.generated.harness;

import java.time.LocalDate;
import java.util.List;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.metadata.annotation.RequiredMode;

/** Independent E4.C expectations for a catalog root with one owned section. */
public final class ScenarioSpec {

    public enum Base { CATALOG, DOCUMENT }

    public static final String REQUIRED_SUITE = "com.example.generated.harness.SectionsScenarioTest";
    public static final String SUPPORT_PROFILE = "g1-sections";
    public static final boolean OWNER_ENABLED = false;
    public static final Base BASE = Base.CATALOG;
    public static final String SCENARIO = "sections-catalog-1";
    public static final String CLASS_NAME = "com.example.generated.CatalogSections1";
    public static final String RELATIVE_PATH = "com/example/generated/CatalogSections1.java";
    public static final String TABLE_NAME = "catalog_sections_1";

    public static final String LIST_TITLE = "Каталог с одной секцией";
    public static final String ITEM_TITLE = "Каталог с одной секцией";
    public static final String SELECTION_TITLE = "Выбор каталога с одной секцией";
    public static final String ICON = "FILE";
    public static final String NAME_SAMPLE = "Каталог с одной секцией G1";
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
            new BaseFieldSpec("code", "code", FieldType.TEXT, String.class, "Код", 10,
                    true, RequiredMode.REQUIRED, true, 50, null, "CATALOG_CODE", null),
            new BaseFieldSpec("name", "name", FieldType.TEXT, String.class, "Наименование", 20,
                    true, RequiredMode.REQUIRED, true, 200, NAME_SAMPLE, null, null));

    public static final List<SectionSpec> SECTIONS = List.of(
            new SectionSpec("com.example.generated.rows.CatalogSections1Row1",
                    "catalog_sections_1_row_1", "Строки каталога", 10, "catalog",
                    "lineNumber", "note", "строка каталога"));

    public static final List<FieldSpec> FIELDS = List.of();

    public static final List<String> LIST_COLUMNS = List.of("code", "name");
    public static final List<String> SELECT_COLUMNS = List.of("code", "name");

    private ScenarioSpec() {
    }
}
