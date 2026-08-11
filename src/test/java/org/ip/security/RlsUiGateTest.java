package org.ip.security;

import jakarta.persistence.EntityManager;
import org.ip.model.Branch;
import org.ip.model.Journal;
import org.ip.model.Nomenclature;
import org.ip.model.PrdSpec;
import org.ip.model.ReceivingDocument;
import org.ip.model.Workshop;
import org.ip.repository.UserRepository;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsUiGate;
import org.ipro.rls.RlsUiGate.AccessDecision;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Решения RlsUiGate "что разрешено" против настоящего AccessService/H2 (стиль
 * RlsIntegrationTest — DataJpaTest с ручной сборкой, без Vaadin/весного контекста).
 * Зеркальные правила write-guard'а AbstractBaseService; сами сервисы не поднимаются.
 */
@DataJpaTest
@EnableJpaRepositories(basePackages = {"org.ip", "org.ipro.rls"})
class RlsUiGateTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private AccessGrantRepository accessGrantRepository;

    @Autowired
    private UserRepository userRepository;

    private RlsUiGate gate;
    private Long journalAId;

    @BeforeEach
    void setUp() {
        var registry = new RlsDimensionRegistry("org.ip");
        registry.rebuild();
        AccessService accessService = new AccessService(accessGrantRepository,
            new UserRepositoryRlsRoleResolver(userRepository), registry);
        gate = new RlsUiGate(accessService, registry, () -> CurrentUser.username());

        Journal journalA = new Journal();
        journalA.setCode("G-A");
        journalA.setName("Журнал A");
        entityManager.persist(journalA);
        entityManager.flush();
        journalAId = journalA.getId();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(String username) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
    }

    private AccessGrant persistGrant(AccessGrant.SubjectType type, String subjectKey, String dimension,
                                     Long dimensionValueId, boolean read, boolean update, boolean delete) {
        AccessGrant grant = new AccessGrant();
        grant.setSubjectType(type);
        grant.setSubjectKey(subjectKey);
        grant.setDimension(dimension);
        grant.setDimensionValueId(dimensionValueId);
        grant.setCanRead(read);
        grant.setCanUpdate(update);
        grant.setCanDelete(delete);
        entityManager.persist(grant);
        return grant;
    }

    private Branch persistBranch(String code) {
        Branch branch = new Branch();
        branch.setCode(code);
        branch.setName("Филиал " + code);
        entityManager.persist(branch);
        return branch;
    }

    private Workshop persistWorkshop(String code, Branch branch) {
        Workshop workshop = new Workshop(code, code);
        workshop.setBranch(branch);
        entityManager.persist(workshop);
        return workshop;
    }

    private ReceivingDocument persistDocument(String number, Journal journal, Workshop receiver, Workshop deliverer) {
        ReceivingDocument doc = new ReceivingDocument(number, java.time.LocalDate.now(), receiver, deliverer);
        doc.setJournal(journal);
        entityManager.persist(doc);
        return doc;
    }

    /** План Ф2: alice с построчным грантом (JOURNAL→journalA, update) → canCreate(PrdSpec) = true. */
    @Test
    void aliceWithPerValueUpdateGrantCanCreatePrdSpec() {
        persistGrant(AccessGrant.SubjectType.USER, "alice", "JOURNAL", journalAId, true, true, false);
        entityManager.flush();
        loginAs("alice");

        assertThat(gate.canCreate(PrdSpec.class).allowed()).isTrue();
    }

    /** План Ф2: read-only пользователь → canCreate(PrdSpec) = false с причиной. */
    @Test
    void readOnlyRightsDoNotAllowCreate() {
        persistGrant(AccessGrant.SubjectType.USER, "bob", "JOURNAL", journalAId, true, false, false);
        entityManager.flush();
        loginAs("bob");

        AccessDecision decision = gate.canCreate(PrdSpec.class);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("JOURNAL");
    }

    /**
     * План Ф2: CHECK_ONLY без гранта → canUpdate(ReceivingDocument) = false. carol имеет
     * права на JOURNAL (journalA) и BRANCH (branchA) — обе проверки документа проходят,
     * блокирует именно отсутствие ENTITY-гранта (bootstrap на CHECK_ONLY не действует).
     */
    @Test
    void checkOnlyGateWithoutEntityGrantBlocksDocumentUpdate() {
        Branch branchA = persistBranch("G-BR");
        Workshop workshopA = persistWorkshop("G-W1", branchA);
        Workshop workshopB = persistWorkshop("G-W2", branchA);
        Journal journalA = entityManager.find(Journal.class, journalAId);
        ReceivingDocument doc = persistDocument("G-RD", journalA, workshopA, workshopB);

        persistGrant(AccessGrant.SubjectType.USER, "carol", "JOURNAL", journalAId, true, true, false);
        persistGrant(AccessGrant.SubjectType.USER, "carol", "BRANCH", branchA.getId(), true, true, false);
        entityManager.flush();
        loginAs("carol");

        AccessDecision decision = gate.canUpdate(doc);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("ENTITY:ReceivingDocument");
    }

    /** План Ф2: сущность без RLS (Nomenclature — ни @RlsDimension, ни RlsDimensionValue) — всё разрешено. */
    @Test
    void entityWithoutRlsIsFullyAllowed() {
        loginAs("mallory");
        Nomenclature nomenclature = new Nomenclature();

        assertThat(gate.canCreate(Nomenclature.class).allowed()).isTrue();
        assertThat(gate.canUpdate(nomenclature).allowed()).isTrue();
        assertThat(gate.canDelete(nomenclature).allowed()).isTrue();
    }
}