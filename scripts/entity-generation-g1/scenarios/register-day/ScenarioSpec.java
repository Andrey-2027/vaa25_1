package com.example.generated.harness;

/** Independent DAY expectations, with a consumer-owned Long-ID Item reference target. */
public final class ScenarioSpec {
    public static final String SCENARIO = "register-day";
    public static final String CLASS_NAME = "com.example.generated.ItemRate";
    public static final String RELATIVE_PATH = "com/example/generated/ItemRate.java";
    public static final String REQUIRED_SUITE = "com.example.generated.harness.RegisterScenarioTest";
    public static final String BASE = "org.ipro.register.PeriodicRegisterEntryBase";
    public static final String TABLE = "item_rate";
    public static final String DISPLAY_NAME = "Ставки по номенклатуре";
    public static final boolean PERIODIC = true;
    public static final String DIMENSION = "item";
    public static final String DIMENSION_TYPE = "com.example.Item";
    public static final String DIMENSION_COLUMN = "item_id";
    public static final String[] KEYS = {"item_id", "period_date"};
    public static final String[] RESOURCES = {"rate"};
    public static final String RESOURCE_TYPE = "java.math.BigDecimal";
    public static final String RESOURCE_COLUMN = "rate";
    public static final boolean KEY_WRITE = true, KEY_CHANGE = false, GENERIC_WRITES = true;
    public static final String PERIODICITY = "DAY";
}
