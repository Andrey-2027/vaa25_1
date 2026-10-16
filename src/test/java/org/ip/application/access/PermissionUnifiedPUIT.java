package org.ip.application.access;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.ip.config.InitialGrantIssuance;
import org.ip.model.AttributeValue;
import org.ip.model.Branch;
import org.ip.model.Role;
import org.ip.model.User;
import org.ip.security.PermissionsWorkspaceP0IT;
import org.ip.service.AccessGrantAdminService;
import org.ip.service.AccessGrantAdminService.GrantFlags;
import org.ipro.data.EntityDataAccess;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.c5.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.*;

/** P-U: real definitions, administration, decisions and canonical writes share one entity row. */
@TestPropertySource(properties = "rls.write-boundary.enforced-types=org.ip.model.AccountingObjectInstallation,org.ip.model.AccountingObjectCorrection,org.ip.model.NomenclatureAccountingObject")
public class PermissionUnifiedPUIT extends PermissionIntegrationSupport {
    @Autowired AccessGrantRepository repository;
    @Autowired AccessGrantAdminService administrator;
    @Autowired C5PermissionDecisionService decision;
    @Autowired C5OperationPermissionRegistry definitions;
    @Autowired C5ReadPermissionRegistry readProjection;
    @Autowired PermissionCatalogService catalog;
    @Autowired EntityDataAccess access;
    @Autowired org.ipro.rls.RlsDimensionRegistry scopeRegistry;
    @Autowired SecurityContextHolderStrategy strategy;
    @PersistenceContext EntityManager em;
    private static final String SUBJECT = "pu-administrator";

    @BeforeEach void authenticate() {
        User subject = new User(SUBJECT, "fixture-password");
        em.persist(subject); em.flush();
        var context = strategy.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(SUBJECT, "fixture",
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        strategy.setContext(context);
        SecurityContextHolder.setContext(context);
    }
    @AfterEach void clearSecurity() { strategy.clearContext(); SecurityContextHolder.clearContext(); }

    @Test void everyEntityHasOneDefinitionOneAddressAndNoOldShortKey() {
        var snapshot = catalog.catalog();
        snapshot.rows().filter(row -> row.kind() == PermissionObjectRow.Kind.ENTITY).forEach(row -> {
            assertThat(row.columns()).extracting(column -> column.binding().key()).containsOnly(C5PermissionKeys.entity(row.entityType()));
            assertThat(row.columns()).extracting(column -> column.binding().authority()).containsOnly(PermissionBinding.Authority.C5);
            assertThat(definitions.isRegistered("ENTITY:" + row.entityType().getSimpleName())).isFalse();
        });
        assertThat(readProjection.registeredKeys()).allMatch(key -> definitions.operationsOf(key).contains(C5Operation.READ));
        for (Class<?> type : List.of(org.ip.model.AccountingObjectInstallation.class,
                org.ip.model.AccountingObjectCorrection.class, org.ip.model.NomenclatureAccountingObject.class)) {
            assertThat(scopeRegistry.policyOf(type).checkOnlyDimensions()).isEmpty();
            assertThat(scopeRegistry.isWriteGoverned(type)).isTrue();
        }
        assertThat(administrator.ungrantedDefinitions()).extracting(AccessGrantAdminService.UngrantedDefinition::key).doesNotHaveDuplicates();
    }

    @Test void oneStoredRowKeepsReadAndWriteIndependentAndShowsBoth() {
        String key = C5PermissionKeys.entity(User.class);
        save(key, new GrantFlags(true, true, true));
        long id = global(key).getId();
        save(key, new GrantFlags(false, true, true));
        assertThat(global(key).getId()).isEqualTo(id);
        assertThat(decision.decideEntity(SUBJECT, User.class, C5Operation.READ).allowed()).isFalse();
        assertThat(decision.decideEntity(SUBJECT, User.class, C5Operation.UPDATE).allowed()).isFalse();
        var effective = administrator.collectEffective(AccessGrant.SubjectType.USER, SUBJECT).get(key);
        assertThat(effective.canRead()).isFalse();
        assertThat(decision.decideEntity(SUBJECT, User.class, C5Operation.CREATE).allowed()).isTrue();
        save(key, new GrantFlags(true, false, false));
        assertThat(global(key).getId()).isEqualTo(id);
        assertThat(decision.decideEntity(SUBJECT, User.class, C5Operation.READ).allowed()).isTrue();
        assertThat(decision.decideEntity(SUBJECT, User.class, C5Operation.UPDATE).allowed()).isFalse();
        save(key, GrantFlags.NONE);
        assertThat(repository.findBySubjectTypeAndSubjectKeyAndDimensionAndDimensionValueIdIsNull(AccessGrant.SubjectType.USER, SUBJECT, key)).isEmpty();
    }

    @Test void capabilityAndExactGlobalRulesStillApply() {
        String key = C5PermissionKeys.entity(AttributeValue.class);
        assertThat(administrator.expressibleFlags(key)).isEqualTo(new GrantFlags(true, true, false));
        assertThatThrownBy(() -> save(key, new GrantFlags(true, true, true))).isInstanceOf(IllegalArgumentException.class);
        raw("ENTITY:AttributeValue", null, true, true, true);
        raw("*", null, true, true, true);
        raw(key, 999L, true, true, true);
        repository.flush();
        assertThat(decision.decideEntity(SUBJECT, AttributeValue.class, C5Operation.READ).allowed()).isFalse();
        assertThat(decision.decideEntity(SUBJECT, AttributeValue.class, C5Operation.CREATE).allowed()).isFalse();
        assertThatThrownBy(() -> decision.decide(SUBJECT, "ENTITY:AttributeValue", C5Operation.READ)).isInstanceOf(C5PermissionConfigurationException.class);
        assertThatThrownBy(() -> decision.decide(SUBJECT, "ENTITY:AttributeValue", C5Operation.CREATE)).isInstanceOf(C5PermissionConfigurationException.class);
    }

    @Test void explicitInitialIssuanceCreatesOneCompleteRowAndIsIdempotent() {
        var issuer = new InitialGrantIssuance(repository, definitions, readProjection, List.of("PU_ADMIN"),
            List.of("USER:ASSIGN_ROLES", "USER:CHANGE_PASSWORD"));
        assertThat(issuer.issueForRole("PU_ADMIN")).isGreaterThan(0);
        var entityRows = repository.findBySubjectTypeAndSubjectKeyAndDimensionAndDimensionValueIdIsNull(
            AccessGrant.SubjectType.ROLE, "PU_ADMIN", C5PermissionKeys.entity(User.class));
        assertThat(entityRows).hasSize(1);
        assertThat(entityRows.getFirst().isCanRead()).isTrue();
        assertThat(entityRows.getFirst().isCanUpdate()).isTrue();
        assertThat(entityRows.getFirst().isCanDelete()).isTrue();
        assertThat(issuer.issueForRole("PU_ADMIN")).isZero();
        assertThat(repository.findBySubjectTypeAndSubjectKeyAndDimensionAndDimensionValueIdIsNull(
            AccessGrant.SubjectType.ROLE, "PU_ADMIN", "ENTITY:User")).isEmpty();
    }

    @Test void canonicalBranchCrudUsesTheUnifiedGrantAndRevocationDeniesWrites() {
        save(C5PermissionKeys.entity(Branch.class), new GrantFlags(true, true, true));
        save("BRANCH", new GrantFlags(true, true, true));
        Branch branch = new Branch(); branch.setCode("PU-BRANCH"); branch.setName("P-U fixture");
        Branch created = access.create(Branch.class, branch);
        em.flush();
        created.setName("P-U changed");
        Branch updated = access.update(Branch.class, created);
        em.flush();
        assertThat(updated.getName()).isEqualTo("P-U changed");
        em.detach(updated);
        save(C5PermissionKeys.entity(Branch.class), new GrantFlags(true, false, true));
        updated.setName("forbidden");
        assertThatThrownBy(() -> access.update(Branch.class, updated)).isInstanceOf(C5AccessDeniedException.class);
        access.delete(Branch.class, updated.getId());
        em.flush();
    }

    @Test void havingAllFlagsDoesNotAuthorizeDirectHibernateInsertion() {
        save(C5PermissionKeys.entity(Branch.class), new GrantFlags(true, true, true));
        save("BRANCH", new GrantFlags(true, true, true));
        Branch branch = new Branch(); branch.setCode("PU-DIRECT"); branch.setName("Direct insertion must fail");
        assertThatThrownBy(() -> { em.persist(branch); em.flush(); })
            .isInstanceOf(org.ipro.rls.RlsAccessDeniedException.class);
    }

    private void save(String key, GrantFlags flags) { administrator.saveSingleGrant(key, AccessGrant.SubjectType.USER, SUBJECT, flags); }
    private AccessGrant global(String key) {
        var rows = repository.findBySubjectTypeAndSubjectKeyAndDimensionAndDimensionValueIdIsNull(AccessGrant.SubjectType.USER, SUBJECT, key);
        assertThat(rows).hasSize(1); return rows.getFirst();
    }
    private void raw(String key, Long value, boolean read, boolean update, boolean delete) {
        AccessGrant grant = new AccessGrant(); grant.setSubjectType(AccessGrant.SubjectType.USER); grant.setSubjectKey(SUBJECT);
        grant.setDimension(key); grant.setDimensionValueId(value); grant.setCanRead(read); grant.setCanUpdate(update); grant.setCanDelete(delete);
        repository.save(grant);
    }
}
