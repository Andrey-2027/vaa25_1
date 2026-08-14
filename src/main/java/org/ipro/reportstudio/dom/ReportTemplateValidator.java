package org.ipro.reportstudio.dom;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Валидация модели отчёта (Фаза 1). Инварианты, которые база не может
 * выразить ограничениями: топология бэндов (ровно один DETAIL, пары
 * GROUP_HEADER/GROUP_FOOTER по (parent, groupField), вложенность только
 * через GROUP_HEADER), согласованность параметров (источники/виды),
 * агрегаты только в footer-бэндах, уникальность имён внутри шаблона.
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
        // Ключ группы: (id родителя, groupField) -> счётчики header/footer.
        Map<GroupKey, int[]> groups = new HashMap<>();

        for (ReportBand band : template.getBands()) {
            ReportBandKind kind = band.getKind();
            switch (kind) {
                case REPORT_HEADER -> headers++;
                case DETAIL -> details++;
                case REPORT_FOOTER -> footers++;
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

            validateFields(band, kind, out);
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
    }

    private static void validateFields(ReportBand band, ReportBandKind kind, List<String> out) {
        Map<String, Integer> seen = new HashMap<>();
        for (ReportField field : band.getFields()) {
            String queryField = field.getQueryField();
            if (isBlank(queryField)) {
                out.add("Бэнд " + kind + ": поле без queryField");
                continue;
            }
            if (seen.merge(queryField, 1, Integer::sum) > 1) {
                out.add("Бэнд " + kind + ": поле «" + queryField + "» дублируется");
            }
            if (field.getAggregation() != ReportFieldAggregation.NONE && !kind.isFooterBand()) {
                out.add("Поле «" + queryField + "»: агрегат " + field.getAggregation()
                    + " допустим только в footer-бэндах");
            }
        }
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