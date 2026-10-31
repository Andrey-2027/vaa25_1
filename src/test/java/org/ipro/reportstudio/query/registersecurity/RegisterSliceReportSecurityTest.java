package org.ipro.query.registersecurity;

import org.ipro.query.ReportContext;
import org.ipro.query.register.RegisterReportReadPolicy;
import org.ip.application.register.RegisterSliceCteSpike;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import org.hibernate.SessionFactory;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.hibernate.cfg.Configuration;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.ipro.register.RegisterMetadata;
import org.ipro.register.PeriodicRecordedRegisterEntryBase;
import org.ipro.register.RecorderType;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.EntityKind;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.report.data.ReportDataset;
import org.ipro.query.model.QueryDefinition;
import org.ipro.query.model.QueryParameter;
import org.ipro.query.model.QuerySource;
import org.ipro.query.param.*;
import org.ipro.query.*;
import org.ipro.query.sqm.SqmQuerySemanticAnalyzer;
import org.ipro.report.render.*;
import org.ipro.rls.*;
import org.ipro.rls.c5.*;
import org.junit.jupiter.api.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/** Р3: реальные grants/evaluator/RLS и run → assembly → guard → executor; dataset проверяется без рендера. */
class RegisterSliceReportSecurityTest {
    private static final LocalDateTime DAY = LocalDateTime.of(2026, 1, 1, 0, 0);
    private SessionFactory factory;
    private EntityManager em;
    private RlsDimensionRegistry registry;
    private org.ipro.register.RegisterRegistry registers;
    private AccessService access;
    private QueryBuilderMetadataCatalog catalog;
    private ReportReadAuthorizer authorizer;
    private ReportQueryGuard guard;
    private ReportQueryExecutor executor;
    private RlsFilterActivator activator;
    private RlsCurrentUser current;
    private ReportQueryRunService run;
    private ReportDataset dataset;
    private String subject;
    private final Map<String, List<String>> roles = new HashMap<>();
    private final List<String> sql = new ArrayList<>();

    @BeforeEach
    void setUp() {
        subject = "slice-user";
        var configuration = new Configuration()
            .addAnnotatedClass(Item.class).addAnnotatedClass(SafePrice.class)
            .addAnnotatedClass(UnsafePrice.class).addAnnotatedClass(UnfilteredPrice.class)
            .addAnnotatedClass(R3RecorderA.class).addAnnotatedClass(R3RecorderB.class)
            .addAnnotatedClass(RecordedPrice.class)
            .addAnnotatedClass(ReferencePrice.class)
            .addAnnotatedClass(AccessGrant.class)
            .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
            .setProperty("hibernate.connection.url", "jdbc:h2:mem:register-security-" + UUID.randomUUID() + ";NON_KEYWORDS=VALUE")
            .setProperty("hibernate.hbm2ddl.auto", "create-drop");
        configuration.setStatementInspector(statement -> { sql.add(statement); return statement; });
        factory = configuration.buildSessionFactory();
        em = factory.createEntityManager();
        em.getTransaction().begin();
        var grants = new JpaRepositoryFactory(em).getRepository(AccessGrantRepository.class);
        registry = new RlsDimensionRegistry("org.ipro.query.registersecurity");
        registry.rebuild();
        var managedEntities = new ManagedEntityCatalog(factory);
        var metadata = new MetadataResolver();
        var types = new org.ipro.metadata.ManagedEntityTypes(factory);
        var recorderTypes = new org.ipro.register.RecorderTypeRegistry(managedEntities, factory,
            metadata);
        registers = new org.ipro.register.RegisterRegistry(managedEntities, recorderTypes);
        var descriptors = new org.ipro.data.EntityDescriptorCatalog(managedEntities,
            new org.ipro.metadata.SectionMetadataRegistry(types, metadata), metadata,
            List.of(), List.of(), List.of(new org.ipro.register.RegisterClassificationContributor(registers)));
        current = () -> subject;
        access = new AccessService(grants, username -> roles.getOrDefault(username, List.of()), registry);
        var permissions = new DefaultC5PermissionEvaluator(grants, username -> roles.getOrDefault(username, List.of()));
        var readGate = new RlsReadGate(org.ipro.rls.RlsTestFixture.allowingC5ReadEvaluator(), registry);
        activator = new RlsFilterActivator(registry, new RlsReadableIdsCache(access), current);
        catalog = new QueryBuilderMetadataCatalog(types, metadata, readGate, current, descriptors);
        authorizer = new ReportReadAuthorizer(factory, permissions, current,
            List.of(new RegisterReportReadPolicy(factory, registry, registers)), access, registry);
        guard = new ReportQueryGuard(new SqmQuerySemanticAnalyzer(factory), access, registry, current,
            factory, authorizer);
        executor = new ReportQueryExecutor(em, activator, current, guard, authorizer);
        var refresher = new EntityParamRefresher(em, activator, readGate, current,
            factory.unwrap(SessionFactoryImplementor.class));
        var resolver = new ReportParamResolver(refresher, access, current,
            factory.unwrap(SessionFactoryImplementor.class), new ObjectMapper(), "SLICE_ITEMS");
        var assembler = new ReportQueryAssemblyService(guard, resolver, refresher, catalog);
        run = new ReportQueryRunService(assembler, executor);
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

    @Test void presentationMasksUnavailableReferenceWithoutDiscardingReadableOwner() {
        var value = new ReferencePrice(); value.id=950L; value.code="visible"; value.period=DAY; value.value=em.getReference(Item.class,1L); em.persist(value);
        grantType(ReferencePrice.class); flushed();
        var recipes = new org.ipro.query.register.RegisterSliceReportTemplates(factory,registers,catalog,new org.ipro.query.register.RegisterSliceCteGenerator());
        var parsed = new ReportQueryLanguage(catalog,recipes).parse("select p.code as code, Представление(p.value) as label from R8SecurityReferencePrice p");
        assertThat(guard.guard(parsed.compiled().jpql(),Set.of()).allowed()).isTrue();
        var reads = org.mockito.Mockito.mock(org.ipro.data.EntityReadAccess.class); var policies=org.mockito.Mockito.mock(org.ipro.data.EntityDataAccessResolver.class); var names=org.mockito.Mockito.mock(org.ipro.fetch.instance.InstanceNameResolver.class);
        org.mockito.Mockito.when(names.instanceNamePaths(Item.class)).thenReturn(List.of("code"));
        org.mockito.Mockito.when(reads.list(org.mockito.ArgumentMatchers.eq(Item.class),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenThrow(new org.springframework.security.access.AccessDeniedException("denied"));
        executor = new ReportQueryExecutor(em, activator, current, guard, authorizer,
            new org.ipro.query.QueryResultLabelPresenter(
                new org.ipro.data.presentation.EntityPresentationService(reads,policies,names,new MetadataResolver()),
                new org.ipro.register.RecorderTypeRegistry(new ManagedEntityCatalog(factory),factory,new MetadataResolver())));
        var rows = executor.execute(parsed.compiled(),100,30000).rows();
        assertThat(rows).singleElement().satisfies(row -> { assertThat(row.value("code")).isEqualTo("visible"); assertThat(row.value("label")).isEqualTo("Объект недоступен"); });
        // Original association projection still requires its target's C5/RLS checks.
        assertThat(authorizer.violations("select p.value.id as id from R8SecurityReferencePrice p",Map.of())).isNotEmpty();
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
        assertThat(dataset).isNull();
    }

    @Test void referenceResourceRequiresEntityReadBeforeDataSql() {
        grantType(ReferencePrice.class); grant("SLICE_ITEMS", 1L); flushed();
        var query = new org.ipro.query.register.RegisterSliceReportTemplates(factory, registers, catalog,
            new org.ipro.query.register.RegisterSliceCteGenerator()).createSliceQuery(
                org.ipro.register.SliceSpec.latest(ReferencePrice.class, DAY), "asOf");
        assertThat(guard.guard(query.jpql(), Set.of("asOf")).errors())
            .anyMatch(error -> error.contains("ENTITY-read") && error.contains(Item.class.getName()));
        assertThat(sql).noneMatch(statement -> statement.toLowerCase(Locale.ROOT).startsWith("select")
            && statement.toLowerCase(Locale.ROOT).contains("r8_reference_price"));
    }

    @Test void sourcePickerChecksRegisterAndReferenceEntityRights() {
        grant("SLICE_ITEMS", 1L); flushed();
        var grants = new JpaRepositoryFactory(em).getRepository(AccessGrantRepository.class);
        var recipe = new org.ipro.query.register.RegisterSliceReportTemplates(factory, registers, catalog,
            new org.ipro.query.register.RegisterSliceCteGenerator(), new DefaultC5PermissionEvaluator(grants, username -> List.of()), () -> subject);
        assertThat(recipe.sliceSources()).noneMatch(entity -> entity.javaType() == ReferencePrice.class);
        grantType(ReferencePrice.class); flushed();
        assertThat(recipe.sliceSources()).anyMatch(entity -> entity.javaType() == ReferencePrice.class);
        assertThatThrownBy(() -> recipe.sliceFilters(ReferencePrice.class, true)).hasMessageContaining("Нет права чтения");
        grantType(Item.class); flushed();
        assertThat(recipe.sliceFilters(ReferencePrice.class, true)).extracting(org.ipro.query.register.RegisterSliceReportTemplates.FilterField::name)
            .containsExactly("value", "period");
    }

    @Test void referenceResourceRlsDoesNotFallBackToAnOlderVisibleValue() {
        grantType(ReferencePrice.class); grantType(Item.class); grant("SLICE_ITEMS", 1L);
        var old = new ReferencePrice(); old.id = 1L; old.code = "sku"; old.period = DAY;
        old.value = em.getReference(Item.class, 1L); em.persist(old);
        var latest = new ReferencePrice(); latest.id = 2L; latest.code = "sku"; latest.period = DAY.plusDays(1);
        latest.value = em.getReference(Item.class, 2L); em.persist(latest); flushed();
        var query = new org.ipro.query.register.RegisterSliceReportTemplates(factory, registers, catalog,
            new org.ipro.query.register.RegisterSliceCteGenerator()).createSliceQuery(
                org.ipro.register.SliceSpec.latest(ReferencePrice.class, DAY), "asOf");
        var compiled = VisualQueryCompiler.compile(new VisualQueryPackageJsonCodec().read(query.visualQueryJson()), catalog);
        var bindings = new HashMap<>(compiled.bindings()); bindings.put("asOf", DAY.plusDays(1));
        assertThat(executor.execute(compiled.jpql(), bindings, compiled.fields(), 100, 30000).rowCount()).isZero();
        bindings.put("asOf", DAY);
        assertThat(executor.execute(compiled.jpql(), bindings, compiled.fields(), 100, 30000).rows()[0].value("value")).isEqualTo(1L);
    }

    @Test
    void c5EntityReadAndRlsAreEnoughWithoutAttributeGrants() {
        grantType(Item.class);
        grant(C5PermissionKeys.entityRead(SafePrice.class), null);
        grant("SLICE_ITEMS", 1L);
        flushed();
        run(slice("R3SafePrice"));
        assertThat(dataset.rowCount()).isEqualTo(1);
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
        assertThat(dataset.rowCount()).isEqualTo(1);
        assertThat(dataset.rows()[0].value("nomenclatureId")).isEqualTo(1L);
        assertThat(dataset.rows()[0].value("period")).isEqualTo(DAY.plusDays(1));
        assertThat((BigDecimal) dataset.rows()[0].value("price")).isEqualByComparingTo("90");
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
        assertThat(dataset.rows()[0].value("total")).isEqualTo(1L);
    }

    @Test
    void recordedSliceKeepsGroupVisibilityAndWinnerIndependentOfRecorderDocumentC5() {
        // Same numeric document id across types; latest row is from B. The second group is hidden.
        recordedPrice(1, 501, "r3-recorder-a", 1, DAY, 100);
        recordedPrice(1, 501, "r3-recorder-b", 2, DAY, 200);
        recordedPrice(2, 502, "r3-recorder-a", 3, DAY.plusDays(1), 999);
        grant(C5PermissionKeys.entityRead(RecordedPrice.class), null);
        grant("R3_RECORDED_ITEMS", 1L);
        flushed();

        var source = org.ipro.query.register.RegisterSliceSource.resolve(
            RecordedPrice.class, registers, factory.getMetamodel(), catalog);
        var fragment = new org.ipro.query.register.RegisterSliceCteGenerator().generate(source,
            org.ipro.register.SliceSpec.latest(RecordedPrice.class, DAY.plusDays(1)),
            org.ipro.query.register.RegisterSliceCteGenerator.NameContext.empty());
        var compiled = fragment.compile(catalog);
        assertThat(compiled.jpql()).doesNotContain(R3RecorderA.class.getSimpleName(),
            R3RecorderB.class.getSimpleName());
        assertThat(authorizer.violations(compiled.jpql(), Map.of())).isEmpty();

        run(fragment.asPackage());

        assertThat(dataset.rowCount()).isEqualTo(1);
        var row = dataset.rows()[0];
        assertThat(row.value(fragment.columnNames().get("itemId"))).isEqualTo(1L);
        assertThat(row.value(fragment.columnNames().get("recorder.typeCode"))).isEqualTo("r3-recorder-b");
        assertThat(row.value(fragment.columnNames().get("recorder.id"))).isEqualTo(501L);
        assertThat(row.value(fragment.columnNames().get("amount"))).isEqualTo(200);
        String executed = sql.stream().filter(statement -> statement.toLowerCase(Locale.ROOT)
            .contains("r3_recorded_price") && statement.stripLeading().toLowerCase(Locale.ROOT).startsWith("select"))
            .findFirst().orElseThrow().toLowerCase(Locale.ROOT);
        assertThat(occurrences(executed, "r3_recorded_price")).isGreaterThanOrEqualTo(4);
        assertThat(occurrences(executed, "item_id in")).isGreaterThanOrEqualTo(4);
    }

    @Test
    void recorderSliceRunsAtSubMillisecondBoundaryWithoutDocumentReadGrant() {
        // H2's timestamp column uses microsecond resolution, which is also PostgreSQL's default.
        // The chosen instant is a representable storage tick finer than the old millisecond UI.
        LocalDateTime exactPeriod = DAY.plusNanos(123_457_000);
        recordedPrice(1, 701, "r3-recorder-a", 1, DAY, 100);
        recordedPrice(1, 702, "r3-recorder-b", 2, exactPeriod, 200);
        grantType(RecordedPrice.class);
        grant("R3_RECORDED_ITEMS", 1L);
        flushed();
        LocalDateTime storedPeriod = (LocalDateTime) em.createNativeQuery(
                "select period_date from r3_recorded_price where recorder_id = 702")
            .getSingleResult();
        assertThat(storedPeriod).isEqualTo(exactPeriod);

        var source = org.ipro.query.register.RegisterSliceSource.resolve(
            RecordedPrice.class, registers, factory.getMetamodel(), catalog);
        var fragment = new org.ipro.query.register.RegisterSliceCteGenerator()
            .generateParameterized(source,
                org.ipro.register.SliceSpec.latest(RecordedPrice.class, exactPeriod),
                org.ipro.query.register.RegisterSliceCteGenerator.NameContext.empty(), "asOf");
        var pack = fragment.asPackage();
        var definition = withDateParameter(visual(pack), "asOf");
        assertThat(new VisualQueryPackageJsonCodec().read(definition.visualQueryJson())).isEqualTo(pack);
        assertThat(authorizer.violations(definition.jpql(), Map.of())).isEmpty();

        execute(definition, Map.of("asOf", exactPeriod));

        assertThat(dataset.rowCount()).isEqualTo(1);
        var row = dataset.rows()[0];
        assertThat(row.value(fragment.columnNames().get("period"))).isEqualTo(exactPeriod);
        assertThat(row.value(fragment.columnNames().get("amount"))).isEqualTo(200);
        assertThat(sql).noneMatch(statement -> statement.toLowerCase(Locale.ROOT)
            .contains("r3_recorder_a") || statement.toLowerCase(Locale.ROOT).contains("r3_recorder_b"));
    }

    @Test
    void parameterizedSavedSliceUsesCurrentSubjectAndRequiredDateThroughReportResolver() {
        for (var role : List.of("price-reader-a", "price-reader-b")) {
            for (var type : List.of(Item.class, SafePrice.class)) {
                roleGrant(role, C5PermissionKeys.entityRead(type), null);
            }
            roleGrant(role, "SLICE_ITEMS", role.endsWith("a") ? 1L : 2L);
        }
        roles.put("reader-a", List.of("price-reader-a")); roles.put("reader-b", List.of("price-reader-b"));
        subject = "reader-a"; flushed();
        var source = org.ipro.query.register.RegisterSliceSource.resolve(
            SafePrice.class, registers, factory.getMetamodel(), catalog);
        var fragment = new org.ipro.query.register.RegisterSliceCteGenerator().generateParameterized(source,
            org.ipro.register.SliceSpec.latest(SafePrice.class, DAY),
            org.ipro.query.register.RegisterSliceCteGenerator.NameContext.empty(), "asOf");
        var definition = withDateParameter(visual(fragment.asPackage()), "asOf");
        execute(definition, Map.of("asOf", DAY));
        assertThat(dataset.rowCount()).isEqualTo(1);
        assertThat((BigDecimal) dataset.rows()[0].value(fragment.columnNames().get("price")))
            .isEqualByComparingTo("150");
        execute(definition, Map.of("asOf", DAY.plusDays(1)));
        assertThat(dataset.rowCount()).isEqualTo(1);
        assertThat((BigDecimal) dataset.rows()[0].value(fragment.columnNames().get("price")))
            .isEqualByComparingTo("90");
        subject = "reader-b";
        execute(definition, Map.of("asOf", DAY.plusDays(1)));
        assertThat(dataset.rowCount()).isEqualTo(1);
        assertThat(dataset.rows()[0].value(fragment.columnNames().get("nomenclature"))).isEqualTo(2L);
        assertThat((BigDecimal) dataset.rows()[0].value(fragment.columnNames().get("price")))
            .isEqualByComparingTo("190");
        sql.clear();
        assertThatThrownBy(() -> execute(definition, Map.of()))
            .hasMessageContaining("asOf");
        assertNoPriceSql("r3_safe_price");
        assertThatThrownBy(() -> execute(definition, Map.of("asOf", "2026-01-01")))
            .hasMessageContaining("LocalDateTime");
        assertNoPriceSql("r3_safe_price");
        subject = "no-grants"; sql.clear();
        assertThatThrownBy(() -> execute(definition, Map.of("asOf", DAY)))
            .hasMessageContaining("Нет доступа");
        assertNoPriceSql("r3_safe_price");
    }

    @Test
    void generatedFragmentUsesRealReportAuthorizationAndCannotResurrectOldResourceValue() {
        grantType(Item.class); grantType(SafePrice.class); grant("SLICE_ITEMS", 1L); flushed();
        var source = org.ipro.query.register.RegisterSliceSource.resolve(
            SafePrice.class, registers, factory.getMetamodel(), catalog);
        var generator = new org.ipro.query.register.RegisterSliceCteGenerator();
        var context = org.ipro.query.register.RegisterSliceCteGenerator.NameContext.empty();
        var fragment = generator.generate(source, org.ipro.register.SliceSpec.latest(SafePrice.class, DAY.plusDays(1)), context);
        run(fragment.asPackage());
        assertThat(dataset.rowCount()).isEqualTo(1);
        assertThat(dataset.rows()[0].value(fragment.columnNames().get("nomenclature"))).isEqualTo(1L);
        assertThat((BigDecimal) dataset.rows()[0].value(fragment.columnNames().get("price"))).isEqualByComparingTo("90");
        var filtered = new org.ipro.register.SliceSpec(SafePrice.class, org.ipro.register.SliceSpec.Mode.LATEST,
            DAY.plusDays(1), List.of(), List.of(new org.ipro.register.SliceSpec.Condition("price",
                org.ipro.register.SliceSpec.Operator.GT, new BigDecimal("100"))));
        run(generator.generate(source, filtered, context).asPackage());
        assertThat(dataset.rowCount()).isZero();
        subject = "no-grants"; sql.clear();
        assertThatThrownBy(() -> run(fragment.asPackage())).hasMessageContaining("Нет доступа");
        assertNoPriceSql("r3_safe_price");
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
        assertThat(dataset.rows()[0].value("nomenclatureId")).isEqualTo(1L);
        subject = "slice-second";
        run(slice("R3SafePrice"));
        assertThat(dataset.rowCount()).isEqualTo(1);
        assertThat(dataset.rows()[0].value("nomenclatureId")).isEqualTo(2L);
        subject = "slice-user";
        run(slice("R3SafePrice"));
        assertThat(dataset.rows()[0].value("nomenclatureId")).isEqualTo(1L);
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
        assertThat(dataset).isNull();
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
        assertThat(dataset.rowCount()).isEqualTo(2);
        var revoked = em.createQuery("select g from AccessGrant g where g.subjectKey = :user "
            + "and g.dimension = 'SLICE_ITEMS' and g.dimensionValueId = 1", AccessGrant.class)
            .setParameter("user", subject).getSingleResult();
        em.remove(revoked);
        flushed();
        run(slice("R3SafePrice"));
        assertThat(dataset.rowCount()).isEqualTo(1);
        assertThat(dataset.rows()[0].value("nomenclatureId")).isEqualTo(2L);
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
            assertThat(new RegisterReportReadPolicy(factory, isolated, registers).violations(Set.of(SafePrice.class)))
                .as("unsupported path %s", path).anyMatch(e -> e.contains("REGISTER_SLICE_VISIBILITY"));
        }
        var custom = new RlsPolicyDescriptor(SafePrice.class, true,
            Map.of("TEST", RlsDimensionKind.FILTERABLE),
            Map.of("TEST", new RlsPolicyDescriptor.ValueRule(List.of("nomenclature.id"), false, true)));
        org.mockito.Mockito.when(isolated.policyOf(SafePrice.class)).thenReturn(custom);
        assertThat(new RegisterReportReadPolicy(factory, isolated, registers).violations(Set.of(SafePrice.class)))
            .anyMatch(e -> e.contains("REGISTER_SLICE_VISIBILITY"));
    }

    @Test
    void targetVisibilityRemovesHiddenGroupsAndLeftJoinKeepsVisibleItemsWithoutHistory() {
        item(3); // Visible, without any price.
        for (long id : List.of(1L, 2L)) {
            var price = new UnfilteredPrice();
            price.id = 300 + id;
            price.nomenclature = em.getReference(Item.class, id);
            price.period = DAY;
            price.price = new BigDecimal(id == 1 ? "90" : "190");
            em.persist(price);
        }
        grantType(Item.class); grantType(UnfilteredPrice.class);
        grant("SLICE_ITEMS", 1L); grant("SLICE_ITEMS", 3L);
        flushed();
        run(slice("R3UnfilteredPrice"));
        assertThat(dataset.rowCount()).isEqualTo(1);
        assertThat(dataset.rows()[0].value("nomenclatureId")).isEqualTo(1L);
        run(RegisterSliceCteSpike.report(slice("R3UnfilteredPrice"), "R3Item", null, null));
        assertThat(dataset.rowCount()).isEqualTo(2);
        var rows = Arrays.stream(dataset.rows()).collect(java.util.stream.Collectors.toMap(
            row -> row.value("nomenclatureId"), row -> row));
        assertThat(rows.keySet()).containsExactlyInAnyOrder(1L, 3L);
        assertThat(rows.get(3L).value("price")).isNull();
        assertThat((BigDecimal) rows.get(1L).value("price")).isEqualByComparingTo("90");
    }

    @Test
    void filteredAssociationCannotBeReadAsAForeignKeyWithoutAnExplicitJoin() {
        grantType(Item.class); grantType(UnfilteredPrice.class);
        grant("SLICE_ITEMS", 1L);
        flushed();
        assertThat(authorizer.violations(
            "select p.nomenclature.id, p.price from R3UnfilteredPrice p", Map.of()))
            .anyMatch(error -> error.contains("RLS_ASSOCIATION_JOIN_REQUIRED"));
        assertThat(authorizer.violations(
            "select i.id, p.price from R3UnfilteredPrice p join p.nomenclature i", Map.of()))
            .anyMatch(error -> error.contains("RLS_ASSOCIATION_JOIN_REQUIRED"));
        // Mere presence of a target alias, a LEFT join or an OR cannot establish target visibility.
        for (String join : List.of(
            "join R3Item i on p.period = :day",
            "left join R3Item i on p.nomenclature.id = i.id",
            "join R3Item i on p.nomenclature.id = i.id or i.id = 1")) {
            assertThat(authorizer.violations(
                "select p.nomenclature.id, p.price from R3UnfilteredPrice p " + join, Map.of()))
                .as("unsafe join %s", join)
                .anyMatch(error -> error.contains("RLS_ASSOCIATION_JOIN_REQUIRED"));
        }
        assertNoPriceSql("r3_unfiltered_price");
    }

    @Test
    void reportQueriesExplicitlyBypassResultAndEntityCaches() {
        grantType(Item.class); grantType(SafePrice.class);
        grant("SLICE_ITEMS", 1L);
        flushed();
        var compiled = VisualQueryCompiler.compile(slice("R3SafePrice"), catalog);
        var checked = guard.guard(compiled.jpql(), compiled.bindings().keySet());
        EntityManager observed = org.mockito.Mockito.spy(em);
        List<org.hibernate.query.Query<?>> queries = new ArrayList<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            var query = em.createQuery(invocation.getArgument(0, String.class), Tuple.class);
            queries.add(query.unwrap(org.hibernate.query.Query.class));
            return query;
        }).when(observed).createQuery(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq(Tuple.class));
        var current = (RlsCurrentUser) () -> subject;
        var protectedExecutor = new ReportQueryExecutor(observed,
            new RlsFilterActivator(registry, new RlsReadableIdsCache(access), current), current, guard, authorizer);
        protectedExecutor.execute(compiled.jpql(), compiled.bindings(), checked.selectFields(), 100, 1000);
        assertThat(queries).singleElement().satisfies(query -> {
            assertThat(query.isCacheable()).isFalse();
            assertThat(query.getCacheMode()).isEqualTo(org.hibernate.CacheMode.IGNORE);
        });
    }

    private VisualQueryPackage slice(String entity) {
        return RegisterSliceCteSpike.slice(entity, "R3Item", DAY.plusDays(1), false, null, null);
    }
    private void run(VisualQueryPackage pack) {
        execute(visual(pack), Map.of());
    }

    private QueryDefinition visual(VisualQueryPackage pack) {
        return new QueryDefinition(VisualQueryCompiler.compile(pack, catalog).jpql(), QuerySource.VISUAL,
            new VisualQueryPackageJsonCodec().write(pack), null, List.of());
    }

    private QueryDefinition withDateParameter(QueryDefinition definition, String name) {
        var parameters = new ArrayList<>(definition.parameters());
        parameters.add(QueryParameter.required(name).withCaption("Момент среза"));
        return definition.withParameters(parameters);
    }

    private ReportDataset execute(QueryDefinition definition, Map<String, Object> values) {
        dataset = run.run(definition, ReportContext.empty(subject), values, List.of(), List.of()).dataset();
        return dataset;
    }
    private void grantType(Class<?> type) {
        grant(C5PermissionKeys.entityRead(type), null);
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
    private void roleGrant(String role, String key, Long id) {
        var grant = new AccessGrant(); grant.setSubjectType(AccessGrant.SubjectType.ROLE);
        grant.setSubjectKey(role); grant.setDimension(key); grant.setDimensionValueId(id); grant.setCanRead(true);
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
    private void price(long id, long item, LocalDateTime date, String value, boolean unsafe) {
        PriceFields price = unsafe ? new UnsafePrice() : new SafePrice();
        price.id = id; price.nomenclature = em.getReference(Item.class, item);
        price.period = date; price.price = new BigDecimal(value); em.persist(price);
    }

    private void recordedPrice(long itemId, long recorderId, String recorderType, long order,
                               LocalDateTime period, int amount) {
        em.createNativeQuery("insert into r3_recorded_price "
                + "(item_id, recorder_type, recorder_id, line_no, recording_order, period_date, amount) "
                + "values (:item, :type, :recorder, 1, :orderValue, :periodValue, :amountValue)")
            .setParameter("item", itemId).setParameter("type", recorderType)
            .setParameter("recorder", recorderId).setParameter("orderValue", order)
            .setParameter("periodValue", period).setParameter("amountValue", amount)
            .executeUpdate();
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

    @Entity(name="R8SecurityReferencePrice") @Table(name="r8_reference_price",
        uniqueConstraints=@UniqueConstraint(columnNames={"code", "period_date"}))
    @EntityMetadata(listFormTitle="Reference resources")
    @RegisterMetadata(dimensions="code", period="period", resources="value")
    public static class ReferencePrice {
        @Id Long id;
        @Column(name="code", nullable=false, updatable=false) @FieldMetadata(label="Code") String code;
        @Column(name="period_date", nullable=false, updatable=false) @FieldMetadata(label="Period") LocalDateTime period;
        @ManyToOne(optional=false) @JoinColumn(name="value_id", nullable=false) @FieldMetadata(label="Value") Item value;
    }

    @MappedSuperclass
    public static class PriceFields {
        @Id Long id;
        @ManyToOne(optional = false) @JoinColumn(name = "item_id", nullable = false, updatable = false)
        @FieldMetadata(label = "Item") Item nomenclature;
        @Column(name = "period_date", nullable = false, updatable = false)
        @FieldMetadata(label = "Period") LocalDateTime period;
        @Column(name = "price", nullable = false, precision = 19, scale = 4)
        @FieldMetadata(label = "Price") BigDecimal price;
        public Long getId() { return id; }
        public Item getNomenclature() { return nomenclature; }
        public void setNomenclature(Item value) { nomenclature = value; }
        public LocalDateTime getPeriod() { return period; }
        public void setPeriod(LocalDateTime value) { period = value; }
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

    /** Register itself has no row filter: only the target controls group visibility. */
    @Entity(name = "R3UnfilteredPrice")
    @Table(name = "r3_unfiltered_price", uniqueConstraints = @UniqueConstraint(columnNames = {"item_id", "period_date"}))
    @EntityMetadata(listFormTitle = "R3 target-filtered prices")
    @RegisterMetadata(dimensions = "nomenclature", period = "period", resources = "price")
    public static class UnfilteredPrice extends PriceFields { }

    @Entity(name = "R3UnsafePrice")
    @Table(name = "r3_unsafe_price", uniqueConstraints = @UniqueConstraint(columnNames = {"item_id", "period_date"}))
    @EntityMetadata(listFormTitle = "R3 unsafe prices")
    @RegisterMetadata(dimensions = "nomenclature", period = "period", resources = "price")
    @RlsDimension(value = "SLICE_ROWS", valuePaths = "id")
    @FilterDef(name = "SLICE_ROWS", parameters = @ParamDef(name = "allowedIds", type = Long.class))
    @Filter(name = "SLICE_ROWS", condition = "id in (:allowedIds)")
    public static class UnsafePrice extends PriceFields { }

    @Entity(name = "R3RecorderA") @Table(name = "r3_recorder_a")
    @EntityMetadata(kind = EntityKind.DOCUMENT, listFormTitle = "R3 recorder A")
    @RecorderType("r3-recorder-a")
    public static class R3RecorderA extends org.ipro.crud.BaseEntity { }

    @Entity(name = "R3RecorderB") @Table(name = "r3_recorder_b")
    @EntityMetadata(kind = EntityKind.DOCUMENT, listFormTitle = "R3 recorder B")
    @RecorderType("r3-recorder-b")
    public static class R3RecorderB extends org.ipro.crud.BaseEntity { }

    @Entity(name = "R3RecordedPrice") @Table(name = "r3_recorded_price",
        uniqueConstraints = @UniqueConstraint(columnNames = {"recorder_type", "recorder_id", "line_no"}))
    @EntityMetadata(listFormTitle = "R3 recorded prices")
    @RegisterMetadata(dimensions = "itemId", period = "period", resources = "amount",
        recorderTypes = {R3RecorderA.class, R3RecorderB.class})
    @RlsDimension(value = "R3_RECORDED_ITEMS", valuePaths = "itemId", grantValues = true)
    @FilterDef(name = "R3_RECORDED_ITEMS", parameters = @ParamDef(name = "allowedIds", type = Long.class))
    @Filter(name = "R3_RECORDED_ITEMS", condition = "item_id in (:allowedIds)")
    public static class RecordedPrice extends PeriodicRecordedRegisterEntryBase {
        @Column(name = "item_id", nullable = false, updatable = false)
        @FieldMetadata(label = "Item id") Long itemId;
        @Column(name = "amount", nullable = false)
        @FieldMetadata(label = "Amount") Integer amount;

        protected RecordedPrice() { }
    }
}
