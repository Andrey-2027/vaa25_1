package org.ip.application.access;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.hibernate.SessionFactory;
import org.ip.model.AttributeValue;
import org.ip.model.Branch;
import org.ip.model.Journal;
import org.ip.model.ReceivingDocument;
import org.ip.model.SklNomOpa;
import org.ip.security.PermissionsWorkspaceP0IT;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.EntityExposure;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.c5.C5Operation;
import org.ipro.rls.c5.C5OperationPermissionRegistry;
import org.ipro.rls.c5.C5ReadPermissionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Includes all six P0 checks in the same isolated context, plus the real administrative catalogue. */
public class PermissionCatalogP1IT extends PermissionIntegrationSupport {
    @Autowired PermissionCatalogService catalogService;
    @Autowired EntityDescriptorCatalog descriptorCatalog;
    @Autowired C5OperationPermissionRegistry operationRegistry;
    @Autowired C5ReadPermissionRegistry readRegistry;
    @Autowired RlsDimensionRegistry dimensionRegistry;
    @Autowired AccessGrantRepository grantRepository;
    @Autowired jakarta.persistence.EntityManagerFactory emf;
    @Autowired SecurityContextHolderStrategy securityStrategy;

    @BeforeEach
    void authenticateAdministrator() { authenticate("ROLE_ADMIN"); }
    @AfterEach
    void clearAuthentication() {
        securityStrategy.clearContext();
        SecurityContextHolder.clearContext();
    }
    private void authenticate(String authority) {
        var context = securityStrategy.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken("catalog-p1", "n/a",
            List.of(new SimpleGrantedAuthority(authority))));
        securityStrategy.setContext(context);
        SecurityContextHolder.setContext(context);
    }

    @Test
    void currentRuntimeDefinitionsAreCoveredWithoutQueriesOrGrantIssuance() throws Exception {
        var statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        var catalog = catalogService.catalog();
        assertThat(statistics.getPrepareStatementCount()).isZero();
        long roots = descriptorCatalog.all().stream().filter(descriptor -> descriptor.exposure() == EntityExposure.STANDARD_ROOT).count();
        assertThat(catalog.rows().filter(row -> row.kind() == PermissionObjectRow.Kind.ENTITY).count()).isEqualTo(roots);
        Set<String> covered = new HashSet<>(catalog.representedKeys());
        covered.addAll(catalog.diagnosticKeys());
        assertThat(covered).containsAll(readRegistry.registeredKeys());
        assertThat(covered).containsAll(operationRegistry.definitions().stream().map(definition -> definition.key()).toList());
        assertThat(covered).containsAll(dimensionRegistry.dimensions());
        assertThat(catalog.row(PermissionObjectRow.entityId(AttributeValue.class)).orElseThrow().columns())
            .extracting(PermissionObjectRow.Column::title).containsExactly("Чтение", "Создание");
        assertThat(catalog.row(PermissionObjectRow.entityId(SklNomOpa.class)).orElseThrow().columns())
            .extracting(PermissionObjectRow.Column::title).containsExactly("Чтение");
        assertThat(catalog.diagnostics()).extracting(PermissionCatalog.Diagnostic::code).containsOnly("CHECK_WITHOUT_OWNER");
        assertThat(catalog.diagnosticKeys()).containsExactly("SETTINGS:Documents");
        assertThat(grantRepository.count()).isZero();

        Path output = Path.of(System.getProperty("permissions.p0.output", "outputs/permissions-p1"));
        List<String> rows = new ArrayList<>(List.of("group\trow\tkind\ttitle\tkey\tflag\toperations"));
        catalog.rows().forEach(row -> row.columns().forEach(column -> rows.add(String.join("\t",
            row.groupId(), row.id(), row.kind().name(), row.title(), column.binding().key(),
            column.binding().flag().name(), new java.util.TreeSet<>(column.binding().operations()).toString()))));
        Files.write(output.resolve("catalog-p1.tsv"), rows, StandardCharsets.UTF_8);
    }

    @Test
    void registeredSourcesAndLegacyOwnersKeepTheirActualMeaning() {
        var catalog = catalogService.catalog();
        assertThat(catalog.scope("BRANCH").orElseThrow().valueType()).isEqualTo(Branch.class);
        assertThat(catalog.scope("JOURNAL").orElseThrow().valueType()).isEqualTo(Journal.class);
        assertThat(catalog.scope("BRANCH").orElseThrow().affectedObjectIds()).contains(PermissionObjectRow.entityId(ReceivingDocument.class));
        assertThat(catalog.rows().filter(row -> row.kind() == PermissionObjectRow.Kind.ACTION)).hasSize(7)
            .allMatch(row -> row.columns().getFirst().binding().operations().equals(Set.of(C5Operation.EXECUTE)));
        assertThat(catalog.rows().filter(row -> row.kind() == PermissionObjectRow.Kind.API)).hasSize(4);
        assertThat(catalog.row("api:REST:specifications:v1:LIST").orElseThrow().scopeIds()).containsExactly("JOURNAL");
        assertThat(catalog.rows().filter(row -> row.kind() == PermissionObjectRow.Kind.SETTINGS)).hasSize(3)
            .allMatch(row -> row.columns().getFirst().binding().flag() == PermissionBinding.Flag.UPDATE);
        var preview = catalog.rows().filter(row -> row.id().equals("legacy:REPORTS:JPQL_PREVIEW")).findFirst().orElseThrow();
        assertThat(preview.columns().getFirst().binding().authority()).isEqualTo(PermissionBinding.Authority.RLS);
        assertThat(preview.columns().getFirst().binding().flag()).isEqualTo(PermissionBinding.Flag.READ);
    }

    @Test
    void diagnosticQueryPreservesUnknownHistoricalAndWildcardGrants() {
        for (String key : List.of("UNDECLARED:P1", "ATTR:example.Type:field", "*")) {
            var grant = new AccessGrant();
            grant.setSubjectType(AccessGrant.SubjectType.USER);
            grant.setSubjectKey("catalog-p1");
            grant.setDimension(key);
            grantRepository.saveAndFlush(grant);
        }
        var diagnostics = catalogService.storedKeyDiagnostics();
        assertThat(diagnostics).extracting(PermissionCatalog.Diagnostic::key)
            .contains("UNDECLARED:P1", "ATTR:example.Type:field", "*");
        assertThat(grantRepository.count()).isEqualTo(3);
    }

    @Test
    void nonAdministratorCannotReadTheCatalogueOrStoredGrantDiagnostics() {
        authenticate("ROLE_VIEWER");
        var statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        assertThatThrownBy(catalogService::catalog).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(catalogService::storedKeyDiagnostics).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(statistics.getPrepareStatementCount()).isZero();
    }
}
