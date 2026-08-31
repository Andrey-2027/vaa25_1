package org.ipro.reportstudio.query;

import jakarta.persistence.EntityManagerFactory;
import org.ipro.reportstudio.dom.ReportQuerySource;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.query.q6.Q6JpaTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = "spring.sql.init.mode=never")
@ContextConfiguration(classes = Q6JpaTestConfiguration.class)
class VisualQueryPackageAssemblyIT {
    @Autowired EntityManagerFactory entityManagerFactory;

    @Test
    void assemblesSavedPackageInsteadOfDroppingCtes() {
        var first = definition("Q6Product", "p");
        var main = definition("tmp1", "t");
        var pack = new VisualQueryPackage(List.of(new VisualQueryPackage.Cte("tmp1", first)), main);

        var template = new ReportTemplate();
        template.setJpql("ignored");
        template.setQuerySource(ReportQuerySource.VISUAL);
        template.setVisualQueryJson(new VisualQueryPackageJsonCodec().write(pack));

        var catalog = new QueryBuilderMetadataCatalog(entityManagerFactory,
                new org.ipro.metadata.MetadataResolver(),
                new org.ipro.rls.RlsReadGate(null, null) {
                    @Override public boolean canRead(Class<?> type, String user) { return true; }
                }, () -> "smoke");
        var guard = mock(ReportQueryGuard.class);
        when(guard.guard(any(String.class), any(Set.class), any(Map.class)))
                .thenReturn(new GuardResult(true, List.of(), List.of(), null));
        when(guard.guard(any(String.class), any(Set.class)))
                .thenReturn(new GuardResult(true, List.of(), List.of(), null));
        var resolver = mock(org.ipro.reportstudio.param.ReportParamResolver.class);
        when(resolver.resolve(any(), any(), any()))
                .thenReturn(new org.ipro.reportstudio.param.ResolvedParams(Map.of(), List.of(), List.of()));
        var service = new ReportQueryAssemblyService(guard, resolver,
                mock(org.ipro.reportstudio.param.EntityParamRefresher.class), catalog);

        var assembled = service.assemble(template,
                mock(org.ipro.reportstudio.param.ReportContext.class), Map.of());

        assertThat(assembled.jpql()).startsWith("with tmp1 as (")
                .contains("select p.code as code from Q6Product p")
                .endsWith("select t.code as code from tmp1 t");
        assertThat(template.getVisualQueryJson()).contains("\"ctes\"");
    }

    private static VisualQueryDefinition definition(String entity, String alias) {
        return new VisualQueryDefinition(VisualQueryDefinition.CURRENT_VERSION, entity, alias,
                List.of(new VisualQueryDefinition.SelectField("code", "code")),
                List.of(), List.of(), List.of(), List.of(), null, null, List.of(), List.of());
    }
}
