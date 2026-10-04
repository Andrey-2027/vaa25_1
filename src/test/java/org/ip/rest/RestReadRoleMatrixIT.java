package org.ip.rest;

import com.vaadin.flow.component.UI;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.ip.Application;
import org.ip.config.DataInitializer;
import org.ip.model.Branch;
import org.ip.model.Journal;
import org.ip.model.Nomenclature;
import org.ip.model.PrdSpec;
import org.ip.model.ReceivingDocument;
import org.ip.model.Role;
import org.ip.model.UnitOfMeasurement;
import org.ip.model.User;
import org.ip.model.Workshop;
import org.ip.repository.BranchRepository;
import org.ip.repository.JournalRepository;
import org.ip.repository.NomenclatureRepository;
import org.ip.repository.PrdSpecRepository;
import org.ip.repository.ReceivingDocumentRepository;
import org.ip.repository.RoleRepository;
import org.ip.repository.UnitOfMeasurementRepository;
import org.ip.repository.UserRepository;
import org.ip.repository.WorkshopRepository;
import org.ip.service.AccessGrantAdminService;
import org.ip.service.AccessGrantAdminService.GrantFlags;
import org.ipro.crud.ServiceLocator;
import org.ipro.data.CanonicalEntityService;
import org.ipro.data.EntityDataAccessResolver;
import org.ipro.rest.catalog.RestReadOperation;
import org.ipro.rest.catalog.RestResourceCatalog;
import org.ipro.rest.service.RestDetailQuery;
import org.ipro.rest.service.RestDetailResult;
import org.ipro.rest.service.RestListQuery;
import org.ipro.rest.service.RestPageResult;
import org.ipro.rest.service.RestReadException;
import org.ipro.rest.service.RestReadOutcome;
import org.ipro.rest.service.RestReadService;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsFilterActivator;
import org.ipro.rls.RlsTestFixture;
import org.ipro.rls.c5.C5AccessDeniedException;
import org.ipro.rls.c5.C5PermissionEvaluator;
import org.ipro.rls.c5.C5PermissionKeys;
import org.ipro.telemetry.api.SqlStatementAudit;
import org.ipro.telemetry.core.SqlStatementAuditBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * F-REST-READ-3, З3: прикладная ролевая матрица на настоящих {@code User}/{@code Role}/
 * назначениях, грантах и действующем ролевом resolver'е приложения.
 *
 * <p>В отличие от {@code RestReadPipelinePilotIT} (прямые гранты пользователю), здесь
 * проверяется то, что нельзя доказать owner-тестами с подменённым resolver'ом: грант роли
 * даёт те же данные и total, что прямой грант; решение меняется при отзыве гранта и снятии
 * роли без перезапуска; решения и видимость не переносятся между субъектами; отсутствие
 * одного права (API, entity, attribute, filter, default sort, reference target) отказывает
 * до business data, а поле вне запрошенной проекции не требует гранта лишь из-за fixed fetch
 * superset. Отдельно проверены политика существующих UI/service callers (обычный UI list/
 * detail и canonical app service читаются по entity C5 роли, REST дополнительно требует API
 * и attribute grants) и сохранение class/row RLS на тех же ролевых назначениях.</p>
 */
@SpringBootTest(classes = Application.class)
@TestPropertySource(properties = {
    "platform.rest.read.enabled=true",
    "platform.rest.read.max-window=10000",
    "spring.jpa.open-in-view=false",
    "spring.jpa.properties.hibernate.cache.use_second_level_cache=false"
})
class RestReadRoleMatrixIT {

    private static final String SPEC_LIST = "REST:specifications:v1:LIST";
    private static final String SPEC_DETAIL = "REST:specifications:v1:DETAIL";
    private static final String NOM_LIST = "REST:nomenclature:v1:LIST";
    private static final String LEGACY_DOCUMENT_CLASS = "ENTITY:ReceivingDocument";
    private static final String ADMIN = "role-matrix-admin";

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private RestReadService readService;

    @Autowired
    private RestResourceCatalog catalog;

    @Autowired
    private C5PermissionEvaluator c5PermissionEvaluator;

    @Autowired
    private EntityDataAccessResolver dataAccessResolver;

    @Autowired
    private ServiceLocator serviceLocator;

    @Autowired
    private AccessGrantAdminService accessGrantAdminService;

    @Autowired
    private AccessGrantRepository accessGrantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private JournalRepository journalRepository;

    @Autowired
    private UnitOfMeasurementRepository unitOfMeasurementRepository;

    @Autowired
    private NomenclatureRepository nomenclatureRepository;

    @Autowired
    private PrdSpecRepository prdSpecRepository;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private WorkshopRepository workshopRepository;

    @Autowired
    private ReceivingDocumentRepository documentRepository;

    @Autowired
    private RlsFilterActivator rlsFilterActivator;

    /**
     * Стратегия контекста безопасности именно этого контекста: {@code @PreAuthorize}
     * admin-сервиса читает субъекта через неё, а {@code SecurityRlsUser} приложения — через
     * статический {@code SecurityContextHolder}; для смены администратора нужно оба.
     */
    @Autowired
    private SecurityContextHolderStrategy securityContextHolderStrategy;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void prepareVaadinStrategy() {
        UI.setCurrent(new UI());
    }

    @AfterEach
    void clearAuthentication() {
        securityContextHolderStrategy.clearContext();
        SecurityContextHolder.clearContext();
        UI.setCurrent(null);
    }

    @Test
    @DisplayName("З3: роль даёт те же данные и total, что прямой грант с тем же набором прав")
    void roleGrantsMatchDirectGrantsForEqualPermissions() {
        Fixture fixture = persistFixture();
        String directUser = unique("matrix-direct");
        String roleUser = unique("matrix-role");
        roleWithUser(roleUser, fullSpecKeys(), fixture.journalAId());
        grantKeysToUser(directUser, fullSpecKeys());
        grantRowToUser(directUser, "JOURNAL", fixture.journalAId());

        loginAs(directUser);
        RestPageResult directPage = readService.list(specList("id", "codeSpec"));
        RestDetailResult directDetail = readService.detail(specDetail(fixture.specAId(),
            "id", "codeSpec", "journalId"));

        loginAs(roleUser);
        RestPageResult rolePage = readService.list(specList("id", "codeSpec"));
        RestDetailResult roleDetail = readService.detail(specDetail(fixture.specAId(),
            "id", "codeSpec", "journalId"));

        assertThat(directPage.totalElements()).isEqualTo(1);
        assertThat(rolePage.totalElements()).isEqualTo(directPage.totalElements());
        assertThat(rolePage.content()).isEqualTo(directPage.content());
        assertThat(roleDetail.data()).isEqualTo(directDetail.data());
        assertThat(roleDetail.data()).containsEntry("journalId", fixture.journalAId())
            .containsEntry("codeSpec", fixture.specACode());
    }

    @Test
    @DisplayName("З3: роли с разными JOURNAL-грантами видят разные строки, чужие detail скрыты")
    void differentJournalRolesSeeDifferentRowsAndHiddenDetailsStayHidden() {
        Fixture fixture = persistFixture();
        String alice = unique("matrix-alice");
        String bob = unique("matrix-bob");
        String dave = unique("matrix-dave");
        roleWithUser(alice, fullSpecKeys(), fixture.journalAId());
        roleWithUser(bob, fullSpecKeys(), fixture.journalBId());

        loginAs(alice);
        RestPageResult alicePage = readService.list(specList("id", "codeSpec"));
        assertThat(alicePage.totalElements()).isEqualTo(1);
        assertThat(alicePage.content()).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("id", fixture.specAId())
                .containsEntry("codeSpec", fixture.specACode()));

        var aliceVisibleDetail = readService.detail(specDetail(fixture.specAId(),
            "id", "codeSpec", "journalId"));
        assertThat(aliceVisibleDetail.data()).containsEntry("journalId", fixture.journalAId());

        // FK-фильтр на скрытую цель не расширяет доступ и не раскрывает её строку.
        RestPageResult hiddenFilter = readService.list(new RestListQuery("specifications", 1,
            List.of("id", "codeSpec"), Map.of("journalId", fixture.journalBId()), null, null, 0, 10));
        assertThat(hiddenFilter.content()).isEmpty();
        assertThat(hiddenFilter.totalElements()).isZero();

        RestPageResult visibleFilter = readService.list(new RestListQuery("specifications", 1,
            List.of("id", "codeSpec"), Map.of("journalId", fixture.journalAId()), null, null, 0, 10));
        assertThat(visibleFilter.totalElements()).isEqualTo(1);

        // Скрытый и отсутствующий detail дают один исход.
        assertThatThrownBy(() -> readService.detail(specDetail(fixture.specBId(), "id", "codeSpec")))
            .isInstanceOf(RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.DETAIL_NOT_FOUND);
        assertThatThrownBy(() -> readService.detail(specDetail(Long.MAX_VALUE, "id", "codeSpec")))
            .isInstanceOf(RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.DETAIL_NOT_FOUND);

        loginAs(bob);
        RestPageResult bobPage = readService.list(specList("id", "codeSpec"));
        assertThat(bobPage.totalElements()).isEqualTo(1);
        assertThat(bobPage.content()).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("id", fixture.specBId())
                .containsEntry("codeSpec", fixture.specBCode()));
        assertThatThrownBy(() -> readService.detail(specDetail(fixture.specAId(), "id", "codeSpec")))
            .isInstanceOf(RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.DETAIL_NOT_FOUND);

        // Роль без назначения пользователю не авторизует его, хотя роли с грантами существуют.
        loginAs(dave);
        assertThatThrownBy(() -> readService.list(specList("id")))
            .isInstanceOf(RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.FORBIDDEN);
    }

    @Test
    @DisplayName("З3: отзыв гранта и снятие роли действуют без перезапуска, решения не переносятся")
    void revocationAndRoleChangesTakeEffectWithoutRestart() {
        Fixture fixture = persistFixture();
        String user = unique("matrix-revoke");
        String witness = unique("matrix-witness");
        Role role = roleWithUser(user, fullSpecKeys(), fixture.journalAId());
        roleWithUser(witness, fullSpecKeys(), fixture.journalBId());

        loginAs(user);
        assertThat(readService.list(specList("id", "codeSpec")).totalElements()).isEqualTo(1);

        // Отзыв одного API-права немедленно закрывает LIST, не трогая DETAIL.
        revokeKeyFromRole(role.getName(), SPEC_LIST);
        loginAs(user);
        assertThatThrownBy(() -> readService.list(specList("id")))
            .isInstanceOf(RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.FORBIDDEN)
            .hasMessageContaining("API operation");
        assertThat(readService.detail(specDetail(fixture.specAId(), "id", "codeSpec")).data())
            .containsEntry("codeSpec", fixture.specACode());

        grantKeyToRole(role.getName(), SPEC_LIST);
        loginAs(user);
        assertThat(readService.list(specList("id", "codeSpec")).totalElements()).isEqualTo(1);

        // Отзыв entity-права также виден сразу; после возврата гранта чтение снова проходит.
        revokeKeyFromRole(role.getName(), C5PermissionKeys.entityRead(PrdSpec.class));
        loginAs(user);
        assertThatThrownBy(() -> readService.list(specList("id")))
            .isInstanceOf(RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.FORBIDDEN)
            .hasMessageContaining("entity");
        assertThatThrownBy(() -> dataAccessResolver.resolve(PrdSpec.class)
            .list(PrdSpec.class, null, PageRequest.of(0, 10, Sort.by("id"))))
            .isInstanceOf(C5AccessDeniedException.class);

        grantKeyToRole(role.getName(), C5PermissionKeys.entityRead(PrdSpec.class));
        loginAs(user);
        assertThat(readService.list(specList("id", "codeSpec")).totalElements()).isEqualTo(1);

        // Снятие роли закрывает все выданные ею права; возврат назначения их восстанавливает.
        setUserRoles(user, Set.of());
        loginAs(user);
        assertThatThrownBy(() -> readService.list(specList("id")))
            .isInstanceOf(RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.FORBIDDEN);
        assertThatThrownBy(() -> readService.detail(specDetail(fixture.specAId(), "id")))
            .isInstanceOf(RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.FORBIDDEN);

        // Пока user закрыт, другой субъект со своей ролью видит только свою строку.
        loginAs(witness);
        RestPageResult witnessPage = readService.list(specList("id", "codeSpec"));
        assertThat(witnessPage.totalElements()).isEqualTo(1);
        assertThat(witnessPage.content()).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("id", fixture.specBId()));

        setUserRoles(user, Set.of(role));
        loginAs(user);
        assertThat(readService.list(specList("id", "codeSpec")).totalElements()).isEqualTo(1);
        assertThatThrownBy(() -> readService.detail(specDetail(fixture.specBId(), "id")))
            .isInstanceOf(RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.DETAIL_NOT_FOUND);
    }

    @Test
    @DisplayName("З3: отсутствие одного права отказывает до business data")
    void missingSinglePermissionDeniesBeforeAnyBusinessData() {
        Fixture fixture = persistFixture();

        List<String> withoutApi = fullSpecKeys();
        withoutApi.remove(SPEC_LIST);
        String missingApi = unique("matrix-no-api");
        roleWithUser(missingApi, withoutApi, fixture.journalAId());
        assertDeniedWithoutBusinessData(missingApi,
            () -> readService.list(specList("id")), "API operation");

        List<String> withoutEntity = fullSpecKeys();
        withoutEntity.remove(C5PermissionKeys.entityRead(PrdSpec.class));
        String missingEntity = unique("matrix-no-entity");
        roleWithUser(missingEntity, withoutEntity, fixture.journalAId());
        assertDeniedWithoutBusinessData(missingEntity,
            () -> readService.list(specList("id")), "entity");

        List<String> withoutOutput = fullSpecKeys();
        withoutOutput.remove(C5PermissionKeys.attributeRead(PrdSpec.class, "comment"));
        String missingOutput = unique("matrix-no-output");
        roleWithUser(missingOutput, withoutOutput, fixture.journalAId());
        assertDeniedWithoutBusinessData(missingOutput,
            () -> readService.detail(specDetail(fixture.specAId(), "id", "comment")),
            "Attribute read denied on root: comment");

        List<String> withoutFilter = fullSpecKeys();
        withoutFilter.remove(C5PermissionKeys.attributeRead(PrdSpec.class, "journal"));
        String missingFilter = unique("matrix-no-filter");
        roleWithUser(missingFilter, withoutFilter, fixture.journalAId());
        assertDeniedWithoutBusinessData(missingFilter,
            () -> readService.list(new RestListQuery("specifications", 1,
                List.of("id", "codeSpec"), Map.of("journalId", fixture.journalAId()), null, null, 0, 10)),
            "Attribute read denied on filter: journal");

        List<String> withoutSort = List.of(
            NOM_LIST,
            C5PermissionKeys.entityRead(Nomenclature.class),
            C5PermissionKeys.attributeRead(Nomenclature.class, "id"),
            C5PermissionKeys.attributeRead(Nomenclature.class, "name"));
        String missingSort = unique("matrix-no-sort");
        roleWithUser(missingSort, withoutSort, null);
        assertDeniedWithoutBusinessData(missingSort,
            () -> readService.list(new RestListQuery("nomenclature", 1,
                List.of("id", "name"), Map.of(), null, null, 0, 10)),
            "Attribute read denied on sort: code");

        List<String> withoutReference = fullSpecKeys();
        withoutReference.remove(C5PermissionKeys.entityRead(Nomenclature.class));
        withoutReference.remove(C5PermissionKeys.attributeRead(Nomenclature.class, "code"));
        String missingReference = unique("matrix-no-reference");
        roleWithUser(missingReference, withoutReference, fixture.journalAId());
        assertDeniedWithoutBusinessData(missingReference,
            () -> readService.list(specList("id", "nomenclatureCode")),
            "Reference authorization denied for field: nomenclatureCode");

        // id — всегда добавляется сервером (обязательное поле, default sort и tie-breaker):
        // его отсутствие отказывает до данных так же, как любое другое.
        List<String> withoutId = fullSpecKeys();
        withoutId.remove(C5PermissionKeys.attributeRead(PrdSpec.class, "id"));
        String missingId = unique("matrix-no-id");
        roleWithUser(missingId, withoutId, fixture.journalAId());
        assertDeniedWithoutBusinessData(missingId,
            () -> readService.list(specList("id", "codeSpec")), "Attribute read denied on root: id");
    }

    @Test
    @DisplayName("З3: поле вне запрошенной проекции не требует гранта из-за fixed fetch superset")
    void unrequestedProjectionFieldNeedsNoGrantDespiteFixedFetchSuperset() {
        Fixture fixture = persistFixture();
        String user = unique("matrix-projection");
        List<String> keys = new ArrayList<>(List.of(
            SPEC_LIST, SPEC_DETAIL,
            C5PermissionKeys.entityRead(PrdSpec.class),
            C5PermissionKeys.attributeRead(PrdSpec.class, "id"),
            C5PermissionKeys.attributeRead(PrdSpec.class, "codeSpec"),
            C5PermissionKeys.attributeRead(PrdSpec.class, "journal"),
            C5PermissionKeys.attributeRead(PrdSpec.class, "nomenclature"),
            C5PermissionKeys.entityRead(Journal.class),
            C5PermissionKeys.attributeRead(Journal.class, "id")));
        roleWithUser(user, keys, fixture.journalAId());

        loginAs(user);
        assertThat(c5PermissionEvaluator.isEntityReadPermitted(user, Nomenclature.class))
            .as("гранта Nomenclature у субъекта нет вовсе")
            .isFalse();
        var specifications = catalog.find("specifications", 1).orElseThrow();
        assertThat(specifications.fixedFetchPaths(RestReadOperation.LIST))
            .as("fixed fetch superset LIST действительно содержит nomenclature.code")
            .contains("nomenclature.code");
        assertThat(specifications.fixedFetchPaths(RestReadOperation.DETAIL))
            .contains("nomenclature.code");

        // journalId в DETAIL требует только segment journal и target Journal.id — Nomenclature не участвует.
        RestPageResult page = readService.list(specList("id", "codeSpec"));
        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content()).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("id", fixture.specAId())
                .containsEntry("codeSpec", fixture.specACode()));

        RestDetailResult detail = readService.detail(specDetail(fixture.specAId(),
            "id", "codeSpec", "journalId"));
        assertThat(detail.data()).containsEntry("journalId", fixture.journalAId());

        // А запрошенный nomenclatureCode в той же роли требует target-грантов и отказывает.
        assertThatThrownBy(() -> readService.list(specList("id", "nomenclatureCode")))
            .isInstanceOf(RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.FORBIDDEN)
            .hasMessageContaining("nomenclatureCode");
    }

    @Test
    @DisplayName("З3: UI list/detail и canonical app service читаются по entity-гранту роли, REST требует API")
    void uiAndServiceCallersUseRoleEntityGrantsWhileRestNeedsApiGrants() {
        Fixture fixture = persistFixture();
        String reader = unique("matrix-ui-reader");
        Role role = roleWithUser(reader, List.of(C5PermissionKeys.entityRead(PrdSpec.class)),
            fixture.journalAId());

        loginAs(reader);
        var uiPage = dataAccessResolver.resolve(PrdSpec.class)
            .list(PrdSpec.class, null, PageRequest.of(0, 10, Sort.by("id")));
        assertThat(uiPage.getTotalElements()).isEqualTo(1);
        assertThat(uiPage.getContent()).extracting(PrdSpec::getId)
            .containsExactly(fixture.specAId());

        assertThat(dataAccessResolver.resolve(PrdSpec.class).detail(PrdSpec.class, fixture.specAId()))
            .isPresent();
        assertThat(dataAccessResolver.resolve(PrdSpec.class).detail(PrdSpec.class, fixture.specBId()))
            .isEmpty();

        CanonicalEntityService<PrdSpec> specs = (CanonicalEntityService<PrdSpec>)
            serviceLocator.<PrdSpec, Long>findService(PrdSpec.class);
        assertThat(specs.findAll(PageRequest.of(0, 10, Sort.by("id"))).getTotalElements())
            .isEqualTo(1);
        assertThat(specs.findById(fixture.specAId())).isPresent();
        assertThat(specs.findById(fixture.specBId())).isEmpty();

        // REST тем же субъектом отказывает: обычное UI-чтение не выдаёт API-права автоматически.
        assertThatThrownBy(() -> readService.list(specList("id")))
            .isInstanceOf(RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.FORBIDDEN)
            .hasMessageContaining("API operation");

        // Явная выдача администратором полного REST-набора роли включает REST-чтение.
        grantKeysToRole(role.getName(), fullSpecKeys());
        loginAs(reader);
        assertThat(readService.list(specList("id", "codeSpec")).totalElements()).isEqualTo(1);
        assertThat(readService.detail(specDetail(fixture.specAId(), "id", "codeSpec")).data())
            .containsEntry("codeSpec", fixture.specACode());

        // Запрещённый caller: у роли есть только построчный JOURNAL-грант, entity C5 нет.
        String forbidden = unique("matrix-ui-forbidden");
        roleWithUser(forbidden, List.of(), fixture.journalAId());
        loginAs(forbidden);
        assertThatThrownBy(() -> dataAccessResolver.resolve(PrdSpec.class)
            .list(PrdSpec.class, null, PageRequest.of(0, 10, Sort.by("id"))))
            .isInstanceOf(C5AccessDeniedException.class);
        assertThatThrownBy(() -> specs.findById(fixture.specAId()))
            .isInstanceOf(C5AccessDeniedException.class);
        assertThatThrownBy(() -> readService.list(specList("id")))
            .isInstanceOf(RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.FORBIDDEN);
    }

    @Test
    @DisplayName("З3: class/row RLS сохраняется для ролевых читателей UI")
    void legacyClassAndRowRlsStillApplyToRoleReaders() {
        DocumentFixture fixture = persistDocumentFixture();
        String reader = unique("matrix-doc-reader");
        Role role = roleWithUser(reader, List.of(
            C5PermissionKeys.entityRead(ReceivingDocument.class),
            LEGACY_DOCUMENT_CLASS), fixture.journalId());
        grantRowToRole(role.getName(), "BRANCH", fixture.branchId());

        loginAs(reader);
        assertThat(documents().findAll()).extracting(ReceivingDocument::getNumber)
            .containsExactly(fixture.number());
        assertThat(documents().findById(fixture.documentId())).isPresent();

        // Классовый CHECK_ONLY-гейт: без него чтение пустое, а не исключение.
        revokeKeyFromRole(role.getName(), LEGACY_DOCUMENT_CLASS);
        loginAs(reader);
        assertThat(documents().findAll()).isEmpty();
        assertThat(documents().findById(fixture.documentId())).isEmpty();

        grantKeyToRole(role.getName(), LEGACY_DOCUMENT_CLASS);
        loginAs(reader);
        assertThat(documents().findAll()).hasSize(1);

        // Без C5 entity-права canonical read отказывает исключением.
        revokeKeyFromRole(role.getName(), C5PermissionKeys.entityRead(ReceivingDocument.class));
        loginAs(reader);
        assertThatThrownBy(() -> documents().findAll())
            .isInstanceOf(C5AccessDeniedException.class);
        grantKeyToRole(role.getName(), C5PermissionKeys.entityRead(ReceivingDocument.class));

        // Построчный BRANCH-грант остаётся независимым: его отзыв скрывает строку.
        setRowGrant(role.getName(), "BRANCH", fixture.branchId(), GrantFlags.NONE);
        loginAs(reader);
        assertThat(documents().findAll()).isEmpty();
        setRowGrant(role.getName(), "BRANCH", fixture.branchId(), new GrantFlags(true, false, false));
        loginAs(reader);
        assertThat(documents().findAll()).hasSize(1);
    }

    @Test
    @DisplayName("З3: anonymous/system/bypass-контексты ролевыми назначениями не открываются")
    void anonymousSystemAndPrivilegedBypassAreRejected() {
        Fixture fixture = persistFixture();
        String user = unique("matrix-context");
        roleWithUser(user, fullSpecKeys(), fixture.journalAId());

        securityContextHolderStrategy.clearContext();
        SecurityContextHolder.clearContext();
        assertThat(statementsDuring(() -> {
            assertThatThrownBy(() -> readService.list(specList("id")))
                .isInstanceOf(RestReadException.class)
                .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.UNAUTHENTICATED);
        })).isEmpty();

        for (String technicalSubject : List.of("system", "anonymous")) {
            loginAs(technicalSubject);
            assertThatThrownBy(() -> readService.list(specList("id")))
                .isInstanceOf(RestReadException.class)
                .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.UNAUTHENTICATED);
        }

        loginAs(user);
        List<String> bypassStatements = statementsDuring(() -> {
            Throwable failure = rlsFilterActivator.withDimensionAdministration(entityManager, "JOURNAL",
                () -> catchThrowable(() -> readService.list(specList("id"))));
            assertThat(failure).isInstanceOf(RestReadException.class)
                .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.FORBIDDEN)
                .hasMessageContaining("bypass");
        });
        assertThat(bypassStatements).noneMatch(RestReadRoleMatrixIT::touchesBusinessData);
    }

    // ------------------------------------------------------------------ ролевые фикстуры

    private Role roleWithUser(String username, List<String> dimensions, Long journalId) {
        Role role = RlsTestFixture.callAsSuperuser(accessGrantRepository, () -> {
            Role created = roleRepository.saveAndFlush(new Role("role-" + username));
            User user = new User(username, "password");
            user.setRoles(Set.of(created));
            userRepository.saveAndFlush(user);
            return created;
        });
        grantKeysToRole(role.getName(), dimensions);
        if (journalId != null) {
            grantRowToRole(role.getName(), "JOURNAL", journalId);
        }
        return role;
    }

    private void setUserRoles(String username, Set<Role> roles) {
        RlsTestFixture.callAsSuperuser(accessGrantRepository, () -> {
            User user = userRepository.findByUsername(username).orElseThrow();
            user.setRoles(roles);
            return userRepository.saveAndFlush(user);
        });
    }

    private void grantKeysToRole(String roleName, List<String> dimensions) {
        withAdmin(() -> {
            for (String dimension : dimensions) {
                accessGrantAdminService.saveSingleGrant(dimension, AccessGrant.SubjectType.ROLE,
                    roleName, new GrantFlags(true, false, false));
            }
        });
    }

    private void grantKeyToRole(String roleName, String dimension) {
        grantKeysToRole(roleName, List.of(dimension));
    }

    private void revokeKeyFromRole(String roleName, String dimension) {
        withAdmin(() -> accessGrantAdminService.saveSingleGrant(dimension,
            AccessGrant.SubjectType.ROLE, roleName, GrantFlags.NONE));
    }

    private void grantRowToRole(String roleName, String dimension, Long valueId) {
        setRowGrant(roleName, dimension, valueId, new GrantFlags(true, false, false));
    }

    private void setRowGrant(String roleName, String dimension, Long valueId, GrantFlags flags) {
        withAdmin(() -> accessGrantAdminService.saveGrants(dimension,
            AccessGrant.SubjectType.ROLE, roleName, Map.of(valueId, flags)));
    }

    private void grantKeysToUser(String username, List<String> dimensions) {
        RlsTestFixture.callAsSuperuser(accessGrantRepository, () -> {
            for (String dimension : dimensions) {
                saveGrant(AccessGrant.SubjectType.USER, username, dimension, null);
            }
            return null;
        });
    }

    private void grantRowToUser(String username, String dimension, Long valueId) {
        RlsTestFixture.callAsSuperuser(accessGrantRepository, () ->
            saveGrant(AccessGrant.SubjectType.USER, username, dimension, valueId));
    }

    private AccessGrant saveGrant(AccessGrant.SubjectType subjectType, String subjectKey,
                                  String dimension, Long valueId) {
        AccessGrant grant = new AccessGrant();
        grant.setSubjectType(subjectType);
        grant.setSubjectKey(subjectKey);
        grant.setDimension(dimension);
        grant.setDimensionValueId(valueId);
        grant.setCanRead(true);
        return accessGrantRepository.saveAndFlush(grant);
    }

    private void withAdmin(Runnable action) {
        SecurityContext previous = securityContextHolderStrategy.getContext();
        SecurityContext adminContext = securityContextHolderStrategy.createEmptyContext();
        adminContext.setAuthentication(new UsernamePasswordAuthenticationToken(ADMIN, "n/a",
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        securityContextHolderStrategy.setContext(adminContext);
        SecurityContextHolder.setContext(adminContext);
        try {
            action.run();
        } finally {
            securityContextHolderStrategy.setContext(previous);
            SecurityContextHolder.setContext(previous);
        }
    }

    // ---------------------------------------------------------------------- REST-запросы

    private RestListQuery specList(String... fields) {
        return new RestListQuery("specifications", 1, List.of(fields), Map.of(), null, null, 0, 10);
    }

    private RestDetailQuery specDetail(Long id, String... fields) {
        return new RestDetailQuery("specifications", 1, List.of(fields), id);
    }

    private void assertDeniedWithoutBusinessData(String username, Runnable call, String messageFragment) {
        loginAs(username);
        Throwable[] failure = new Throwable[1];
        List<String> statements = statementsDuring(() -> failure[0] = catchThrowable(call::run));
        assertThat(failure[0]).isInstanceOf(RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.FORBIDDEN)
            .hasMessageContaining(messageFragment);
        assertThat(statements).as("deny не должен обращаться к business data").isNotEmpty();
        assertThat(statements).noneMatch(RestReadRoleMatrixIT::touchesBusinessData);
    }

    private List<String> statementsDuring(Runnable action) {
        List<String> statements = new ArrayList<>();
        Thread measuredThread = Thread.currentThread();
        SqlStatementAudit audit = sql -> {
            if (Thread.currentThread() == measuredThread) {
                statements.add(sql.replaceAll("\\s+", " ").trim());
            }
        };
        SqlStatementAuditBridge.install(audit);
        try {
            action.run();
        } finally {
            SqlStatementAuditBridge.uninstall(audit);
        }
        return List.copyOf(statements);
    }

    private static boolean touchesBusinessData(String sql) {
        String normalized = sql.toLowerCase(Locale.ROOT);
        return normalized.contains(" from prd_spec") || normalized.contains(" from nomenclature")
            || normalized.contains(" from journal") || normalized.contains(" from receiving_document")
            || normalized.contains(" from unit_of_measurement");
    }

    // ------------------------------------------------------------------------- фикстуры

    private static List<String> fullSpecKeys() {
        return new ArrayList<>(List.of(
            SPEC_LIST, SPEC_DETAIL,
            C5PermissionKeys.entityRead(PrdSpec.class),
            C5PermissionKeys.attributeRead(PrdSpec.class, "id"),
            C5PermissionKeys.attributeRead(PrdSpec.class, "codeSpec"),
            C5PermissionKeys.attributeRead(PrdSpec.class, "draft"),
            C5PermissionKeys.attributeRead(PrdSpec.class, "comment"),
            C5PermissionKeys.attributeRead(PrdSpec.class, "nomenclature"),
            C5PermissionKeys.attributeRead(PrdSpec.class, "journal"),
            C5PermissionKeys.entityRead(Nomenclature.class),
            C5PermissionKeys.attributeRead(Nomenclature.class, "code"),
            C5PermissionKeys.entityRead(Journal.class),
            C5PermissionKeys.attributeRead(Journal.class, "id")));
    }

    private Fixture persistFixture() {
        String token = token();
        return RlsTestFixture.callAsSuperuser(accessGrantRepository, () -> {
            Journal journalA = new Journal();
            journalA.setCode("JA" + token);
            journalA.setName("Journal A " + token);
            journalA = journalRepository.save(journalA);

            Journal journalB = new Journal();
            journalB.setCode("JB" + token);
            journalB.setName("Journal B " + token);
            journalB = journalRepository.save(journalB);

            UnitOfMeasurement unit = new UnitOfMeasurement();
            unit.setCode("U" + token);
            unit.setShortCode("U" + token);
            unit.setName("Unit " + token);
            unit = unitOfMeasurementRepository.save(unit);

            Nomenclature nomenclature = new Nomenclature();
            nomenclature.setCode("N" + token);
            nomenclature.setName("Nomenclature " + token);
            nomenclature.setTypeNom("RT" + token);
            nomenclature.setUnitOfMeasurement(unit);
            nomenclature = nomenclatureRepository.save(nomenclature);

            PrdSpec specA = new PrdSpec();
            specA.setJournal(journalA);
            specA.setNomenclature(nomenclature);
            specA.setCodeSpec("SA" + token);
            specA = prdSpecRepository.save(specA);

            PrdSpec specB = new PrdSpec();
            specB.setJournal(journalB);
            specB.setNomenclature(nomenclature);
            specB.setCodeSpec("SB" + token);
            specB = prdSpecRepository.save(specB);

            return new Fixture(journalA.getId(), journalB.getId(),
                specA.getId(), specA.getCodeSpec(), specB.getId(), specB.getCodeSpec());
        });
    }

    private DocumentFixture persistDocumentFixture() {
        String token = token();
        return RlsTestFixture.callAsSuperuser(accessGrantRepository, () -> {
            Branch branch = new Branch();
            branch.setCode("DB" + token);
            branch.setName("Branch " + token);
            branch = branchRepository.save(branch);

            Workshop receiving = workshopRepository.save(new Workshop("RW" + token, "Receiving " + token));
            receiving.setBranch(branch);
            receiving = workshopRepository.save(receiving);

            Workshop transferring = workshopRepository.save(new Workshop("TW" + token, "Transferring " + token));
            transferring.setBranch(branch);
            transferring = workshopRepository.save(transferring);

            Journal journal = new Journal();
            journal.setCode("DJ" + token);
            journal.setName("Document journal " + token);
            journal = journalRepository.save(journal);

            ReceivingDocument document = new ReceivingDocument("RD" + token, LocalDate.now(),
                receiving, transferring);
            document.setJournal(journal);
            document = documentRepository.saveAndFlush(document);
            return new DocumentFixture(branch.getId(), journal.getId(), document.getId(), document.getNumber());
        });
    }

    private CanonicalEntityService<ReceivingDocument> documents() {
        return (CanonicalEntityService<ReceivingDocument>) serviceLocator
            .<ReceivingDocument, Long>findService(ReceivingDocument.class);
    }

    private void loginAs(String username) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
    }

    private static String unique(String prefix) {
        return prefix + "-" + token();
    }

    private static String token() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record Fixture(Long journalAId, Long journalBId,
                           Long specAId, String specACode, Long specBId, String specBCode) {
    }

    private record DocumentFixture(Long branchId, Long journalId, Long documentId, String number) {
    }
}
