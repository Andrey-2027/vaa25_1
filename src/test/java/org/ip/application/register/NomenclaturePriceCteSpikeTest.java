package org.ip.application.register;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.ip.Application;
import org.ip.config.DataInitializer;
import org.ip.model.Nomenclature;
import org.ip.model.NomenclaturePrice;
import org.ip.model.UnitOfMeasurement;
import org.ipro.filtergrid.filter.*;
import org.ipro.metadata.MetadataResolver;
import org.ipro.query.*;
import org.ipro.query.sqm.SqmQuerySemanticAnalyzer;
import org.ipro.rls.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Р2: production mapping, настоящий каталог и SQM/guard; не приёмка безопасности Р3. */
@SpringBootTest(classes = Application.class)
@Transactional
class NomenclaturePriceCteSpikeTest {
    private static final LocalDateTime START = LocalDateTime.of(2026, 1, 1, 0, 0);
    @MockitoBean DataInitializer initializer;
    @Autowired EntityManager em;
    @Autowired AccessGrantRepository grants;
    @Autowired org.ipro.data.EntityDataAccess writes;
    @Autowired AccountingObjectSaveService commands;
    @Autowired org.ipro.crud.MetadataDrivenAggregateSaveService aggregateSave;
    @Autowired EntityManagerFactory emf;
    @Autowired MetadataResolver metadata;
    @Autowired RlsReadGate readGate;
    @Autowired AccessService access;
    @Autowired RlsDimensionRegistry dimensions;
    @Autowired NomenclaturePriceService pointReader;
    @Autowired org.ipro.register.RegisterRegistry registers;
    @Autowired org.ipro.data.EntityDescriptorCatalog descriptors;
    @Autowired org.ipro.query.register.RegisterSliceCteGenerator sliceGenerator;
    @Autowired org.ipro.query.register.RegisterSliceReportTemplates sliceRecipes;
    @Autowired ReportQueryAssemblyService assembly;
    @Autowired ReportQueryExecutor executor;
    private QueryBuilderMetadataCatalog catalog;
    private ReportQueryGuard guard;
    private String username;
    private UnitOfMeasurement unit;

    @BeforeEach
    void setUp() {
        username = "ct" + UUID.randomUUID().toString().substring(0, 8);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(username, "unused", List.of()));
        for (Class<?> type : List.of(NomenclaturePrice.class, Nomenclature.class, UnitOfMeasurement.class)) {
            var grant = new AccessGrant();
            grant.setSubjectType(AccessGrant.SubjectType.USER);
            grant.setSubjectKey(username);
            grant.setDimension("ENTITY:" + type.getName());
            grant.setCanRead(true);
            em.persist(grant);
        }
        unit = new UnitOfMeasurement(username, "Штука", "ШТ");
        em.persist(unit);
        em.flush();
        catalog = new QueryBuilderMetadataCatalog(new org.ipro.metadata.ManagedEntityTypes(emf), metadata, readGate, () -> username, descriptors);
        guard = new ReportQueryGuard(new SqmQuerySemanticAnalyzer(emf), access, dimensions, () -> username, emf);
    }

    @AfterEach
    void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test void accountingObjectSliceUsesItsReferenceResourceAndOrdinarySavedPackage() {
        var gate = org.mockito.Mockito.mock(RlsReadGate.class);
        org.mockito.Mockito.when(gate.canRead(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        var allCatalog = new QueryBuilderMetadataCatalog(new org.ipro.metadata.ManagedEntityTypes(emf), metadata, gate, () -> username, descriptors);
        var recipe = new org.ipro.query.register.RegisterSliceReportTemplates(emf, registers, allCatalog, sliceGenerator);
        var fields = recipe.sliceFilters(org.ip.model.NomenclatureAccountingObject.class, true);
        assertThat(fields).anySatisfy(field -> {
            assertThat(field.name()).isEqualTo("accountingType");
            assertThat(field.javaType()).isEqualTo(Long.class);
            assertThat(field.referenceType()).isEqualTo(org.ip.model.AccountingType.class);
        });
        var query = recipe.createSliceQuery(org.ipro.register.SliceSpec.latest(org.ip.model.NomenclatureAccountingObject.class, START), "asOf");
        var pack = new VisualQueryPackageJsonCodec().read(query.visualQueryJson());
        assertThat(pack.ctes()).hasSize(5);
        assertThat(pack.ctes().get(pack.ctes().size() - 2).definition().joins()).anyMatch(join -> "AccountingType".equals(join.sourcePath()));
        var constructor = new org.ipro.query.ui.constructor.JpqlQueryConstructor(allCatalog, List.of());
        constructor.setPackage(pack);
        assertThat(constructor.queryPackage()).isEqualTo(pack);
        assertThat(constructor.compileError()).isNull();
    }

    @Test
    void sliceAndPointReaderMatchIndependentOracleIncludingEmptyHistoryAndBoundaryDates() {
        var random = new Random(1302);
        Map<Long, NavigableMap<LocalDateTime, BigDecimal>> histories = new LinkedHashMap<>();
        List<Nomenclature> items = new ArrayList<>();
        for (int itemIndex = 0; itemIndex < 4; itemIndex++) {
            var item = item(itemIndex);
            items.add(item);
            var history = new TreeMap<LocalDateTime, BigDecimal>();
            if (itemIndex < 3) {
                for (int day = 0; day <= 20; day += 2) {
                    var price = BigDecimal.valueOf(random.nextInt(20001), 2);
                    history.put(START.plusDays(day), price);
                    em.persist(new NomenclaturePrice(item, START.plusDays(day), price));
                }
            }
            histories.put(item.getId(), history);
        }
        em.flush();
        em.clear();
        var generatedSource = org.ipro.query.register.RegisterSliceSource.resolve(
            NomenclaturePrice.class, registers, emf.getMetamodel(), catalog);
        for (int offset = -1; offset <= 22; offset++) {
            LocalDateTime date = START.plusDays(offset);
            List<Object[]> rows = execute(report(slice(date, null)), date);
            assertThat(rows).hasSize(4);
            var generated = sliceGenerator.generate(generatedSource,
                org.ipro.register.SliceSpec.latest(NomenclaturePrice.class, date),
                org.ipro.query.register.RegisterSliceCteGenerator.NameContext.empty());
            var generatedRows = execute(generated.asPackage(), date);
            var generatedById = new HashMap<Long, Object[]>();
            generatedRows.forEach(row -> assertThat(generatedById.put(((Number) row[0]).longValue(), row)).isNull());
            assertThat(generatedById).hasSize((int) histories.values().stream().filter(history -> history.floorEntry(date) != null).count());
            for (Object[] row : rows) {
                long id = ((Number) row[0]).longValue();
                var expected = histories.get(id).floorEntry(date);
                assertThat(row[1]).isEqualTo(expected == null ? null : expected.getKey());
                if (expected == null) assertThat(row[2]).isNull();
                else assertThat((BigDecimal) row[2]).isEqualByComparingTo(expected.getValue());
                if (expected == null) assertThat(generatedById).doesNotContainKey(id);
                else {
                    assertThat(generatedById.get(id)[1]).isEqualTo(expected.getKey());
                    assertThat((BigDecimal) generatedById.get(id)[2]).isEqualByComparingTo(expected.getValue());
                }
            }
            for (Nomenclature item : items) {
                var expected = histories.get(item.getId()).floorEntry(date);
                var actual = pointReader.priceOn(item, date);
                assertThat(actual.isPresent()).isEqualTo(expected != null);
                actual.ifPresent(value -> assertThat(value).isEqualByComparingTo(expected.getValue()));
            }
        }
    }

    @Test
    void resourceFilterAfterLatestDoesNotResurrectAnOlderMatchingPrice() {
        var falling = item(0);
        var rising = item(1);
        item(2); // Без истории: должен остаться в LEFT JOIN.
        price(falling, START, "150");
        price(falling, START.plusDays(1), "90");
        price(rising, START, "80");
        price(rising, START.plusDays(1), "120");
        em.flush();
        var date = START.plusDays(1);
        var sourceFilter = RegisterSliceCteSpike.condition("p.price", FilterOperator.GT, "100", FilterDataType.NUMBER);
        var filteredSlice = slice(date, sourceFilter);
        var sliceRows = execute(filteredSlice, date);
        assertThat(sliceRows).hasSize(1);
        assertThat(sliceRows.getFirst()[0]).isEqualTo(rising.getId());
        assertThat((BigDecimal) sliceRows.getFirst()[2]).isEqualByComparingTo("120");
        var reportRows = execute(report(filteredSlice), date);
        assertThat(reportRows).hasSize(3);
        assertThat(reportRows.stream().filter(r -> Objects.equals(r[0], falling.getId())).findFirst().orElseThrow()[2])
            .isNull();
        var consumer = RegisterSliceCteSpike.condition("l.price", FilterOperator.GT, "100", FilterDataType.NUMBER);
        var outerFiltered = RegisterSliceCteSpike.report(slice(date, null), "Nomenclature", scope(), consumer);
        assertThat(execute(outerFiltered, date)).hasSize(1);
    }

    @Test
    void codecRoundTripPreservesPackageBindingsTypesAndExecutedRows() {
        var item = item(0);
        price(item, START, "0");
        em.flush();
        var pack = report(slice(START, null));
        var codec = new VisualQueryPackageJsonCodec();
        var restored = codec.read(codec.write(pack));
        assertThat(restored).isEqualTo(pack);
        var before = VisualQueryCompiler.compile(pack, catalog);
        var after = VisualQueryCompiler.compile(restored, catalog);
        assertThat(after).isEqualTo(before);
        var first = execute(pack, START);
        var second = execute(restored, START);
        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
        assertThat(second.getFirst()).containsExactly(first.getFirst());
        assertThat((BigDecimal) second.getFirst()[2]).isEqualByComparingTo("0");
    }

    @Test
    void realGuardRejectsMissingDateBindingAndSeesRegisterInsideCte() {
        var compiled = VisualQueryCompiler.compile(slice(START, null), catalog);
        var checked = guard.guard(compiled.jpql(), compiled.bindings().keySet());
        assertThat(checked.allowed()).as("%s", checked.errors()).isTrue();
        assertThat(checked.analysis().entities()).anyMatch(e -> e.entityName().contains("NomenclaturePrice"));
        assertThat(guard.guard(compiled.jpql(), Set.of()).allowed()).isFalse();
    }

    private VisualQueryPackage slice(LocalDateTime date, FilterNode resources) {
        return RegisterSliceCteSpike.slice("NomenclaturePrice", date, false, null, resources);
    }
    private VisualQueryPackage report(VisualQueryPackage slice) {
        return RegisterSliceCteSpike.report(slice, "Nomenclature", scope(), null);
    }
    private FilterNode scope() {
        return RegisterSliceCteSpike.condition("n.code", FilterOperator.STARTS_WITH, username, FilterDataType.TEXT);
    }
    private List<Object[]> execute(VisualQueryPackage pack, LocalDateTime date) {
        return RegisterSliceCteSpike.execute(em, catalog, guard, pack, date);
    }
    private Nomenclature item(int index) {
        var item = new Nomenclature(username + "-" + index, "CTE " + index, unit);
        em.persist(item);
        return item;
    }
    private void price(Nomenclature item, LocalDateTime date, String price) {
        em.persist(new NomenclaturePrice(item, date, new BigDecimal(price)));
    }
}
