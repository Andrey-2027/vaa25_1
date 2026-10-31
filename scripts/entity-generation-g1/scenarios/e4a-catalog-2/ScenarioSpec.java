package com.example.generated.harness;

import java.time.LocalDate;
import java.util.List;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.metadata.annotation.RequiredMode;

/** Independent expectations for the empty standard document, authored from the E1 input contract. */
public final class ScenarioSpec {

    public enum Base { CATALOG, DOCUMENT }

    public static final String REQUIRED_SUITE = "com.example.generated.harness.SectionsScenarioTest";
    public static final String SUPPORT_PROFILE = "g1-sections";
    public static final boolean OWNER_ENABLED = false;
    public static final Base BASE = Base.CATALOG;
    public static final String SCENARIO = "sections-catalog-2";
    public static final String CLASS_NAME = "com.example.generated.CatalogSections2";
    public static final String RELATIVE_PATH = "com/example/generated/CatalogSections2.java";
    public static final String TABLE_NAME = "catalog_2";

    public static final String LIST_TITLE = "Комплект";
    public static final String ITEM_TITLE = "Комплект";
    public static final String SELECTION_TITLE = "Комплект";
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
            new BaseFieldSpec("code", "code", FieldType.TEXT, String.class, "Код", 10,
                    true, RequiredMode.REQUIRED, true, 50, null, "CATALOG_CODE", null),
            new BaseFieldSpec("name", "name", FieldType.TEXT, String.class, "Наименование", 20,
                    true, RequiredMode.REQUIRED, true, 200, NAME_SAMPLE, null, null));

    public record SectionSpec(String className, String tableName, String title, int order, String parentField, String lineNumberField, String fieldName, String sample) {}
    public static final List<SectionSpec> SECTIONS = List.of(new SectionSpec("com.example.generated.rows.CatalogSections2Row1", "catalog_2_row_1", "Строки 1", 10, "catalog", "lineNumber", "note", "row 0"), new SectionSpec("com.example.generated.rows.CatalogSections2Row2", "catalog_2_row_2", "Строки 2", 20, "catalog", "lineNumber", "note", "row 0"));
    public static final List<FieldSpec> FIELDS = List.of();

    public static final List<String> LIST_COLUMNS = List.of("code", "name");
    public static final List<String> SELECT_COLUMNS = List.of("code", "name");

    private ScenarioSpec() {
    }
}
