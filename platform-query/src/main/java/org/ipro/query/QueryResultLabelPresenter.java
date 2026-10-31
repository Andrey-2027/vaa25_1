package org.ipro.query;

import org.ipro.data.presentation.EntityPresentationService;
import org.ipro.register.RecorderTypeRegistry;
import org.ipro.report.data.QueryField;
import org.ipro.report.data.ReportDataset;
import org.ipro.report.data.ReportRow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Реализация {@link QueryResultPresenter}: физические колонки идентификаторов
 * превращаются в подписи ссылок теми же сервисами, что используют списки и
 * карточки (presentation-сервис + реестр типов регистратора).
 *
 * <p>Живёт в backend'е запросов, а не в приложении: оба сервиса принадлежат
 * платформе, а платформенная auto-configuration не должна зависеть от
 * прикладного пакета.</p>
 */
public final class QueryResultLabelPresenter implements QueryResultPresenter {

    private final EntityPresentationService presentations;
    private final RecorderTypeRegistry recorderTypes;

    public QueryResultLabelPresenter(EntityPresentationService presentations,
                                     RecorderTypeRegistry recorderTypes) {
        this.presentations = Objects.requireNonNull(presentations, "presentations");
        this.recorderTypes = Objects.requireNonNull(recorderTypes, "recorderTypes");
    }

    @Override
    public ReportDataset present(QueryPresentationPlan plan, List<QueryField> fields,
                                 ReportDataset physical) {
        if (plan == null || plan.isEmpty()) {
            return physical;
        }
        ReportRow[] physicalRows = physical.rows();
        var references = new LinkedHashSet<EntityPresentationService.Reference>();
        var rowRefs = new ArrayList<Map<String, EntityPresentationService.Reference>>(physicalRows.length);
        for (ReportRow row : physicalRows) {
            Map<String, EntityPresentationService.Reference> refs = new HashMap<>();
            for (QueryPresentationPlan.Column column : plan.columns()) {
                Object id = row.value(column.idColumn());
                if (id == null) {
                    continue;
                }
                Class<?> type = column.entityType();
                if (column.typeColumn() != null) {
                    type = recorderTypes.findType(String.valueOf(row.value(column.typeColumn())))
                            .orElse(null);
                }
                if (type != null) {
                    var reference = new EntityPresentationService.Reference(type, ((Number) id).longValue());
                    refs.put(column.name(), reference);
                    references.add(reference);
                }
            }
            rowRefs.add(refs);
        }
        var labels = presentations.resolve(references);
        QueryField[] schema = fields.toArray(QueryField[]::new);
        var rows = new ArrayList<ReportRow>(physicalRows.length);
        for (int index = 0; index < physicalRows.length; index++) {
            ReportRow raw = physicalRows[index];
            Object[] values = new Object[schema.length];
            for (int cell = 0; cell < schema.length; cell++) {
                String name = schema[cell].name();
                var presentation = plan.columns().stream()
                        .filter(column -> column.name().equals(name)).findFirst();
                if (presentation.isEmpty()) {
                    values[cell] = raw.value(name);
                } else if (raw.value(presentation.get().idColumn()) == null) {
                    values[cell] = null;
                } else {
                    var ref = rowRefs.get(index).get(name);
                    values[cell] = ref == null ? "Документ недоступен"
                            : labels.getOrDefault(ref, presentations.unavailable(ref.type()));
                }
            }
            rows.add(new ReportRow(schema, values));
        }
        var referenceColumns = new ArrayList<ReportDataset.ReferenceColumn>(plan.columns().size());
        for (QueryPresentationPlan.Column column : plan.columns()) {
            var keys = new ArrayList<Object>(physicalRows.length);
            for (ReportRow row : physicalRows) {
                Object id = row.value(column.idColumn());
                keys.add(id == null ? null : column.typeColumn() == null ? id.toString()
                        : Objects.toString(row.value(column.typeColumn()), "") + ":" + id);
            }
            referenceColumns.add(new ReportDataset.ReferenceColumn(column.name(), String.class, keys));
        }
        return new ReportDataset(schema, rows.toArray(ReportRow[]::new), referenceColumns);
    }
}
