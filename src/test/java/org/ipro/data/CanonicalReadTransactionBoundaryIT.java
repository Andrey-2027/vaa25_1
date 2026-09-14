package org.ipro.data;

import org.ip.config.DataInitializer;
import org.ip.model.Branch;
import org.ip.model.Workshop;
import org.ip.repository.BranchRepository;
import org.ip.repository.WorkshopRepository;
import org.ipro.crud.LookupService;
import org.ipro.crud.ServiceLocator;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C4.6: canonical read boundary сама открывает транзакцию и не зависит от вызывающего.
 *
 * <p>Тест намеренно <b>не</b> транзакционный: {@code RlsFilterActivator} включает Hibernate
 * {@code @Filter} на сессии текущего {@code EntityManager}. Без активной транзакции shared
 * {@code EntityManager} proxy выдаёт каждый вызов на отдельной сессии, поэтому gate включает
 * фильтр на одной сессии, а content query выполняется на другой — и RLS молча не применяется
 * (чужой филиал становится виден). Раньше это маскировалось тем, что вызывающие сервисы
 * наследовали class-level {@code @Transactional} от compatibility base, а RLS-тесты шли
 * внутри транзакции теста; после C4.6 граница обязана быть самодостаточной.</p>
 */
@SpringBootTest(classes = org.ip.Application.class)
class CanonicalReadTransactionBoundaryIT {

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private LookupService lookupService;

    @Autowired
    private ServiceLocator serviceLocator;

    @Autowired
    private AccessGrantRepository accessGrantRepository;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private WorkshopRepository workshopRepository;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void foreignBranchRowIsInvisibleToLookupAndListWithoutAmbientTransaction() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Fixture fixture = RlsTestFixture.callAsSuperuser(accessGrantRepository, () -> fixture(suffix));
        try {
            loginAs(fixture.username());

            assertThat(lookupService.findById(Workshop.class, fixture.foreignWorkshop().getId()))
                .as("lookup обязан прятать чужой филиал без транзакции вызывающего")
                .isEmpty();

            // Конкретный handle: у интерфейса BaseService методы delete(Long) из CrudService и
            // delete(ID) из BaseService при ID = Long неразличимы.
            @SuppressWarnings("unchecked")
            CanonicalEntityService<Workshop> workshops = (CanonicalEntityService<Workshop>)
                serviceLocator.<Workshop, Long>findService(Workshop.class);

            assertThat(workshops.findAll())
                .as("список идёт той же границей: строк чужого филиала в нём быть не должно")
                .extracting(Workshop::getId)
                .contains(fixture.ownWorkshop().getId())
                .doesNotContain(fixture.foreignWorkshop().getId());
            assertThat(workshops.findById(fixture.foreignWorkshop().getId()))
                .as("detail той же границей прячет чужую строку")
                .isEmpty();
        } finally {
            RlsTestFixture.runAsSuperuser(accessGrantRepository, () -> cleanup(fixture));
        }
    }

    private Fixture fixture(String suffix) {
        Branch allowed = branchRepository.save(branch("A-" + suffix));
        Branch foreign = branchRepository.save(branch("B-" + suffix));
        Workshop own = workshopRepository.save(workshop("W-A-" + suffix, allowed));
        Workshop foreignWorkshop = workshopRepository.save(workshop("W-B-" + suffix, foreign));

        String username = "canonical-read-" + suffix;
        AccessGrant grant = new AccessGrant();
        grant.setSubjectType(AccessGrant.SubjectType.USER);
        grant.setSubjectKey(username);
        grant.setDimension("BRANCH");
        grant.setDimensionValueId(allowed.getId());
        grant.setCanRead(true);
        accessGrantRepository.saveAndFlush(grant);

        return new Fixture(username, own, foreignWorkshop, foreign, allowed);
    }

    private void cleanup(Fixture fixture) {
        accessGrantRepository.deleteAll(accessGrantRepository
            .findBySubjectTypeAndSubjectKeyAndDimension(
                AccessGrant.SubjectType.USER, fixture.username(), "BRANCH"));
        accessGrantRepository.flush();
        workshopRepository.deleteAll(List.of(fixture.ownWorkshop(), fixture.foreignWorkshop()));
        branchRepository.deleteAll(List.of(fixture.foreignBranch(), fixture.allowedBranch()));
    }

    private void loginAs(String username) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
    }

    private static Branch branch(String code) {
        Branch branch = new Branch();
        branch.setCode(code);
        branch.setName("Филиал " + code);
        return branch;
    }

    private static Workshop workshop(String code, Branch branch) {
        Workshop workshop = new Workshop(code, "Цех " + code);
        workshop.setBranch(branch);
        return workshop;
    }

    private record Fixture(String username, Workshop ownWorkshop, Workshop foreignWorkshop,
                           Branch foreignBranch, Branch allowedBranch) {
    }
}
