package org.ip.application.access;

import org.ip.persistence.access.PermissionScopePageService;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.hibernate.SessionFactory;
import static org.assertj.core.api.Assertions.*;

/** Criteria paging on real mapped Branch, using isolated transactional H2. */
public class PermissionScopeP3IT extends PermissionSubjectSupport {
    @Autowired PermissionScopePageService pages;

    private PermissionScopePageService.Query query(int offset, String code, boolean selected, Map<Long, PermissionPatch.Flags> draft) {
        return new PermissionScopePageService.Query(offset, 50, code, "", selected, "code", false, draft);
    }
    private void seed(int size) {
        // Fixture-only JDBC bypass: no production bulk write path is introduced.
        em.unwrap(org.hibernate.Session.class).doWork(connection -> {
            try (var statement = connection.prepareStatement("insert into branch (code, name, version) values (?, ?, 0)")) {
                for (int i = 1; i <= size; i++) {
                    statement.setString(1, String.format("P3-%05d", i)); statement.setString(2, "P3 branch " + i);
                    statement.addBatch();
                    if (i % 500 == 0) statement.executeBatch();
                }
                statement.executeBatch();
            }
        });
    }
    private void allow(long id, String key) {
        var grant = new org.ipro.rls.AccessGrant(); grant.setSubjectType(org.ipro.rls.AccessGrant.SubjectType.ROLE);
        grant.setSubjectKey(first.getName()); grant.setDimension(key); grant.setDimensionValueId(id); grant.setCanRead(true);
        repository.saveAndFlush(grant);
    }

    @Test void largeCatalogPagesSearchAndSelectedCountWithDraftExceptions() {
        seed(4200);
        var identity = SubjectRef.of(user).identity();
        var firstPage = pages.page(identity, "BRANCH", query(0, "", false, Map.of()));
        assertThat(firstPage.total()).isEqualTo(4200);
        assertThat(firstPage.values()).hasSize(50);
        var found = pages.page(identity, "BRANCH", query(0, "P3-04199", false, Map.of()));
        assertThat(found.total()).isEqualTo(1);
        long id = found.values().getFirst().id();
        allow(id, "BRANCH");
        var selected = pages.page(identity, "BRANCH", query(0, "", true, Map.of()));
        assertThat(selected.total()).isEqualTo(1);
        assertThat(selected.values().getFirst().id()).isEqualTo(id);
        // Removing a personal patch cannot remove saved role membership.
        assertThat(pages.page(identity, "BRANCH", query(0, "", true,
            Map.of(id, new PermissionPatch.Flags(false, false, false)))).total()).isEqualTo(1);
        long added = firstPage.values().getFirst().id();
        var drafted = pages.page(identity, "BRANCH", query(0, "", true,
            Map.of(added, new PermissionPatch.Flags(true, false, false))));
        assertThat(drafted.total()).isEqualTo(2);
        assertThat(drafted.permissions().cells()).anyMatch(cell -> cell.address().valueId().equals(added)
            && cell.binding().flag() == PermissionBinding.Flag.READ && cell.direct() && cell.dirty());
        var last = pages.page(identity, "BRANCH", query(5000, "", false, Map.of()));
        assertThat(last.offset()).isEqualTo(5000);
        assertThat(last.total()).isEqualTo(4200);
        assertThat(last.values()).isEmpty();
        assertThat(pages.page(identity, "BRANCH", query(0, "%", false, Map.of())).total()).isZero();
    }

    @Test void growthToFiftyThousandKeepsPageAndSqlBudgetBounded() {
        seed(50000); em.clear();
        var stats = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        try {
            stats.clear(); long start = System.nanoTime();
            var result = pages.page(SubjectRef.of(user).identity(), "BRANCH", query(49000, "", false, Map.of()));
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            assertThat(result.total()).isEqualTo(50000); assertThat(result.values()).hasSize(50);
            assertThat(result.permissions().cells()).hasSize(150);
            assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(6);
            System.out.println("P3 H2 50000: page=50, SQL=" + stats.getPrepareStatementCount() + ", elapsedMs=" + elapsed);
        } finally { stats.setStatisticsEnabled(false); }
    }

    @Test void directRemovalGlobalSourceAndServiceSecurity() {
        seed(10); var identity = SubjectRef.of(user).identity();
        var all = pages.page(identity, "BRANCH", query(0, "", false, Map.of()));
        long id = all.values().getFirst().id();
        var grant = new org.ipro.rls.AccessGrant(); grant.setSubjectType(org.ipro.rls.AccessGrant.SubjectType.USER);
        grant.setSubjectKey(user.getUsername()); grant.setDimension("BRANCH"); grant.setDimensionValueId(id); grant.setCanRead(true);
        repository.saveAndFlush(grant);
        assertThat(pages.page(identity, "BRANCH", query(0, "", true,
            Map.of(id, new PermissionPatch.Flags(false, false, false)))).total()).isZero();
        grant.setDimensionValueId(null); repository.saveAndFlush(grant);
        assertThat(pages.page(identity, "BRANCH", query(0, "", true,
            Map.of(id, new PermissionPatch.Flags(false, false, false)))).total()).isEqualTo(10);
        var context = strategy.createEmptyContext();
        context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("ordinary", "fixture",
            List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"))));
        strategy.setContext(context); org.springframework.security.core.context.SecurityContextHolder.setContext(context);
        assertThatThrownBy(() -> pages.page(identity, "BRANCH", query(0, "", false, Map.of())))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
}
