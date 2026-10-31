package com.example.generated.harness;

import java.util.List;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.metadata.annotation.RequiredMode;

/** Expectations authored from the user's Matrix request, independently of generated annotations. */
public final class ScenarioSpec {
    public static final String SCENARIO = "matrix";
    public static final String CLASS_NAME = "com.example.generated.Matrix";
    public static final String RELATIVE_PATH = "com/example/generated/Matrix.java";
    public static final String TABLE_NAME = "matrix";
    public static final String LIST_TITLE = "Матрицы", ITEM_TITLE = "Матрица", SELECTION_TITLE = "Выбор матрицы";
    public static final String ICON = "FILE", NAME_SAMPLE = "Матрица испытательная";
    public record FieldSpec(String name, String column, FieldType type, Class<?> javaType,
                            String label, int order, boolean serverRequired, RequiredMode requiredMode,
                            boolean form, Integer length, Integer precision, Integer scale, Object sample) { }
    public static final List<FieldSpec> FIELDS = List.of(
        new FieldSpec("idSys", "id_sys", FieldType.INTEGER, Long.class, "ID внешней системы", 30, false, RequiredMode.AUTO, true, null, null, null, 4294967296L),
        new FieldSpec("draftAgk", "draft_agk", FieldType.TEXT, String.class, "Обозначение АгК", 40, false, RequiredMode.AUTO, true, 150, null, null, "АгК-Матрица-001"),
        new FieldSpec("active", "active", FieldType.BOOLEAN, Boolean.class, "Активен", 50, false, RequiredMode.AUTO, true, null, null, null, true));
    public static final List<String> LIST_COLUMNS = List.of("code", "name", "idSys", "draftAgk", "active");
    public static final List<String> SELECT_COLUMNS = LIST_COLUMNS;
    private ScenarioSpec() { }
}
