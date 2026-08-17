package org.ipro.reportstudio.config;

import org.ipro.reportstudio.dom.ReportBandKind;
import org.ipro.reportstudio.dom.ReportComputedValue;
import org.ipro.reportstudio.dom.ReportFieldAggregation;
import org.ipro.reportstudio.dom.ReportFieldAlignment;
import org.ipro.reportstudio.dom.ReportFieldKind;
import org.ipro.reportstudio.dom.ReportOrderDirection;
import org.ipro.reportstudio.dom.ReportPageOrientation;
import org.ipro.reportstudio.dom.ReportPageSize;
import org.ipro.reportstudio.dom.ReportParamKind;
import org.ipro.reportstudio.dom.ReportParamSource;
import org.ipro.reportstudio.dom.ReportTemplateState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Идемпотентная совместимость схемы (PostgreSQL). {@code ddl-auto=update}
 * добавляет новые колонки, но не снимает NOT NULL и не обновляет CHECK-ограничения
 * — устаревшие из них остаются и валят INSERT'ы новыми значениями enum
 * (например, EXPRESSION в report_field_kind). При старте приводим схему в
 * соответствие с маппингом:
 * <ul>
 *   <li>report_field.query_field — nullable (TEXT/EXPRESSION/FORMULA/ROW_NUMBER
 *       не имеют поля запроса);</li>
 *   <li>для каждой enum-колонки: если CHECK таблицы не содержит всех значений
 *       Java-enum'а — старый CHECK снимается и создаётся заново.</li>
 * </ul>
 * На H2 (тесты) схема создаётся заново под текущий маппинг — миграция пропускается.
 */
public class ReportSchemaCompatibility implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ReportSchemaCompatibility.class);

    /** Enum-колонки reportstudio: table.column -> Java enum (источник истины). */
    private static final Map<String, Class<? extends Enum<?>>> ENUM_COLUMNS = enumColumns();

    private final JdbcTemplate jdbcTemplate;

    public ReportSchemaCompatibility(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) {
        Boolean postgres = jdbcTemplate.execute((ConnectionCallback<Boolean>) this::isPostgres);
        if (!Boolean.TRUE.equals(postgres)) {
            return;
        }
        ensureQueryFieldNullable();
        reconcileEnumChecks();
    }

    private boolean isPostgres(Connection connection) throws java.sql.SQLException {
        String product = connection.getMetaData().getDatabaseProductName();
        return product != null && product.toLowerCase(Locale.ROOT).contains("postgres");
    }

    /** report_field.query_field: NOT NULL снимается (ddl-auto этого не делает). */
    private void ensureQueryFieldNullable() {
        Integer notNull = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.columns"
                        + " where lower(table_name) = 'report_field'"
                        + " and lower(column_name) = 'query_field'"
                        + " and is_nullable = 'NO'",
                Integer.class);
        if (notNull == null || notNull == 0) {
            return;
        }
        jdbcTemplate.execute("alter table report_field alter column query_field drop not null");
        log.info("report_field.query_field: снят NOT NULL (поля без запроса: TEXT/EXPRESSION/FORMULA/ROW_NUMBER)");
    }

    /**
     * Для каждой enum-колонки сверяет CHECK с Java-enum'ом и пересоздаёт
     * устаревший (не содержащий всех значений). Имя констрейнта сохраняется.
     */
    private void reconcileEnumChecks() {
        List<Map<String, Object>> checks = jdbcTemplate.queryForList(
                "select c.conname, pg_get_constraintdef(c.oid) as condef,"
                        + " t.relname as tbl"
                        + " from pg_constraint c"
                        + " join pg_class t on t.oid = c.conrelid"
                        + " where t.relname in ('report_field','report_band','report_template',"
                        + " 'report_param','report_order')"
                        + " and c.contype = 'c'");
        for (Map.Entry<String, Class<? extends Enum<?>>> entry : ENUM_COLUMNS.entrySet()) {
            int dot = entry.getKey().indexOf('.');
            reconcileIfNeeded(entry.getKey().substring(0, dot), entry.getKey().substring(dot + 1),
                    entry.getValue(), checks);
        }
    }

    private void reconcileIfNeeded(String table, String column,
                                   Class<? extends Enum<?>> enumType,
                                   List<Map<String, Object>> checks) {
        List<String> expected = Arrays.stream(enumType.getEnumConstants())
                .map(value -> "'" + value.name().toLowerCase(Locale.ROOT) + "'")
                .toList();
        for (Map<String, Object> check : checks) {
            if (!table.equals(check.get("tbl"))) {
                continue;
            }
            String condef = String.valueOf(check.get("condef"));
            String def = condef.toLowerCase(Locale.ROOT);
            if (!def.contains(column)) {
                continue;
            }
            boolean upToDate = expected.stream().allMatch(def::contains);
            if (upToDate) {
                return;
            }
            String name = String.valueOf(check.get("conname"));
            String allowed = Arrays.stream(enumType.getEnumConstants())
                    .map(value -> "'" + value.name() + "'")
                    .collect(Collectors.joining(", "));
            jdbcTemplate.execute("alter table " + table + " drop constraint " + name);
            jdbcTemplate.execute("alter table " + table + " add constraint " + name
                    + " check (" + column + " in (" + allowed + "))");
            log.info("{}: CHECK пересоздан с {}.", table + "." + column,
                    enumType.getSimpleName() + " " + Arrays.toString(enumType.getEnumConstants()));
        }
    }

    private static Map<String, Class<? extends Enum<?>>> enumColumns() {
        Map<String, Class<? extends Enum<?>>> map = new LinkedHashMap<>();
        map.put("report_field.kind", ReportFieldKind.class);
        map.put("report_field.aggregation", ReportFieldAggregation.class);
        map.put("report_field.alignment", ReportFieldAlignment.class);
        map.put("report_band.kind", ReportBandKind.class);
        map.put("report_template.state", ReportTemplateState.class);
        map.put("report_template.page_size", ReportPageSize.class);
        map.put("report_template.page_orientation", ReportPageOrientation.class);
        map.put("report_param.kind", ReportParamKind.class);
        map.put("report_param.value_source", ReportParamSource.class);
        map.put("report_param.computed", ReportComputedValue.class);
        map.put("report_order.direction", ReportOrderDirection.class);
        return Map.copyOf(map);
    }
}