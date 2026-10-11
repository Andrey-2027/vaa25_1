package org.ipro.reportstudio.query.register;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.ipro.filtergrid.filter.*;
import org.ipro.crud.BaseEntity;
import org.ipro.data.CanonicalReadExecutor;
import org.ipro.data.EntityCapabilities;
import org.ipro.data.EntityDescriptor;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.EntityExposure;
import org.ipro.data.ReadTelemetry;
import org.ipro.data.ScenarioFetchGraphResolver;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.EntityKind;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.register.*;
import org.ipro.reportstudio.query.*;
import org.ipro.rls.*;
import org.ipro.rls.c5.C5PermissionEvaluator;
import org.junit.jupiter.api.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Real metamodel/catalog/compiler/H2. Oracle is independent of both CTE phases. */
class RegisterSliceCteGeneratorTest {
    static final LocalDateTime DAY = LocalDateTime.of(2026, 1, 1, 0, 0);
    static SessionFactory factory;
    EntityManager em;
    RegisterRegistry registers;
    QueryBuilderMetadataCatalog catalog;
    RegisterSliceSource source;
    final RegisterSliceCteGenerator generator = new RegisterSliceCteGenerator();
    long sequence;

    @BeforeAll static void open() {
        factory = new Configuration().addAnnotatedClass(Item.class).addAnnotatedClass(Price.class)
            .addAnnotatedClass(OtherEntity.class).addAnnotatedClass(Aperiodic.class)
            .addAnnotatedClass(StringItem.class).addAnnotatedClass(StringPrice.class)
            .addAnnotatedClass(CollectionPrice.class).addAnnotatedClass(RepeatedPrice.class)
            .addAnnotatedClass(ReferencePrice.class)
            .addAnnotatedClass(RecorderDocumentA.class).addAnnotatedClass(RecorderDocumentB.class)
            .addAnnotatedClass(RecordedPrice.class)
            .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
            .setProperty("hibernate.connection.url", "jdbc:h2:mem:r5-" + UUID.randomUUID())
            .setProperty("hibernate.hbm2ddl.auto", "create-drop").setProperty("hibernate.hbm2ddl.halt_on_error", "true").buildSessionFactory();
    }
    @AfterAll static void close() { if (factory != null) factory.close(); }
    @BeforeEach void begin() {
        em = factory.createEntityManager(); em.getTransaction().begin();
        var managed = new ManagedEntityCatalog(factory);
        var recorderTypes = new RecorderTypeRegistry(managed, factory, new MetadataResolver());
        registers = new RegisterRegistry(managed, recorderTypes);
        var rls = new RlsDimensionRegistry("org.ipro.reportstudio.query.register"); rls.rebuild();
        var access = new AccessService(null, name -> List.of(), rls);
        catalog = new QueryBuilderMetadataCatalog(factory, new MetadataResolver(),
            new RlsReadGate(org.ipro.rls.RlsTestFixture.allowingC5ReadEvaluator(), rls), () -> "r5");
        source = RegisterSliceSource.resolve(Price.class, registers, factory.getMetamodel(), catalog);
    }
    @AfterEach void rollback() { em.getTransaction().rollback(); em.close(); }
    RegisterSliceCteGenerator.Fragment fragment(SliceSpec spec) {
        return generator.generate(source, spec, RegisterSliceCteGenerator.NameContext.empty());
    }
    List<Object[]> execute(VisualQueryPackage pack) {
        em.flush(); em.clear();
        var compiled = VisualQueryCompiler.compile(pack, catalog);
        var query = em.createQuery(compiled.jpql(), Object[].class);
        compiled.bindings().forEach(query::setParameter);
        return query.getResultList();
    }
    SliceSpec.Condition condition(String name, SliceSpec.Operator op, Object value) { return new SliceSpec.Condition(name, op, value); }
    SliceSpec spec(LocalDateTime date, List<SliceSpec.Condition> dims, List<SliceSpec.Condition> after) {
        return new SliceSpec(Price.class, SliceSpec.Mode.LATEST, date, dims, after);
    }

    @Test void editorRecipePreservesDateFiltersAndConstructorPackageOnReopen() {
        var item = item(1L);
        price(item, "retail", DAY, new BigDecimal("150"), 1);
        price(item, "retail", DAY.plusDays(1), new BigDecimal("90"), 2);
        var recipes = new RegisterSliceReportTemplates(factory, registers, catalog, generator);
        var template = recipes.createSliceQuery(spec(DAY.plusDays(1),
            List.of(condition("channel", SliceSpec.Operator.EQ, "retail")),
            List.of(condition("price", SliceSpec.Operator.GT, new BigDecimal("100")))), "asOf");
        var pack = new VisualQueryPackageJsonCodec().read(template.getVisualQueryJson());
        var constructor = new org.ipro.reportstudio.query.constructor.JpqlQueryConstructor(catalog, List.of());
        constructor.setPackage(pack);
        var reopened = constructor.queryPackage();
        assertThat(reopened).isEqualTo(pack);
        assertThat(constructor.compileError()).isNull();
        var compiled = VisualQueryCompiler.compile(reopened, catalog);
        em.flush(); em.clear();
        var query = em.createQuery(compiled.jpql(), Object[].class);
        compiled.bindings().forEach((name, value) -> query.setParameter(name, name.equals("asOf") ? DAY.plusDays(1) : value));
        assertThat(query.getResultList()).isEmpty(); // Latest 90 must not fall back to old 150.
        query.setParameter("asOf", DAY);
        assertThat(query.getResultList()).singleElement().satisfies(row -> assertThat((BigDecimal) row[3]).isEqualByComparingTo("150"));
        assertThat(RegisterSliceReportTemplates.dateTimeParameters(template)).containsExactly("asOf");
        assertThat(recipes.sliceFilters(Price.class, false)).extracting(RegisterSliceReportTemplates.FilterField::name)
            .containsExactly("item", "channel");
        assertThat(recipes.sliceFilters(Price.class, true)).extracting(RegisterSliceReportTemplates.FilterField::name)
            .containsExactly("price", "quantity", "period");
    }

    @Test void referenceResourceIsAReadOnlyScalarIdentityAndFiltersAfterWinner() {
        var oldValue = item(11L); var newValue = item(12L);
        var old = new ReferencePrice(); old.id = 101L; old.code = "sku"; old.period = DAY; old.value = oldValue; em.persist(old);
        var current = new ReferencePrice(); current.id = 102L; current.code = "sku"; current.period = DAY.plusDays(1); current.value = newValue; em.persist(current);
        var resolved = RegisterSliceSource.resolve(ReferencePrice.class, registers, factory.getMetamodel(), catalog);
        var spec = new SliceSpec(ReferencePrice.class, SliceSpec.Mode.LATEST, DAY.plusDays(1), List.of(),
            List.of(condition("value", SliceSpec.Operator.EQ, 11L)));
        assertThat(execute(generator.generate(resolved, spec, RegisterSliceCteGenerator.NameContext.empty()).asPackage())).isEmpty();
        var latest = generator.generate(resolved, SliceSpec.latest(ReferencePrice.class, DAY.plusDays(1)), RegisterSliceCteGenerator.NameContext.empty());
        assertThat(execute(latest.asPackage())).singleElement().satisfies(row -> assertThat(row[2]).isEqualTo(12L));
        assertThat(latest.ctes().getLast().definition().joins()).anyMatch(join -> "R5Item".equals(join.sourcePath()));
        assertThatThrownBy(() -> generator.generate(resolved, new SliceSpec(ReferencePrice.class, SliceSpec.Mode.LATEST,
            DAY, List.of(), List.of(condition("value", SliceSpec.Operator.GT, 11L))), RegisterSliceCteGenerator.NameContext.empty()))
            .hasMessageContaining("SLICE_OPERATOR");
    }

    @Test void timestampSliceDistinguishesMillisecondsAndKeepsInclusiveBoundary() {
        var item = item(1L);
        var first = DAY.plusHours(10).withNano(123000000);
        var second = first.plusNanos(1000000);
        price(item, "retail", first, new BigDecimal("150"), 1);
        price(item, "retail", second, new BigDecimal("90"), 2);
        assertThat(execute(fragment(SliceSpec.latest(Price.class, first.minusNanos(1000000))).asPackage())).isEmpty();
        assertThat(execute(fragment(SliceSpec.latest(Price.class, first)).asPackage())).singleElement().satisfies(row -> {
            assertThat(row[2]).isEqualTo(first);
            assertThat((BigDecimal) row[3]).isEqualByComparingTo("150");
        });
        assertThat(execute(fragment(SliceSpec.latest(Price.class, second)).asPackage())).singleElement().satisfies(row -> {
            assertThat(row[2]).isEqualTo(second);
            assertThat((BigDecimal) row[3]).isEqualByComparingTo("90");
        });
        var filtered = spec(second, List.of(), List.of(condition("period", SliceSpec.Operator.LT, second)));
        assertThat(execute(fragment(filtered).asPackage())).isEmpty();
    }

    @Test void literalTimestampBindingsUseMillisecondsAndNamedBoundaryDeclaresDateTime() {
        var boundary = DAY.plusHours(10).withNano(123456789);
        var literal = fragment(SliceSpec.latest(Price.class, boundary)).compile(catalog);
        assertThat(literal.bindings().values()).contains(boundary.withNano(123000000));
        var named = generator.generateParameterized(source, SliceSpec.latest(Price.class, boundary),
            RegisterSliceCteGenerator.NameContext.empty(), "asOf");
        assertThat(named.asPackage().main().parameters()).singleElement().satisfies(parameter -> {
            assertThat(parameter.dataType()).isEqualTo("DATETIME");
            assertThat(parameter.required()).isTrue();
            assertThat(parameter.defaultValue()).isNull();
        });
    }

    @Test void criteriaPointReaderDistinguishesMilliseconds() {
        var item = item(1L);
        var first = DAY.plusHours(10).withNano(123000000);
        var second = first.plusNanos(1000000);
        price(item, "retail", first, new BigDecimal("150"), 1);
        price(item, "retail", second, new BigDecimal("90"), 2);
        em.flush(); em.clear();
        var access = org.mockito.Mockito.mock(org.ipro.data.EntityDataAccess.class);
        org.mockito.Mockito.when(access.list(org.mockito.ArgumentMatchers.eq(Price.class),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(org.springframework.data.domain.Pageable.class)))
            .thenAnswer(invocation -> {
                org.springframework.data.jpa.domain.Specification<Price> specification = invocation.getArgument(1);
                org.springframework.data.domain.Pageable page = invocation.getArgument(2);
                var builder = em.getCriteriaBuilder();
                var query = builder.createQuery(Price.class); var root = query.from(Price.class);
                query.select(root).where(specification.toPredicate(root, query, builder));
                query.orderBy(page.getSort().stream().map(order -> order.isAscending()
                    ? builder.asc(root.get(order.getProperty())) : builder.desc(root.get(order.getProperty()))).toList());
                var result = em.createQuery(query).setFirstResult((int) page.getOffset())
                    .setMaxResults(page.getPageSize()).getResultList();
                return new org.springframework.data.domain.PageImpl<>(result, page, result.size());
            });
        var reader = new RegisterReader<Price>(Price.class, access);
        var dimensions = Map.<String, Object>of("item", em.getReference(Item.class, 1L), "channel", "retail");
        assertThat(reader.getLast(dimensions, first.minusNanos(1000000))).isEmpty();
        assertThat(reader.getLast(dimensions, first)).hasValueSatisfying(value -> {
            assertThat(value.period).isEqualTo(first);
            assertThat(value.price).isEqualByComparingTo("150");
        });
        assertThat(reader.getLast(dimensions, second)).hasValueSatisfying(value -> {
            assertThat(value.period).isEqualTo(second);
            assertThat(value.price).isEqualByComparingTo("90");
        });
    }

    @Test void generatedSliceMatchesOracleAcrossCompositeHistoriesAndMultipleResources() {
        var random = new Random(5501);
        Map<String, NavigableMap<LocalDateTime, Object[]>> histories = new LinkedHashMap<>();
        for (long id = 1; id <= 4; id++) {
            var item = item(id);
            for (String channel : List.of("retail", "wholesale")) {
                var history = new TreeMap<LocalDateTime, Object[]>();
                if (id != 4) for (int day = 0; day < 15; day++) if (random.nextBoolean()) {
                    var price = BigDecimal.valueOf(random.nextInt(20001), 2);
                    price(item, channel, DAY.plusDays(day), price, day);
                    history.put(DAY.plusDays(day), new Object[] {price, day});
                }
                histories.put(id + "/" + channel, history);
            }
        }
        for (int day = -1; day <= 16; day++) {
            var date = DAY.plusDays(day);
            var generated = fragment(SliceSpec.latest(Price.class, date));
            var rows = execute(generated.asPackage());
            Map<String, Object[]> actual = new LinkedHashMap<>();
            rows.forEach(row -> assertThat(actual.put(row[0] + "/" + row[1], row)).isNull());
            Set<String> expectedKeys = new LinkedHashSet<>();
            histories.forEach((key, history) -> {
                var expected = history.floorEntry(date);
                if (expected == null) assertThat(actual).doesNotContainKey(key);
                else {
                    expectedKeys.add(key);
                    assertThat(actual.get(key)[2]).isEqualTo(expected.getKey());
                    assertThat((BigDecimal) actual.get(key)[3]).isEqualByComparingTo((BigDecimal) expected.getValue()[0]);
                    assertThat(actual.get(key)[4]).isEqualTo(expected.getValue()[1]);
                }
            });
            assertThat(actual.keySet()).isEqualTo(expectedKeys);
            assertThat(generated.compile(catalog).fields()).extracting(field -> field.javaType())
                .isEqualTo(List.of(Long.class, String.class, LocalDateTime.class, BigDecimal.class, int.class));
        }
    }
    @Test void filtersReferenceIdentitiesBeforeMaxAndResourcesAndPeriodAfterMax() {
        var a = item(1); var b = item(2);
        price(a, "retail", DAY, new BigDecimal("150"), 1);
        price(a, "retail", DAY.plusDays(1), new BigDecimal("90"), 2);
        price(a, "wholesale", DAY, BigDecimal.ZERO, 3);
        price(b, "retail", DAY, new BigDecimal("300"), 4);
        var dims = List.of(condition("item", SliceSpec.Operator.EQ, 1L), condition("channel", SliceSpec.Operator.EQ, "retail"));
        assertThat(execute(fragment(spec(DAY.plusDays(1), dims, List.of())).asPackage())).singleElement()
            .satisfies(row -> assertThat((BigDecimal) row[3]).isEqualByComparingTo("90"));
        assertThat(execute(fragment(spec(DAY.plusDays(1), dims, List.of(condition("price", SliceSpec.Operator.GT, new BigDecimal("100"))))).asPackage())).isEmpty();
        assertThat(execute(fragment(spec(DAY.plusDays(1), dims, List.of(condition("period", SliceSpec.Operator.LT, DAY.plusDays(1))))).asPackage())).isEmpty();
        assertThat(execute(fragment(spec(DAY.plusDays(1), List.of(condition("channel", SliceSpec.Operator.EQ, "wholesale")), List.of())).asPackage())).singleElement()
            .satisfies(row -> assertThat((BigDecimal) row[3]).isEqualByComparingTo(BigDecimal.ZERO));
    }
    @Test void twoDatesComposeWithForeignCtesAndPreserveIndependentBindingsAndOriginalSpec() {
        var item = item(1); price(item, "retail", DAY, new BigDecimal("150"), 1);
        price(item, "retail", DAY.plusDays(1), new BigDecimal("90"), 2);
        var firstSpec = SliceSpec.latest(Price.class, DAY);
        var foreignDefinition = new VisualQueryDefinition("R5Item", "foreignRow", List.of(new VisualQueryDefinition.SelectField("id", "foreignId")));
        var foreign = new VisualQueryPackage(List.of(new VisualQueryPackage.Cte("rs1_mx", foreignDefinition)), foreignDefinition);
        var originalForeign = new VisualQueryPackageJsonCodec().write(foreign);
        var first = generator.generate(source, firstSpec, RegisterSliceCteGenerator.NameContext.from(foreign));
        var second = generator.generate(source, SliceSpec.latest(Price.class, DAY.plusDays(1)), first.nextContext());
        assertThat(first.latestName()).startsWith("rs2_"); assertThat(second.latestName()).startsWith("rs3_");
        String left = "oldValue", right = "newValue";
        var main = definition(first.latestName(), left, List.of(
            new VisualQueryDefinition.SelectField(left + "." + first.columnNames().get("price"), "oldPrice"),
            new VisualQueryDefinition.SelectField(right + "." + second.columnNames().get("price"), "newPrice")),
            List.of(new VisualQueryDefinition.Join(null, second.latestName(), right, VisualQueryDefinition.JoinKind.INNER,
                new JoinCondition.Predicate(left + "." + first.columnNames().get("item"), JoinCondition.Operator.EQ,
                    right + "." + second.columnNames().get("item")))));
        var composed = second.appendTo(first.appendTo(new VisualQueryPackage(foreign.ctes(), main)));
        var compiled = VisualQueryCompiler.compile(composed, catalog);
        assertThat(compiled.bindings().values()).containsExactlyInAnyOrder(DAY, DAY.plusDays(1));
        assertThat(execute(composed)).singleElement().satisfies(row -> {
            assertThat((BigDecimal) row[0]).isEqualByComparingTo("150"); assertThat((BigDecimal) row[1]).isEqualByComparingTo("90");
        });
        assertThat(firstSpec).isEqualTo(SliceSpec.latest(Price.class, DAY));
        assertThat(new VisualQueryPackageJsonCodec().write(foreign)).isEqualTo(originalForeign);
        var codec = new VisualQueryPackageJsonCodec();
        assertThat(VisualQueryCompiler.compile(codec.read(codec.write(composed)), catalog)).isEqualTo(compiled);
    }
    @Test void runtimeDatesComposeWithoutLiteralFallbackAndConflictingDeclarationsAreRejected() {
        var item = item(1); price(item, "retail", DAY, new BigDecimal("150"), 1);
        price(item, "retail", DAY.plusDays(1), new BigDecimal("90"), 2);
        var first = generator.generateParameterized(source, SliceSpec.latest(Price.class, LocalDateTime.MIN),
            RegisterSliceCteGenerator.NameContext.empty(), "oldDate");
        var second = generator.generateParameterized(source, SliceSpec.latest(Price.class, LocalDateTime.MAX),
            first.nextContext(), "newDate");
        var main = definition(first.latestName(), "oldRow", List.of(
            new VisualQueryDefinition.SelectField("oldRow." + first.columnNames().get("price"), "oldPrice"),
            new VisualQueryDefinition.SelectField("newRow." + second.columnNames().get("price"), "newPrice")),
            List.of(new VisualQueryDefinition.Join(null, second.latestName(), "newRow", VisualQueryDefinition.JoinKind.INNER,
                new JoinCondition.Predicate("oldRow." + first.columnNames().get("item"), JoinCondition.Operator.EQ,
                    "newRow." + second.columnNames().get("item")))));
        var pack = second.appendTo(first.appendTo(new VisualQueryPackage(List.of(), main)));
        var codec = new VisualQueryPackageJsonCodec();
        var restored = codec.read(codec.write(pack));
        assertThat(restored.main().parameters()).extracting(VisualQueryDefinition.Parameter::name)
            .containsExactly("oldDate", "newDate");
        var compiled = VisualQueryCompiler.compile(restored, catalog);
        assertThat(compiled.bindings()).containsOnlyKeys("oldDate", "newDate");
        var query = em.createQuery(compiled.jpql(), Object[].class);
        query.setParameter("oldDate", DAY); query.setParameter("newDate", DAY.plusDays(1));
        assertThat(query.getResultList()).singleElement().satisfies(row -> {
            assertThat((BigDecimal) row[0]).isEqualByComparingTo("150");
            assertThat((BigDecimal) row[1]).isEqualByComparingTo("90");
        });
        var incompatible = new VisualQueryDefinition(3, "R5Item", "r", List.of(new VisualQueryDefinition.SelectField("id", "id")), List.of(), List.of(), List.of(),
            List.of(), null, null, List.of(new VisualQueryDefinition.Parameter("oldDate", "TEXT", true, null)));
        assertThatThrownBy(() -> first.appendTo(new VisualQueryPackage(List.of(), incompatible)))
            .hasMessageContaining("SLICE_PARAMETER_CONFLICT");
        assertThatThrownBy(() -> generator.generateParameterized(source, SliceSpec.latest(Price.class, DAY),
            RegisterSliceCteGenerator.NameContext.empty(), "visualFilter_1")).hasMessageContaining("SLICE_BINDING_NAMESPACE");
        assertThatThrownBy(() -> generator.generateParameterized(source, SliceSpec.latest(Price.class, DAY),
            RegisterSliceCteGenerator.NameContext.empty(), "x) or 1=1")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void leftJoinRetainsVisibleItemsWithoutHistoryAndLiteralTextIsBound() {
        var item = item(1); item(2);
        String text = "x' OR 1=1 --"; price(item, text, DAY, BigDecimal.ONE, 1);
        var generated = fragment(spec(DAY, List.of(condition("channel", SliceSpec.Operator.EQ, text)), List.of()));
        var main = definition("R5Item", "items", List.of(new VisualQueryDefinition.SelectField("items.id", "itemId"),
            new VisualQueryDefinition.SelectField("sliceValues." + generated.columnNames().get("price"), "price")),
            List.of(new VisualQueryDefinition.Join(null, generated.latestName(), "sliceValues", VisualQueryDefinition.JoinKind.LEFT,
                new JoinCondition.Predicate("items.id", JoinCondition.Operator.EQ, "sliceValues." + generated.columnNames().get("item")))));
        var pack = generated.appendTo(new VisualQueryPackage(List.of(), main));
        var compiled = VisualQueryCompiler.compile(pack, catalog);
        assertThat(compiled.jpql()).doesNotContain(text); assertThat(compiled.bindings().values()).contains(text);
        var rows = execute(pack); assertThat(rows).hasSize(2);
        assertThat(rows.stream().filter(row -> row[0].equals(2L)).findFirst().orElseThrow()[1]).isNull();
    }
    @Test void invalidTypesPhasesOperatorsAndMappingsFailBeforeGeneration() {
        for (var spec : List.of(spec(DAY, List.of(condition("price", SliceSpec.Operator.EQ, BigDecimal.ONE)), List.of()),
                spec(DAY, List.of(), List.of(condition("channel", SliceSpec.Operator.EQ, "x"))),
                spec(DAY, List.of(condition("item", SliceSpec.Operator.EQ, "1")), List.of()),
                spec(DAY, List.of(condition("item", SliceSpec.Operator.GT, 1L)), List.of()),
                spec(DAY, List.of(condition("channel", SliceSpec.Operator.GT, "x")), List.of()),
                spec(DAY, List.of(), List.of(condition("price", SliceSpec.Operator.EQ, 1L))),
                SliceSpec.latest(Item.class, DAY)))
            assertThatThrownBy(() -> fragment(spec)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SLICE_");
        for (Class<?> type : List.of(Aperiodic.class, StringPrice.class, CollectionPrice.class, OtherEntity.class, String.class))
            assertThatThrownBy(() -> RegisterSliceSource.resolve(type, registers, factory.getMetamodel(), catalog))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void twoReferenceDimensionsToTheSameTargetHaveIndependentJoinsInBothPhases() {
        var a = item(1); var b = item(2);
        var value = new RepeatedPrice(); value.id = 1L; value.first = a; value.second = b; value.period = DAY; value.value = 7;
        em.persist(value);
        var prepared = RegisterSliceSource.resolve(RepeatedPrice.class, registers, factory.getMetamodel(), catalog);
        var generated = generator.generate(prepared, SliceSpec.latest(RepeatedPrice.class, DAY), RegisterSliceCteGenerator.NameContext.empty());
        assertThat(execute(generated.asPackage())).singleElement().satisfies(row -> assertThat(row).containsExactly(1L, 2L, DAY, 7));
        assertThat(generated.ctes().getFirst().definition().joins()).hasSize(2);
        assertThat(generated.ctes().getLast().definition().joins()).hasSize(3);
    }
    @Test void generatedMetadataIsImmutableAndLateNameOrBindingCollisionsAreRejected() {
        var generated = fragment(SliceSpec.latest(Price.class, DAY));
        assertThat(generated).isEqualTo(fragment(SliceSpec.latest(Price.class, DAY)));
        assertThatThrownBy(() -> generated.columnNames().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> generated.columns().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> generated.nextContext().occupied().clear()).isInstanceOf(UnsupportedOperationException.class);
        var main = generated.asPackage().main();
        var conflict = new VisualQueryPackage(List.of(new VisualQueryPackage.Cte(generated.latestName(), main)), main);
        assertThatThrownBy(() -> generated.appendTo(conflict)).hasMessageContaining("SLICE_NAME_COLLISION");
        var reserved = new VisualQueryDefinition(3, "R5Item", "r", List.of(new VisualQueryDefinition.SelectField("id", "id")),
            List.of(), List.of(), List.of(), List.of(), null, null,
            List.of(new VisualQueryDefinition.Parameter("visualFilter_1", "DATE", true, DAY)), List.of());
        assertThatThrownBy(() -> generated.appendTo(new VisualQueryPackage(List.of(), reserved)))
            .hasMessageContaining("SLICE_BINDING_NAMESPACE");
    }
    static VisualQueryDefinition definition(String entity, String alias, List<VisualQueryDefinition.SelectField> fields, List<VisualQueryDefinition.Join> joins) {
        return new VisualQueryDefinition(3, entity, alias, fields, joins, List.of(), List.of(), List.of(), null, null, List.of(), List.of());
    }
    Item item(long id) { var item = new Item(); item.id = id; item.code = "item-" + id; em.persist(item); return item; }
    void price(Item item, String channel, LocalDateTime date, BigDecimal price, int quantity) {
        var value = new Price(); value.id = ++sequence; value.item = item; value.channel = channel;
        value.period = date; value.price = price; value.quantity = quantity; em.persist(value);
    }
    void recordedPrice(String item, LocalDateTime date, String recorderType, long recorderId,
                       int line, long order, int amount) {
        em.createNativeQuery("insert into r5_recorded_price "
                + "(item_key, period_date, recorder_type, recorder_id, line_no, recording_order, amount, version) "
                + "values (?, ?, ?, ?, ?, ?, ?, 0)")
            .setParameter(1, item).setParameter(2, date).setParameter(3, recorderType)
            .setParameter(4, recorderId).setParameter(5, line).setParameter(6, order)
            .setParameter(7, amount).executeUpdate();
    }

    @Test void canonicalPagedRecorderSliceMatchesCteAndIndependentWinnerOracle() {
        var recordedSource = RegisterSliceSource.resolve(RecordedPrice.class, registers,
            factory.getMetamodel(), catalog);
        var boundary = DAY.plusHours(10).withNano(123000000);
        var sourceRows = new ArrayList<>(List.of(
            new ExpectedRecordedRow("sku", boundary.minusDays(1), "r5-document-a", 7L, 9, 99, 40),
            new ExpectedRecordedRow("sku", boundary, "r5-document-a", 7L, 1, 5, 150),
            new ExpectedRecordedRow("sku", boundary, "r5-document-b", 7L, 1, 6, 120),
            new ExpectedRecordedRow("sku", boundary, "r5-document-b", 7L, 2, 6, 90),
            new ExpectedRecordedRow("sku", boundary.plusNanos(1000000), "r5-document-b", 8L, 1, 100, 1),
            new ExpectedRecordedRow("tea", boundary, "r5-document-b", 9L, 1, 7, 200),
            new ExpectedRecordedRow("tea", boundary.plusDays(1), "r5-document-a", 10L, 1, 1000, 80),
            new ExpectedRecordedRow("future-only", boundary.plusNanos(1000000), "r5-document-a", 11L, 1, 1001, 5)));
        sourceRows.forEach(row -> recordedPrice(row.itemKey(), row.period(), row.recorderType(),
            row.recorderId(), row.lineNumber(), row.recordingOrder(), row.amount()));

        var access = canonicalPageAccess(null).access();
        var reader = new RegisterSliceReader<>(RecordedPrice.class, access, registers, factory);
        var spec = SliceSpec.latest(RecordedPrice.class, boundary);
        em.flush();
        em.clear();
        var firstPage = reader.latest(spec, PageRequest.of(0, 1, Sort.by(Sort.Order.desc("amount"))));
        var secondPage = reader.latest(spec, PageRequest.of(1, 1, Sort.by(Sort.Order.desc("amount"))));
        var emptyPage = reader.latest(spec, PageRequest.of(2, 1, Sort.by(Sort.Order.desc("amount"))));
        assertThat(firstPage.getTotalElements()).isEqualTo(2);
        assertThat(secondPage.getTotalElements()).isEqualTo(2);
        assertThat(emptyPage.getTotalElements()).isEqualTo(2);
        assertThat(emptyPage.getContent()).isEmpty();

        var oracle = sourceRows.stream().filter(row -> !row.period().isAfter(boundary))
            .collect(java.util.stream.Collectors.groupingBy(ExpectedRecordedRow::itemKey))
            .values().stream().map(rows -> rows.stream().max(ExpectedRecordedRow.WINNER_ORDER).orElseThrow())
            .sorted(java.util.Comparator.comparingInt(ExpectedRecordedRow::amount).reversed()
                .thenComparing(ExpectedRecordedRow::itemKey)).toList();
        assertThat(firstPage.getContent()).singleElement().satisfies(row -> assertRecordedRow(row, oracle.get(0)));
        assertThat(secondPage.getContent()).singleElement().satisfies(row -> assertRecordedRow(row, oracle.get(1)));

        var cteRows = execute(generator.generate(recordedSource, spec,
            RegisterSliceCteGenerator.NameContext.empty()).asPackage());
        Map<String, ExpectedRecordedRow> oracleByGroup = oracle.stream().collect(
            java.util.stream.Collectors.toMap(ExpectedRecordedRow::itemKey, row -> row));
        assertThat(cteRows).hasSize(oracle.size()).allSatisfy(row -> {
            var expected = oracleByGroup.get(row[0]);
            assertThat(expected).isNotNull();
            assertThat(row).containsExactly(expected.itemKey(), expected.period(), expected.amount(),
                expected.recorderType(), expected.recorderId(), expected.recordingOrder(), expected.lineNumber());
        });

        for (ExpectedRecordedRow expected : oracle) {
            var point = new RegisterReader<RecordedPrice>(RecordedPrice.class, access, registers)
                .getLast(Map.of("itemKey", expected.itemKey()), boundary);
            assertThat(point).hasValueSatisfying(row -> assertRecordedRow(row, expected));
        }

        // A post-slice condition sees only the winner: the older amount=150 must not reappear.
        var postFilter = new SliceSpec(RecordedPrice.class, SliceSpec.Mode.LATEST, boundary,
            List.of(new SliceSpec.Condition("itemKey", SliceSpec.Operator.EQ, "sku")),
            List.of(new SliceSpec.Condition("amount", SliceSpec.Operator.EQ, 150)));
        assertThat(reader.latest(postFilter, PageRequest.of(0, 10)).getTotalElements()).isZero();

        Map<SliceSpec.Operator, Long> filterTotals = Map.of(
            SliceSpec.Operator.EQ, 1L, SliceSpec.Operator.NE, 1L,
            SliceSpec.Operator.GT, 1L, SliceSpec.Operator.GE, 2L,
            SliceSpec.Operator.LT, 1L, SliceSpec.Operator.LE, 1L);
        Map<SliceSpec.Operator, Integer> filterValues = Map.of(
            SliceSpec.Operator.EQ, 90, SliceSpec.Operator.NE, 90,
            SliceSpec.Operator.GT, 90, SliceSpec.Operator.GE, 90,
            SliceSpec.Operator.LT, 100, SliceSpec.Operator.LE, 90);
        filterTotals.forEach((operator, total) -> {
            var filtered = new SliceSpec(RecordedPrice.class, SliceSpec.Mode.LATEST, boundary,
                List.of(), List.of(new SliceSpec.Condition("amount", operator, filterValues.get(operator))));
            assertThat(reader.latest(filtered, PageRequest.of(0, 10)).getTotalElements())
                .as("post-slice %s amount %s", operator, filterValues.get(operator)).isEqualTo(total);
        });
        var periodFilter = new SliceSpec(RecordedPrice.class, SliceSpec.Mode.LATEST, boundary, List.of(),
            List.of(new SliceSpec.Condition("period", SliceSpec.Operator.LT, boundary)));
        assertThat(reader.latest(periodFilter, PageRequest.of(0, 10)).getTotalElements()).isZero();

        var selectedA = SliceSpec.latestAmongRecorders(RecordedPrice.class, boundary,
            List.of(new RecorderRef("r5-document-a", 7L)));
        assertThat(reader.latest(selectedA, PageRequest.of(0, 10)).getContent())
            .singleElement().satisfies(row -> {
                assertThat(row.getItemKey()).isEqualTo("sku");
                assertThat(row.getAmount()).isEqualTo(150);
                assertThat(row.getRecorder()).isEqualTo(new RecorderRef("r5-document-a", 7L));
            });

        // Repeat replacement removes the old recorder set and advances its persisted order.
        em.flush();
        em.clear();
        em.createNativeQuery("delete from r5_recorded_price where recorder_type = ? and recorder_id = ?")
            .setParameter(1, "r5-document-a").setParameter(2, 7L).executeUpdate();
        var replacement = new ExpectedRecordedRow("sku", boundary, "r5-document-a", 7L, 1, 8, 110);
        recordedPrice(replacement.itemKey(), replacement.period(), replacement.recorderType(),
            replacement.recorderId(), replacement.lineNumber(), replacement.recordingOrder(), replacement.amount());
        var replacementRows = new ArrayList<>(sourceRows.stream()
            .filter(row -> !(row.recorderType().equals("r5-document-a") && row.recorderId() == 7L)).toList());
        replacementRows.add(replacement);
        var replacementOracle = replacementRows.stream().filter(row -> !row.period().isAfter(boundary))
            .collect(java.util.stream.Collectors.groupingBy(ExpectedRecordedRow::itemKey))
            .values().stream().map(rows -> rows.stream().max(ExpectedRecordedRow.WINNER_ORDER).orElseThrow())
            .sorted(java.util.Comparator.comparingInt(ExpectedRecordedRow::amount).reversed()
                .thenComparing(ExpectedRecordedRow::itemKey)).toList();
        var replacedPage0 = reader.latest(spec, PageRequest.of(0, 1, Sort.by(Sort.Order.desc("amount"))));
        var replacedPage1 = reader.latest(spec, PageRequest.of(1, 1, Sort.by(Sort.Order.desc("amount"))));
        assertThat(replacedPage0.getTotalElements()).isEqualTo(2);
        assertThat(replacedPage1.getTotalElements()).isEqualTo(2);
        assertThat(replacedPage0.getContent()).singleElement().satisfies(row -> assertRecordedRow(row, replacementOracle.get(0)));
        assertThat(replacedPage1.getContent()).singleElement().satisfies(row -> assertRecordedRow(row, replacementOracle.get(1)));
        assertThat(execute(generator.generate(recordedSource, spec,
            RegisterSliceCteGenerator.NameContext.empty()).asPackage())).hasSize(2).allSatisfy(row -> {
                var expected = replacementOracle.stream().filter(value -> value.itemKey().equals(row[0]))
                    .findFirst().orElseThrow();
                assertThat(row).containsExactly(expected.itemKey(), expected.period(), expected.amount(),
                    expected.recorderType(), expected.recorderId(), expected.recordingOrder(), expected.lineNumber());
            });
        for (ExpectedRecordedRow expected : replacementOracle) {
            var point = new RegisterReader<RecordedPrice>(RecordedPrice.class, access, registers)
                .getLast(Map.of("itemKey", expected.itemKey()), boundary);
            assertThat(point).hasValueSatisfying(row -> assertRecordedRow(row, expected));
        }

        assertThatThrownBy(() -> reader.latest(spec, Pageable.unpaged()))
            .hasMessageContaining("SLICE_PAGE");
        assertThatThrownBy(() -> reader.latest(spec,
            PageRequest.of(0, RegisterSliceReader.MAX_PAGE_SIZE + 1))).hasMessageContaining("SLICE_PAGE_SIZE");
        assertThatThrownBy(() -> reader.latest(spec, PageRequest.of(0, 10, Sort.by("recordingOrder"))))
            .hasMessageContaining("SLICE_SORT_FIELD");
    }

    @Test void canonicalSliceComparesReferenceDimensionsByPersistentIdentity() {
        var itemA = item(31L);
        var itemB = item(32L);
        price(itemA, "retail", DAY, new BigDecimal("150"), 1);
        price(itemA, "retail", DAY.plusDays(1), new BigDecimal("90"), 2);
        price(itemA, "wholesale", DAY.plusDays(1), new BigDecimal("200"), 3);
        price(itemB, "retail", DAY.plusDays(1), new BigDecimal("300"), 4);

        var reader = new RegisterSliceReader<>(Price.class, criteriaPageAccess(Price.class), registers, factory);
        var spec = new SliceSpec(Price.class, SliceSpec.Mode.LATEST, DAY.plusDays(1), List.of(
            new SliceSpec.Condition("item", SliceSpec.Operator.EQ, 31L),
            new SliceSpec.Condition("channel", SliceSpec.Operator.EQ, "retail")), List.of());
        em.flush();
        em.clear();
        var canonical = reader.latest(spec, PageRequest.of(0, 10));
        assertThat(canonical.getTotalElements()).isEqualTo(1);
        assertThat(canonical.getContent()).singleElement().satisfies(row -> {
            assertThat(row.getItem().getId()).isEqualTo(31L);
            assertThat(row.getPeriod()).isEqualTo(DAY.plusDays(1));
            assertThat(row.getPrice()).isEqualByComparingTo("90");
        });
        var cte = generator.generate(source, spec, RegisterSliceCteGenerator.NameContext.empty());
        assertThat(execute(cte.asPackage())).singleElement().satisfies(row -> {
            assertThat(row[0]).isEqualTo(31L);
            assertThat(row[1]).isEqualTo("retail");
            assertThat(row[2]).isEqualTo(DAY.plusDays(1));
            assertThat((BigDecimal) row[3]).isEqualByComparingTo("90");
        });
    }

    @Test void canonicalPageAndCountKeepC5AndRlsGroupVisibility() {
        var boundary = DAY.plusHours(10);
        recordedPrice("sku", boundary, "r5-document-a", 7, 1, 1, 150);
        recordedPrice("tea", boundary, "r5-document-b", 8, 1, 2, 200);
        var harness = canonicalPageAccess("sku");
        var reader = new RegisterSliceReader<>(RecordedPrice.class, harness.access(), registers, factory);

        em.flush();
        em.clear();
        var page = reader.latest(SliceSpec.latest(RecordedPrice.class, boundary), PageRequest.of(0, 1));
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).singleElement().satisfies(row -> {
            assertThat(row.getItemKey()).isEqualTo("sku");
            assertThat(row.getAmount()).isEqualTo(150);
        });
        org.mockito.Mockito.verify(harness.c5()).isEntityReadPermitted("r5-slice-user", RecordedPrice.class);
        org.mockito.Mockito.verify(harness.rls()).canRead(RecordedPrice.class, "r5-slice-user");
        var detached = page.getContent().getFirst();
        em.clear();
        assertThat(detached.getItemKey()).isEqualTo("sku");
        assertThat(detached.getAmount()).isEqualTo(150);
    }

    @Test void canonicalPagesUseStableDimensionTieBreakersForEqualSortValues() {
        var boundary = DAY.plusHours(10);
        recordedPrice("beta", boundary, "r5-document-a", 7, 1, 1, 77);
        recordedPrice("alpha", boundary, "r5-document-b", 8, 1, 2, 77);
        var reader = new RegisterSliceReader<>(RecordedPrice.class, canonicalPageAccess(null).access(), registers, factory);
        var first = reader.latest(SliceSpec.latest(RecordedPrice.class, boundary),
            PageRequest.of(0, 1, Sort.by(Sort.Order.desc("amount"))));
        var firstAgain = reader.latest(SliceSpec.latest(RecordedPrice.class, boundary),
            PageRequest.of(0, 1, Sort.by(Sort.Order.desc("amount"))));
        var second = reader.latest(SliceSpec.latest(RecordedPrice.class, boundary),
            PageRequest.of(1, 1, Sort.by(Sort.Order.desc("amount"))));
        assertThat(first.getContent()).extracting(RecordedPrice::getItemKey).containsExactly("alpha");
        assertThat(firstAgain.getContent()).extracting(RecordedPrice::getId)
            .containsExactly(first.getContent().getFirst().getId());
        assertThat(second.getContent()).extracting(RecordedPrice::getItemKey).containsExactly("beta");
        assertThat(second.getTotalElements()).isEqualTo(2);
    }

    @Test void canonicalPointPageAndCteAgreeForEveryNormalizedCalendarPeriodicity() {
        var reader = new RegisterSliceReader<>(Price.class, criteriaPageAccess(Price.class), registers, factory);
        for (RegisterPeriodicity periodicity : List.of(RegisterPeriodicity.DAY,
                RegisterPeriodicity.MONTH, RegisterPeriodicity.YEAR)) {
            var item = item(100L + periodicity.ordinal());
            var rawCurrent = DAY.plusMonths(5).plusDays(15).plusHours(12).plusNanos(123456000);
            var rawOlder = switch (periodicity) {
                case DAY -> rawCurrent.minusDays(1);
                case MONTH -> rawCurrent.minusMonths(1);
                case YEAR -> rawCurrent.minusYears(1);
                case EXACT -> throw new AssertionError("EXACT covered by the timestamp boundary oracle");
            };
            var rawFuture = switch (periodicity) {
                case DAY -> rawCurrent.plusDays(1);
                case MONTH -> rawCurrent.plusMonths(1);
                case YEAR -> rawCurrent.plusYears(1);
                case EXACT -> throw new AssertionError("EXACT covered by the timestamp boundary oracle");
            };
            List<Map.Entry<LocalDateTime, Integer>> oracleRows = List.of(
                Map.entry(periodicity.normalize(rawOlder), 150),
                Map.entry(periodicity.normalize(rawCurrent), 90),
                Map.entry(periodicity.normalize(rawFuture), 1));
            oracleRows.forEach(row -> price(item, "calendar-" + periodicity, row.getKey(),
                BigDecimal.valueOf(row.getValue()), row.getValue()));
            var asOf = periodicity.normalize(rawCurrent);
            int expected = oracleRows.stream().filter(row -> !row.getKey().isAfter(asOf))
                .max(Map.Entry.comparingByKey()).orElseThrow().getValue();

            var spec = SliceSpec.latest(Price.class, asOf);
            var page = reader.latest(spec, PageRequest.of(0, 10));
            assertThat(page.getTotalElements()).isEqualTo(1);
            assertThat(page.getContent()).singleElement().satisfies(row ->
                assertThat(row.getPrice()).isEqualByComparingTo(BigDecimal.valueOf(expected)));
            assertThat(execute(generator.generate(source, spec,
                RegisterSliceCteGenerator.NameContext.empty()).asPackage())).singleElement()
                .satisfies(row -> {
                    assertThat(row[2]).isEqualTo(asOf);
                    assertThat((BigDecimal) row[3]).isEqualByComparingTo(BigDecimal.valueOf(expected));
                });
            assertThat(new RegisterReader<Price>(Price.class, criteriaPageAccess(Price.class), registers)
                .getLast(Map.of("item", em.getReference(Item.class, item.id),
                    "channel", "calendar-" + periodicity), asOf))
                .hasValueSatisfying(row -> assertThat(row.getPrice())
                    .isEqualByComparingTo(BigDecimal.valueOf(expected)));
        }
    }

    private <E> org.ipro.data.EntityDataAccess criteriaPageAccess(Class<E> entityType) {
        var access = org.mockito.Mockito.mock(org.ipro.data.EntityDataAccess.class);
        org.mockito.Mockito.when(access.list(org.mockito.ArgumentMatchers.eq(entityType),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(Pageable.class)))
            .thenAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                org.springframework.data.jpa.domain.Specification<E> specification = invocation.getArgument(1);
                Pageable page = invocation.getArgument(2);
                var builder = em.getCriteriaBuilder();
                var dataQuery = builder.createQuery(entityType);
                var dataRoot = dataQuery.from(entityType);
                dataQuery.select(dataRoot).where(specification.toPredicate(dataRoot, dataQuery, builder));
                dataQuery.orderBy(page.getSort().stream().map(order -> order.isAscending()
                    ? builder.asc(criteriaPath(dataRoot, order.getProperty()))
                    : builder.desc(criteriaPath(dataRoot, order.getProperty()))).toList());
                var content = em.createQuery(dataQuery).setFirstResult((int) page.getOffset())
                    .setMaxResults(page.getPageSize()).getResultList();

                var countQuery = builder.createQuery(Long.class);
                var countRoot = countQuery.from(entityType);
                countQuery.select(builder.count(countRoot))
                    .where(specification.toPredicate(countRoot, countQuery, builder));
                long total = em.createQuery(countQuery).getSingleResult();
                return new org.springframework.data.domain.PageImpl<>(content, page, total);
            });
        return access;
    }

    private CanonicalHarness canonicalPageAccess(String visibleItemKey) {
        var entityCatalog = org.mockito.Mockito.mock(EntityDescriptorCatalog.class);
        org.mockito.Mockito.when(entityCatalog.descriptorOf(RecordedPrice.class)).thenReturn(new EntityDescriptor(
            RecordedPrice.class, EntityExposure.STANDARD_ROOT, true, true,
            new EntityCapabilities(java.util.Set.of(FetchScenario.LIST), java.util.Set.of(), "G5 test"), "G5 test"));
        var readGate = org.mockito.Mockito.mock(RlsReadGate.class);
        org.mockito.Mockito.when(readGate.canRead(RecordedPrice.class, "r5-slice-user")).thenReturn(true);
        var c5 = org.mockito.Mockito.mock(C5PermissionEvaluator.class);
        org.mockito.Mockito.when(c5.isEntityReadPermitted("r5-slice-user", RecordedPrice.class)).thenReturn(true);
        var filterActivator = org.mockito.Mockito.mock(RlsFilterActivator.class);
        if (visibleItemKey != null) {
            org.mockito.Mockito.doAnswer(invocation -> {
                var session = em.unwrap(org.hibernate.Session.class);
                var filter = session.getEnabledFilter("r5ItemScope");
                if (filter == null) filter = session.enableFilter("r5ItemScope");
                filter.setParameter("visibleItem", visibleItemKey);
                return null;
            }).when(filterActivator).ensureRlsEnabled(org.mockito.ArgumentMatchers.any(EntityManager.class));
        }
        var executor = new CanonicalReadExecutor(entityCatalog, org.mockito.Mockito.mock(ScenarioFetchGraphResolver.class),
            new MetadataResolver(), filterActivator, readGate, null, ReadTelemetry.noop(),
            null, () -> "r5-slice-user", c5);
        org.springframework.test.util.ReflectionTestUtils.setField(executor, "entityManager", em);
        var access = org.mockito.Mockito.mock(org.ipro.data.EntityDataAccess.class);
        org.mockito.Mockito.when(access.list(org.mockito.ArgumentMatchers.eq(RecordedPrice.class),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(Pageable.class)))
            .thenAnswer(invocation -> executor.readPage(org.ipro.data.PageRead.of(RecordedPrice.class,
                FetchScenario.LIST, invocation.getArgument(1), invocation.getArgument(2))));
        return new CanonicalHarness(access, c5, readGate);
    }

    private record CanonicalHarness(org.ipro.data.EntityDataAccess access,
                                    C5PermissionEvaluator c5, RlsReadGate rls) { }

    private static jakarta.persistence.criteria.Path<?> criteriaPath(jakarta.persistence.criteria.Path<?> root,
                                                                       String property) {
        jakarta.persistence.criteria.Path<?> current = root;
        for (String segment : property.split("\\.")) current = current.get(segment);
        return current;
    }

    private static void assertRecordedRow(RecordedPrice actual, ExpectedRecordedRow expected) {
        assertThat(actual.getItemKey()).isEqualTo(expected.itemKey());
        assertThat(actual.getPeriod()).isEqualTo(expected.period());
        assertThat(actual.getRecorder()).isEqualTo(new RecorderRef(expected.recorderType(), expected.recorderId()));
        assertThat(actual.getRecordingOrder()).isEqualTo(expected.recordingOrder());
        assertThat(actual.getLineNumber()).isEqualTo(expected.lineNumber());
        assertThat(actual.getAmount()).isEqualTo(expected.amount());
    }

    private record ExpectedRecordedRow(String itemKey, LocalDateTime period, String recorderType,
                                       Long recorderId, int lineNumber, long recordingOrder, int amount) {
        private static final java.util.Comparator<ExpectedRecordedRow> WINNER_ORDER =
            java.util.Comparator.comparing(ExpectedRecordedRow::period)
                .thenComparingLong(ExpectedRecordedRow::recordingOrder)
                .thenComparingInt(ExpectedRecordedRow::lineNumber);
    }

    @Test void recordedPeriodicCteUsesPeriodThenRecordingOrderThenLineAndProjectsRecorderScalars() {
        var recordedSource = RegisterSliceSource.resolve(RecordedPrice.class, registers,
            factory.getMetamodel(), catalog);
        var first = DAY.plusHours(10).withNano(123000000);
        recordedPrice("sku", first.minusDays(1), "r5-document-a", 7, 9, 99, 40);
        recordedPrice("sku", first, "r5-document-a", 7, 1, 5, 150);
        recordedPrice("sku", first, "r5-document-b", 7, 1, 6, 120);
        recordedPrice("sku", first, "r5-document-b", 7, 2, 6, 90);
        recordedPrice("sku", first.plusDays(1), "r5-document-b", 8, 1, 100, 80);

        var fragment = generator.generate(recordedSource, SliceSpec.latest(RecordedPrice.class, first),
            RegisterSliceCteGenerator.NameContext.empty());
        assertThat(fragment.ctes()).extracting(VisualQueryPackage.Cte::name)
            .containsExactly("rs1_mx", "rs1_orderMax", "rs1_lineMax", "rs1_latest");
        assertThat(fragment.columnNames()).containsKeys("recorder.typeCode", "recorder.id", "recordingOrder", "lineNumber");
        assertThat(fragment.columns()).filteredOn(column -> column.role() == RegisterSliceCteGenerator.Role.RECORDER)
            .extracting(RegisterSliceCteGenerator.Column::field)
            .containsExactly("recorder.typeCode", "recorder.id", "recordingOrder", "lineNumber");

        var compiled = fragment.compile(catalog);
        assertThat(compiled.jpql()).doesNotContain("R5RecorderDocumentA", "R5RecorderDocumentB");
        assertThat(compiled.jpql()).contains("recorder.typeCode", "recorder.id", "recordingOrder", "lineNumber");
        assertThat(execute(fragment.asPackage())).singleElement().satisfies(row -> {
            assertThat(row).containsExactly("sku", first, 90, "r5-document-b", 7L, 6L, 2);
        });
        var access = org.mockito.Mockito.mock(org.ipro.data.EntityDataAccess.class);
        org.mockito.Mockito.when(access.list(org.mockito.ArgumentMatchers.eq(RecordedPrice.class),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(org.springframework.data.domain.Pageable.class)))
            .thenAnswer(invocation -> {
                org.springframework.data.jpa.domain.Specification<RecordedPrice> specification = invocation.getArgument(1);
                org.springframework.data.domain.Pageable page = invocation.getArgument(2);
                var builder = em.getCriteriaBuilder();
                var query = builder.createQuery(RecordedPrice.class);
                var root = query.from(RecordedPrice.class);
                query.select(root).where(specification.toPredicate(root, query, builder));
                query.orderBy(page.getSort().stream().map(order -> order.isAscending()
                    ? builder.asc(root.get(order.getProperty())) : builder.desc(root.get(order.getProperty()))).toList());
                var result = em.createQuery(query).setFirstResult((int) page.getOffset())
                    .setMaxResults(page.getPageSize()).getResultList();
                return new org.springframework.data.domain.PageImpl<>(result, page, result.size());
            });
        var pointWinner = new RegisterReader<RecordedPrice>(RecordedPrice.class, access, registers)
            .getLast(Map.of("itemKey", "sku"), first);
        assertThat(pointWinner).hasValueSatisfying(value -> {
            assertThat(value.getPeriod()).isEqualTo(first);
            assertThat(value.getAmount()).isEqualTo(90);
            assertThat(value.getRecorder()).isEqualTo(new RecorderRef("r5-document-b", 7L));
            assertThat(value.getRecordingOrder()).isEqualTo(6L);
            assertThat(value.getLineNumber()).isEqualTo(2);
        });
        var codec = new VisualQueryPackageJsonCodec();
        var serialized = codec.write(fragment.asPackage());
        var restored = codec.read(serialized);
        assertThat(VisualQueryCompiler.compile(restored, catalog)).isEqualTo(compiled);
        assertThat(execute(restored)).singleElement().satisfies(row ->
            assertThat(row).containsExactly("sku", first, 90, "r5-document-b", 7L, 6L, 2));
    }

    @Test void recorderReadIntentRestrictsCandidatesByTypedIdentityBeforeEveryWinnerMaximum() {
        var recordedSource = RegisterSliceSource.resolve(RecordedPrice.class, registers,
            factory.getMetamodel(), catalog);
        var period = DAY.plusHours(10);
        recordedPrice("sku", period, "r5-document-a", 7, 1, 5, 150);
        recordedPrice("sku", period, "r5-document-b", 7, 1, 6, 120);
        recordedPrice("sku", period, "r5-document-b", 7, 2, 6, 90);

        var recorderA = new RecorderRef("r5-document-a", 7L);
        var recorderB = new RecorderRef("r5-document-b", 7L);
        var selectedA = generator.generate(recordedSource,
            SliceSpec.latestAmongRecorders(RecordedPrice.class, period, List.of(recorderA)),
            RegisterSliceCteGenerator.NameContext.empty());
        var selectedB = generator.generate(recordedSource,
            SliceSpec.latestAmongRecorders(RecordedPrice.class, period, List.of(recorderB)),
            RegisterSliceCteGenerator.NameContext.empty());

        assertThat(selectedA.asPackage().version()).isEqualTo(VisualQueryPackage.CURRENT_VERSION);
        assertThat(selectedA.ctes()).extracting(VisualQueryPackage.Cte::selectedRecorders)
            .containsExactly(List.of(recorderA), List.of(recorderA), List.of(recorderA), List.of(recorderA));
        assertThat(execute(selectedA.asPackage())).singleElement().satisfies(row ->
            assertThat(row).containsExactly("sku", period, 150, "r5-document-a", 7L, 5L, 1));
        assertThat(execute(selectedB.asPackage())).singleElement().satisfies(row ->
            assertThat(row).containsExactly("sku", period, 90, "r5-document-b", 7L, 6L, 2));

        var codec = new VisualQueryPackageJsonCodec();
        var roundTrip = codec.read(codec.write(selectedA.asPackage()));
        assertThat(roundTrip.version()).isEqualTo(VisualQueryPackage.CURRENT_VERSION);
        assertThat(roundTrip.ctes()).extracting(VisualQueryPackage.Cte::selectedRecorders)
            .containsExactly(List.of(recorderA), List.of(recorderA), List.of(recorderA), List.of(recorderA));
        assertThat(execute(roundTrip)).singleElement().satisfies(row ->
            assertThat(row).containsExactly("sku", period, 150, "r5-document-a", 7L, 5L, 1));

        var forgedCtes = selectedA.ctes().stream().map(cte -> new VisualQueryPackage.Cte(cte.name(),
            cte.source(), cte.definition(), List.of(new RecorderRef("unlisted-document", 7L)))).toList();
        var forgedPackage = new VisualQueryPackage(VisualQueryPackage.CURRENT_VERSION, forgedCtes,
            selectedA.asPackage().main());
        assertThatThrownBy(() -> VisualQueryCompiler.compile(forgedPackage, catalog))
            .hasMessageContaining("QUERY_RECORDER_SELECTION");

        assertThatThrownBy(() -> generator.generate(recordedSource,
            SliceSpec.latestAmongRecorders(RecordedPrice.class, period,
                List.of(new RecorderRef("unlisted-document", 7L))), RegisterSliceCteGenerator.NameContext.empty()))
            .hasMessageContaining("SLICE_RECORDER_TYPE");
        assertThatThrownBy(() -> generator.generate(source,
            SliceSpec.latestAmongRecorders(Price.class, period, List.of(recorderA)),
            RegisterSliceCteGenerator.NameContext.empty()))
            .hasMessageContaining("SLICE_RECORDER_INTENT");
    }

    @Test void recorderWinnerColumnsAreSelectableButNotGenericFilterFields() {
        assertThat(catalog.root("R5RecordedPrice").fields())
            .filteredOn(QueryBuilderMetadataCatalog.Field::technical)
            .extracting(QueryBuilderMetadataCatalog.Field::name)
            .contains("recorder.typeCode", "recorder.id", "recordingOrder", "lineNumber");
        var query = new VisualQueryDefinition(3, "R5RecordedPrice", "r", List.of(
            new VisualQueryDefinition.SelectField("r.recorder.typeCode", "recorderType")), List.of(), List.of(), List.of(),
            List.of(), null,
            new FilterConditionNode(new FilterCondition("r.recorder.typeCode", FilterOperator.EQ,
                "r5-document-a", null, FilterDataType.TEXT)), List.of(), List.of());
        assertThatThrownBy(() -> VisualQueryCompiler.compile(query, catalog))
            .hasMessageContaining("Поле визуального запроса не найдено");
        assertThat(VisualQueryCompiler.compile(new VisualQueryDefinition(3, "R5RecordedPrice", "r", List.of(
            new VisualQueryDefinition.SelectField("r.recorder.typeCode", "recorderType")), List.of(), List.of(),
            List.of(), List.of(), null, null, List.of(), List.of()), catalog).jpql())
            .contains("r.recorder.typeCode");
    }
    ReportQueryLanguage language() { return new ReportQueryLanguage(catalog, new RegisterSliceReportTemplates(factory, registers, catalog, generator)); }
    org.ipro.reportstudio.dom.ReportTemplate languageTemplate(ReportQueryLanguage.Parsed parsed) {
        var template = new org.ipro.reportstudio.dom.ReportTemplate(); template.setQuerySource(org.ipro.reportstudio.dom.ReportQuerySource.VISUAL);
        template.setJpql(parsed.compiled().jpql()); template.setVisualQueryJson(parsed.json()); return template;
    }
    List<Object[]> languageRows(ReportQueryLanguage.Parsed parsed, Map<String,Object> supplied) {
        em.flush(); em.clear(); var query = em.createQuery(parsed.compiled().jpql(), Object[].class);
        var bindings = new LinkedHashMap<>(parsed.compiled().bindings()); bindings.putAll(supplied);
        for (var parameter : query.getParameters()) query.setParameter(parameter.getName(), bindings.get(parameter.getName()));
        return query.getResultList();
    }
    @Test void editableLanguageParametersRoundTripAndApplyAfterWinner() {
        var item = item(1L); price(item, "retail", DAY, new BigDecimal("150"), 1); price(item, "retail", DAY.plusDays(1), new BigDecimal("90"), 2);
        var language = language();
        var parsed = language.parse("select s.item as item, s.price as price from СрезПоследних(R5Price, :date, item = :chosen and channel = 'retail', послеСреза = (price > :minimum)) s");
        assertThat(parsed.entityParameters()).containsEntry("chosen", Item.class);
        assertThat(languageRows(parsed, Map.of("date", DAY.plusDays(1), "sliceId_chosen", 1L, "minimum", new BigDecimal("100")))).isEmpty();
        assertThat(languageRows(parsed, Map.of("date", DAY, "sliceId_chosen", 1L, "minimum", new BigDecimal("100")))).hasSize(1);
        String text = language.format(languageTemplate(parsed)).orElseThrow();
        assertThat(text).contains("СрезПоследних(R5Price", "item = :chosen", "послеСреза", "price > :minimum").doesNotContain("НАСТРОЙКИ", "sliceId_");
        var reopened = language.parse(text);
        assertThat(reopened.compiled().jpql()).isEqualTo(parsed.compiled().jpql());
        assertThat(languageRows(reopened, Map.of("date", DAY, "sliceId_chosen", 1L, "minimum", new BigDecimal("100")))).hasSize(1);
    }
    @Test void sliceReferenceParameterRemainsAnEntityForAnOrdinaryWhere() {
        var item = item(1L); price(item, "retail", DAY, new BigDecimal("150"), 1);
        var parsed = language().parse("select s.price as price from СрезПоследних(R5Price, :date, item = :chosen) s join R5Price p on p.item.id = s.item where p.item = :chosen");
        assertThat(parsed.entityParameters()).containsEntry("chosen", Item.class);
        assertThat(parsed.compiled().jpql()).contains("p.item = :chosen", ":sliceId_chosen");
        assertThat(languageRows(parsed, Map.of("date", DAY, "sliceId_chosen", item.getId(), "chosen", item))).hasSize(1);
    }

    @Test void editableLanguageTwoSlicesAndLeftJoinKeepIndependentParameters() {
        var item = item(1L); item(2L); price(item, "retail", DAY, new BigDecimal("150"), 1);
        var parsed = language().parse("select n.id as id, a.price as oldPrice, b.price as newPrice from R5Item n left join СрезПоследних(R5Price, :oldDate, channel = :channel) a on a.item = n.id left join СрезПоследних(R5Price, :newDate, channel = :channel) b on b.item = n.id");
        assertThat(languageRows(parsed, Map.of("oldDate", DAY.minusDays(1), "newDate", DAY, "channel", "retail"))).hasSize(2);
        var draft = new org.ipro.reportstudio.query.constructor.JpqlQueryConstructor(catalog, List.of());
        draft.setPackage(parsed.pack());
        assertThat(VisualQueryCompiler.compile(draft.queryPackage(), catalog).jpql()).isEqualTo(parsed.compiled().jpql());
        var text = language().format(languageTemplate(parsed)).orElseThrow();
        assertThat(language().parse(text).compiled().jpql()).isEqualTo(parsed.compiled().jpql());
    }
    @Test void constructorDropsParametersOfRemovedFiltersAndKeepsTypedVirtualReferences() {
        var recipes = new RegisterSliceReportTemplates(factory, registers, catalog, generator);
        var source = recipes.constructorSlice(SliceSpec.latest(Price.class, DAY), "date", Set.of(), List.of(
            new SliceParameterFilter("item", SliceSpec.Operator.EQ, "chosen", false),
            new SliceParameterFilter("price", SliceSpec.Operator.GT, "minimum", true)));
        var draft = new org.ipro.reportstudio.query.constructor.QueryConstructorDraft(); draft.setCatalog(catalog);
        draft.addSlice(source); draft.addField(source.entity(), List.of(), "price");
        assertThat(draft.parameterNames()).contains("date", "chosen", "sliceId_chosen", "minimum");
        var replacement = recipes.constructorSlice(SliceSpec.latest(Price.class, DAY), "date", recipes.constructorNamespace(source));
        draft.updateSlice(source.name(), replacement);
        assertThat(draft.parameterNames()).containsExactly("date");
        var parsed = language().parse("select s.item as item from СрезПоследних(R5Price, :date) s");
        var virtual = VisualQueryPackage.VirtualCatalog.empty();
        for (var stage : parsed.pack().ctes()) virtual = virtual.add(stage.name(), stage.definition(), catalog);
        assertThat(virtual.entity(parsed.pack().main().entityName()).fields()).anySatisfy(field -> {
            assertThat(field.name()).isEqualTo("item"); assertThat(field.referenceType()).isEqualTo(Item.class);
        });
    }

    @Test void languagePresentationKeepsTypedReferenceAndRecorderIdentities() {
        for (String text : List.of("select Представление(n) as text from R5Item n", "select Представление(p.item) as text from R5Price p", "select Представление(r.recorder) as text from R5RecordedPrice r", "select Представление(s.recorder) as text from СрезПоследних(R5RecordedPrice, :date) s", "select Представление(s.item) as text from СрезПоследних(R5Price, :date) s")) {
            var parsed = language().parse(text);
            assertThat(parsed.compiled().fields()).singleElement().satisfies(field -> assertThat(field.javaType()).isEqualTo(String.class));
            assertThat(parsed.compiled().presentations().columns()).hasSize(1);
            assertThat(parsed.compiled().jpql()).doesNotContain("Представление");
            assertThat(language().parse(language().format(languageTemplate(parsed)).orElseThrow()).compiled()).isEqualTo(parsed.compiled());
        }
        assertThatThrownBy(() -> language().parse("select Представление(n.id) as text from R5Item n")).hasMessageContaining("ссылку");
    }
    @Test void presentationExecutionKeepsNullUnavailableAndRepeatedReferences() {
        var item = item(1L); item(2L); price(item, "retail", DAY, new BigDecimal("150"), 1);
        var parsed = language().parse("select n.id as id, Представление(p.item) as label, Представление(p.item) as repeated from R5Item n left join R5Price p on p.item.id = n.id");
        em.flush(); em.clear();
        var labels = org.mockito.Mockito.mock(org.ipro.data.presentation.EntityPresentationService.class);
        org.mockito.Mockito.when(labels.resolve(org.mockito.ArgumentMatchers.anyCollection())).thenAnswer(invocation -> {
            Collection<org.ipro.data.presentation.EntityPresentationService.Reference> refs = invocation.getArgument(0);
            assertThat(refs).hasSize(1); var result = new HashMap<org.ipro.data.presentation.EntityPresentationService.Reference,String>();
            refs.forEach(ref -> result.put(ref,"Объект недоступен")); return result;
        });
        var types = new RecorderTypeRegistry(new ManagedEntityCatalog(factory), factory, new MetadataResolver());
        var executor = new ReportQueryExecutor(em, org.mockito.Mockito.mock(RlsFilterActivator.class)); executor.usePresentations(labels,types);
        var dataset = executor.execute(parsed.compiled(),100,10000);
        assertThat(dataset.fields()).extracting(org.ipro.reportstudio.data.QueryField::name).containsExactly("id","label","repeated");
        assertThat(dataset.rows()).hasSize(2);
        assertThat(Arrays.stream(dataset.rows()).filter(row -> row.value("id").equals(1L)).findFirst().orElseThrow().value("label")).isEqualTo("Объект недоступен");
        assertThat(Arrays.stream(dataset.rows()).filter(row -> row.value("id").equals(2L)).findFirst().orElseThrow().value("label")).isNull();
        var preview = new ReportPreviewService(executor);
        assertThat(preview.preview(parsed.compiled(),100,10000).rows()).hasSize(2);
    }
    @Test void recorderPresentationExecutionUsesTypeAndIdForOrdinaryRegisterAndSlice() {
        recordedPrice("sku",DAY,"r5-document-a",7,1,1,150);
        var labels = org.mockito.Mockito.mock(org.ipro.data.presentation.EntityPresentationService.class);
        org.mockito.Mockito.when(labels.resolve(org.mockito.ArgumentMatchers.anyCollection())).thenAnswer(invocation -> {
            Collection<org.ipro.data.presentation.EntityPresentationService.Reference> refs = invocation.getArgument(0); var result = new HashMap<org.ipro.data.presentation.EntityPresentationService.Reference,String>();
            refs.forEach(ref -> { assertThat(ref.type()).isEqualTo(RecorderDocumentA.class); assertThat(ref.id()).isEqualTo(7L); result.put(ref,"Документ А № 7"); }); return result;
        });
        var executor = new ReportQueryExecutor(em,org.mockito.Mockito.mock(RlsFilterActivator.class));
        executor.usePresentations(labels,new RecorderTypeRegistry(new ManagedEntityCatalog(factory),factory,new MetadataResolver()));
        for (String source : List.of("R5RecordedPrice", "СрезПоследних(R5RecordedPrice, :date)")) {
            var parsed = language().parse("select Представление(r.recorder) as recorder from " + source + " r");
            em.flush(); em.clear(); var bindings = new HashMap<>(parsed.compiled().bindings()); bindings.put("date",DAY);
            var compiled = parsed.compiled(); var assembled = new ReportQueryAssembler(compiled.jpql(),bindings,compiled.fields(),compiled.warnings(),compiled.presentations());
            assertThat(executor.execute(assembled,100,10000).rows()).singleElement().satisfies(row -> assertThat(row.value("recorder")).isEqualTo("Документ А № 7"));
        }
    }

    @Test void languageRejectsUnsupportedPresentationContextsAndPartialParse() {
        for (String text : List.of("select distinct Представление(n) as text from R5Item n", "select n.id as id from R5Item n where Представление(n) = 'x'", "select Представление(n) as text from R5Item n order by text", "select lower(Представление(n)) as text from R5Item n", "select Представление(n) as text from R5Item n group by n.id", "select s.price as price from СрезПоследних(R5Price, :date, item > :chosen) s", "select s.price as price from СрезПоследних(R5Price, :date, channel = :date) s"))
            assertThatThrownBy(() -> language().parse(text)).as(text).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> language().parse("select a.price as price from СрезПоследних(R5Price, :firstDate, channel = :shared) a join СрезПоследних(R5Price, :secondDate, послеСреза = (quantity = :shared)) b on a.item = b.item"))
            .hasMessageContaining("Несовместимые типы параметра");
        assertThatThrownBy(() -> language().parse("select n.id as repeated, Представление(n) as repeated from R5Item n"))
            .hasMessageContaining("Повторное имя колонки SELECT");
        assertThatThrownBy(() -> language().parse("select s.price from СрезПоследних(R5Price, :date, missing = :x) s")).hasMessageContaining("Строка").hasMessageContaining("missing");
    }

    @Entity(name="R5Item") @EntityMetadata(listFormTitle="Items")
    static class Item { @Id Long id; @FieldMetadata(label="Code") String code; public Long getId(){ return id; } public String getCode(){return code;} }
    @Entity(name="R5Price") @EntityMetadata(listFormTitle="Prices")
    @Table(uniqueConstraints=@UniqueConstraint(columnNames={"item_id","channel","period_date"}))
    @RegisterMetadata(dimensions={"item","channel"},period="period",resources={"price","quantity"})
    static class Price {
        @Id Long id;
        @ManyToOne @JoinColumn(name="item_id",nullable=false,updatable=false) @FieldMetadata(label="Item") Item item;
        @Column(name="channel",nullable=false,updatable=false) @FieldMetadata(label="Channel") String channel;
        @Column(name="period_date",nullable=false,updatable=false) @FieldMetadata(label="Date") LocalDateTime period;
        @FieldMetadata(label="Price") BigDecimal price;
        @FieldMetadata(label="Quantity") int quantity;
        public Long getId(){return id;} public Item getItem(){return item;} public String getChannel(){return channel;}
        public LocalDateTime getPeriod(){return period;} public BigDecimal getPrice(){return price;} public int getQuantity(){return quantity;}
    }
    @Entity static class OtherEntity { @Id Long id; }
    @Entity(name="R8ReferencePrice") @EntityMetadata(listFormTitle="Reference values")
    @Table(uniqueConstraints=@UniqueConstraint(columnNames={"code", "period_date"}))
    @RegisterMetadata(dimensions="code", period="period", resources="value")
    static class ReferencePrice {
        @Id Long id;
        @Column(name="code", nullable=false, updatable=false) @FieldMetadata(label="Code") String code;
        @Column(name="period_date", nullable=false, updatable=false) @FieldMetadata(label="Period") LocalDateTime period;
        @ManyToOne(optional=false) @JoinColumn(name="value_id", nullable=false) @FieldMetadata(label="Value") Item value;
    }
    @Entity(name="R5Aperiodic") @EntityMetadata(listFormTitle="Current") @Table(uniqueConstraints=@UniqueConstraint(columnNames="code"))
    @RegisterMetadata(dimensions="code",resources="value")
    static class Aperiodic { @Id Long id; @Column(name="code",nullable=false,updatable=false) @FieldMetadata(label="Code") String code; @Column(name="stored_value") @FieldMetadata(label="Value") int value; }
    @Entity(name="R5StringItem") @EntityMetadata(listFormTitle="String id") static class StringItem { @Id String id; @FieldMetadata(label="Code") String code; }
    @Entity(name="R5StringPrice") @EntityMetadata(listFormTitle="String price") @Table(uniqueConstraints=@UniqueConstraint(columnNames={"item_id","period_date"}))
    @RegisterMetadata(dimensions="item",period="period",resources="value")
    static class StringPrice { @Id Long id; @ManyToOne @JoinColumn(name="item_id",nullable=false,updatable=false) @FieldMetadata(label="Item") StringItem item;
        @Column(name="period_date",nullable=false,updatable=false) @FieldMetadata(label="Date") LocalDateTime period; @Column(name="stored_value") @FieldMetadata(label="Value") int value; }
    @Entity(name="R5CollectionPrice") @EntityMetadata(listFormTitle="Collection") @Table(uniqueConstraints=@UniqueConstraint(columnNames={"code","period_date"}))
    @RegisterMetadata(dimensions="code",period="period",resources="values")
    static class CollectionPrice { @Id Long id; @Column(name="code",nullable=false,updatable=false) @FieldMetadata(label="Code") String code;
        @Column(name="period_date",nullable=false,updatable=false) @FieldMetadata(label="Date") LocalDateTime period;
        @ElementCollection @Column(name="stored_value") @FieldMetadata(label="Values") List<String> values; }
    @Entity(name="R5RepeatedPrice") @EntityMetadata(listFormTitle="Repeated target") @Table(uniqueConstraints=@UniqueConstraint(columnNames={"first_id","second_id","period_date"}))
    @RegisterMetadata(dimensions={"first","second"},period="period",resources="value")
    static class RepeatedPrice { @Id Long id;
        @ManyToOne @JoinColumn(name="first_id",nullable=false,updatable=false) @FieldMetadata(label="First") Item first;
        @ManyToOne @JoinColumn(name="second_id",nullable=false,updatable=false) @FieldMetadata(label="Second") Item second;
        @Column(name="period_date",nullable=false,updatable=false) @FieldMetadata(label="Date") LocalDateTime period; @Column(name="stored_value") @FieldMetadata(label="Value") int value; }

    @Entity(name="R5RecorderDocumentA") @EntityMetadata(kind=EntityKind.DOCUMENT, listFormTitle="Recorder A")
    @RecorderType("r5-document-a") static class RecorderDocumentA extends BaseEntity { }
    @Entity(name="R5RecorderDocumentB") @EntityMetadata(kind=EntityKind.DOCUMENT, listFormTitle="Recorder B")
    @RecorderType("r5-document-b") static class RecorderDocumentB extends BaseEntity { }

    @Entity(name="R5RecordedPrice") @EntityMetadata(kind=EntityKind.INFORMATION_REGISTER, listFormTitle="Recorded prices")
    @org.hibernate.annotations.FilterDef(name="r5ItemScope",
        parameters=@org.hibernate.annotations.ParamDef(name="visibleItem", type=String.class))
    @org.hibernate.annotations.Filter(name="r5ItemScope", condition="item_key = :visibleItem")
    @Table(name="r5_recorded_price", uniqueConstraints=@UniqueConstraint(
        columnNames={"recorder_type", "recorder_id", "line_no"}))
    @RegisterMetadata(dimensions="itemKey", period="period", resources="amount",
        recorderTypes={RecorderDocumentA.class, RecorderDocumentB.class}, periodicity=RegisterPeriodicity.EXACT)
    static class RecordedPrice extends PeriodicRecordedRegisterEntryBase {
        @NotNull @Column(name="item_key", nullable=false, updatable=false) @FieldMetadata(label="Item") String itemKey;
        @NotNull @Column(name="amount", nullable=false) @FieldMetadata(label="Amount") Integer amount;
        public String getItemKey() { return itemKey; }
        public Integer getAmount() { return amount; }
    }
}

