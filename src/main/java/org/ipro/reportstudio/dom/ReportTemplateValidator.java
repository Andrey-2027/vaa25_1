package org.ipro.reportstudio.dom;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Валидация модели отчёта (Фаза 1). Инварианты, которые база не может
 * выразить ограничениями: топология бэндов (ровно один DETAIL, пары
 * GROUP_HEADER/GROUP_FOOTER по (parent, groupField), вложенность только
 * через GROUP_HEADER), согласованность параметров (источники/виды), и главное —
 * допустимость {@link ReportFieldKind} по типу бэнда и ссылочная целостность:
 * queryField footer-агрегата обязан совпадать с колонкой DETAIL.
 *
 * <p>Правила «какие поля в каком бэнде»:</p>
 * <pre>
 * бэнд            допустимые kinds           требования
 * REPORT_HEADER   TEXT                       text обязателен; без queryField/width/format/aggregation
 * NO_DATA         TEXT                       text обязателен; без queryField/width/format/aggregation
 * GROUP_HEADER    —                          полей нет вообще (заголовок — только groupField);
 *                                           startNewPage допустим только здесь
 * DETAIL          COLUMN | ROW_NUMBER |    COLUMN: queryField обязателен + aggregation == NONE;
 *                 EXPRESSION | FORMULA     ROW_NUMBER: без queryField, aggregation == NONE;
 *                                           EXPRESSION/FORMULA: text обязателен, без queryField,
 *                                           aggregation == NONE, {алиасы} ⊆ колонок DETAIL,
 *                                           для FORMULA — валидна грамматика арифметики
 * GROUP_FOOTER,   COLUMN | TEXT              COLUMN: queryField из колонок DETAIL + aggregation != NONE;
 * REPORT_FOOTER                             TEXT: text обязателен, остальное пусто
 * </pre>
 *
 * <p>Правила сортировки (orders): columnName обязателен; алиас из SELECT
 * проверяется только на этапе выполнения запроса (JPQL отвергнет неизвестный).</p>
 *
 * Возвращает человекочитаемый список нарушений (пустой = модель валидна).
 * Используется тестами сейчас и конструктором (Фаза 5) позже.
 */
public final class ReportTemplateValidator {

    private static final Pattern PARAM_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");

    private ReportTemplateValidator() {
    }

    public static List<String> validate(ReportTemplate template) {
        List<String> violations = new ArrayList<>();
        if (isBlank(template.getName())) {
            violations.add("Отчёт: имя обязательно");
        }
        if (isBlank(template.getJpql())) {
            violations.add("Отчёт: JPQL обязателен");
        }
        validateParams(template, violations);
        validateBands(template, violations);
        validateOrders(template, violations);
        return violations;
    }

    private static void validateParams(ReportTemplate template, List<String> out) {
        Map<String, Integer> seen = new HashMap<>();
        for (ReportParam p : template.getParams()) {
            String name = p.getName();
            boolean attrsOk = !isBlank(name);
            if (attrsOk) {
                if (!PARAM_NAME.matcher(name).matches()) {
                    out.add("Параметр «" + name + "»: недопустимое имя (буквы, цифры, подчёркивание)");
                }
                if (seen.merge(name, 1, Integer::sum) > 1) {
                    out.add("Параметр «" + name + "»: имя повторяется");
                }
            } else {
                out.add("Параметр: имя обязательно");
            }

            boolean entityKind = p.getKind() == ReportParamKind.ENTITY || p.getKind() == ReportParamKind.ENTITY_LIST;
            if (entityKind && isBlank(p.getEntityClass())) {
                out.add("Параметр «" + name + "»: для " + p.getKind() + " требуется entityClass");
            }
            if (!entityKind && !isBlank(p.getEntityClass())) {
                out.add("Параметр «" + name + "»: entityClass задан, но вид " + p.getKind());
            }
            if (p.getValueSource() == ReportParamSource.COMPUTED && p.getComputed() == ReportComputedValue.NONE) {
                out.add("Параметр «" + name + "»: источник COMPUTED требует computed (now/currentUser/rlsOrg)");
            }
            if (p.getValueSource() != ReportParamSource.COMPUTED && p.getComputed() != ReportComputedValue.NONE) {
                out.add("Параметр «" + name + "»: computed задан, но источник не COMPUTED");
            }
        }
    }

    private static void validateBands(ReportTemplate template, List<String> out) {
        int headers = 0;
        int details = 0;
        int footers = 0;
        int noData = 0;
        // Ключ группы: (id родителя, groupField) -> счётчики header/footer.
        Map<GroupKey, int[]> groups = new HashMap<>();
        // queryField всех колонок DETAIL — для ссылочной целостности footer-агрегатов.
        Set<String> detailColumns = new LinkedHashSet<>();

        for (ReportBand band : template.getBands()) {
            ReportBandKind kind = band.getKind();
            switch (kind) {
                case REPORT_HEADER -> headers++;
                case DETAIL -> details++;
                case REPORT_FOOTER -> footers++;
                case NO_DATA -> noData++;
                case GROUP_HEADER, GROUP_FOOTER -> {
                    GroupKey key = new GroupKey(band);
                    int[] counters = groups.computeIfAbsent(key, k -> new int[2]);
                    if (kind == ReportBandKind.GROUP_HEADER) {
                        counters[0]++;
                    } else {
                        counters[1]++;
                    }
                }
            }

            if (kind.isGroupBand() != (band.getGroupField() != null && !band.getGroupField().isBlank())) {
                if (kind.isGroupBand()) {
                    out.add("Бэнд " + kind + ": требуется groupField");
                } else {
                    out.add("Бэнд " + kind + ": groupField допустим только у групповых бэндов");
                }
            }
            if (band.getParent() != null && !kind.isGroupBand()) {
                out.add("Бэнд " + kind + ": parent допустим только у групповых бэндов");
            }
            if (kind.isGroupBand() && band.getParent() != null && band.getParent().getKind() != ReportBandKind.GROUP_HEADER) {
                out.add("Бэнд " + kind + ": родителем группы может быть только GROUP_HEADER");
            }
            if (band.isStartNewPage() && kind != ReportBandKind.GROUP_HEADER) {
                out.add("Бэнд " + kind + ": «с новой страницы» допустимо только у GROUP_HEADER");
            }

            if (kind == ReportBandKind.DETAIL) {
                for (ReportField field : band.getFields()) {
                    if (!isBlank(field.getQueryField())) {
                        detailColumns.add(field.getQueryField());
                    }
                }
            }
        }

        if (headers > 1) {
            out.add("Отчёт: не более одного бэнда REPORT_HEADER");
        }
        if (details != 1) {
            out.add("Отчёт: должен быть ровно один бэнд DETAIL (найдено " + details + ")");
        }
        if (footers > 1) {
            out.add("Отчёт: не более одного бэнда REPORT_FOOTER");
        }
        if (noData > 1) {
            out.add("Отчёт: не более одного бэнда NO_DATA (найдено " + noData + ")");
        }
        for (Map.Entry<GroupKey, int[]> e : groups.entrySet()) {
            String label = e.getKey().groupField;
            if (e.getValue()[0] == 0) {
                out.add("Группа «" + label + "»: нет парного GROUP_HEADER");
            }
            if (e.getValue()[1] == 0) {
                out.add("Группа «" + label + "»: нет парного GROUP_FOOTER");
            }
            if (e.getValue()[0] > 1 || e.getValue()[1] > 1) {
                out.add("Группа «" + label + "»: пара (родитель, groupField) повторяется");
            }
        }

        for (ReportBand band : template.getBands()) {
            validateBandFields(band, band.getKind(), detailColumns, out);
        }
    }

    private static void validateBandFields(ReportBand band, ReportBandKind kind,
                                          Set<String> detailColumns, List<String> out) {
        if (kind == ReportBandKind.GROUP_HEADER) {
            if (!band.getFields().isEmpty()) {
                out.add("Бэнд GROUP_HEADER: поля недопустимы — заголовок настраивается через группировку (groupField)");
            }
            return;
        }

        Map<String, Integer> seen = new HashMap<>();
        for (ReportField field : band.getFields()) {
            validateField(band, kind, field, seen, detailColumns, out);
        }
    }

    private static void validateField(ReportBand band, ReportBandKind kind, ReportField field,
                                      Map<String, Integer> seen, Set<String> detailColumns, List<String> out) {
        String queryField = field.getQueryField();
        boolean textField = field.isText();

        if (kind.isTextOnlyBand()) {
            if (!textField) {
                out.add("Бэнд " + kind + ": допустимы только текстовые блоки (поле «"
                        + displayName(queryField) + "» не является текстовым)");
                return;
            }
            requireTextOnly(field, kind.name(), queryField, out);
            return;
        }

        if (kind == ReportBandKind.DETAIL) {
            if (textField) {
                out.add("Бэнд DETAIL: текстовые блоки недопустимы — только колонки");
                return;
            }
            ReportFieldKind fieldKind = field.kindOrDefault();
            if (fieldKind == ReportFieldKind.ROW_NUMBER) {
                if (!isBlank(queryField)) {
                    out.add("Бэнд DETAIL: у колонки «№ п/п» не указывается queryField («"
                            + queryField + "»)");
                }
                if (field.getAggregation() != ReportFieldAggregation.NONE) {
                    out.add("Поле «" + displayName(queryField)
                            + "»: агрегат допустим только в footer-бэндах");
                }
                return;
            }
            if (fieldKind == ReportFieldKind.EXPRESSION || fieldKind == ReportFieldKind.FORMULA) {
                validateComputed(field, fieldKind, queryField, detailColumns, out);
                return;
            }
            if (isBlank(queryField)) {
                out.add("Бэнд DETAIL: поле без queryField");
                return;
            }
            if (seen.merge(queryField, 1, Integer::sum) > 1) {
                out.add("Бэнд DETAIL: поле «" + queryField + "» дублируется");
            }
            if (field.getAggregation() != ReportFieldAggregation.NONE) {
                out.add("Поле «" + queryField + "»: агрегат " + field.getAggregation()
                        + " допустим только в footer-бэндах");
            }
            return;
        }

        // GROUP_FOOTER / REPORT_FOOTER
        if (textField) {
            requireTextOnly(field, kind.name(), queryField, out);
            return;
        }
        if (isBlank(queryField)) {
            out.add("Бэнд " + kind + ": агрегат без queryField");
            return;
        }
        if (seen.merge(queryField, 1, Integer::sum) > 1) {
            out.add("Бэнд " + kind + ": агрегат по колонке «" + queryField + "» дублируется");
        }
        if (!detailColumns.contains(queryField)) {
            out.add("Бэнд " + kind + ": агрегат по колонке «" + queryField
                    + "», отсутствующей в DETAIL");
            return;
        }
        if (field.getAggregation() == null || field.getAggregation() == ReportFieldAggregation.NONE) {
            out.add("Бэнд " + kind + ": агрегат по колонке «" + queryField
                    + "» — выберите функцию агрегации");
        }
    }

    /**
     * Проверки, общие для текстового блока в шапке/футере: текст обязателен,
     * queryField/width/format/aggregation неприменимы.
     */
    private static void requireTextOnly(ReportField field, String bandKind, String queryField, List<String> out) {
        if (isBlank(field.getText())) {
            out.add("Бэнд " + bandKind + ": текст блока обязателен");
        }
        if (!isBlank(queryField)) {
            out.add("Бэнд " + bandKind + ": у текстового блока не указывается queryField («"
                    + queryField + "»)");
        }
        if (field.getWidth() != null || field.getFormat() != null) {
            out.add("Бэнд " + bandKind + ": у текстового блока не задаются ширина/формат");
        }
        if (field.getAggregation() != null && field.getAggregation() != ReportFieldAggregation.NONE) {
            out.add("Бэнд " + bandKind + ": у текстового блока не задаётся агрегат");
        }
    }

    /** Правила сортировки: имя колонки обязательно; алиас SELECT проверяется на этапе выполнения. */
    private static void validateOrders(ReportTemplate template, List<String> out) {
        Map<String, Integer> seen = new HashMap<>();
        for (ReportOrder order : template.getOrders()) {
            String columnName = order.getColumnName();
            if (isBlank(columnName)) {
                out.add("Сортировка: имя колонки обязательно");
                continue;
            }
            if (seen.merge(columnName, 1, Integer::sum) > 1) {
                out.add("Сортировка: колонка «" + columnName + "» задана несколько раз");
            }
        }
    }

    /**
     * Вычисляемая колонка DETAIL (EXPRESSION/FORMULA): текст шаблона/формулы
     * обязателен, aggregation == NONE, без queryField; все {алиасы} обязаны
     * ссылаться на колонки DETAIL. Для FORMULA дополнительно проверяется
     * грамматика арифметики (без данных).
     */
    private static void validateComputed(ReportField field, ReportFieldKind kind,
                                         String queryField, Set<String> detailColumns, List<String> out) {
        if (!isBlank(queryField)) {
            out.add("Бэнд DETAIL: у " + (kind == ReportFieldKind.EXPRESSION ? "выражения" : "формулы")
                    + " не указывается queryField («" + queryField + "»)");
        }
        if (field.getAggregation() != ReportFieldAggregation.NONE) {
            out.add("Поле «" + displayName(queryField) + "»: агрегат допустим только в footer-бэндах");
        }
        if (isBlank(field.getText())) {
            out.add("Бэнд DETAIL: "
                    + (kind == ReportFieldKind.EXPRESSION ? "текст выражения" : "формула")
                    + " обязателен");
            return;
        }
        Set<String> aliases;
        try {
            aliases = Placeholders.aliasesOf(field.getText());
        } catch (IllegalArgumentException broken) {
            out.add("Бэнд DETAIL: некорректный шаблон («" + field.getText() + "»): "
                    + broken.getMessage());
            return;
        }
        if (kind == ReportFieldKind.FORMULA) {
            try {
                org.ipro.reportstudio.render.FormulaEvaluator.validate(field.getText());
            } catch (IllegalArgumentException broken) {
                out.add("Бэнд DETAIL: некорректная формула «" + field.getText() + "»: "
                        + broken.getMessage());
                return;
            }
        }
        for (String alias : aliases) {
            if (!detailColumns.contains(alias)) {
                out.add("Бэнд DETAIL: вычисляемая колонка ссылается на неизвестную колонку «"
                        + alias + "»");
            }
        }
    }

    private static String displayName(String queryField) {
        return isBlank(queryField) ? "<пусто>" : queryField;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record GroupKey(Long parentId, String groupField) {
        GroupKey(ReportBand band) {
            this(band.getParent() == null ? null : band.getParent().getId(), band.getGroupField());
        }
    }
}
