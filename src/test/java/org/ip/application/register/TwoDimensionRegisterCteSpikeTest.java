package org.ip.application.register;

import jakarta.persistence.*;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.ipro.register.RegisterMetadata;
import org.ipro.register.RegisterMetadataValidator;
import org.ipro.filtergrid.filter.*;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.query.*;
import org.ipro.query.sqm.SqmQuerySemanticAnalyzer;
import org.ipro.rls.*;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Отдельный persistence unit: два измерения не добавляются в production-модель цен. */
class TwoDimensionRegisterCteSpikeTest {
    private static final LocalDateTime START = LocalDateTime.of(2026, 1, 1, 0, 0);
    private SessionFactory factory;
    private EntityManager em;
    private QueryBuilderMetadataCatalog catalog;
    private ReportQueryGuard guard;
    private long sequence;

    @BeforeEach
    void setUp() {
        factory = new Configuration()
            .addAnnotatedClass(SpikeItem.class).addAnnotatedClass(SpikePrice.class)
            .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
            .setProperty("hibernate.connection.url", "jdbc:h2:mem:register-cte-" + UUID.randomUUID())
            .setProperty("hibernate.hbm2ddl.auto", "create-drop")
            .buildSessionFactory();
        em = factory.createEntityManager();
        em.getTransaction().begin();
        // Эти фикстуры не имеют RLS-измерений. Реальные gate/analyzer не подменяются.
        var registry = new RlsDimensionRegistry("org.ip.application.register");
        registry.rebuild();
        var access = new AccessService(null, username -> List.of(), registry);
        var metadata = new MetadataResolver();
        var types = new org.ipro.metadata.ManagedEntityTypes(factory);
        var managed = new org.ipro.metadata.ManagedEntityCatalog(types);
        var registers = new org.ipro.register.RegisterRegistry(managed);
        var descriptors = new org.ipro.data.EntityDescriptorCatalog(managed,
            new org.ipro.metadata.SectionMetadataRegistry(types, metadata), metadata,
            List.of(), List.of(), List.of(new org.ipro.register.RegisterClassificationContributor(registers)));
        catalog = new QueryBuilderMetadataCatalog(types, metadata,
            new RlsReadGate(org.ipro.rls.RlsTestFixture.allowingC5ReadEvaluator(), registry),
            () -> "cte-spike", descriptors);
        guard = new ReportQueryGuard(new SqmQuerySemanticAnalyzer(factory), access, registry,
            () -> "cte-spike", factory);
        assertThat(RegisterMetadataValidator.validate(SpikePrice.class)).isEmpty();
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
    void compositeJoinMatchesOracleForEveryDimensionAndPeriod() {
        var random = new Random(2302);
        Map<Key, NavigableMap<LocalDateTime, BigDecimal>> histories = new LinkedHashMap<>();
        for (int index = 0; index < 3; index++) {
            var item = item(index + 1);
            for (String channel : List.of("retail", "wholesale")) {
                var history = new TreeMap<LocalDateTime, BigDecimal>();
                for (int offset = 0; offset < 15; offset++) {
                    if (random.nextBoolean()) {
                        var value = BigDecimal.valueOf(random.nextInt(20001), 2);
                        history.put(START.plusDays(offset), value);
                        price(item, channel, START.plusDays(offset), value);
                    }
                }
                histories.put(new Key(item.id, channel), history);
            }
        }
        em.flush();
        em.clear();
        for (int offset = -1; offset <= 16; offset++) {
            var date = START.plusDays(offset);
            var rows = execute(slice(date, null, null), date);
            Map<Key, Object[]> actual = new HashMap<>();
            for (Object[] row : rows) {
                var key = new Key(((Number) row[0]).longValue(), (String) row[1]);
                assertThat(actual.put(key, row)).as("duplicate composite key %s", key).isNull();
            }
            int expectedCount = 0;
            for (var entry : histories.entrySet()) {
                var expected = entry.getValue().floorEntry(date);
                if (expected == null) {
                    assertThat(actual).doesNotContainKey(entry.getKey());
                } else {
                    expectedCount++;
                    assertThat(actual).containsKey(entry.getKey());
                    assertThat(actual.get(entry.getKey())[2]).isEqualTo(expected.getKey());
                    assertThat((BigDecimal) actual.get(entry.getKey())[3]).isEqualByComparingTo(expected.getValue());
                }
            }
            assertThat(actual).hasSize(expectedCount);
        }
    }

    @Test
    void dimensionSelectionPrecedesMaxAndResourceSelectionFollowsIt() {
        var item = item(1);
        price(item, "retail", START, new BigDecimal("150"));
        price(item, "retail", START.plusDays(1), new BigDecimal("90"));
        price(item, "wholesale", START, new BigDecimal("250"));
        em.flush();
        var date = START.plusDays(1);
        var retail = RegisterSliceCteSpike.condition("p.channel", FilterOperator.EQ, "retail", FilterDataType.TEXT);
        var expensive = RegisterSliceCteSpike.condition("p.price", FilterOperator.GT, "100", FilterDataType.NUMBER);
        var selected = execute(slice(date, retail, null), date);
        assertThat(selected).hasSize(1);
        assertThat(selected.getFirst()[1]).isEqualTo("retail");
        assertThat((BigDecimal) selected.getFirst()[3]).isEqualByComparingTo("90");
        assertThat(execute(slice(date, retail, expensive), date)).isEmpty();
    }

    @Test
    void codecPreservesCompositeOnAndDecodedDateBinding() {
        var item = item(1);
        price(item, "retail", START, new BigDecimal("100"));
        price(item, "wholesale", START, new BigDecimal("200"));
        em.flush();
        var original = slice(START, null, null);
        var codec = new VisualQueryPackageJsonCodec();
        var restored = codec.read(codec.write(original));
        assertThat(restored).isEqualTo(original);
        assertThat(VisualQueryCompiler.compile(restored, catalog))
            .isEqualTo(VisualQueryCompiler.compile(original, catalog));
        var rows = execute(restored, START);
        assertThat(rows).hasSize(2);
        assertThat(rows.stream().map(r -> (String) r[1]).toList()).containsExactlyInAnyOrder("retail", "wholesale");
    }

    private VisualQueryPackage slice(LocalDateTime date, FilterNode dimensions, FilterNode resources) {
        return RegisterSliceCteSpike.slice("SpikePrice", date, true, dimensions, resources);
    }
    private List<Object[]> execute(VisualQueryPackage pack, LocalDateTime date) {
        return RegisterSliceCteSpike.execute(em, catalog, guard, pack, date);
    }
    private SpikeItem item(long id) {
        var item = new SpikeItem();
        item.id = id;
        item.code = "item-" + id;
        em.persist(item);
        return item;
    }
    private void price(SpikeItem item, String channel, LocalDateTime date, BigDecimal value) {
        var price = new SpikePrice();
        price.id = ++sequence;
        price.nomenclature = item;
        price.channel = channel;
        price.period = date;
        price.price = value;
        em.persist(price);
    }
    private record Key(long item, String channel) { }

    @Entity(name = "SpikeItem")
    @Table(name = "spike_item")
    @EntityMetadata(listFormTitle = "CTE items")
    public static class SpikeItem {
        @Id Long id;
        @FieldMetadata(label = "Code") String code;
        public Long getId() { return id; }
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
    }

    @Entity(name = "SpikePrice")
    @Table(name = "spike_price", uniqueConstraints = @UniqueConstraint(
        columnNames = {"item_id", "channel_code", "period_date"}))
    @EntityMetadata(listFormTitle = "CTE two-dimension prices")
    @RegisterMetadata(dimensions = {"nomenclature", "channel"}, period = "period", resources = "price")
    public static class SpikePrice {
        @Id Long id;
        @ManyToOne(optional = false)
        @JoinColumn(name = "item_id", nullable = false, updatable = false)
        @FieldMetadata(label = "Item") SpikeItem nomenclature;
        @Column(name = "channel_code", nullable = false, updatable = false)
        @FieldMetadata(label = "Channel") String channel;
        @Column(name = "period_date", nullable = false, updatable = false)
        @FieldMetadata(label = "Period") LocalDateTime period;
        @Column(name = "price", nullable = false, precision = 19, scale = 4)
        @FieldMetadata(label = "Price") BigDecimal price;
        public Long getId() { return id; }
        public SpikeItem getNomenclature() { return nomenclature; }
        public void setNomenclature(SpikeItem value) { nomenclature = value; }
        public String getChannel() { return channel; }
        public void setChannel(String value) { channel = value; }
        public LocalDateTime getPeriod() { return period; }
        public void setPeriod(LocalDateTime value) { period = value; }
        public BigDecimal getPrice() { return price; }
        public void setPrice(BigDecimal value) { price = value; }
    }
}
