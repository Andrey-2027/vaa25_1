package org.ip.unidr.query;

import org.ip.unidr.model.UniDrDefinition.*;
import org.ip.unidr.model.UniDrValidationException;
import org.ip.unidr.run.*;
import org.ipro.reportstudio.data.ReportDataset;
import org.ipro.reportstudio.param.ReportContext;
import org.ipro.reportstudio.query.ReportQueryExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** A complete detached scalar dataset, or a failure. Rendering does not hold this transaction. */
public final class UniDrQueryService {
    private static final Map<String, Class<?>> SCHEMA = Map.ofEntries(
        Map.entry("rowId", Long.class), Map.entry("documentId", Long.class), Map.entry("documentNumber", String.class),
        Map.entry("date", LocalDate.class), Map.entry("receivingWorkshopId", Long.class), Map.entry("receivingWorkshopName", String.class),
        Map.entry("transferringWorkshopId", Long.class), Map.entry("transferringWorkshopName", String.class),
        Map.entry("nomenclatureId", Long.class), Map.entry("nomenclatureCode", String.class), Map.entry("nomenclatureName", String.class),
        Map.entry("unitId", Long.class), Map.entry("unit", String.class), Map.entry("quantity", BigDecimal.class));
    private final UniDrAccess access;
    private final UniDrRunParameterResolver parameters;
    private final UniDrReferenceService references;
    private final UniDrQueryAdapter adapter;
    private final ReportQueryExecutor executor;
    private final TransactionTemplate reads;
    private final int maxRows;
    private final long timeoutMs;
    private UniDrQuerySourceService querySources;
    public void useQuerySources(UniDrQuerySourceService service) {querySources=Objects.requireNonNull(service);}

    public record Data(ReportDataset dataset, Map<String, Literal> parameters, org.ip.unidr.source.UniDrSourceDefinition source) {
        public Data(ReportDataset dataset,Map<String,Literal> parameters) {this(dataset,parameters,null);}
        public Data { parameters = Map.copyOf(parameters); }
        public String message() { return dataset.isEmpty() ? "По выбранным условиям данных нет" : ""; }
    }

    public UniDrQueryService(UniDrAccess access, UniDrRunParameterResolver parameters, UniDrReferenceService references,
                            UniDrQueryAdapter adapter, ReportQueryExecutor executor, PlatformTransactionManager transactions,
                            int maxRows, long timeoutMs) {
        if (maxRows < 1 || maxRows > 5000 || timeoutMs < 1 || timeoutMs > 120_000)
            throw new IllegalArgumentException("UniDR limits: 1..5000 rows, 1..120000 ms");
        this.access = access; this.parameters = parameters; this.references = references; this.adapter = adapter;
        this.executor = executor; this.maxRows = maxRows; this.timeoutMs = timeoutMs;
        reads = new TransactionTemplate(transactions); reads.setReadOnly(true); reads.setTimeout((int) ((timeoutMs + 999) / 1000));
        reads.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Data read(UniDrRunRequest request, Instant now, String owner) {
        access.requireOwner(owner);
        Map<String, Literal> resolved = parameters.resolve(request, now);
        try {
            return reads.execute(status -> {
                access.requireOwner(owner);
                if(request.definition().query()!=null) {
                    if(querySources==null)throw new UniDrRunException(UniDrRunException.Code.QUERY_REJECTED,"Источник запроса не подключён");
                    var assembled=querySources.assemble(request.definition().query(),ReportContext.empty(owner,now),request.queryValues());
                    var dataset=executor.execute(assembled,maxRows+1,timeoutMs);
                    if(dataset.rowCount()>maxRows)throw new UniDrRunException(UniDrRunException.Code.LIMIT_EXCEEDED,"Запрос вернул больше "+maxRows+" строк. Уточните условия запроса");
                    dataset=querySources.prepare(request.definition(),assembled,dataset,resolved);
                    access.requireOwner(owner);return new Data(dataset,resolved,UniDrQuerySourceService.metadata(assembled.fields()));
                }
                for (Parameter parameter : request.definition().parameters()) {
                    Literal value = resolved.get(parameter.id());
                    if (value != null && value.type() == ValueType.REFERENCE) checkReferences(parameter.fieldId(), value);
                }
                for (Filter filter : request.definition().filters())
                    if (filter.value() instanceof Literal literal && literal.type() == ValueType.REFERENCE) checkReferences(filter.fieldId(), literal);
                var assembled = adapter.assemble(request.definition(), resolved, ReportContext.empty(owner, now));
                Map<String, Class<?>> actual = new HashMap<>();
                assembled.fields().forEach(field -> actual.put(field.name(), field.javaType()));
                if (!SCHEMA.equals(actual) || assembled.fields().size() != SCHEMA.size())
                    throw new UniDrRunException(UniDrRunException.Code.SCHEMA_MISMATCH, "Схема источника изменилась; формирование остановлено");
                ReportDataset dataset = executor.execute(assembled, maxRows + 1, timeoutMs);
                if (dataset.rowCount() > maxRows)
                    throw new UniDrRunException(UniDrRunException.Code.LIMIT_EXCEEDED, "Выбрано больше " + maxRows + " позиций. Уточните период или отборы");
                access.requireOwner(owner);
                return new Data(dataset, resolved);
            });
        } catch (UniDrRunException | UniDrValidationException known) { throw known; }
        catch (RuntimeException failed) {
            for (Throwable cause = failed; cause != null; cause = cause.getCause()) {
                if (cause instanceof jakarta.persistence.QueryTimeoutException || cause instanceof org.hibernate.QueryTimeoutException
                    || cause instanceof org.springframework.dao.QueryTimeoutException
                    || cause instanceof org.springframework.transaction.TransactionTimedOutException
                    || cause instanceof SQLException sql && "57014".equals(sql.getSQLState()))
                    throw new UniDrRunException(UniDrRunException.Code.TIMEOUT, "Время выполнения отчёта истекло. Уточните условия", failed);
                if (cause instanceof org.springframework.security.access.AccessDeniedException)
                    throw new UniDrRunException(UniDrRunException.Code.ACCESS_DENIED, "Источник или выбранная ссылка недоступны", failed);
            }
            if (failed instanceof IllegalArgumentException)
                throw new UniDrRunException(UniDrRunException.Code.QUERY_REJECTED, "Источник недоступен или не прошёл проверку запроса", failed);
            throw new UniDrRunException(UniDrRunException.Code.QUERY_FAILED, "Не удалось получить данные отчёта", failed);
        }
    }

    private void checkReferences(String field, Literal value) {
        value.values().forEach(id -> references.requireVisible(field, Long.valueOf(id)));
    }
}
