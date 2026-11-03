package com.example.registerconsumer;

import com.example.registerconsumer.model.Item;
import com.example.registerconsumer.model.ItemRate;
import com.example.registerconsumer.model.RegionRate;
import com.example.registerconsumer.support.ConsumerGrants;
import org.ipro.crud.BaseEntity;
import org.ipro.data.EntityDataAccess;
import org.ipro.register.RegisterKeyWritePermissions;
import org.ipro.register.RegisterKeyWrites;
import org.ipro.rls.AccessGrantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A2.1 security acceptance: entity grants and named-operation grants are checked separately,
 * reader cannot write, RLS follows the reference dimension, and the named set does not move an
 * existing row to another key.
 */
@SpringBootTest(properties = "consumer.demo-password=register-consumer-test-only")
class RegisterConsumerSecurityTest {

    @Autowired EntityDataAccess data;
    @Autowired RegisterKeyWrites keyWrites;
    @Autowired RegisterKeyWritePermissions keyWritePermissions;
    @Autowired AccessGrantRepository grants;

    @BeforeEach
    void noGrants() {
        ConsumerGrants.logout();
        for (String user : List.of("writer", "reader", "denied")) {
            ConsumerGrants.revokeAll(grants, user);
        }
    }

    @AfterEach
    void clearPrincipal() {
        ConsumerGrants.logout();
    }

    @Test
    void withoutGrantsReadsAndNamedWritesAreDenied() {
        writerGrants();
        Item item = writerItem("DENIED-");
        RegionRate row = writerSet("DENIED-" + suffix(), new BigDecimal("10"));

        ConsumerGrants.login("denied");
        assertThatThrownBy(() -> data.list(RegionRate.class, null, PageRequest.of(0, 10)))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> keyWrites.set(RegionRate.class, Map.of("regionCode", "X"),
            Map.of("rate", BigDecimal.ONE), null))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> data.detail(RegionRate.class, row.getId()))
            .isInstanceOf(AccessDeniedException.class);
        assertThat(keyWritePermissions.canWriteKey(ItemRate.class, false)).isFalse();
    }

    @Test
    void entityGrantsAndTheNamedOperationAreSeparateDecisions() {
        writerGrants();
        Item item = writerItem("ENTITY-");
        ConsumerGrants.revokeAll(grants, "writer");
        ConsumerGrants.login("writer");
        ConsumerGrants.entityWrite(grants, "writer", Item.class);
        ConsumerGrants.entityWrite(grants, "writer", RegionRate.class);
        ConsumerGrants.entityWrite(grants, "writer", ItemRate.class);
        ConsumerGrants.rowScope(grants, "writer", ItemRate.ITEM_DIMENSION, item.getId());

        RegionRate generic = new RegionRate("GEN-" + suffix(), BigDecimal.ONE);
        assertThat(data.create(RegionRate.class, generic).getId()).isNotNull();

        String region = "NAMED-" + suffix();
        assertThatThrownBy(() -> keyWrites.set(RegionRate.class, Map.of("regionCode", region),
            Map.of("rate", BigDecimal.TEN), null))
            .as("generic CRUD is allowed, the named set is not")
            .isInstanceOf(AccessDeniedException.class);

        ConsumerGrants.action(grants, "writer", RegisterKeyWritePermissions.setPermissionId(RegionRate.class));
        var created = keyWrites.set(RegionRate.class, Map.of("regionCode", region),
            Map.of("rate", BigDecimal.TEN), null);
        assertThat(created.verdict()).isEqualTo(RegisterKeyWrites.Verdict.CREATED);
        assertThat(keyWritePermissions.canWriteKey(RegionRate.class, false)).isTrue();
        assertThat(keyWritePermissions.canWriteKey(RegionRate.class, true))
            .as("delete is a separate action")
            .isFalse();

        assertThatThrownBy(() -> keyWrites.delete(RegionRate.class, Map.of("regionCode", region), null,
            created.row().getVersion()))
            .isInstanceOf(AccessDeniedException.class);

        data.delete(RegionRate.class, generic.getId());
        assertThat(data.detail(RegionRate.class, generic.getId())).isEmpty();
    }

    @Test
    void readerSeesOnlyRowsAllowedByTheReferenceDimensionScope() {
        writerGrants();
        Item allowed = writerItem("ALLOWED-");
        Item foreign = writerItem("FOREIGN-");
        ItemRate allowedRow = writerItemRate(allowed, new BigDecimal("11"));
        ItemRate foreignRow = writerItemRate(foreign, new BigDecimal("22"));

        ConsumerGrants.login("reader");
        ConsumerGrants.entityRead(grants, "reader", ItemRate.class);
        ConsumerGrants.rowScope(grants, "reader", ItemRate.ITEM_DIMENSION, allowed.getId());

        List<Long> visible = data.list(ItemRate.class, null, PageRequest.of(0, 50)).getContent().stream()
            .map(BaseEntity::getId).toList();
        assertThat(visible).contains(allowedRow.getId()).doesNotContain(foreignRow.getId());
        assertThat(data.detail(ItemRate.class, foreignRow.getId())).isEmpty();

        ItemRate loaded = data.detail(ItemRate.class, allowedRow.getId()).orElseThrow();
        loaded.setRate(new BigDecimal("99"));
        assertThatThrownBy(() -> data.update(ItemRate.class, loaded))
            .as("reader has no update permission")
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void namedSetNeverMovesAnExistingRowToAnotherKey() {
        writerGrants();
        String firstRegion = "MOVE-" + suffix();
        String secondRegion = "MOVE-" + suffix();
        var created = keyWrites.set(RegionRate.class, Map.of("regionCode", firstRegion),
            Map.of("rate", new BigDecimal("7")), null);

        var other = keyWrites.set(RegionRate.class, Map.of("regionCode", secondRegion),
            Map.of("rate", new BigDecimal("8")), null);
        assertThat(other.verdict()).isEqualTo(RegisterKeyWrites.Verdict.CREATED);
        assertThat(other.row().getId()).isNotEqualTo(created.row().getId());
        assertThat(data.detail(RegionRate.class, created.row().getId()).orElseThrow().getRegionCode())
            .isEqualTo(firstRegion);
        assertThat(data.detail(RegionRate.class, created.row().getId()).orElseThrow().getRate())
            .isEqualByComparingTo("7");
    }

    private void writerGrants() {
        ConsumerGrants.login("writer");
        ConsumerGrants.revokeAll(grants, "writer");
        ConsumerGrants.entityWrite(grants, "writer", Item.class);
        ConsumerGrants.entityWrite(grants, "writer", RegionRate.class);
        ConsumerGrants.entityWrite(grants, "writer", ItemRate.class);
        ConsumerGrants.action(grants, "writer", RegisterKeyWritePermissions.setPermissionId(RegionRate.class));
        ConsumerGrants.action(grants, "writer", RegisterKeyWritePermissions.deletePermissionId(RegionRate.class));
        ConsumerGrants.action(grants, "writer", RegisterKeyWritePermissions.setPermissionId(ItemRate.class));
        ConsumerGrants.action(grants, "writer", RegisterKeyWritePermissions.deletePermissionId(ItemRate.class));
    }

    private Item writerItem(String prefix) {
        ConsumerGrants.login("writer");
        Item item = new Item();
        item.setName(prefix + suffix());
        Item saved = data.create(Item.class, item);
        ConsumerGrants.rowScope(grants, "writer", ItemRate.ITEM_DIMENSION, saved.getId());
        return saved;
    }

    private RegionRate writerSet(String region, BigDecimal rate) {
        ConsumerGrants.login("writer");
        return keyWrites.set(RegionRate.class, Map.of("regionCode", region), Map.of("rate", rate), null).row();
    }

    private ItemRate writerItemRate(Item item, BigDecimal rate) {
        ConsumerGrants.login("writer");
        return keyWrites.set(ItemRate.class, Map.of("item", item.getId()), Map.of("rate", rate),
            LocalDateTime.now()).row();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
