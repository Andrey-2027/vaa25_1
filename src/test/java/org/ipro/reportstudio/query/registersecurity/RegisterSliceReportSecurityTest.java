package org.ipro.reportstudio.query.registersecurity;

import org.ip.application.register.RegisterReportReadPolicy;
import org.ip.application.register.RegisterSliceCteSpike;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import net.sf.jasperreports.engine.JasperPrint;
import org.hibernate.SessionFactory;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.hibernate.cfg.Configuration;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.ip.register.RegisterMetadata;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.reportstudio.data.ReportDataset;
import org.ipro.reportstudio.dom.ReportQuerySource;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.param.*;
import org.ipro.reportstudio.query.*;
import org.ipro.reportstudio.query.sqm.SqmQuerySemanticAnalyzer;
import org.ipro.reportstudio.render.*;
import org.ipro.reportstudio.run.*;
import org.ipro.rls.*;
import org.ipro.rls.c5.*;
import org.junit.jupiter.api.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/** Р3: реальные grants/evaluator/RLS и run → assembly → guard → executor, renderer лишь записывает dataset. */
class RegisterSliceReportSecurityTest {
    private static final LocalDate DAY = LocalDate.of(2026, 1, 1);
    private SessionFactory factory;
    private EntityManager em;
    private RlsDimensionRegistry registry;
    private AccessService access;
    private QueryBuilderMetadataCatalog catalog;
    private ReportReadAuthorizer authorizer;
    private ReportQueryGuard guard;
    private ReportQueryExecutor executor;
    private ReportExecutionService run;
    private RecordingRenderer renderer;
    private String subject;
    private final List<String> sql = new ArrayList<>();

    @BeforeEach
    void setUp() {
        subject = "slice-user";
        var configuration = new Configuration()
            .addAnnotatedClass(Item.class).addAnnotatedClass(SafePrice.class)
            .addAnnotatedClass(UnsafePrice.class).addAnnotatedClass(AccessGrant.class)
            .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
            .setProperty("hibernate.connection.url", "jdbc:h2:mem:register-security-" + UUID.randomUUID())
            .setProperty("hibernate.hbm2ddl.auto", "create-drop");
        configuration.setStatementInspector(statement -> { sql.add(statement); return statement; });
        factory = configuration.buildSessionFactory();
        em = factory.createEntityManager();
        em.getTransaction().begin();
        var grants = new JpaRepositoryFactory(em).getRepository(AccessGrantRepository.class);
        registry = new RlsDimensionRegistry("org.ipro.reportstudio.query.registersecurity");
        registry.rebuild();
        RlsCurrentUser current = () -> subject;
        access = new AccessService(grants, username -> List.of(), registry);
        var permissions = new DefaultC5PermissionEvaluator(grants, username -> List.of());
        var readGate = new RlsReadGate(access, registry);
        var activator = new RlsFilterActivator(registry, new RlsReadableIdsCache(access), current);
        catalog = new QueryBuilderMetadataCatalog(factory, new MetadataResolver(), readGate, current);
        authorizer = new ReportReadAuthorizer(factory, permissions, current,
            List.of(new RegisterReportReadPolicy(factory, registry)), access, registry);
        guard = new ReportQueryGuard(new SqmQuerySemanticAnalyzer(factory), access, registry, current,
            factory, authorizer);
        executor = new ReportQueryExecutor(em, activator, current, guard, authorizer);
        var refresher = new EntityParamRefresher(em, activator, readGate, current,
            factory.unwrap(SessionFactoryImplementor.class));
        var resolver = new ReportParamResolver(refresher, access, current,
            factory.unwrap(SessionFactoryImplementor.class), new ObjectMapper(), "SLICE_ITEMS");
        var assembler = new ReportQueryAssemblyService(guard, resolver, refresher, catalog);
        renderer = new RecordingRenderer();
        run = new ReportExecutionService(guard, executor, resolver, refresher, renderer,
            new ReportArtifactCache(), assembler, current, new ReportTaskExecutor(Runnable::run));
        item(1); item(2);
        price(101, 1, DAY, "150", false);
        price(102, 1, DAY.plusDays(1), "90", false);
        price(103, 2, DAY, "250", false);
        price(104, 2, DAY.plusDays(1), "190", false);
        em.flush();
        em.clear();
        sql.clear();
        assertThat(RlsContext.isBypassed()).isFalse();
    }

    @AfterEach
    void tearDown() {
        if (em != null) {
            if (em.getTransaction().isActive()) em.getTransaction().rollback();
            em.close();
        }
        if (factory != null) factory.close();
    }

    @Test
    void c5DeniesEntityUsedOnlyInsideCteBeforeDataSql() {
        grantType(Item.class);
        grant("SLICE_ITEMS", 1L);
        flushed();
        var compiled = VisualQueryCompiler.compile(slice("R3SafePrice"), catalog);
        assertThat(guard.guard(compiled.jpql(), compiled.bindings().keySet()).errors())
            .anyMatch(e -> e.contains("ENTITY-read") && e.contains(SafePrice.class.getName()));
        assertThatThrownBy(() -> run(slice("R3SafePrice"))).hasMessageContaining("ENTITY-read");
        assertNoPriceSql("r3_safe_price");
        assertThat(renderer.dataset).isNull();
    }

    @Test
    void c5EntityGrantDoesNotReplaceResourceAttributeGrant() {
        grantType(Item.class);
        grant(C5PermissionKeys.entityRead(SafePrice.class), null);
        for (var attribute : factory.getMetamodel().entity(SafePrice.class).getAttributes()) {
            if (!attribute.getName().equals("price")) {
                grant(C5PermissionKeys.attributeRead(SafePrice.class, attribute.getName()), null);
            }
        }
        grant("SLICE_ITEMS", 1L);
        flushed();
        assertThatThrownBy(() -> run(slice("R3SafePrice"))).hasMessageContaining("ATTR-read");
        assertNoPriceSql("r3_safe_price");
    }

    @Test
    void c5WithoutRlsGrantIsDeniedRatherThanReportedAsAnEmptySlice() {
        grantType(Item.class); grantType(SafePrice.class);
        flushed();
        assertThatThrownBy(() -> run(slice("R3SafePrice"))).hasMessageContaining("Нет доступа");
        assertNoPriceSql("r3_safe_price");
    }

    @Test
    void realFiltersCoverBothCtePhasesAndCountOnlyVisibleGroups() {
        grantType(Item.class); grantType(SafePrice.class);
        grant("SLICE_ITEMS", 1L);
        flushed();
        run(slice("R3SafePrice"));
        assertThat(renderer.dataset.rowCount()).isEqualTo(1);
        assertThat(renderer.dataset.rows()[0].value("nomenclatureId")).isEqualTo(1L);
        assertThat(renderer.dataset.rows()[0].value("period")).isEqualTo(DAY.plusDays(1));
        assertThat((BigDecimal) renderer.dataset.rows()[0].value("price")).isEqualByComparingTo("90");
        String executed = sql.stream().filter(s -> s.toLowerCase(Locale.ROOT).contains("r3_safe_price"))
            .findFirst().orElseThrow().toLowerCase(Locale.ROOT);
        // Две физические ссылки на регистр и фильтр item_id в обеих фазах, не просто вызов activator.
        assertThat(occurrences(executed, "r3_safe_price")).isEqualTo(2);
        assertThat(occurrences(executed, "item_id in")).isGreaterThanOrEqualTo(2);
        var slice = slice("R3SafePrice");
        var count = new VisualQueryDefinition(VisualQueryDefinition.CURRENT_VERSION, "latest", "l",
            List.of(), List.of(), List.of(),
            List.of(new VisualQueryDefinition.Aggregate("COUNT", "nomenclatureId", "total")),
            List.of(), null, null, List.of(), List.of());
        run(new VisualQueryPackage(slice.ctes(), count));
        assertThat(renderer.dataset.rows()[0].value("total")).isEqualTo(1L);
    }

    @Test
    void sameSessionAndCachedReadableIdsDoNotCarryPermissionsToAnotherSubject() {
        grantType(Item.class); grantType(SafePrice.class);
        grant("SLICE_ITEMS", 1L);
        subject = "slice-second";
        grantType(Item.class); grantType(SafePrice.class);
        grant("SLICE_ITEMS", 2L);
        flushed();
        subject = "slice-user";
        run(slice("R3SafePrice"));
        assertThat(renderer.dataset.rows()[0].value("nomenclatureId")).isEqualTo(1L);
        subject = "slice-second";
        run(slice("R3SafePrice"));
        assertThat(renderer.dataset.rowCount()).isEqualTo(1);
        assertThat(renderer.dataset.rows()[0].value("nomenclatureId")).isEqualTo(2L);
        subject = "slice-user";
        run(slice("R3SafePrice"));
        assertThat(renderer.dataset.rows()[0].value("nomenclatureId")).isEqualTo(1L);
    }

    @Test
    void hiddenFreshVersionCannotResurrectOldVersionWhenPolicyUsesRowId() {
        price(201, 1, DAY, "150", true);
        price(202, 1, DAY.plusDays(1), "90", true);
        grantType(Item.class); grantType(UnsafePrice.class);
        grant("SLICE_ITEMS", 1L);
        grant("SLICE_ROWS", 201L); // Новая версия 202 скрыта, такая policy несовместима со срезом.
        flushed();
        assertThatThrownBy(() -> run(slice("R3UnsafePrice")))
            .hasMessageContaining("REGISTER_SLICE_VISIBILITY");
        assertNoPriceSql("r3_unsafe_price");
        assertThat(renderer.dataset).isNull();
    }

    @Test
    void finalExecutorRechecksPermissionsAndRejectsAnAnonymousSubject() {
        grantType(Item.class); grantType(SafePrice.class);
        grant("SLICE_ITEMS", 1L);
        flushed();
        var compiled = VisualQueryCompiler.compile(slice("R3SafePrice"), catalog);
        var prepared = guard.guard(compiled.jpql(), compiled.bindings().keySet());
        assertThat(prepared.allowed()).as("%s", prepared.errors()).isTrue();
        subject = "ungranted-user";
        assertThatThrownBy(() -> executor.execute(compiled.jpql(), compiled.bindings(),
            prepared.selectFields(), 100, 1000)).hasMessageContaining("ENTITY-read");
        subject = "system";
        assertThatThrownBy(() -> executor.execute(compiled.jpql(), compiled.bindings(),
            prepared.selectFields(), 100, 1000)).isInstanceOf(RlsAccessDeniedException.class);
        assertNoPriceSql("r3_safe_price");
    }

    @Test
    void missingC5ProviderIsAConfigurationError() {
        assertThatNullPointerException().isThrownBy(() -> new ReportReadAuthorizer(factory, null,
            () -> subject, List.of(), access, registry)).withMessageContaining("C5PermissionEvaluator");
    }

    @Test
    void implicitAssociationAndEntityParameterAreCoveredByC5() {
        grantType(SafePrice.class);
        grant("SLICE_ITEMS", 1L);
        flushed();
        assertThat(authorizer.violations("select p.nomenclature.code from R3SafePrice p", Map.of()))
            .anyMatch(e -> e.contains("ENTITY-read") && e.contains(Item.class.getName()));
        assertThat(authorizer.violations("select p.price from R3SafePrice p where p.nomenclature = :item",
            Map.of("item", Item.class)))
            .anyMatch(e -> e.contains("ENTITY-read") && e.contains(Item.class.getName()));
    }

    @Test
    void entityOutputIsRejectedUntilSafeAttributeLineageIsAvailable() {
        grantType(Item.class);
        flushed();
        assertThat(authorizer.violations("select i from R3Item i", Map.of()))
            .anyMatch(e -> e.contains("scalar-проекция"));
    }

    @Test
    void grantRevocationInvalidatesCachedFiltersInTheSameSession() {
        grantType(Item.class); grantType(SafePrice.class);
        grant("SLICE_ITEMS", 1L);
        grant("SLICE_ITEMS", 2L);
        flushed();
        run(slice("R3SafePrice"));
        assertThat(renderer.dataset.rowCount()).isEqualTo(2);
        var revoked = em.createQuery("select g from AccessGrant g where g.subjectKey = :user "
            + "and g.dimension = 'SLICE_ITEMS' and g.dimensionValueId = 1", AccessGrant.class)
            .setParameter("user", subject).getSingleResult();
        em.remove(revoked);
        flushed();
        run(slice("R3SafePrice"));
        assertThat(renderer.dataset.rowCount()).isEqualTo(1);
        assertThat(renderer.dataset.rows()[0].value("nomenclatureId")).isEqualTo(2L);
    }

    @Test
    void policyRejectsMutableNestedPathsResourcesPeriodAndCustomRules() {
        // Узкий unit-контракт policy; остальные сценарии выше используют настоящий registry/enforcement.
        var isolated = org.mockito.Mockito.mock(RlsDimensionRegistry.class);
        for (String path : List.of("nomenclature.code", "price", "period", "id")) {
            var policy = new RlsPolicyDescriptor(SafePrice.class, true,
                Map.of("TEST", RlsDimensionKind.FILTERABLE),
                Map.of("TEST", new RlsPolicyDescriptor.ValueRule(List.of(path), false, false)));
            org.mockito.Mockito.when(isolated.policyOf(SafePrice.class)).thenReturn(policy);
            assertThat(new RegisterReportReadPolicy(factory, isolated).violations(Set.of(SafePrice.class)))
                .as("unsupported path %s", path).anyMatch(e -> e.contains("REGISTER_SLICE_VISIBILITY"));
        }
        var custom = new RlsPolicyDescriptor(SafePrice.class, true,
            Map.of("TEST", RlsDimensionKind.FILTERABLE),
            Map.of("TEST", new RlsPolicyDescriptor.ValueRule(List.of("nomenclature.id"), false, true)));
        org.mockito.Mockito.when(isolated.policyOf(SafePrice.class)).thenReturn(custom);
        assertThat(new RegisterReportReadPolicy(factory, isolated).violations(Set.of(SafePrice.class)))
            .anyMatch(e -> e.contains("REGISTER_SLICE_VISIBILITY"));
    }

    private VisualQueryPackage slice(String entity) {
        return RegisterSliceCteSpike.slice(entity, DAY.plusDays(1), false, null, null);
    }
    private void run(VisualQueryPackage pack) {
        var template = new ReportTemplate();
        template.setQuerySource(ReportQuerySource.VISUAL);
        template.setVisualQueryJson(new VisualQueryPackageJsonCodec().write(pack));
        run.run(template, ReportContext.empty(subject), Map.of(), "ru", "Europe/Moscow");
    }
    private void grantType(Class<?> type) {
        grant(C5PermissionKeys.entityRead(type), null);
        for (var attribute : factory.getMetamodel().entity(type).getAttributes()) {
            grant(C5PermissionKeys.attributeRead(type, attribute.getName()), null);
        }
    }
    private void grant(String key, Long id) {
        var grant = new AccessGrant();
        grant.setSubjectType(AccessGrant.SubjectType.USER);
        grant.setSubjectKey(subject);
        grant.setDimension(key);
        grant.setDimensionValueId(id);
        grant.setCanRead(true);
        em.persist(grant);
    }
    private void flushed() { em.flush(); em.clear(); sql.clear(); }
    private void assertNoPriceSql(String table) {
        assertThat(sql).noneMatch(s -> s.toLowerCase(Locale.ROOT).contains(table));
    }
    private static int occurrences(String text, String fragment) {
        return (text.length() - text.replace(fragment, "").length()) / fragment.length();
    }
    private void item(long id) {
        var item = new Item(); item.id = id; item.code = "item-" + id; em.persist(item);
    }
    private void price(long id, long item, LocalDate date, String value, boolean unsafe) {
        PriceFields price = unsafe ? new UnsafePrice() : new SafePrice();
        price.id = id; price.nomenclature = em.getReference(Item.class, item);
        price.period = date; price.price = new BigDecimal(value); em.persist(price);
    }

    private static final class RecordingRenderer implements ReportCompiler {
        ReportDataset dataset;
        @Override public JasperPrint compile(ReportTemplate template, ReportDataset dataset) {
            this.dataset = dataset;
            return new JasperPrint();
        }
        @Override public byte[] export(JasperPrint print, ReportExportFormat format) { return new byte[0]; }
    }

    @Entity(name = "R3Item") @Table(name = "r3_item")
    @EntityMetadata(listFormTitle = "R3 items")
    @RlsDimension(value = "SLICE_ITEMS", valuePaths = "id", grantValues = true)
    @FilterDef(name = "SLICE_ITEMS", parameters = @ParamDef(name = "allowedIds", type = Long.class))
    @Filter(name = "SLICE_ITEMS", condition = "id in (:allowedIds)")
    public static class Item {
        @Id Long id;
        @FieldMetadata(label = "Code") String code;
        public Long getId() { return id; }
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
    }

    @MappedSuperclass
    public static class PriceFields {
        @Id Long id;
        @ManyToOne(optional = false) @JoinColumn(name = "item_id", nullable = false, updatable = false)
        @FieldMetadata(label = "Item") Item nomenclature;
        @Column(name = "period_date", nullable = false, updatable = false)
        @FieldMetadata(label = "Period") LocalDate period;
        @Column(name = "price", nullable = false, precision = 19, scale = 4)
        @FieldMetadata(label = "Price") BigDecimal price;
        public Long getId() { return id; }
        public Item getNomenclature() { return nomenclature; }
        public void setNomenclature(Item value) { nomenclature = value; }
        public LocalDate getPeriod() { return period; }
        public void setPeriod(LocalDate value) { period = value; }
        public BigDecimal getPrice() { return price; }
        public void setPrice(BigDecimal value) { price = value; }
    }

    @Entity(name = "R3SafePrice")
    @Table(name = "r3_safe_price", uniqueConstraints = @UniqueConstraint(columnNames = {"item_id", "period_date"}))
    @EntityMetadata(listFormTitle = "R3 safe prices")
    @RegisterMetadata(dimensions = "nomenclature", period = "period", resources = "price")
    @RlsDimension(value = "SLICE_ITEMS", valuePaths = "nomenclature.id")
    @FilterDef(name = "SLICE_ITEMS", parameters = @ParamDef(name = "allowedIds", type = Long.class))
    @Filter(name = "SLICE_ITEMS", condition = "item_id in (:allowedIds)")
    public static class SafePrice extends PriceFields { }

    @Entity(name = "R3UnsafePrice")
    @Table(name = "r3_unsafe_price", uniqueConstraints = @UniqueConstraint(columnNames = {"item_id", "period_date"}))
    @EntityMetadata(listFormTitle = "R3 unsafe prices")
    @RegisterMetadata(dimensions = "nomenclature", period = "period", resources = "price")
    @RlsDimension(value = "SLICE_ROWS", valuePaths = "id")
    @FilterDef(name = "SLICE_ROWS", parameters = @ParamDef(name = "allowedIds", type = Long.class))
    @Filter(name = "SLICE_ROWS", condition = "id in (:allowedIds)")
    public static class UnsafePrice extends PriceFields { }
}
