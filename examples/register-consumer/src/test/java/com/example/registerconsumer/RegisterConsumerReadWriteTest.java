package com.example.registerconsumer;

import com.example.registerconsumer.model.Item;
import com.example.registerconsumer.model.ItemRate;
import com.example.registerconsumer.model.RegionRate;
import com.example.registerconsumer.support.ConsumerGrants;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.ipro.data.EntityDataAccess;
import org.ipro.register.RegisterKeyWritePermissions;
import org.ipro.register.RegisterKeyWrites;
import org.ipro.register.RegisterReaderFactory;
import org.ipro.register.SliceSpec;
import org.ipro.rls.AccessGrantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A2.1 read/write acceptance on the existing runtime: named set/delete with versions, canonical
 * reads through a new persistence context, DAY normalization, getLast and slice, and key
 * immutability for both managed and generic flush paths.
 */
@SpringBootTest(properties = "consumer.demo-password=register-consumer-test-only")
class RegisterConsumerReadWriteTest {

    private static final String USER = "writer";

    @Autowired EntityDataAccess data;
    @Autowired RegisterKeyWrites keyWrites;
    @Autowired RegisterReaderFactory readers;
    @Autowired AccessGrantRepository grants;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired PlatformTransactionManager transactions;
    @PersistenceContext EntityManager entityManager;

    @BeforeEach
    void grantsForWriter() {
        ConsumerGrants.login(USER);
        ConsumerGrants.revokeAll(grants, USER);
        ConsumerGrants.entityWrite(grants, USER, Item.class);
        ConsumerGrants.entityWrite(grants, USER, RegionRate.class);
        ConsumerGrants.entityWrite(grants, USER, ItemRate.class);
        ConsumerGrants.action(grants, USER, RegisterKeyWritePermissions.setPermissionId(RegionRate.class));
        ConsumerGrants.action(grants, USER, RegisterKeyWritePermissions.deletePermissionId(RegionRate.class));
        ConsumerGrants.action(grants, USER, RegisterKeyWritePermissions.setPermissionId(ItemRate.class));
        ConsumerGrants.action(grants, USER, RegisterKeyWritePermissions.deletePermissionId(ItemRate.class));
    }

    @AfterEach
    void clearPrincipal() {
        ConsumerGrants.logout();
    }

    @Test
    void noneRegisterWritesReadsUpdatesAndDeletesThroughANewPersistenceContext() {
        String region = "R-" + UUID.randomUUID().toString().substring(0, 8);
        var created = keyWrites.set(RegionRate.class, Map.of("regionCode", region),
            Map.of("rate", new BigDecimal("10.0000")), null);
        assertThat(created.verdict()).isEqualTo(RegisterKeyWrites.Verdict.CREATED);
        Long id = created.row().getId();

        entityManager.clear();
        try (EntityManager fresh = entityManagerFactory.createEntityManager()) {
            RegionRate persisted = fresh.find(RegionRate.class, id);
            assertThat(persisted).isNotNull();
            assertThat(persisted.getRegionCode()).isEqualTo(region);
            assertThat(persisted.getRate()).isEqualByComparingTo("10");
        }

        var read = data.detail(RegionRate.class, id).orElseThrow();
        assertThat(read.getRate()).isEqualByComparingTo("10");

        var updated = keyWrites.set(RegionRate.class, Map.of("regionCode", region),
            Map.of("rate", new BigDecimal("12")), null, read.getVersion());
        assertThat(updated.verdict()).isEqualTo(RegisterKeyWrites.Verdict.UPDATED);
        assertThat(updated.row().getRate()).isEqualByComparingTo("12");

        var unchanged = keyWrites.set(RegionRate.class, Map.of("regionCode", region),
            Map.of("rate", new BigDecimal("12")), null, updated.row().getVersion());
        assertThat(unchanged.verdict()).isEqualTo(RegisterKeyWrites.Verdict.UNCHANGED);

        var generic = data.detail(RegionRate.class, id).orElseThrow();
        generic.setRate(new BigDecimal("15"));
        data.update(RegionRate.class, generic);
        assertThat(data.detail(RegionRate.class, id).orElseThrow().getRate()).isEqualByComparingTo("15");

        var beforeDelete = data.detail(RegionRate.class, id).orElseThrow();
        var deleted = keyWrites.delete(RegionRate.class, Map.of("regionCode", region), null,
            beforeDelete.getVersion());
        assertThat(deleted.verdict()).isEqualTo(RegisterKeyWrites.Verdict.DELETED);
        assertThat(data.detail(RegionRate.class, id)).isEmpty();

        var absent = keyWrites.delete(RegionRate.class, Map.of("regionCode", region), null,
            beforeDelete.getVersion());
        assertThat(absent.verdict()).isEqualTo(RegisterKeyWrites.Verdict.ABSENT);
    }

    @Test
    void dayRegisterNormalizesThePeriodAndAnswersLastAndSlice() {
        Item item = item("DAY-" + UUID.randomUUID().toString().substring(0, 8));
        LocalDateTime afternoon = LocalDateTime.of(2026, 10, 31, 18, 45);

        var first = keyWrites.set(ItemRate.class, Map.of("item", item.getId()),
            Map.of("rate", new BigDecimal("100")), afternoon);
        assertThat(first.verdict()).isEqualTo(RegisterKeyWrites.Verdict.CREATED);
        assertThat(first.row().getPeriod()).isEqualTo(LocalDateTime.of(2026, 10, 31, 0, 0));

        var sameDay = keyWrites.set(ItemRate.class, Map.of("item", item.getId()),
            Map.of("rate", new BigDecimal("100")), afternoon.withHour(9));
        assertThat(sameDay.verdict()).isEqualTo(RegisterKeyWrites.Verdict.UNCHANGED);
        assertThat(sameDay.row().getId()).isEqualTo(first.row().getId());

        keyWrites.set(ItemRate.class, Map.of("item", item.getId()),
            Map.of("rate", new BigDecimal("200")), LocalDateTime.of(2026, 11, 2, 10, 0));

        var managed = entityManager.find(Item.class, item.getId());
        var reader = readers.point(ItemRate.class);
        assertThat(reader.getLast(Map.of("item", managed), LocalDateTime.of(2026, 10, 31, 23, 59)))
            .hasValueSatisfying(row -> assertThat(row.getRate()).isEqualByComparingTo("100"));
        assertThat(reader.getLast(Map.of("item", managed), LocalDateTime.of(2026, 10, 30, 23, 59)))
            .isEmpty();
        assertThat(reader.getLast(Map.of("item", managed), LocalDateTime.of(2026, 11, 2, 23, 59)))
            .hasValueSatisfying(row -> assertThat(row.getRate()).isEqualByComparingTo("200"));

        LocalDateTime asOf = LocalDateTime.of(2026, 11, 2, 12, 0);
        var slice = readers.slice(ItemRate.class).latest(
            new SliceSpec(ItemRate.class, SliceSpec.Mode.LATEST, asOf,
                List.of(new SliceSpec.Condition("item", SliceSpec.Operator.EQ, item.getId())), List.of()),
            PageRequest.of(0, 10));
        assertThat(slice.getContent()).singleElement()
            .satisfies(row -> assertThat(row.getRate()).isEqualByComparingTo("200"));
    }

    @Test
    void changingTheKeyIsRejectedWhenItRidesWithAResourceUpdate() {
        String region = "IMM-" + UUID.randomUUID().toString().substring(0, 8);
        RegionRate first = keyWrites.set(RegionRate.class, Map.of("regionCode", region),
            Map.of("rate", BigDecimal.ONE), null).row();
        RegionRate second = keyWrites.set(RegionRate.class, Map.of("regionCode", region),
            Map.of("rate", BigDecimal.TEN), null).row();

        var generic = data.detail(RegionRate.class, first.getId()).orElseThrow();
        generic.setRegionCode(region + "-CHANGED");
        generic.setRate(new BigDecimal("2"));
        assertThatThrownBy(() -> data.update(RegionRate.class, generic))
            .hasMessageContaining("REGISTER_KEY_CHANGE_REQUIRED");

        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            RegionRate row = entityManager.find(RegionRate.class, second.getId());
            row.setRegionCode("OTHER");
            row.setRate(new BigDecimal("11"));
            entityManager.flush();
        })).hasMessageContaining("REGISTER_KEY_CHANGE_REQUIRED");
        entityManager.clear();

        assertThat(data.detail(RegionRate.class, first.getId()).orElseThrow().getRegionCode())
            .isEqualTo(region);
        assertThat(data.detail(RegionRate.class, second.getId()).orElseThrow().getRegionCode())
            .isEqualTo(region);

        // A key-only mutation has no UPDATE to ride: the non-updatable mapping simply never
        // writes it, so the stored key stays the same without an exception.
        var keyOnly = data.detail(RegionRate.class, first.getId()).orElseThrow();
        keyOnly.setRegionCode(region + "-KEY-ONLY");
        data.update(RegionRate.class, keyOnly);
        assertThat(data.detail(RegionRate.class, first.getId()).orElseThrow().getRegionCode())
            .isEqualTo(region);
    }

    private Item item(String name) {
        Item item = new Item();
        item.setName(name);
        Item saved = data.create(Item.class, item);
        ConsumerGrants.rowScope(grants, USER, ItemRate.ITEM_DIMENSION, saved.getId());
        return saved;
    }
}
