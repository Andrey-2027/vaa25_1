package org.ipro.reportstudio.query;

import jakarta.persistence.EntityManagerFactory;
import org.ipro.reportstudio.query.q6.Q6JpaTestConfiguration;
import org.ipro.reportstudio.query.q6.Q6Product;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = "spring.sql.init.mode=never")
@ContextConfiguration(classes = Q6JpaTestConfiguration.class)
class VisualQueryPackageRlsIT {
    @Autowired EntityManagerFactory entityManagerFactory;

    @Test
    void guardChecksEntityUsedOnlyInsideCte() {
        var access = mock(org.ipro.rls.AccessService.class);
        var registry = new org.ipro.rls.RlsDimensionRegistry("org.ipro.reportstudio.query.q6");
        registry.rebuild();
        var guard = guard(access, registry);

        var result = guard.guard("with tmp1 as (select p.code as code from Q6Product p) "
                + "select t.code from tmp1 t", Set.of());

        assertThat(result.allowed()).isTrue();
        assertThat(result.analysis().entities()).anyMatch(e -> e.entityName().contains("Q6Product"));
        if (!registry.dimensionsOf(Q6Product.class).isEmpty()) {
            org.mockito.Mockito.verify(access, org.mockito.Mockito.atLeastOnce())
                    .getReadableIds(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("smoke"));
        }
    }

    @Test
    void guardRejectsWhenCteEntityHasNoReadAccess() {
        var access = mock(org.ipro.rls.AccessService.class);
        var registry = new org.ipro.rls.RlsDimensionRegistry("org.ipro.reportstudio.query.q6");
        registry.rebuild();
        if (registry.dimensionsOf(Q6Product.class).isEmpty()) return;
        when(access.getReadableIds(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("smoke")))
                .thenReturn(List.of(org.ipro.rls.AccessService.NO_ACCESS_SENTINEL));

        var result = guard(access, registry).guard("with tmp1 as (select p.code as code from Q6Product p) "
                + "select t.code from tmp1 t", Set.of());

        assertThat(result.allowed()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("Нет доступа")
                && error.contains("Q6Product"));
    }

    private ReportQueryGuard guard(org.ipro.rls.AccessService access,
                                   org.ipro.rls.RlsDimensionRegistry registry) {
        return new ReportQueryGuard(new org.ipro.reportstudio.query.sqm.SqmQuerySemanticAnalyzer(entityManagerFactory),
                access, registry, () -> "smoke", entityManagerFactory);
    }

}
