package com.example.generated.harness;

/** Independent expectations for the additional numeric-region NONE RLS route. */
public final class ScenarioSpec {
    public static final String SCENARIO="register-none-rls";
    public static final String CLASS_NAME="com.example.generated.RegionScopedRate";
    public static final String RELATIVE_PATH="com/example/generated/RegionScopedRate.java";
    public static final String REQUIRED_SUITE="com.example.generated.harness.RegisterScenarioTest";
    public static final String REQUIRED_RLS_SUITE="com.example.generated.harness.RegisterRlsSecurityTest";
    public static final String BASE="org.ipro.crud.BaseEntity";
    public static final String TABLE="region_scoped_rate";
    public static final String DISPLAY_NAME="Ставки по регионам";
    public static final boolean PERIODIC=false;
    public static final String DIMENSION="region";
    public static final String DIMENSION_TYPE="com.example.Region";
    public static final String DIMENSION_COLUMN="region_id";
    public static final String[] KEYS={"region_id"};
    public static final String[] RESOURCES={"rate"};
    public static final String RESOURCE_TYPE="java.math.BigDecimal";
    public static final String RESOURCE_COLUMN="rate";
    public static final boolean KEY_WRITE=true, KEY_CHANGE=false, GENERIC_WRITES=true;
    public static final String PERIODICITY="EXACT";
    public static final String RLS_DIMENSION="REGISTER_REGION";
    public static final String RLS_VALUE_PATH="region.id";
}
