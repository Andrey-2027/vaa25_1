package org.ip.unidr.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.ip.Application;
import org.ip.model.*;
import org.ip.unidr.model.*;
import org.ip.unidr.model.UniDrDefinition.*;
import org.ip.unidr.run.*;
import org.ip.unidr.source.UniDrSourceCatalog;
import org.ipro.crud.LookupService;
import org.ipro.data.*;
import org.ipro.metadata.*;
import org.ipro.reportstudio.param.*;
import org.ipro.reportstudio.query.*;
import org.ipro.reportstudio.query.sqm.SqmQuerySemanticAnalyzer;
import org.ipro.rls.*;
import org.ipro.rls.c5.*;
import org.ipro.rls.config.RlsPersistenceAutoConfiguration;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Real canonical lookups, grants, filters and guarded SQL on actual application entities. */
@DataJpaTest(showSql=false, properties={"spring.jpa.show-sql=false",
    "spring.jpa.properties.hibernate.session_factory.statement_inspector=org.ip.unidr.query.UniDrReceivingItemsQueryTest$SqlCapture"})
@ImportAutoConfiguration(RlsPersistenceAutoConfiguration.class)
@ContextConfiguration(classes=Application.class)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class UniDrReceivingItemsQueryTest {
    private static UniDrPostgresSandbox postgres;
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) throws Exception {
        if (!Boolean.getBoolean("unidr.u2.postgres")) return;
        postgres=UniDrPostgresSandbox.create();
        properties.add("spring.test.database.replace",()->"NONE");
        properties.add("spring.datasource.url",postgres::url);
        properties.add("spring.datasource.username",postgres::user);
        properties.add("spring.datasource.password",postgres::password);
        properties.add("spring.jpa.database-platform",()->"org.hibernate.dialect.PostgreSQLDialect");
        properties.add("spring.jpa.properties.hibernate.default_schema",postgres::schema);
        properties.add("spring.jpa.hibernate.ddl-auto",()->"create");
    }
    @AfterAll static void dropSandbox() throws Exception { if(postgres!=null) postgres.close(); }
    public static final class SqlCapture implements org.hibernate.resource.jdbc.spi.StatementInspector {
        static final ThreadLocal<List<String>> SQL=ThreadLocal.withInitial(ArrayList::new);
        static final ThreadLocal<List<Boolean>> READ_ONLY=ThreadLocal.withInitial(ArrayList::new);
        @Override public String inspect(String sql) {
            SQL.get().add(sql);
            if (sourceSql(sql)) READ_ONLY.get().add(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            return sql;
        }
        static boolean sourceSql(String sql) { return sql.stripLeading().toLowerCase(Locale.ROOT).startsWith("select") && sql.contains("receiving_document_item"); }
    }
    @Autowired EntityManager em;
    @Autowired EntityManagerFactory factory;
    @Autowired AccessGrantRepository grants;
    @Autowired PlatformTransactionManager transactions;
    private final Instant now=Instant.parse("2026-10-10T12:00:00Z");
    private final String subject="unidr-u2-user";
    private final UniDrDefinition base=UniDrDefinitions.receivingItemsSummary();
    private UniDrQueryService queries;
    private UniDrReferenceService references;
    private UniDrQueryAdapter adapter;
    private ReportQueryExecutor executor;
    private UniDrAccess access;
    private UniDrRunParameterResolver parameters;
    private UniDrQuerySourceService querySources;
    private UniDrSourceCatalog dynamicSources;
    private Long unitId,recipientA,hiddenWorkshop,hiddenDocument,visibleDocument;
    private void write(Runnable task) { new TransactionTemplate(transactions).executeWithoutResult(status->task.run()); }
    @BeforeEach void setup() {
        login(subject);
        var registry=new RlsDimensionRegistry("org.ip"); registry.rebuild();
        RlsCurrentUser current=()->SecurityContextHolder.getContext().getAuthentication().getName();
        var grantsAccess=new AccessService(grants,user->List.of(),registry);
        var permissions=new DefaultC5PermissionEvaluator(grants,user->List.of());
        var activator=new RlsFilterActivator(registry,new RlsReadableIdsCache(grantsAccess),current);
        var readGate=new RlsReadGate(permissions,registry);
        var authorizer=new ReportReadAuthorizer(factory,permissions,current,List.of(),grantsAccess,registry);
        var guard=new ReportQueryGuard(new SqmQuerySemanticAnalyzer(factory),grantsAccess,registry,current,factory,authorizer);
        executor=new ReportQueryExecutor(em,activator,current,guard,authorizer);
        var refresher=new EntityParamRefresher(em,activator,readGate,current,factory.unwrap(SessionFactoryImplementor.class));
        var resolver=new ReportParamResolver(refresher,grantsAccess,current,factory.unwrap(SessionFactoryImplementor.class),new ObjectMapper(),"BRANCH");
        var metadata=new MetadataResolver();
        var visuals=new QueryBuilderMetadataCatalog(factory,metadata,readGate,current);
        var assembly=new ReportQueryAssemblyService(guard,resolver,refresher,visuals);
        var recipes=new org.ipro.reportstudio.query.register.RegisterSliceReportTemplates(factory,
            new org.ipro.register.RegisterRegistry(new ManagedEntityCatalog(factory)),visuals,null);
        assembly.useQueryLanguage(new ReportQueryLanguage(visuals,recipes));
        querySources=new UniDrQuerySourceService(assembly,factory);querySources.useTables(new UniDrTableSources(visuals));
        var sections=new SectionMetadataRegistry("org.ip.model",metadata); sections.afterPropertiesSet();
        var catalog=new EntityDescriptorCatalog(new ManagedEntityCatalog(factory),sections,metadata,List.of(),List.of());
        var canonical=new CanonicalReadExecutor(catalog,new ScenarioFetchGraphResolver(metadata,null,null),metadata,
            activator,readGate,null,ReadTelemetry.noop(),null,current,permissions);
        ReflectionTestUtils.setField(canonical,"entityManager",em);
        var policies=new EntityDataAccessResolver(catalog,org.mockito.Mockito.mock(EntityDataAccess.class),canonical,List.of());
        var names=new org.ipro.fetch.instance.InstanceNameResolver(new ManagedEntityCatalog(factory).managedEntityClasses(),metadata);
        var presentations=new org.ipro.data.presentation.EntityPresentationService(new CanonicalEntityReadAccess(canonical),policies,names,metadata);
        var recorderTypes=new org.ipro.register.RecorderTypeRegistry(new ManagedEntityCatalog(factory),factory,metadata);
        recorderTypes.initialize();executor.usePresentations(presentations,recorderTypes);
        access=new UniDrAccess(()->true,current);
        references=new UniDrReferenceService(new LookupService(canonical),access,transactions);
        references.useEntityTypes(new ManagedEntityCatalog(factory).managedEntityClasses());
        var sources=new UniDrSourceCatalog(List.of(org.ip.unidr.source.UniDrReceivingItemsSource.definition()),querySources::describe,querySources::describeParameters);dynamicSources=sources;
        sources.usePlans(querySources::describeSource);
        var validator=new UniDrDefinitionValidator(sources);
        parameters=new UniDrRunParameterResolver(validator);
        adapter=new UniDrQueryAdapter(sources,validator,assembly);
        queries=service(5000);
        write(this::fixtures); SqlCapture.SQL.get().clear(); SqlCapture.READ_ONLY.get().clear();
    }
    private UniDrQueryService service(int limit) { var service=new UniDrQueryService(access,parameters,references,adapter,executor,transactions,limit,30000);service.useQuerySources(querySources);return service; }
    private void login(String user) { SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,List.of())); }
    @AfterEach void cleanup() {
        try { login(subject); write(()-> {
            em.createQuery("delete from ReceivingDocumentItem i where i.document.number like 'U2-%'").executeUpdate();
            em.createQuery("delete from ReceivingDocument d where d.number like 'U2-%'").executeUpdate();
            em.createQuery("delete from Nomenclature n where n.code like 'U2-%'").executeUpdate();
            em.createQuery("delete from Workshop w where w.code like 'U2-%'").executeUpdate();
            em.createQuery("delete from UnitOfMeasurement u where u.code like 'U2-%'").executeUpdate();
            em.createQuery("delete from Journal j where j.code like 'U2-%'").executeUpdate();
            em.createQuery("delete from Branch b where b.code like 'U2-%'").executeUpdate();
            em.createQuery("delete from AccessGrant g where g.subjectKey=:user").setParameter("user",subject).executeUpdate();
        }); } finally { SecurityContextHolder.clearContext(); SqlCapture.SQL.remove(); SqlCapture.READ_ONLY.remove(); }
    }
    private UniDrRunRequest request(UniDrDefinition definition,Map<String,Literal> extra) {
        var values=new HashMap<>(extra); values.putIfAbsent("unit",new Literal(ValueType.REFERENCE,List.of(unitId.toString())));
        return new UniDrRunRequest(definition,values,ZoneId.of("Europe/Moscow"));
    }
    private UniDrQueryService.Data read(UniDrDefinition definition,Map<String,Literal> extra) { return queries.read(request(definition,extra),now,subject); }
    private UniDrDefinition filters(List<Filter> filters,List<Parameter> params) {
        return new UniDrDefinition(1,base.sourceId(),1,base.title(),base.columns(),base.measures(),base.groups(),filters,params,base.sorts(),base.mode(),true);
    }
    private void assertNoSourceSql() { assertThat(SqlCapture.SQL.get()).noneMatch(SqlCapture::sourceSql); }
    @Test void actualRowsUseReadOnlyTransactionRlsAndDetachedScalars() throws Exception {
        var result=read(base,Map.of());
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(result.dataset().rowCount()).isEqualTo(6);
        assertThat(result.dataset().rows()).extracting(row->row.value("rowId")).doesNotHaveDuplicates();
        assertThat(result.dataset().rows()).extracting(row->row.value("documentNumber")).containsExactly("U2-01","U2-01","U2-02","U2-03","U2-03","U2-03");
        assertThat(Arrays.stream(result.dataset().rows()).map(row->(BigDecimal)row.value("quantity")).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("36");
        assertThat(SqlCapture.READ_ONLY.get()).containsExactly(true);
        var sql=SqlCapture.SQL.get().stream().filter(SqlCapture::sourceSql).findFirst().orElseThrow();
        assertThat(sql).contains("journal_id in","receiving_workshop_id in","transferring_workshop_id in");
        for(var row:result.dataset().rows()) for(var field:result.dataset().fields()) assertThat(row.value(field.name())).isNotInstanceOf(org.ipro.crud.BaseEntity.class);
        Files.createDirectories(Path.of("outputs/unidr-u2"));
        Files.writeString(Path.of("outputs/unidr-u2/source-sql-"+(postgres==null?"h2":"postgresql")+".log"),sql);
    }
    @Test void recipientAndFixedDecimalAndParameterizedFiltersComposeWithAnd() {
        var params=new ArrayList<>(base.parameters()); params.add(new Parameter("minimum","quantity",Operator.GE,"Минимум",DefaultRule.NONE,null));
        var definition=filters(List.of(new Filter("quantity",Operator.GE,new ParameterRef("minimum")),
            new Filter("quantity",Operator.LE,new Literal(ValueType.DECIMAL,List.of("8")))),params);
        var result=read(definition,Map.of("minimum",new Literal(ValueType.DECIMAL,List.of("3")),"recipient",new Literal(ValueType.REFERENCE,List.of(recipientA.toString()))));
        assertThat(result.dataset().rows()).extracting(row->row.value("quantity")).containsExactly(new BigDecimal("4.00"));
    }
    @Test void canonicalLookupHidesBranchAndJournalAndChecksSelectedIdAgain() {
        assertThat(references.search("receivingWorkshop","",50)).extracting(UniDrReferenceService.Option::id).contains(recipientA).doesNotContain(hiddenWorkshop);
        assertThat(references.search("document","U2-",50)).extracting(UniDrReferenceService.Option::id).contains(visibleDocument).doesNotContain(hiddenDocument);
        assertThatThrownBy(()->references.requireVisible("receivingWorkshop",hiddenWorkshop)).isInstanceOf(UniDrRunException.class);
        assertThatThrownBy(()->references.requireVisible("document",hiddenDocument)).isInstanceOf(UniDrRunException.class);
        assertNoSourceSql();
    }
    @Test void inaccessibleAndUnknownReferencesStopBeforeReportSql() {
        for(String id:List.of(hiddenWorkshop.toString(),Long.toString(Long.MAX_VALUE)))
            assertThatThrownBy(()->read(base,Map.of("recipient",new Literal(ValueType.REFERENCE,List.of(id)))))
                .isInstanceOfSatisfying(UniDrRunException.class,e->assertThat(e.code()).isEqualTo(UniDrRunException.Code.REFERENCE_UNAVAILABLE));
        var definition=filters(List.of(new Filter("document",Operator.EQ,new Literal(ValueType.REFERENCE,List.of(hiddenDocument.toString())))),base.parameters());
        assertThatThrownBy(()->read(definition,Map.of())).isInstanceOf(UniDrRunException.class); assertNoSourceSql();
    }
    @Test void countRowsCanIncludeDifferentUnitsButQuantityCannotRunWithoutUnit() {
        var count=new UniDrDefinition(1,base.sourceId(),1,base.title(),List.of(),List.of(new Measure("rowCount",Aggregation.COUNT_ROWS,null)),
            List.of(),List.of(),base.parameters(),List.of(),Mode.SUMMARY,true);
        assertThat(queries.read(new UniDrRunRequest(count,Map.of(),ZoneOffset.UTC),now,subject).dataset().rowCount()).isEqualTo(7);
        SqlCapture.SQL.get().clear();
        assertThatThrownBy(()->queries.read(new UniDrRunRequest(base,Map.of(),ZoneOffset.UTC),now,subject)).isInstanceOf(UniDrValidationException.class); assertNoSourceSql();
    }
    @Test void exactLimitSucceedsOverflowFailsAndEmptyPeriodHasMessage() {
        assertThat(service(6).read(request(base,Map.of()),now,subject).dataset().rowCount()).isEqualTo(6);
        assertThatThrownBy(()->service(5).read(request(base,Map.of()),now,subject)).isInstanceOfSatisfying(UniDrRunException.class,e->assertThat(e.code()).isEqualTo(UniDrRunException.Code.LIMIT_EXCEEDED));
        var empty=read(base,Map.of("period",new Literal(ValueType.DATE,List.of("2025-01-01","2025-01-31"))));
        assertThat(empty.dataset().isEmpty()).isTrue(); assertThat(empty.message()).isNotBlank();
    }
    @Test void relatedTypePermissionRevokedAfterLookupIsCheckedBeforeSourceSql() {
        references.requireVisible("unit",unitId);
        write(()->em.createQuery("delete from AccessGrant g where g.subjectKey=:user and g.dimension=:key")
            .setParameter("user",subject).setParameter("key",C5PermissionKeys.entityRead(Workshop.class)).executeUpdate());
        SqlCapture.SQL.get().clear();
        assertThatThrownBy(()->read(base,Map.of())).isInstanceOf(UniDrRunException.class); assertNoSourceSql();
    }
    @Test void equalGroupCaptionsKeepDistinctContiguousKeys() {
        write(()->em.createQuery("update Workshop w set w.name='Одинаковый цех' where w.code in ('U2-A','U2-B')").executeUpdate());
        var rows=read(base,Map.of()).dataset().rows();
        assertThat(Arrays.stream(rows).map(row->row.value("receivingWorkshopName")).distinct()).containsExactly("Одинаковый цех");
        var other=rows[5].value("receivingWorkshopId"); assertThat(other).isNotEqualTo(recipientA);
        assertThat(rows).extracting(row->row.value("receivingWorkshopId")).containsExactly(recipientA,recipientA,recipientA,other,other,other);
    }
    @Test void realSourceFeedsCompilerAndExportsWithoutAnotherDatabaseRead() {
        var data=read(base,Map.of());
        var sources=UniDrSourceCatalog.pilot();
        var compiler=new org.ip.unidr.render.UniDrReportCompiler(sources,new UniDrDefinitionValidator(sources));
        int statements=SqlCapture.SQL.get().size();
        var print=compiler.compile(base,data.dataset());
        var report=new org.ip.unidr.render.UniDrRenderedReport(print,base.title(),subject,access,new org.ipro.reportstudio.render.JasperReportCompiler());
        assertThat(report.preview().bytes()).startsWith((byte)'%',(byte)'P',(byte)'D',(byte)'F');
        assertThat(report.export(org.ipro.reportstudio.render.ReportExportFormat.PDF).bytes()).isEqualTo(report.preview().bytes());
        assertThat(report.export(org.ipro.reportstudio.render.ReportExportFormat.XLSX).bytes()).startsWith((byte)'P',(byte)'K');
        assertThat(SqlCapture.SQL.get()).hasSize(statements);
    }
    private UniDrDefinition queryDefinition() {
        var unit=new org.ipro.reportstudio.dom.ReportParam();unit.setName("unit");unit.setCaption("Единица");unit.setKind(org.ipro.reportstudio.dom.ReportParamKind.ENTITY);unit.setEntityClass(UnitOfMeasurement.class.getName());unit.setRequired(true);
        var template=new org.ipro.reportstudio.dom.ReportTemplate();template.addParam(unit);
        template.setJpql("""
            select r.name as recipient, n.name as item, i.quantity as qty, d.date as day
            from ReceivingDocumentItem i
            join ReceivingDocument d on i.document.id=d.id
            join Workshop r on d.receivingWorkshop.id=r.id
            join Workshop t on d.transferringWorkshop.id=t.id
            join Nomenclature n on i.nomenclature.id=n.id
            join Journal j on d.journal.id=j.id
            where n.unitOfMeasurement=:unit and d.date between :periodFrom and :periodTo
            """);
        var period=new org.ipro.reportstudio.dom.ReportParam();period.setName("period");period.setKind(org.ipro.reportstudio.dom.ReportParamKind.PERIOD);period.setRequired(true);template.addParam(period);
        return new UniDrDefinition(1,UniDrQuerySource.ID,1,"Запрос по накладным",List.of(new Column("recipient",null),new Column("item",null),new Column("qty",null),new Column("day",null)),
            List.of(new Measure("qty",Aggregation.SUM,null),new Measure(UniDrQuerySourceService.COUNT,Aggregation.COUNT_ROWS,null)),
            List.of("recipient","item"),List.of(),List.of(),List.of(new Sort("day",Direction.DESC)),Mode.SUMMARY,true).withQuery(UniDrQuerySource.from(template));
    }
    private UniDrRunRequest arbitraryRequest(UniDrDefinition definition,Long unit) {
        return new UniDrRunRequest(definition,Map.of(),ZoneOffset.UTC,Map.of("unit",unit,"periodFrom",LocalDate.of(2026,10,1),"periodTo",LocalDate.of(2026,10,31)));
    }
    @Test void arbitraryQueryUsesUdrEntityParametersRlsNativeGroupsAndExports() {
        var definition=queryDefinition();var data=queries.read(arbitraryRequest(definition,unitId),now,subject);
        assertThat(data.dataset().rowCount()).isEqualTo(6);
        assertThat(Arrays.stream(data.dataset().rows()).map(row->(BigDecimal)row.value("qty")).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("36");
        assertThat(data.dataset().rows()).allSatisfy(row->assertThat(row.value("recipient")).isInstanceOf(String.class));
        int statements=SqlCapture.SQL.get().size();
        var print=new org.ip.unidr.render.UniDrReportCompiler(dynamicSources,new UniDrDefinitionValidator(dynamicSources)).compile(definition,data.dataset(),data.source());
        var report=new org.ip.unidr.render.UniDrRenderedReport(print,definition.title(),subject,access,new org.ipro.reportstudio.render.JasperReportCompiler());
        assertThat(report.preview().bytes()).startsWith((byte)'%',(byte)'P');assertThat(report.export(org.ipro.reportstudio.render.ReportExportFormat.XLSX).bytes()).startsWith((byte)'P',(byte)'K');
        assertThat(SqlCapture.SQL.get()).hasSize(statements);
    }
    @Test void arbitraryQueryLimitAndMissingOrInvisibleEntityParameterFailClosed() {
        var definition=queryDefinition();
        assertThatThrownBy(()->service(5).read(arbitraryRequest(definition,unitId),now,subject)).isInstanceOfSatisfying(UniDrRunException.class,error->assertThat(error.code()).isEqualTo(UniDrRunException.Code.LIMIT_EXCEEDED));
        SqlCapture.SQL.get().clear();
        assertThatThrownBy(()->queries.read(arbitraryRequest(definition,Long.MAX_VALUE),now,subject)).isInstanceOf(UniDrRunException.class);assertNoSourceSql();
        assertThatThrownBy(()->queries.read(new UniDrRunRequest(definition,Map.of(),ZoneOffset.UTC),now,subject)).isInstanceOf(UniDrRunException.class);assertNoSourceSql();
    }
    @Test void arbitraryQueryDeniedRelatedTypeAndDmlStopBeforeSourceSql() {
        var definition=queryDefinition();
        write(()->em.createQuery("delete from AccessGrant g where g.subjectKey=:user and g.dimension=:key").setParameter("user",subject).setParameter("key",C5PermissionKeys.entityRead(Workshop.class)).executeUpdate());
        SqlCapture.SQL.get().clear();
        assertThatThrownBy(()->queries.read(arbitraryRequest(definition,unitId),now,subject)).isInstanceOf(UniDrRunException.class);assertNoSourceSql();
        var query=new UniDrQuerySource(org.ipro.reportstudio.dom.ReportQuerySource.MANUAL,"delete from ReceivingDocumentItem",null,List.of());
        assertThatThrownBy(()->querySources.describe(query)).isInstanceOf(UniDrRunException.class);assertNoSourceSql();
    }
    // Fixture helpers below write only into the embedded database or this run's disposable schema.
    @Test void tableAndItsCompiledManualQueryUseTheSameRowsTypesAndRenderResult() {
        var table=new UniDrDefinition(UniDrDefinition.SCHEMA_VERSION,"query",1,"По таблице",List.of(new Column("quantity",null)),
            List.of(new Measure("quantity",Aggregation.SUM,null)),List.of(),List.of(),List.of(),List.of(),Mode.DETAILS,true)
            .withQuery(querySources.tables().select("ReceivingDocumentItem"));
        var compiled=querySources.tables().project(table,false);
        var manual=table.withQuery(new UniDrQuerySource(org.ipro.reportstudio.dom.ReportQuerySource.MANUAL,compiled.jpql(),null,List.of()));
        var fromTable=queries.read(new UniDrRunRequest(table,Map.of(),ZoneOffset.UTC),now,subject);
        var fromQuery=queries.read(new UniDrRunRequest(manual,Map.of(),ZoneOffset.UTC),now,subject);
        assertThat(fromTable.dataset().fields()).isEqualTo(fromQuery.dataset().fields());
        assertThat(fromTable.dataset().rows()).extracting(row->row.value("quantity"))
            .doesNotContain(new BigDecimal("999.00"),new BigDecimal("777.00"),new BigDecimal("888.00"));
        assertThat(fromTable.dataset().rows()).extracting(row->row.value("quantity")).containsExactlyElementsOf(Arrays.stream(fromQuery.dataset().rows()).map(row->row.value("quantity")).toList());
        var compiler=new org.ip.unidr.render.UniDrReportCompiler(dynamicSources,new UniDrDefinitionValidator(dynamicSources));
        assertThat(compiler.compile(table,fromTable.dataset(),fromTable.source()).getPages()).hasSameSizeAs(compiler.compile(manual,fromQuery.dataset(),fromQuery.source()).getPages());
    }
    @Test void tableLinkedFieldsKeepRowsWithMissingOptionalReferences() {
        write(()->{grant(C5PermissionKeys.entityRead(Branch.class),null);var unassigned=new Workshop("U2-N","Без филиала");em.persist(unassigned);});
        var table=new UniDrDefinition(UniDrDefinition.SCHEMA_VERSION,"query",1,"Цеха",List.of(new Column("name",null),new Column("branch_name",null)),
            List.of(),List.of(),List.of(),List.of(),List.of(),Mode.DETAILS,true).withQuery(querySources.tables().select("Workshop"));
        var result=queries.read(new UniDrRunRequest(table,Map.of(),ZoneOffset.UTC),now,subject);
        assertThat(result.dataset().rows()).anySatisfy(row->{assertThat(row.value("name")).isEqualTo("Без филиала");assertThat(row.value("branch_name")).isNull();});
    }
    @Test void entityReferencesUseApplicationCaptionsAndMatchManualSource() {
        write(()->em.createQuery("update Workshop w set w.name='Одинаковый цех' where w.code in ('U2-A','U2-B')").executeUpdate());
        var table=new UniDrDefinition(UniDrDefinition.SCHEMA_VERSION,"query",1,"Ссылки",List.of(new Column("receivingWorkshop",null)),
            List.of(new Measure(UniDrQuerySourceService.COUNT,Aggregation.COUNT_ROWS,null)),List.of("receivingWorkshop"),List.of(),List.of(),List.of(),Mode.SUMMARY,true)
            .withQuery(querySources.tables().select("ReceivingDocument"));
        var compiled=querySources.tables().project(table,false);
        var manual=table.withQuery(new UniDrQuerySource(org.ipro.reportstudio.dom.ReportQuerySource.MANUAL,compiled.jpql(),null,List.of()));
        var result=queries.read(new UniDrRunRequest(table,Map.of(),ZoneOffset.UTC),now,subject);
        var equivalent=queries.read(new UniDrRunRequest(manual,Map.of(),ZoneOffset.UTC),now,subject);
        assertThat(result.dataset().rows()).isNotEmpty();
        // Workshop's canonical display name is its code; the name attribute is deliberately identical.
        assertThat(Arrays.stream(result.dataset().rows()).map(row->row.value("receivingWorkshop")).distinct()).containsExactlyInAnyOrder("U2-A","U2-B");
        assertThat(Arrays.stream(result.dataset().rows()).map(row->row.value("__unidr_key_receivingWorkshop")).distinct()).hasSize(2);
        assertThat(result.dataset().fields()).isEqualTo(equivalent.dataset().fields());
        assertThat(result.dataset().rows()).extracting(row->row.value("__unidr_key_receivingWorkshop"))
            .containsExactlyElementsOf(Arrays.stream(equivalent.dataset().rows()).map(row->row.value("__unidr_key_receivingWorkshop")).toList());
        var compiler=new org.ip.unidr.render.UniDrReportCompiler(dynamicSources,new UniDrDefinitionValidator(dynamicSources));
        assertThat(compiler.compile(table,result.dataset(),result.source()).getPages()).isNotEmpty();
    }
    @Test void optionalJoinCannotAuthorizeReadingUnfilteredForeignKeyOrAnOrJoin() {
        write(()->grant(C5PermissionKeys.entityRead(Branch.class),null));
        for(String text:List.of("select w.branch.id as leaked from Workshop w left join Branch b on w.branch.id=b.id",
                "select b.name as leaked from Workshop w left join Branch b on w.branch.id=b.id or b.id>0")) {
            assertThatThrownBy(()->querySources.describe(new UniDrQuerySource(org.ipro.reportstudio.dom.ReportQuerySource.MANUAL,text,null,List.of())))
                .isInstanceOf(UniDrRunException.class);
        }
        assertNoSourceSql();
    }
    @Test void sectionQueryRequiresAVisibleParentAndItsEntityPermission() {
        assertThatThrownBy(()->querySources.describe(new UniDrQuerySource(org.ipro.reportstudio.dom.ReportQuerySource.MANUAL,
            "select i.quantity as qty from ReceivingDocumentItem i",null,List.of())))
            .isInstanceOf(UniDrRunException.class).hasMessageContaining("RLS_SECTION_PARENT_JOIN_REQUIRED");
        var table=new UniDrDefinition(UniDrDefinition.SCHEMA_VERSION,"query",1,"Позиции",List.of(new Column("quantity",null)),
            List.of(),List.of(),List.of(),List.of(),List.of(),Mode.DETAILS,true).withQuery(querySources.tables().select("ReceivingDocumentItem"));
        write(()->em.createQuery("delete from AccessGrant g where g.subjectKey=:user and g.dimension=:key")
            .setParameter("user",subject).setParameter("key",C5PermissionKeys.entityRead(ReceivingDocument.class)).executeUpdate());
        assertThatThrownBy(()->queries.read(new UniDrRunRequest(table,Map.of(),ZoneOffset.UTC),now,subject)).isInstanceOf(UniDrRunException.class);
        assertNoSourceSql();
    }
    @Test void nativeFilterOnHiddenTableFieldRunsBeforeRowLimit() {
        var query=querySources.tables().select("Nomenclature");
        var tree=new org.ipro.filtergrid.filter.FilterConditionNode(new org.ipro.filtergrid.filter.FilterCondition("name",org.ipro.filtergrid.filter.FilterOperator.CONTAINS,
            ":search",null,org.ipro.filtergrid.filter.FilterDataType.TEXT));
        var p=new UniDrParameter("p_search","search","Поиск",UniDrParameter.Kind.SCALAR,UniDrParameter.ScalarType.TEXT,null,true,true,
            UniDrParameter.ValueSource.FORM,UniDrParameter.InitialRule.NONE,List.of(),UniDrParameter.Computed.NONE,null,null);
        var definition=new UniDrDefinition(UniDrDefinition.SCHEMA_VERSION,"query",1,"Номенклатура",List.of(new Column("code",null)),
            List.of(),List.of(),List.of(),List.of(),List.of(),Mode.DETAILS,true).withQuery(query).withParameters(List.of(p)).withFilterTree(tree);
        var data=service(1).read(new UniDrRunRequest(definition,Map.of(),ZoneOffset.UTC,Map.of("search","ДЕТАЛЬ X")),now,subject);
        assertThat(data.dataset().rows()).extracting(row->row.value("code")).containsExactly("U2-X");
        assertThat(data.dataset().fields()).extracting(org.ipro.reportstudio.data.QueryField::name).containsExactly("code");
        assertThatThrownBy(()->service(1).validateRequest(new UniDrRunRequest(definition,Map.of(),ZoneOffset.UTC),now,subject)).hasMessageContaining("Поиск");
    }
    @Test void nativeEntityFilterChecksAccessAndMatchesGoldenFixture() {
        var x=references.search("nomenclature","U2-X",10).getFirst().id();
        var tree=new org.ipro.filtergrid.filter.FilterConditionNode(new org.ipro.filtergrid.filter.FilterCondition("nomenclature",org.ipro.filtergrid.filter.FilterOperator.EQ,
            x.toString(),null,org.ipro.filtergrid.filter.FilterDataType.ENTITY_REFERENCE));
        var data=read(base.withFilterTree(tree),Map.of());
        assertThat(data.dataset().rowCount()).isEqualTo(4);
        assertThat(Arrays.stream(data.dataset().rows()).map(row->(BigDecimal)row.value("quantity")).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("21");
        var invalid=base.withFilterTree(new org.ipro.filtergrid.filter.FilterConditionNode(new org.ipro.filtergrid.filter.FilterCondition("receivingWorkshop",org.ipro.filtergrid.filter.FilterOperator.EQ,
            hiddenWorkshop.toString(),null,org.ipro.filtergrid.filter.FilterDataType.ENTITY_REFERENCE)));
        SqlCapture.SQL.get().clear();assertThatThrownBy(()->read(invalid,Map.of())).isInstanceOf(UniDrRunException.class);assertNoSourceSql();
    }
    @Test void repeatedEntityParameterAndEntityListUseTypedIdsAndTheSameGoldenRows() {
        var x=references.search("nomenclature","U2-X",10).getFirst().id();
        for(var kind:List.of(UniDrParameter.Kind.ENTITY,UniDrParameter.Kind.ENTITY_LIST)) {
            var parameter=new UniDrParameter("p_items","items","Номенклатура",kind,UniDrParameter.ScalarType.UNRESOLVED,Nomenclature.class.getName(),true,true,
                UniDrParameter.ValueSource.FORM,UniDrParameter.InitialRule.NONE,List.of(),UniDrParameter.Computed.NONE,null,null);
            var leaf=new org.ipro.filtergrid.filter.FilterConditionNode(new org.ipro.filtergrid.filter.FilterCondition("nomenclature",kind==UniDrParameter.Kind.ENTITY?org.ipro.filtergrid.filter.FilterOperator.EQ:org.ipro.filtergrid.filter.FilterOperator.IN,
                ":items",null,org.ipro.filtergrid.filter.FilterDataType.ENTITY_REFERENCE));
            var declarations=new ArrayList<>(base.parameterDeclarations());declarations.add(parameter);
            var recipe=base.withParameters(declarations).withFilterTree(org.ipro.filtergrid.filter.FilterGroup.and(leaf,leaf));
            var data=queries.read(new UniDrRunRequest(recipe,Map.of("unit",new Literal(ValueType.REFERENCE,List.of(unitId.toString()))),ZoneOffset.UTC,
                Map.of("items",kind==UniDrParameter.Kind.ENTITY?x:List.of(x))),now,subject);
            assertThat(data.dataset().rowCount()).isEqualTo(4);
            assertThat(Arrays.stream(data.dataset().rows()).map(row->(BigDecimal)row.value("quantity")).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("21");
        }
    }
    @Test void optionalManualQueryParameterBindsNullAndLetsTheQueryDefineItsMeaning() {
        var query=new UniDrQuerySource(org.ipro.reportstudio.dom.ReportQuerySource.MANUAL,"select n.code as code from Nomenclature n where :search is null or n.name = :search",null,List.of());
        var p=new UniDrParameter("p_search","search","Поиск",UniDrParameter.Kind.SCALAR,UniDrParameter.ScalarType.TEXT,null,false,true,
            UniDrParameter.ValueSource.FORM,UniDrParameter.InitialRule.NONE,List.of(),UniDrParameter.Computed.NONE,null,null);
        var recipe=new UniDrDefinition(UniDrDefinition.SCHEMA_VERSION,"query",1,"Поиск",List.of(new Column("code",null)),List.of(),List.of(),List.of(),List.of(),List.of(),Mode.DETAILS,true).withQuery(query).withParameters(List.of(p));
        assertThat(queries.read(new UniDrRunRequest(recipe,Map.of(),ZoneOffset.UTC),now,subject).dataset().rowCount()).isEqualTo(3);
        assertThat(queries.read(new UniDrRunRequest(recipe,Map.of(),ZoneOffset.UTC,Map.of("search","Деталь X")),now,subject).dataset().rowCount()).isEqualTo(1);
    }
    private void fixtures() {
        Branch visible=branch("U2-A"),hidden=branch("U2-H");
        Journal journal=journal("U2-J"),otherJournal=journal("U2-HJ");
        Workshop a=workshop("U2-A","Цех A",visible),b=workshop("U2-B","Цех B",visible),h=workshop("U2-H","Закрытый цех",hidden);
        recipientA=a.getId(); hiddenWorkshop=h.getId();
        UnitOfMeasurement unit=new UnitOfMeasurement("u2-шт","Штука U2","U2-PCS");em.persist(unit);
        UnitOfMeasurement kg=new UnitOfMeasurement("u2-кг","Килограмм U2","U2-KG");em.persist(kg);unitId=unit.getId();
        Nomenclature x=item("U2-X","Деталь X",unit),y=item("U2-Y","Деталь Y",unit),otherUnit=item("U2-KG","Весовая деталь",kg);
        var first=document("U2-01",journal,a,b,1); visibleDocument=first.getId(); positions(first,x,"2",x,"4");
        positions(document("U2-02",journal,a,b,2),y,"9"); positions(document("U2-03",journal,b,a,3),x,"5",x,"10",y,"6",otherUnit,"100");
        var closed=document("U2-JH",otherJournal,a,b,3); hiddenDocument=closed.getId(); positions(closed,x,"999");
        positions(document("U2-TH",journal,a,h,3),x,"777");positions(document("U2-RH",journal,h,a,3),x,"888");
        var outside=document("U2-OUT",journal,a,b,1);outside.setDate(LocalDate.of(2026,9,30));positions(outside,x,"333");
        for(Class<?> type:List.of(ReceivingDocument.class,ReceivingDocumentItem.class,Workshop.class,Nomenclature.class,UnitOfMeasurement.class,Journal.class)) grant(C5PermissionKeys.entityRead(type),null);
        grant("JOURNAL",journal.getId());grant("BRANCH",visible.getId());grant("ENTITY:ReceivingDocument",null);em.flush();em.clear();
    }
    private Branch branch(String code) {var value=new Branch();value.setCode(code);value.setName(code);em.persist(value);return value;}
    private Journal journal(String code) {var value=new Journal();value.setCode(code);value.setName(code);em.persist(value);return value;}
    private Workshop workshop(String code,String name,Branch branch) {var value=new Workshop(code,name);value.setBranch(branch);em.persist(value);return value;}
    private Nomenclature item(String code,String name,UnitOfMeasurement unit) {var value=new Nomenclature(code,name,unit);em.persist(value);return value;}
    private ReceivingDocument document(String number,Journal journal,Workshop recipient,Workshop sender,int day) {
        var value=new ReceivingDocument(number,LocalDate.of(2026,10,day),recipient,sender);value.setJournal(journal);em.persist(value);return value;
    }
    private void positions(ReceivingDocument document,Object... values) {
        for(int index=0;index<values.length;index+=2) {var row=new ReceivingDocumentItem((Nomenclature)values[index],new BigDecimal((String)values[index+1]));row.setDocument(document);row.setLineNumber(index/2+1);em.persist(row);}
    }
    private void grant(String dimension,Long valueId) {
        var grant=new AccessGrant();grant.setSubjectType(AccessGrant.SubjectType.USER);grant.setSubjectKey(subject);grant.setDimension(dimension);grant.setDimensionValueId(valueId);grant.setCanRead(true);em.persist(grant);
    }
}
