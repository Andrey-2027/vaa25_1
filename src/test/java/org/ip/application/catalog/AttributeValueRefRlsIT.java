package org.ip.application.catalog;

import org.ip.config.DataInitializer;
import org.ip.model.AttributeType;
import org.ip.model.AttributeValueType;
import org.ip.model.Branch;
import org.ip.model.Workshop;
import org.ip.repository.AttributeValueRepository;
import org.ip.repository.BranchRepository;
import org.ip.repository.WorkshopRepository;
import org.ip.service.AttributeValueService;
import org.ipro.crud.ValidationException;
import org.ipro.data.EntityDataAccess;
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
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DAC-13: REF-значения на защищённый словарь ({@code Workshop}, измерение BRANCH)
 * резолвятся только через RLS-aware чтение. Пользователь ветки A создаёт ссылку
 * на цех своей ветки, а цех ветки B для него неотличим от отсутствующего —
 * единой доменной ошибкой, без раскрытия существования чужой записи.
 */
@SpringBootTest(classes = org.ip.Application.class)
class AttributeValueRefRlsIT {

    private final List<Fixture> fixtures = new ArrayList<>();

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private AttributeValueService attributeValueService;

    @Autowired
    private AttributeValueRepository attributeValueRepository;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private WorkshopRepository workshopRepository;

    @Autowired
    private AccessGrantRepository accessGrantRepository;

    @Autowired
    private EntityDataAccess access;

    /** Bulk-уборка строк значения: у типа нет canonical delete by design (см. cleanup). */
    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactions;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        for (Fixture fixture : List.copyOf(fixtures)) {
            RlsTestFixture.runAsSuperuser(accessGrantRepository, () -> cleanup(fixture));
        }
        fixtures.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void userCreatesRefToOwnBranchWorkshop() {
        Fixture fixture = RlsTestFixture.callAsSuperuser(accessGrantRepository, this::fixture);
        fixtures.add(fixture);
        loginAs(fixture.username());

        assertThat(attributeValueService
            .getOrCreateRef(fixture.type(), fixture.workshopA().getId())
            .getRefId()).isEqualTo(fixture.workshopA().getId());
        assertThat(attributeValueService.getOrCreateRefInCurrentTransaction(
            fixture.type(), fixture.workshopA().getId()).getRefId())
            .isEqualTo(fixture.workshopA().getId());
    }

    @Test
    void foreignBranchWorkshopIsIndistinguishableFromMissing() {
        Fixture fixture = RlsTestFixture.callAsSuperuser(accessGrantRepository, this::fixture);
        fixtures.add(fixture);
        loginAs(fixture.username());

        assertThatThrownBy(() -> attributeValueService.getOrCreateRef(
            fixture.type(), fixture.workshopB().getId()))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("не найдена или недоступна");
        assertThatThrownBy(() -> attributeValueService.getOrCreateRefInCurrentTransaction(
            fixture.type(), fixture.workshopB().getId()))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("не найдена или недоступна");

        assertThat(attributeValueRepository.findByAttrTypeAndRefId(
            fixture.type(), fixture.workshopB().getId())).isEmpty();
    }

    private void cleanup(Fixture fixture) {
        // Строки значения снимаются bulk-запросом: значения бессмертны, canonical delete у типа
        // нет by design (ADR-0019 §3), а repository-удаление managed-строки вооружённая граница
        // отклоняет на flush — и правильно делает. Bulk-операция не является entity-write и
        // delete-handle типу не выдаёт: это уборка данных теста, а не пользовательский путь.
        transactions.executeWithoutResult(status -> entityManager.createQuery(
                "delete from AttributeValue v where v.attrType.id = :typeId")
            .setParameter("typeId", fixture.type().getId())
            .executeUpdate());
        // Тип атрибута снимается canonical path: у него есть write handle, значит, прямая
        // запись — вход в обход финальной границы (ADR-0017 §9).
        grantTypeReadKey();
        access.delete(AttributeType.class, fixture.type().getId());
        workshopRepository.deleteAll(List.of(fixture.workshopA(), fixture.workshopB()));
        branchRepository.deleteAll(List.of(fixture.workshopA().getBranch(), fixture.workshopB().getBranch()));
        accessGrantRepository.deleteAll(accessGrantRepository
            .findBySubjectTypeAndSubjectKeyAndDimension(
                AccessGrant.SubjectType.USER, fixture.username(), "BRANCH"));
    }

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Branch branchA = branchRepository.save(branch("A-" + suffix));
        Branch branchB = branchRepository.save(branch("B-" + suffix));
        Workshop workshopA = workshopRepository.save(workshop("W-A-" + suffix, branchA));
        Workshop workshopB = workshopRepository.save(workshop("W-B-" + suffix, branchB));

        AttributeType type = new AttributeType(
            "REF-" + suffix, "Ссылка " + suffix, AttributeValueType.REF);
        type.setTargetDictionary(Workshop.class.getName());
        type = createType(type);

        String username = "ref-user-" + suffix;
        AccessGrant grant = new AccessGrant();
        grant.setSubjectType(AccessGrant.SubjectType.USER);
        grant.setSubjectKey(username);
        grant.setDimension("BRANCH");
        grant.setDimensionValueId(branchA.getId());
        grant.setCanRead(true);
        accessGrantRepository.save(grant);

        return new Fixture(username, type, workshopA, workshopB);
    }

    /**
     * Фикстура типа идёт canonical path. Операции субъект уже получил (см.
     * {@link RlsTestFixture#callAsSuperuser}), а read-ключ выдаётся здесь же: он нужен и
     * созданию, и снятию типа, и не выдаётся wildcard-строкой "*" (ADR-0017 §4).
     */
    private AttributeType createType(AttributeType type) {
        grantTypeReadKey();
        return access.create(AttributeType.class, type);
    }

    private void grantTypeReadKey() {
        RlsTestFixture.grantC5EntityRead(accessGrantRepository,
            SecurityContextHolder.getContext().getAuthentication().getName(),
            AttributeType.class);
    }

    private void loginAs(String username) {
        // Entity C5 is a prerequisite; this test varies the independent BRANCH RLS grant.
        RlsTestFixture.grantC5EntityRead(accessGrantRepository, username, Workshop.class);
        // Создание значения идёт canonical create (C5.6b, ADR-0019 §4): без строки на ключ
        // операций типа создание отказывает — первичная выдача, а не ослабление проверки.
        RlsTestFixture.grantC5EntityOperations(accessGrantRepository, username,
            org.ip.model.AttributeValue.class);
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

    private record Fixture(String username, AttributeType type, Workshop workshopA, Workshop workshopB) {
    }
}
