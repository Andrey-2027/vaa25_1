package org.ip.security;

import jakarta.persistence.EntityManager;
import org.ip.persistence.access.PermissionSubjectLock;
import org.ip.application.catalog.AttributeValuePermissions;
import org.ip.application.catalog.NamedOperationOwner;
import org.ip.application.catalog.NamedOperationSource;
import org.ip.config.DataInitializer;
import org.ip.model.AttributeType;
import org.ip.model.AttributeValue;
import org.ip.model.AttributeValueType;
import org.ip.model.NomenclatureAccountingObject;
import org.ip.model.NomenclaturePrice;
import org.ip.repository.AttributeValueRepository;
import org.ip.service.AccessGrantAdminService;
import org.ip.service.AccessGrantAdminService.GrantFlags;
import org.ip.service.AttributeValueService;
import org.ipro.data.EntityDataAccess;
import org.ipro.register.RegisterKeyChangePermissions;
import org.ipro.register.RegisterKeyWritePermissions;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsTestFixture;
import org.ipro.rls.c5.C5AccessDeniedException;
import org.ipro.rls.c5.C5DecisionReason;
import org.ipro.rls.c5.C5PermissionKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C5.8: право на именованную операцию выдаётся штатным путём — админ-сервисом, который выдает
 * ровно то, что объявляет определение ({@code ACTION:ATTRIBUTE_VALUE:RENAME}, единственная
 * операция {@code EXECUTE} решается флагом «изменение»).
 *
 * <p>Проверяется не «кнопка появилась», а пара: выданное право открывает операцию человеку, а
 * отозванное закрывает её, причём <b>записанная строка говорит то, что значит</b> — у операции
 * нет флага чтения, и выдача его не записывает (иначе администратор считал бы, что выдал чтение
 * там, где решается выполнение).</p>
 *
 * <p>Отдельно закреплено, чего право не делает: операции мало — переименование читает исходное
 * состояние, поэтому без read-ключа оно остаётся закрытым; флаг, которого определение не
 * выражает, отвергается на выдаче, а не молча записывается.</p>
 */
@SpringBootTest(classes = org.ip.Application.class)
class NamedOperationGrantAdminIT {

    private static final String RENAME_KEY =
        C5PermissionKeys.action(AttributeValuePermissions.RENAME_PERMISSION_ID);
    private static final String READ_KEY = C5PermissionKeys.entityRead(AttributeValue.class);

    private final List<Fixture> fixtures = new ArrayList<>();

    @MockitoBean
    private DataInitializer dataInitializer;

    /** Фикстура создаёт только имя пользователя; lock субъекта — предмет P-U, не этого IT. */
    @MockitoBean
    private PermissionSubjectLock permissionSubjectLock;

    // Method security uses this context's strategy; the static holder may have
    // been replaced when another Spring test context started in the same JVM.
    @Autowired
    private SecurityContextHolderStrategy securityContextHolderStrategy;

    @Autowired
    private AccessGrantAdminService accessGrantAdminService;

    @Autowired
    private AttributeValueService valueService;

    @Autowired
    private AttributeValueRepository attributeValueRepository;

    @Autowired
    private AccessGrantRepository accessGrantRepository;

    @Autowired
    private EntityDataAccess access;

    @Autowired
    private List<NamedOperationOwner> namedOperationOwners;

    @Autowired
    private List<NamedOperationSource> namedOperationSources;

    @Autowired
    private org.ip.views.admin.AdminView adminView;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private EntityManager entityManager;

    @AfterEach
    void tearDown() {
        securityContextHolderStrategy.clearContext();
        SecurityContextHolder.clearContext();
        for (Fixture fixture : List.copyOf(fixtures)) {
            RlsTestFixture.runAsSuperuser(accessGrantRepository, () -> cleanup(fixture));
        }
        fixtures.clear();
        securityContextHolderStrategy.clearContext();
        SecurityContextHolder.clearContext();
    }

    /** Операция видна там, где её выдают, и предлагает ровно один флаг — «выполнение». */
    @Test
    void theScreenOffersTheOperationWithItsDeclaredFlagOnly() {
        asAdmin(() -> {
            assertThat(accessGrantAdminService.availableDimensions())
                .as("владелец зарегистрировал определение — ключ обязан быть на экране выдачи")
                .contains(RENAME_KEY);
            assertThat(accessGrantAdminService.isOperationDimension(RENAME_KEY)).isTrue();
            assertThat(accessGrantAdminService.isNamedOperationDimension(RENAME_KEY)).isTrue();
            assertThat(accessGrantAdminService.expressibleFlags(RENAME_KEY))
                .as("EXECUTE решается флагом «изменение»; чтения и удаления у операции нет")
                .isEqualTo(new GrantFlags(false, true, false));
            assertThat(accessGrantAdminService.expressibleFlags(READ_KEY))
                .as("единое ENTITY-определение AttributeValue поддерживает чтение и создание")
                .isEqualTo(new GrantFlags(true, true, false));
        });

        assertThat(namedOperationOwners)
            .as("владелец объявляет название операции: иначе экран показывал бы сырой ключ")
            .anySatisfy(owner -> {
                assertThat(owner.permissionId())
                    .isEqualTo(AttributeValuePermissions.RENAME_PERMISSION_ID);
                assertThat(owner.title()).isNotBlank();
            });
    }

    /**
     * Проводка экрана: новый список владельцев операций резолвится Spring'ом на настоящем bean'е
     * экрана. Ценность проверки — в самом создании: неразрешённая зависимость уронила бы контекст,
     * а не дала молча пустой экран без названий операций.
     */
    @Test
    void theScreenIsWiredWithTheDeclaredOperationOwners() {
        assertThat(adminView).isNotNull();
        assertThat(namedOperationOwners).isNotEmpty();
    }

    /**
     * S5(а): подписи операций регистра приходят из его декларации через <b>один</b> адаптер, и
     * проверяется это на настоящем контексте — с настоящими объявлениями цен номенклатуры и
     * объектов учёта, а не на строке текста в тесте.
     */
    @Test
    void registerOperationTitlesComeFromTheDeclarationThroughTheSingleAdapter() {
        Map<String, String> titles = new HashMap<>();
        NamedOperationSource.merge(namedOperationOwners, namedOperationSources)
            .forEach(owner -> titles.put(owner.permissionId(), owner.title()));

        assertThat(titles)
            .as("приложение больше не пишет текстов регистровых операций: их собирает платформа"
                + " из displayName объявленного регистра")
            .containsEntry(RegisterKeyChangePermissions.permissionId(NomenclaturePrice.class),
                "Изменить ключ записи: Цены номенклатуры")
            .containsEntry(RegisterKeyWritePermissions.setPermissionId(NomenclaturePrice.class),
                "Записать по ключу: Цены номенклатуры")
            .containsEntry(RegisterKeyWritePermissions.deletePermissionId(NomenclaturePrice.class),
                "Удалить по ключу: Цены номенклатуры")
            .containsEntry(RegisterKeyChangePermissions.permissionId(NomenclatureAccountingObject.class),
                "Изменить ключ записи: Объекты учёта номенклатуры");
        assertThat(namedOperationSources)
            .as("адаптер один на всё приложение: следующий регистр не потребует нового бина подписи")
            .hasSize(1);
    }

    /** Пара «выдал — открылось, отозвал — закрылось», включая вид записанной строки. */
    @Test
    void theIssuedGrantOpensTheOperationAndRevokingClosesIt() {
        Fixture fixture = fixture();
        asAdmin(() -> {
            accessGrantAdminService.saveSingleGrant(RENAME_KEY, AccessGrant.SubjectType.USER,
                fixture.username(), new GrantFlags(false, true, false));
            accessGrantAdminService.saveSingleGrant(READ_KEY, AccessGrant.SubjectType.USER,
                fixture.username(), new GrantFlags(true, false, false));
        });

        AccessGrant row = accessGrantRepository
            .findBySubjectTypeAndSubjectKeyAndDimensionAndDimensionValueIdIsNull(
                AccessGrant.SubjectType.USER, fixture.username(), RENAME_KEY)
            .stream().findFirst().orElseThrow();
        assertThat(row.isCanUpdate())
            .as("выдача операции пишет «изменение» — флаг, которым решается EXECUTE")
            .isTrue();
        assertThat(row.isCanRead())
            .as("чтение на операционном ключе не записывается: определение его не выражает")
            .isFalse();
        assertThat(row.isCanDelete()).isFalse();

        loginAs(fixture.username());
        assertThat(valueService.renameValue(fixture.value().getId(), "Алый", null).getCode())
            .isEqualTo("Алый");

        asAdmin(() -> accessGrantAdminService.saveSingleGrant(RENAME_KEY,
            AccessGrant.SubjectType.USER, fixture.username(), GrantFlags.NONE));
        loginAs(fixture.username());
        assertThatThrownBy(() -> valueService.renameValue(fixture.value().getId(), "Синий", null))
            .isInstanceOf(C5AccessDeniedException.class)
            .satisfies(thrown -> assertThat(((C5AccessDeniedException) thrown).decision().reason())
                .isEqualTo(C5DecisionReason.ACTION_PERMISSION_DENIED));
        assertThat(attributeValueRepository.findById(fixture.value().getId()).orElseThrow().getCode())
            .as("отозванное право не оставляет записи: отказ виден и по строке")
            .isEqualTo("Алый");
    }

    /** Права на операцию мало: без read-ключа переименование остаётся закрытым. */
    @Test
    void theOperationGrantAloneDoesNotOpenRenaming() {
        Fixture fixture = fixture();
        asAdmin(() -> accessGrantAdminService.saveSingleGrant(RENAME_KEY,
            AccessGrant.SubjectType.USER, fixture.username(), new GrantFlags(false, true, false)));

        loginAs(fixture.username());
        assertThatThrownBy(() -> valueService.renameValue(fixture.value().getId(), "Алый", null))
            .isInstanceOf(C5AccessDeniedException.class);
        assertThat(attributeValueRepository.findById(fixture.value().getId()).orElseThrow().getCode())
            .isEqualTo(fixture.value().getCode());
    }

    /** Флаг, которого определение не выражает, отвергается на выдаче — а не пишется молча. */
    @Test
    void issuingAFlagTheDefinitionCannotExpressIsRefused() {
        Fixture fixture = fixture();
        asAdmin(() -> {
            assertThatThrownBy(() -> accessGrantAdminService.saveSingleGrant(RENAME_KEY,
                AccessGrant.SubjectType.USER, fixture.username(), new GrantFlags(true, false, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(RENAME_KEY)
                .hasMessageContaining("чтение");
            assertThatThrownBy(() -> accessGrantAdminService.saveSingleGrant(RENAME_KEY,
                AccessGrant.SubjectType.USER, fixture.username(), new GrantFlags(false, false, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("удаление");

            assertThat(accessGrantRepository.findBySubjectTypeAndSubjectKeyAndDimension(
                AccessGrant.SubjectType.USER, fixture.username(), RENAME_KEY))
                .as("отказ на выдаче не оставляет частичной строки")
                .isEmpty();
            assertThat(accessGrantAdminService.currentSingleGrant(RENAME_KEY,
                AccessGrant.SubjectType.USER, fixture.username())).isEqualTo(GrantFlags.NONE);
        });
    }

    /**
     * Строка с флагом, которого определение не выражает (наследство прежней выдачи), показывается
     * как «права нет»: экран показывает решения определения, а не сырые колонки.
     */
    @Test
    void aLegacyRowWithAnUndeclaredFlagIsShownAsNoRight() {
        Fixture fixture = fixture();
        asAdmin(() -> {
            AccessGrant legacy = new AccessGrant();
            legacy.setSubjectType(AccessGrant.SubjectType.USER);
            legacy.setSubjectKey(fixture.username());
            legacy.setDimension(RENAME_KEY);
            legacy.setDimensionValueId(null);
            legacy.setCanRead(true);
            legacy.setCanUpdate(true);
            accessGrantRepository.saveAndFlush(legacy);

            assertThat(accessGrantAdminService.currentSingleGrant(RENAME_KEY,
                AccessGrant.SubjectType.USER, fixture.username()))
                .isEqualTo(new GrantFlags(false, true, false));
        });
    }

    // === Фикстура ===

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = "rename-grant-" + suffix;
        AttributeType type = RlsTestFixture.callAsSuperuser(accessGrantRepository, () -> {
            RlsTestFixture.grantC5EntityRead(accessGrantRepository,
                SecurityContextHolder.getContext().getAuthentication().getName(),
                AttributeType.class);
            RlsTestFixture.grantC5EntityOperations(accessGrantRepository,
                SecurityContextHolder.getContext().getAuthentication().getName(),
                AttributeType.class);
            return access.create(AttributeType.class, new AttributeType("GRN-" + suffix,
                "Цвет " + suffix, AttributeValueType.STRING));
        });
        AttributeValue value = RlsTestFixture.callAsSuperuser(accessGrantRepository, () ->
            valueService.getOrCreate(type, "Красный"));
        Fixture fixture = new Fixture(username, type, value);
        fixtures.add(fixture);
        return fixture;
    }

    /**
     * Администратор: выдача прав — его операция, поэтому вызов идёт под ролью ADMIN, как из экрана.
     * Права на собственные canonical-записи администратора выдаёт фикстура суперпользователя —
     * та же первичная выдача, что и у приложения.
     */
    private void asAdmin(Runnable action) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext previousMethodContext = securityContextHolderStrategy.getContext();
        SecurityContext admin = securityContextHolderStrategy.createEmptyContext();
        admin.setAuthentication(new UsernamePasswordAuthenticationToken("admin", "n/a",
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        securityContextHolderStrategy.setContext(admin);
        SecurityContextHolder.setContext(admin);
        try {
            RlsTestFixture.grantC5EntityRead(accessGrantRepository, "admin", AttributeType.class,
                AttributeValue.class);
            RlsTestFixture.grantC5EntityOperations(accessGrantRepository, "admin",
                AttributeType.class, AttributeValue.class);
            action.run();
        } finally {
            securityContextHolderStrategy.setContext(previousMethodContext);
            SecurityContextHolder.setContext(previous);
        }
    }

    private void loginAs(String username) {
        SecurityContext context = securityContextHolderStrategy.createEmptyContext();
        context.setAuthentication(
            new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
        securityContextHolderStrategy.setContext(context);
        SecurityContextHolder.setContext(context);
    }

    /** Уборка: строки значения снимаются bulk-запросом, тип — canonical path (ADR-0019 §3). */
    private void cleanup(Fixture fixture) {
        transactions.executeWithoutResult(status -> entityManager.createQuery(
                "delete from AttributeValue v where v.attrType.id = :typeId")
            .setParameter("typeId", fixture.type().getId())
            .executeUpdate());
        RlsTestFixture.grantC5EntityRead(accessGrantRepository,
            SecurityContextHolder.getContext().getAuthentication().getName(), AttributeType.class);
        access.delete(AttributeType.class, fixture.type().getId());
        for (String subject : List.of(fixture.username(), "admin")) {
            for (String dimension : List.of(READ_KEY, RENAME_KEY,
                    C5PermissionKeys.entityOperations(AttributeValue.class),
                    C5PermissionKeys.entityRead(AttributeType.class),
                    C5PermissionKeys.entityOperations(AttributeType.class))) {
                accessGrantRepository.deleteAll(accessGrantRepository
                    .findBySubjectTypeAndSubjectKeyAndDimension(
                        AccessGrant.SubjectType.USER, subject, dimension));
            }
        }
        accessGrantRepository.flush();
    }

    private record Fixture(String username, AttributeType type, AttributeValue value) {
    }
}
