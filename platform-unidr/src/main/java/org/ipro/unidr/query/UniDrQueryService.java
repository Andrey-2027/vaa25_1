package org.ipro.unidr.query;

import org.ipro.unidr.model.UniDrDefinition.*;
import org.ipro.unidr.model.UniDrValidationException;
import org.ipro.unidr.run.*;
import org.ipro.report.data.ReportDataset;
import org.ipro.query.ReportContext;
import org.ipro.query.ReportQueryExecutor;
import org.ipro.query.ReportQueryAssembler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** A complete detached scalar dataset, or a failure. Rendering does not hold this transaction. */
public final class UniDrQueryService {
    private final UniDrAccess access;
    private final UniDrRunParameterResolver parameters;
    private final UniDrReferenceCatalog references;
    private final UniDrRunQueryAdapter adapter;
    private final ReportQueryExecutor executor;
    private final TransactionTemplate reads;
    private final int maxRows;
    private final long timeoutMs;
    private UniDrQuerySourceService querySources;
    private UniDrOrgContext organisations;
    public void useOrgContext(UniDrOrgContext organisations){this.organisations=organisations;}
    private ReportContext context(UniDrRunRequest request,Instant now,String owner) {
        var supplied=request.context();if(supplied==null || supplied.entityClass()==null)return ReportContext.empty(owner,now);
        Class<?> type=references.entityType(supplied.entityClass().getName());
        return ReportContext.of(type,supplied.entityId(),supplied.selectedIds(),supplied.viewId(),owner,now);
    }
    private UniDrOrgContext.Value organisation(String owner,org.ipro.unidr.model.UniDrDefinition definition){return organisations==null || definition.parameterDeclarations().stream().noneMatch(p->p.valueSource()==org.ipro.unidr.model.UniDrParameter.ValueSource.COMPUTED && p.computed()==org.ipro.unidr.model.UniDrParameter.Computed.RLS_ORG && UniDrCanonicalValues.used(definition,p))?null:organisations.resolve(owner);}
    public void useQuerySources(UniDrQuerySourceService service) {querySources=Objects.requireNonNull(service);}
    public void validateRequest(UniDrRunRequest request,Instant now,String owner) {
        access.requireOwner(owner);var legacy=parameters.resolve(request,now);
        UniDrCanonicalValues.resolve(request.definition(),request.queryValues(),legacy,context(request,now,owner),request.zone(),organisation(owner,request.definition()));
    }

    public record Data(ReportDataset dataset, Map<String, Literal> parameters, org.ipro.unidr.source.UniDrSourceDefinition source) {
        public Data(ReportDataset dataset,Map<String,Literal> parameters) {this(dataset,parameters,null);}
        public Data { parameters = Map.copyOf(parameters); }
        public String message() { return dataset.isEmpty() ? "По выбранным условиям данных нет" : ""; }
    }

    public UniDrQueryService(UniDrAccess access, UniDrRunParameterResolver parameters, UniDrReferenceCatalog references,
                            UniDrRunQueryAdapter adapter, ReportQueryExecutor executor, PlatformTransactionManager transactions,
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
        Map<String,Object> canonical;
        var context=context(request,now,owner);
        try {canonical=UniDrCanonicalValues.resolve(request.definition(),request.queryValues(),resolved,context,request.zone(),organisation(owner,request.definition()));}
        catch(IllegalArgumentException invalid){throw new UniDrRunException(UniDrRunException.Code.QUERY_REJECTED,invalid.getMessage(),invalid);}
        try {
            return reads.execute(status -> {
                access.requireOwner(owner);
                ReportQueryAssembler assembled;
                UniDrSourcePlan plan=null;
                if(request.definition().query()!=null) {
                    if(querySources==null)throw new UniDrRunException(UniDrRunException.Code.QUERY_REJECTED,"Источник запроса не подключён");
                    var prepared=querySources.prepareRun(request.definition(),context,canonical);assembled=prepared.query();plan=prepared.plan();
                    if(request.definition().filterTree()!=null || request.definition().compatibilityFilterStage()==CompatibilityStage.BEFORE_READ && !request.definition().filters().isEmpty())assembled=querySources.applyFilters(assembled,request.definition(),plan,canonical,references::requireEntity,references);
                } else {
                for (Parameter parameter : request.definition().parameters()) {
                    Literal value = resolved.get(parameter.id());
                    if (value != null && value.type() == ValueType.REFERENCE) checkReferences(parameter.fieldId(), value);
                }
                for (Filter filter : request.definition().filters())
                    if (filter.value() instanceof Literal literal && literal.type() == ValueType.REFERENCE) checkReferences(filter.fieldId(), literal);
                assembled = adapter.assemble(request.definition(), resolved, context);
                if(request.definition().filterTree()!=null)assembled=adapter.applyFilters(assembled,request.definition(),canonical,references::requireEntity);
                Map<String, Class<?>> actual = new HashMap<>();
                assembled.fields().forEach(field -> actual.put(field.name(), field.javaType()));
                var expected = adapter.expectedSchema();
                if (!expected.equals(actual) || assembled.fields().size() != expected.size())
                    throw new UniDrRunException(UniDrRunException.Code.SCHEMA_MISMATCH, "Схема источника изменилась; формирование остановлено");
                }
                ReportDataset dataset = executor.execute(assembled,maxRows+1,timeoutMs);
                if (dataset.rowCount() > maxRows)
                    throw new UniDrRunException(UniDrRunException.Code.LIMIT_EXCEEDED, "Выбрано больше " + maxRows + " строк. Уточните период или отборы");
                if(plan!=null)dataset=querySources.prepare(request.definition(),assembled,dataset,resolved,plan.schema());
                access.requireOwner(owner);
                return new Data(dataset,resolved,plan==null?null:plan.schema());
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
