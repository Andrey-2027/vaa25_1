package org.ip.security;

import org.ip.config.DataInitializer;
import org.ip.model.Branch;
import org.ip.model.Journal;
import org.ip.model.ReceivingDocument;
import org.ip.model.User;
import org.ip.model.Workshop;
import org.ip.repository.BranchRepository;
import org.ip.repository.JournalRepository;
import org.ip.repository.UserRepository;
import org.ip.repository.WorkshopRepository;
import org.ip.service.AccessGrantAdminService;
import org.ip.service.AccessGrantAdminService.GrantFlags;
import org.ipro.crud.ServiceLocator;
import org.ipro.data.CanonicalEntityService;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsFilterActivator;
import org.ipro.rls.RlsReadableIdsCache;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Полный контекст приложения с новой автоконфигурацией org.ipro.rls.config.RlsAutoConfiguration:
 * бины RLS зарегистрированы (пакет за пределами component-scan org.ip), прикладные бины
 * и репозитории org.ip не выпали после декларации @EnableJpaRepositories в автоконфигурации
 * (back-off Boot-сканирования), @EntityScan покрывает org.ipro.rls (AccessGrant).
 */
@SpringBootTest
class RlsAutoConfigurationSmokeTest {

    /**
     * Стратегия хранения контекста безопасности ИМЕННО ЭТОГО контекста приложения.
     *
     * @PreAuthorize проверяется интерцепторами, которым Spring Security подставил
     * SecurityContextHolderStrategy из контекста (PrePostMethodSecurityConfiguration);
     * у Vaadin это бин VaadinAwareSecurityContextHolderStrategy
     * (SpringSecurityAutoConfiguration.vaadinAwareSecurityContextHolderStrategy).
     * Статическая SecurityContextHolder — глобальная на всю JVM, и при каждом старте
     * любого контекста в ней переустанавливается стратегия этого контекста
     * (securityContextHolderStrategyInitializer). В общем Surefire-JVM контекстов много,
     * поэтому к моменту теста статическая стратегия может принадлежать ДРУГОМУ контексту,
     * и аутентификация, положенная только в неё, до интерцепторов этого теста не доходит
     * ("An Authentication object was not found in the SecurityContext"). Поэтому
     * аутентифицируем в стратегии контекста — том же объекте, который читают интерцепторы.
     */
    @Autowired
    private SecurityContextHolderStrategy securityContextHolderStrategy;

    @BeforeEach
    void authenticateAdmin() {
        authenticateAs("admin");
    }

    /** Аутентификация от имени заданного субъекта — для проверок, где важен свой субъект. */
    private void authenticateAs(String username) {
        SecurityContext context = securityContextHolderStrategy.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(username, "n/a",
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        securityContextHolderStrategy.setContext(context);
        // Дополнительно — для бинов, читающих статическую SecurityContextHolder напрямую:
        // аудит (AuditConfig), RlsCurrentUser, UserContext в телеметрии.
        SecurityContextHolder.setContext(context);
    }

    @AfterEach
    void clearAuthentication() {
        securityContextHolderStrategy.clearContext();
        SecurityContextHolder.clearContext();
    }

    /**
     * Сидинг стартовых данных (DataInitializer) имеет известный баг при свежей БД
     * (detached Role с неинициализированным version при userRepository.save) — вне
     * скоупа этой фазы; для проверки автоконфигурации не нужен.
     */
    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private AccessGrantRepository accessGrantRepository;

    @Autowired
    private AccessService accessService;

    @Autowired
    private RlsDimensionRegistry dimensionRegistry;

    @Autowired
    private RlsReadableIdsCache readableIdsCache;

    @Autowired
    private RlsFilterActivator rlsFilterActivator;

    @Autowired
    private RlsCurrentUser rlsCurrentUser;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ServiceLocator serviceLocator;

    /** RLS-мост переопределяет GLOBAL-only fallback из NumberingAutoConfiguration. */
    @Autowired
    private org.ipro.numbering.NumberingScopeResolver numberingScopeResolver;

    /** C4.6 волна B: у {@code ReceivingDocument} нет typed-сервиса — резолв идёт canonical handle. */
    private CanonicalEntityService<ReceivingDocument> documents() {
        return (CanonicalEntityService<ReceivingDocument>) serviceLocator
            .<ReceivingDocument, Long>findService(ReceivingDocument.class);
    }

    @Test
    void rlsBeansAreRegisteredByAutoConfiguration() {
        assertThat(accessGrantRepository).isNotNull();
        assertThat(accessService).isNotNull();
        assertThat(dimensionRegistry).isNotNull();
        assertThat(readableIdsCache).isNotNull();
        assertThat(rlsFilterActivator).isNotNull();
        assertThat(rlsCurrentUser).isNotNull();
        // RLS-адаптер резолвит только реальные измерения — fail-fast работает, а не
        // GLOBAL-only fallback с canResolve=false (контекст бы не поднялся: ReceivingDocument
        // объявляет scope="JOURNAL").
        assertThat(numberingScopeResolver).isInstanceOf(org.ipro.rls.RlsScopeResolver.class);
        assertThat(numberingScopeResolver.canResolve("JOURNAL")).isTrue();
    }

    @Test
    void applicationBeansAndRepositoriesAreStillRegistered() {
        assertThat(userRepository).isNotNull();
        // C4.6: typed-сервиса у Nomenclature больше нет, но тип остаётся обслуживаемым —
        // резолв отдаёт canonical-хэндл с тем же контрактом.
        assertThat(serviceLocator.findService(org.ip.model.Nomenclature.class))
            .isInstanceOf(CanonicalEntityService.class);
    }

    @Test
    void rlsDimensionRegistryScansApplicationPackage() {
        assertThat(dimensionRegistry.dimensions())
            .contains("JOURNAL", "BRANCH", "ENTITY:ReceivingDocument");
    }

    @Test
    void tablesMapToTheirFilterableDimensions() {
        Map<String, Set<String>> byTable = dimensionRegistry.filterableDimensionsByTable();
        assertThat(byTable.get("journal")).containsExactly("JOURNAL");
        assertThat(byTable.get("branch")).containsExactly("BRANCH");
        assertThat(byTable.get("workshop")).containsExactly("BRANCH");
        assertThat(byTable.get("prd_spec")).containsExactly("JOURNAL");
        assertThat(byTable.get("receiving_document")).containsExactlyInAnyOrder("BRANCH", "JOURNAL");
        assertThat(byTable).doesNotContainKey("app_user");
        assertThat(byTable.get("app_user")).isNull();
    }

    @Test
    void filterableDimensionWithoutFilterDefFailsAtRebuild() {
        RlsDimensionRegistry bad = new RlsDimensionRegistry("rlsfixture");
        assertThatThrownBy(bad::rebuild)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("MISSING_FILTER");
    }

    @Test
    void currentUserDelegatesToSecurityContext() {
        assertThat(rlsCurrentUser.username()).isEqualTo("admin");
    }

    @Test
    void accessGrantIsMappedAsEntity() {
        assertThat(entityManagerFactory.getMetamodel().getEntities())
            .extracting(em -> em.getName())
            .contains("AccessGrant");
    }

    @Autowired
    private AccessGrantAdminService accessGrantAdminService;


    @Autowired
    private JournalRepository journalRepository;

    @Autowired
    private WorkshopRepository workshopRepository;

    @Autowired
    private BranchRepository branchRepository;

    /**
     * Write-guard на реальном сервисе (после удаления дублирующей сервисной проверки
     * в C3.0.1): без ENTITY-гранта создание Накладной должно быть заблокировано общей
     * RLS-границей (RlsRepositoryEnforcementAspect при repository.save), а не сохранено
     * молча.
     * В свежей БД bootstrap разрешает JOURNAL/BRANCH (грантов нет), но CHECK_ONLY
     * измерение "ENTITY:ReceivingDocument" bootstrap'ом НЕ покрывается (AccessService.
     * isNewDimensionValueAllowed) — блокирует именно оно.
     */
    @Test
    void receivingDocumentCreateBlockedByWriteGuardWithoutEntityGrant() {
        // Субъект — свой, не «admin»: другие тесты (RlsStatementGuardTest, JrxmlExecutionIT,
        // VisualQueryGuardIT) оставляют в общей H2-базе wildcard-грант для admin, и такая
        // утечка данных делала проверку гейта зависимой от порядка выполнения тестов.
        String actor = "write-guard-user";
        authenticateAs(actor);
        // Разрешаем только подготовку двух FILTERABLE-измерений. ENTITY-грант
        // намеренно отсутствует, поэтому сам документ обязан быть отклонён.
        accessGrantRepository.save(grant(actor, AccessGrant.SubjectType.USER,
            "BRANCH", null, true, true, false));
        accessGrantRepository.save(grant(actor, AccessGrant.SubjectType.USER,
            "JOURNAL", null, true, true, false));

        Branch branch = new Branch();
        branch.setCode("NW-1");
        branch.setName("Филиал");
        branchRepository.save(branch);

        Workshop receiver = new Workshop("NW-A", "Цех А");
        receiver.setBranch(branch);
        workshopRepository.save(receiver);

        Workshop deliverer = new Workshop("NW-B", "Цех Б");
        deliverer.setBranch(branch);
        workshopRepository.save(deliverer);

        Journal journal = new Journal();
        journal.setCode("NWJ");
        journal.setName("Журнал");
        journalRepository.save(journal);

        ReceivingDocument doc = new ReceivingDocument("NW-1", java.time.LocalDate.now(), receiver, deliverer);
        doc.setJournal(journal);

        assertThatThrownBy(() -> documents().save(doc))
            .isInstanceOf(org.ipro.rls.RlsAccessDeniedException.class)
            .hasMessageContaining("Нет прав на изменение");
    }

    @Test
    void saveGrantsNormalizesReadFlag() {
        authenticateAdmin();
        accessGrantAdminService.saveGrants("JOURNAL", AccessGrant.SubjectType.USER, "normalize-u1",
            Map.of(101L, new GrantFlags(false, true, false),
                102L, new GrantFlags(false, false, true)));

        List<AccessGrant> saved = accessGrantRepository.findBySubjectTypeAndSubjectKeyAndDimension(
            AccessGrant.SubjectType.USER, "normalize-u1", "JOURNAL");
        assertThat(saved).hasSize(2).allSatisfy(g -> assertThat(g.isCanRead()).isTrue());
    }

    @Test
    void saveGrantsWithAllFlagsFalseRemovesGrantRow() {
        authenticateAdmin();
        accessGrantAdminService.saveGrants("JOURNAL", AccessGrant.SubjectType.USER, "normalize-u2",
            Map.of(201L, new GrantFlags(true, false, false)));
        accessGrantAdminService.saveGrants("JOURNAL", AccessGrant.SubjectType.USER, "normalize-u2",
            Map.of(201L, GrantFlags.NONE));

        assertThat(accessGrantRepository.findBySubjectTypeAndSubjectKeyAndDimension(
            AccessGrant.SubjectType.USER, "normalize-u2", "JOURNAL")).isEmpty();
    }

    @Test
    void saveSingleGrantNormalizesReadFlagAndRemovesOnNone() {
        authenticateAdmin();
        accessGrantAdminService.saveSingleGrant("ENTITY:ReceivingDocument", AccessGrant.SubjectType.USER,
            "normalize-u3", new GrantFlags(false, true, false));

        List<AccessGrant> saved = accessGrantRepository.findBySubjectTypeAndSubjectKeyAndDimension(
            AccessGrant.SubjectType.USER, "normalize-u3", "ENTITY:ReceivingDocument");
        assertThat(saved).singleElement().satisfies(g -> {
            assertThat(g.isCanRead()).isTrue();
            assertThat(g.isCanUpdate()).isTrue();
            assertThat(g.isCanDelete()).isFalse();
        });

        accessGrantAdminService.saveSingleGrant("ENTITY:ReceivingDocument", AccessGrant.SubjectType.USER,
            "normalize-u3", GrantFlags.NONE);
        assertThat(accessGrantRepository.findBySubjectTypeAndSubjectKeyAndDimension(
            AccessGrant.SubjectType.USER, "normalize-u3", "ENTITY:ReceivingDocument")).isEmpty();
    }

    @Autowired
    private jakarta.persistence.EntityManagerFactory entityManagerFactory;

    @Autowired
    private org.ip.repository.RoleRepository roleRepository;

    /**
     * Свёртка прямых грантов пользователя и грантов его ролей (Фаза 7): права — по
     * ИЛИ, источники перечислены отдельно, wildcard '*' применяется ко всем измерениям.
     */
    @Test
    void effectiveGrantsFoldDirectAndRoleGrants() {
        authenticateAdmin();
        org.ip.model.Role role = new org.ip.model.Role("eff-роль");
        roleRepository.save(role);
        User user = new User("eff-u1", "password");
        user.setRoles(java.util.Set.of(role));
        userRepository.save(user);

        accessGrantRepository.save(grant("eff-u1", AccessGrant.SubjectType.USER, "JOURNAL", 301L,
            true, true, false));
        accessGrantRepository.save(grant("eff-роль", AccessGrant.SubjectType.ROLE, "JOURNAL", 302L,
            true, false, false));

        AccessService.EffectiveGrant effective = accessService.effectiveGrants("JOURNAL", "eff-u1");
        assertThat(effective.canRead()).isTrue();
        assertThat(effective.canUpdate()).isTrue();
        assertThat(effective.canDelete()).isFalse();
        assertThat(effective.readableValueIds()).containsExactlyInAnyOrder(301L, 302L);
        assertThat(effective.sources()).containsExactlyInAnyOrder("прямой", "роль: eff-роль");
    }

    /** Wildcard-грант ('*') на чтение — эффективный доступ ко всем записям любого измерения. */
    @Test
    void effectiveGrantsUnlimitedOnWildcardGrant() {
        authenticateAdmin();
        accessGrantRepository.save(grant("eff-u2", AccessGrant.SubjectType.USER, "*", null,
            true, false, false));

        AccessService.EffectiveGrant effective = accessService.effectiveGrants("ENTITY:ReceivingDocument", "eff-u2");
        assertThat(effective.canRead()).isTrue();
        assertThat(effective.unlimited()).isTrue();
        assertThat(effective.readableValueIds()).isEmpty();
    }

    /** Эффективные права РОЛИ — ровно её собственные гранты (у ролей нет своих ролей). */
    @Test
    void effectiveGrantsOfRoleFoldsOwnGrantsOnly() {
        authenticateAdmin();
        accessGrantRepository.save(grant("eff-роль2", AccessGrant.SubjectType.ROLE, "BRANCH", 401L,
            true, false, true));

        AccessService.EffectiveGrant effective = accessService.effectiveGrantsOfRole("BRANCH", "eff-роль2");
        assertThat(effective.canRead()).isTrue();
        assertThat(effective.canDelete()).isTrue();
        assertThat(effective.canUpdate()).isFalse();
        assertThat(effective.sources()).containsExactly("роль: eff-роль2");
    }

    /** collectEffective покрывает все настраиваемые измерения, включая CHECK_ONLY. */
    @Test
    void collectEffectiveCoversAllAvailableDimensions() {
        authenticateAdmin();
        accessGrantRepository.save(grant("eff-u3", AccessGrant.SubjectType.USER, "ENTITY:ReceivingDocument", null,
            true, true, false));

        Map<String, AccessService.EffectiveGrant> effective =
            accessGrantAdminService.collectEffective(AccessGrant.SubjectType.USER, "eff-u3");
        assertThat(effective.keySet()).isEqualTo(
            java.util.Set.copyOf(accessGrantAdminService.availableDimensions()));
        assertThat(effective.get("ENTITY:ReceivingDocument").canRead()).isTrue();
        assertThat(effective.get("ENTITY:ReceivingDocument").canUpdate()).isTrue();
        assertThat(effective.get("JOURNAL").canRead()).isFalse();
        assertThat(effective.get("JOURNAL").sources()).isEmpty();
    }

    @Test
    void grantValueCatalogIsDerivedFromRlsAndEntityMetadata() {
        authenticateAdmin();
        accessGrantRepository.save(grant("admin", AccessGrant.SubjectType.USER,
            "JOURNAL", null, true, true, false));
        Journal journal = new Journal();
        journal.setCode("META-CATALOG");
        journal.setName("Metadata catalog");
        Journal saved = journalRepository.save(journal);

        assertThat(accessGrantAdminService.allValues("JOURNAL"))
            .anySatisfy(value -> {
                assertThat(value.id()).isEqualTo(saved.getId());
                assertThat(value.code()).isEqualTo("META-CATALOG");
                assertThat(value.name()).isEqualTo("Metadata catalog");
            });
    }

    private static AccessGrant grant(String subjectKey, AccessGrant.SubjectType subjectType,
                                     String dimension, Long valueId, boolean read, boolean update, boolean delete) {
        AccessGrant grant = new AccessGrant();
        grant.setSubjectType(subjectType);
        grant.setSubjectKey(subjectKey);
        grant.setDimension(dimension);
        grant.setDimensionValueId(valueId);
        grant.setCanRead(read);
        grant.setCanUpdate(update);
        grant.setCanDelete(delete);
        return grant;
    }
}