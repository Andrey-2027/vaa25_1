package com.example.entitytypes.config;

import com.example.entitytypes.model.*;
import com.example.entitytypes.model.Order;
import org.ipro.data.EntityDataAccess;
import org.ipro.crud.MetadataDrivenAggregateSaveService;
import org.ipro.events.EventSource;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.c5.C5PermissionKeys;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.data.domain.PageRequest;
import java.util.List;

/** Explicit disposable-demo grants and records, not entity registration or a business service. */
@Component @ConditionalOnProperty(name = "et5.fixtures.enabled", havingValue = "true")
public class FixtureData implements CommandLineRunner {
    public static final List<Class<?>> ROOTS = List.of(Product.class, ExtendedProduct.class, Note.class,
        Order.class, TwoSectionDocument.class, CustomCatalog.class, CustomDocument.class, InternedLabel.class);
    private final AccessGrantRepository grants;
    private final EntityDataAccess data;
    private final MetadataDrivenAggregateSaveService aggregates;
    private final boolean grantDenied;
    public FixtureData(AccessGrantRepository grants, EntityDataAccess data,
            MetadataDrivenAggregateSaveService aggregates, @Value("${et5.grant-denied:false}") boolean grantDenied) {
        this.grants = grants; this.data = data; this.aggregates = aggregates; this.grantDenied = grantDenied;
    }
    public static void grant(AccessGrantRepository grants, String user, String key, Long value, boolean write) {
        if (grants.findBySubjectTypeAndSubjectKeyAndDimension(AccessGrant.SubjectType.USER, user, key).stream()
            .anyMatch(g -> java.util.Objects.equals(value, g.getDimensionValueId()))) return;
        var g = new AccessGrant(); g.setSubjectType(AccessGrant.SubjectType.USER); g.setSubjectKey(user);
        g.setDimension(key); g.setDimensionValueId(value); g.setCanRead(true); g.setCanUpdate(write); g.setCanDelete(write);
        grants.save(g);
    }
    @Override @Transactional
    public void run(String... args) {
        for (Class<?> type : ROOTS) {
            grant(grants, "writer", C5PermissionKeys.entity(type), null, true);
            grant(grants, "reader", C5PermissionKeys.entity(type), null, false);
            grant(grants, "et5-fixtures", C5PermissionKeys.entity(type), null, true);
            if (grantDenied) grant(grants, "denied", C5PermissionKeys.entity(type), null, true);
        }
        grant(grants, "writer", Order.TEAM, 1L, true);
        grant(grants, "reader", Order.TEAM, 2L, false);
        grant(grants, "et5-fixtures", Order.TEAM, 1L, true);
        grant(grants, "et5-fixtures", Order.TEAM, 2L, true);
        if (grantDenied) grant(grants, "denied", Order.TEAM, 1L, true);
        grants.flush();
        var previous = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("et5-fixtures", null, List.of()));
        SecurityContextHolder.setContext(context);
        try {
            if (data.list(Product.class, null, PageRequest.of(0, 1)).isEmpty()) {
                var product = new Product(); product.setCode("ET5-001"); product.setName("Тестовый товар ёж");
                data.create(Product.class, product);
            }
            if (data.list(ExtendedProduct.class, null, PageRequest.of(0, 1)).isEmpty()) {
                var product = new ExtendedProduct(); product.setName("Расширенный товар");
                product.setDescription("Публикуемое описание"); product.setStorageOnly("ET5-STORAGE-ONLY");
                data.create(ExtendedProduct.class, product);
            }
            for (long team : List.of(1L, 2L)) {
                if (data.list(Order.class, (root, query, cb) -> cb.equal(root.get("teamId"), team), PageRequest.of(0, 1)).isEmpty()) {
                    var order = new Order(); order.setNumber("FIXTURE-" + team); order.setTeamId(team);
                    aggregates.save(order, List.of(), EventSource.SYSTEM);
                }
            }
        } finally {
            grants.deleteAll(grants.findAll().stream().filter(g -> g.getSubjectType() == AccessGrant.SubjectType.USER
                && g.getSubjectKey().equals("et5-fixtures")).toList());
            grants.flush();
            SecurityContextHolder.setContext(previous);
        }
    }
}
