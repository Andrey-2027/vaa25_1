package org.ip.unidr.spike;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.ip.Application;
import org.ip.model.*;
import org.ipro.reportstudio.data.ReportDataset;
import org.ipro.reportstudio.dom.*;
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
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/** U0 on actual application entities, real C5 grants, real Hibernate filters and existing assembly. */
@DataJpaTest(showSql=false, properties={"spring.jpa.show-sql=false",
    "spring.jpa.properties.hibernate.session_factory.statement_inspector=org.ip.unidr.spike.UniDrReceivingItemsSpikeTest$SqlCapture"})
@ImportAutoConfiguration(RlsPersistenceAutoConfiguration.class)
@ContextConfiguration(classes=Application.class)
class UniDrReceivingItemsSpikeTest {
    private static UniDrPostgresSandbox postgres;

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) throws Exception {
        if (!Boolean.getBoolean("unidr.u0.postgres")) return;
        postgres=UniDrPostgresSandbox.create();
        properties.add("spring.test.database.replace",()->"NONE");
        properties.add("spring.datasource.url",postgres::url);
        properties.add("spring.datasource.username",postgres::user);
        properties.add("spring.datasource.password",postgres::password);
        properties.add("spring.jpa.database-platform",()->"org.hibernate.dialect.PostgreSQLDialect");
        properties.add("spring.jpa.properties.hibernate.default_schema",postgres::schema);
        // The sandbox owns cleanup; Hibernate must never issue DDL in the application's schema.
        properties.add("spring.jpa.hibernate.ddl-auto",()->"create");
    }

    @AfterAll static void dropSandbox() throws Exception { if(postgres!=null) postgres.close(); }
    private static final String SOURCE="""
        select i.id as rowId, d.id as documentId, d.number as documentNumber, d.date as date,
               r.id as receivingWorkshopId, r.name as receivingWorkshopName,
               t.id as transferringWorkshopId, t.name as transferringWorkshopName,
               n.id as nomenclatureId, n.code as nomenclatureCode, n.name as nomenclatureName,
               u.id as unitId, u.shortCode as unit, i.quantity as quantity
        from ReceivingDocumentItem i
        join ReceivingDocument d on i.document.id = d.id
        join Workshop r on d.receivingWorkshop.id = r.id
        join Workshop t on d.transferringWorkshop.id = t.id
        join Nomenclature n on i.nomenclature.id = n.id
        join UnitOfMeasurement u on n.unitOfMeasurement.id = u.id
        join Journal j on d.journal.id = j.id
        where d.date between :periodFrom and :periodTo and u.id = :unitId
        """;

    @Autowired EntityManager em;
    @Autowired EntityManagerFactory factory;
    @Autowired AccessGrantRepository grants;
    private String subject;
    private ReportQueryGuard guard;
    private ReportQueryExecutor executor;
    private ReportQueryAssemblyService assembly;
    private Long unitId;
    private Long recipientA;

    public static final class SqlCapture implements org.hibernate.resource.jdbc.spi.StatementInspector {
        static final ThreadLocal<List<String>> SQL=ThreadLocal.withInitial(ArrayList::new);
        @Override public String inspect(String sql) { SQL.get().add(sql); return sql; }
    }

    @BeforeEach void setup() {
        subject="unidr-u0-user";
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(subject,"",List.of()));
        var registry=new RlsDimensionRegistry("org.ip"); registry.rebuild();
        RlsCurrentUser current=()->subject;
        var access=new AccessService(grants,user->List.of(),registry);
        var permissions=new DefaultC5PermissionEvaluator(grants,user->List.of());
        var activator=new RlsFilterActivator(registry,new RlsReadableIdsCache(access),current);
        var readGate=new RlsReadGate(permissions,registry);
        var authorizer=new ReportReadAuthorizer(factory,permissions,current,List.of(),access,registry);
        guard=new ReportQueryGuard(new SqmQuerySemanticAnalyzer(factory),access,registry,current,factory,authorizer);
        executor=new ReportQueryExecutor(em,activator,current,guard,authorizer);
        var refresher=new EntityParamRefresher(em,activator,readGate,current,factory.unwrap(SessionFactoryImplementor.class));
        var resolver=new ReportParamResolver(refresher,access,current,factory.unwrap(SessionFactoryImplementor.class),
            new ObjectMapper(),"BRANCH");
        assembly=new ReportQueryAssemblyService(guard,resolver,refresher);

        Branch visible=branch("U0-A"),hidden=branch("U0-H");
        Journal journal=journal("U0-J"),otherJournal=journal("U0-HJ");
        Workshop a=workshop("U0-A","Цех A",visible),b=workshop("U0-B","Цех B",visible),
            h=workshop("U0-H","Закрытый цех",hidden);
        recipientA=a.getId();
        UnitOfMeasurement unit=new UnitOfMeasurement("u0-шт","Штука U0","U0-PCS");em.persist(unit);
        UnitOfMeasurement kg=new UnitOfMeasurement("u0-кг","Килограмм U0","U0-KG");em.persist(kg);
        unitId=unit.getId();
        Nomenclature x=item("U0-X","Деталь X",unit),y=item("U0-Y","Деталь Y",unit),
            otherUnit=item("U0-KG","Весовая деталь",kg);
        positions(document("U0-01",journal,a,b,1),x,"2",x,"4");
        positions(document("U0-02",journal,a,b,2),y,"9");
        positions(document("U0-03",journal,b,a,3),x,"5",x,"10",y,"6",otherUnit,"100");
        positions(document("U0-JH",otherJournal,a,b,3),x,"999");
        positions(document("U0-TH",journal,a,h,3),x,"777");
        positions(document("U0-RH",journal,h,a,3),x,"888");
        ReceivingDocument outside=document("U0-OUT",journal,a,b,1);
        outside.setDate(LocalDate.of(2026,9,30)); positions(outside,x,"333");
        for(Class<?> type:List.of(ReceivingDocument.class,ReceivingDocumentItem.class,Workshop.class,
                Nomenclature.class,UnitOfMeasurement.class,Journal.class)) grant(C5PermissionKeys.entityRead(type),null);
        grant("JOURNAL",journal.getId()); grant("BRANCH",visible.getId());
        grant("ENTITY:ReceivingDocument",null);
        em.flush();em.clear();SqlCapture.SQL.get().clear();
        assertThat(RlsContext.isBypassed()).isFalse();
    }

    @AfterEach void cleanup() { SecurityContextHolder.clearContext();SqlCapture.SQL.remove(); }

    @Test void actualOwnedRowsRespectDocumentAndBothWorkshopFiltersWithoutMultiplication() throws Exception {
        ReportTemplate template=template(false);
        assertThat(template.getBands()).isEmpty();
        var request=assembly.assemble(template,ReportContext.empty(subject),values(false));
        assertThat(request.jpql()).contains("order by r.name, r.id, n.code, n.id, d.date, i.id");
        ReportDataset data=executor.execute(request,5001,30_000);
        assertThat(data.rowCount()).isEqualTo(6);
        assertThat(data.rows()).extracting(row->row.value("rowId")).doesNotHaveDuplicates();
        assertThat(data.rows()).extracting(row->row.value("documentNumber"))
            .containsExactly("U0-01","U0-01","U0-02","U0-03","U0-03","U0-03");
        assertThat(data.rows()).extracting(row->row.value("quantity"))
            .containsExactly(new BigDecimal("2.00"),new BigDecimal("4.00"),new BigDecimal("9.00"),
                new BigDecimal("5.00"),new BigDecimal("10.00"),new BigDecimal("6.00"));
        String sql=dataSql().toLowerCase(Locale.ROOT);
        assertThat(sql).contains("journal_id in", "receiving_workshop_id in", "transferring_workshop_id in");
        List<UniDrRenderSpike.Input> input=Arrays.stream(data.rows()).map(row->new UniDrRenderSpike.Input(
            (Long)row.value("receivingWorkshopId"),(String)row.value("receivingWorkshopName"),
            (Long)row.value("nomenclatureId"),(String)row.value("nomenclatureName"),
            ((LocalDate)row.value("date")).toEpochDay(),row.value("date").toString(),
            "Позиция "+row.value("rowId"),(BigDecimal)row.value("quantity"))).toList();
        Files.createDirectories(Path.of("outputs/unidr-u0"));
        for(boolean details:List.of(false,true)) {
            var report=UniDrRenderSpike.compile(input,2,details);
            var lines=UniDrRenderSpike.lines(report);
            assertThat(lines).filteredOn(line->line.label().equals("Всего"))
                .singleElement().satisfies(line->assertThat(line.cells()).containsExactly("Всего","36,00","6,00","2,00","10,00","6"));
            assertThat(lines.stream().filter(line->line.label().startsWith("Позиция ")).count()).isEqualTo(details?6:0);
            Files.writeString(Path.of("outputs/unidr-u0/receiving-items-"+(postgres==null?"h2":"postgresql")
                +(details?"-details":"-summary")+".html"),UniDrRenderSpike.html(report));
        }
        Files.writeString(Path.of("outputs/unidr-u0/receiving-items-query.hql"),request.jpql());
        Files.writeString(Path.of("outputs/unidr-u0/receiving-items-sql-"+(postgres==null?"h2":"postgresql")+".log"),sql);
    }

    @Test void periodUnitAndOptionalRecipientUseExistingTypedParameterAssembly() {
        var values=values(true);
        var request=assembly.assemble(template(true),ReportContext.empty(subject),values);
        assertThat(request.bindings()).containsEntry("periodFrom",LocalDate.of(2026,10,1))
            .containsEntry("unitId",unitId).containsEntry("recipientId",recipientA);
        ReportDataset rows=executor.execute(request,5001,30_000);
        assertThat(rows.rowCount()).isEqualTo(3);
        assertThat(rows.rows()).extracting(row->row.value("receivingWorkshopName")).containsOnly("Цех A");
        values.put("periodFrom",LocalDate.of(2026,10,2));
        values.put("periodTo",LocalDate.of(2026,10,2));
        assertThat(executor.execute(assembly.assemble(template(true),ReportContext.empty(subject),values),5001,30_000)
            .rows()).extracting(row->row.value("documentNumber")).containsExactly("U0-02");
    }

    @Test void missingRelatedTypeReadIsDeniedBeforeSourceSql() {
        em.createQuery("delete from AccessGrant g where g.subjectKey = :user and g.dimension = :key")
            .setParameter("user",subject).setParameter("key",C5PermissionKeys.entityRead(Workshop.class)).executeUpdate();
        em.flush();SqlCapture.SQL.get().clear();
        assertThatThrownBy(()->assembly.assemble(template(false),ReportContext.empty(subject),values(false)))
            .hasMessageContaining("ENTITY-read").hasMessageContaining(Workshop.class.getName());
        assertNoSourceSql();
    }

    @Test void executorRechecksCurrentSubjectAfterAssembly() {
        var request=assembly.assemble(template(false),ReportContext.empty(subject),values(false));
        subject="unidr-u0-no-grants";SqlCapture.SQL.get().clear();
        assertThatThrownBy(()->executor.execute(request,5001,30_000)).hasMessageContaining("Отказ");
        assertNoSourceSql();
    }

    @Test void foreignKeyOnlyProjectionCannotBypassRestrictedAssociationVisibility() {
        var checked=guard.guard("select d.receivingWorkshop.id as recipient from ReceivingDocument d",Set.of());
        assertThat(checked.allowed()).isFalse();
        assertThat(checked.errors()).anyMatch(error->error.contains("RLS_ASSOCIATION_JOIN_REQUIRED"));
    }

    @Test void additionalRowDistinguishesCompleteFromTruncatedSelection() {
        var request=assembly.assemble(template(false),ReportContext.empty(subject),values(false));
        int limit=5;
        assertThat(executor.execute(request,limit+1,30_000).rowCount()).isGreaterThan(limit);
        limit=6;
        assertThat(executor.execute(request,limit+1,30_000).rowCount()).isEqualTo(limit);
    }

    @Test void existingGuardRequiresExactBindingNamesForLogicalPeriod() {
        var template=template(false);
        template.getParams().removeIf(param->param.getName().startsWith("period"));
        var period=new ReportParam();period.setName("period");period.setKind(ReportParamKind.PERIOD);
        period.setRequired(true);template.addParam(period);
        assertThatThrownBy(()->assembly.assemble(template,ReportContext.empty(subject),values(false)))
            .hasMessageContaining("periodFrom").hasMessageContaining("periodTo");
        assertNoSourceSql();
    }

    private ReportTemplate template(boolean recipient) {
        var template=new ReportTemplate();template.setName("UniDR U0");
        template.setJpql(SOURCE+(recipient?" and r.id = :recipientId":""));
        // Existing guard declares exact names, while PERIOD resolves nameFrom/nameTo.
        // UniDR's single logical period therefore adapts to two SCALAR date parameters.
        scalar(template,"periodFrom");scalar(template,"periodTo");
        scalar(template,"unitId");if(recipient)scalar(template,"recipientId");
        // Paths avoid Hibernate 7 SqmAliasedNodeRef, unsupported by the current C5 walker.
        for(String alias:List.of("r.name","r.id","n.code","n.id","d.date","i.id")) {
            var order=new ReportOrder();order.setColumnName(alias);order.setPosition(template.getOrders().size());
            template.addOrder(order);
        }
        return template;
    }

    private void scalar(ReportTemplate template,String name) {
        var param=new ReportParam();param.setName(name);param.setKind(ReportParamKind.SCALAR);
        param.setRequired(true);template.addParam(param);
    }
    private Map<String,Object> values(boolean recipient) {
        Map<String,Object> values=new HashMap<>();values.put("periodFrom",LocalDate.of(2026,10,1));
        values.put("periodTo",LocalDate.of(2026,10,31));values.put("unitId",unitId);
        if(recipient)values.put("recipientId",recipientA);return values;
    }
    private String dataSql() {
        return SqlCapture.SQL.get().stream().filter(sql->sql.stripLeading().toLowerCase(Locale.ROOT).startsWith("select")
            && sql.toLowerCase(Locale.ROOT).contains("receiving_document_item")).reduce((first,second)->second).orElseThrow();
    }
    private void assertNoSourceSql() {
        assertThat(SqlCapture.SQL.get()).noneMatch(sql->sql.stripLeading().toLowerCase(Locale.ROOT).startsWith("select")
            && sql.toLowerCase(Locale.ROOT).contains("receiving_document"));
    }
    private Branch branch(String code) {var value=new Branch();value.setCode(code);value.setName(code);em.persist(value);return value;}
    private Journal journal(String code) {var value=new Journal();value.setCode(code);value.setName(code);em.persist(value);return value;}
    private Workshop workshop(String code,String name,Branch branch) {var value=new Workshop(code,name);value.setBranch(branch);em.persist(value);return value;}
    private Nomenclature item(String code,String name,UnitOfMeasurement unit) {var value=new Nomenclature(code,name,unit);em.persist(value);return value;}
    private ReceivingDocument document(String number,Journal journal,Workshop recipient,Workshop sender,int day) {
        var value=new ReceivingDocument(number,LocalDate.of(2026,10,day),recipient,sender);value.setJournal(journal);em.persist(value);return value;
    }
    private void positions(ReceivingDocument document,Object... values) {
        for(int index=0;index<values.length;index+=2) {
            var row=new ReceivingDocumentItem((Nomenclature)values[index],new BigDecimal((String)values[index+1]));
            row.setDocument(document);row.setLineNumber(index/2+1);em.persist(row);
        }
    }
    private void grant(String dimension,Long valueId) {
        var grant=new AccessGrant();grant.setSubjectType(AccessGrant.SubjectType.USER);grant.setSubjectKey(subject);
        grant.setDimension(dimension);grant.setDimensionValueId(valueId);grant.setCanRead(true);em.persist(grant);
    }
}
