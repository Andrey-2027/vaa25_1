package org.ip.application.catalog;

import jakarta.persistence.EntityManager;
import org.ip.config.DataInitializer;
import org.ip.model.AttributeType;
import org.ip.model.AttributeValue;
import org.ip.model.AttributeValueType;
import org.ip.model.SklNomOpa;
import org.ip.repository.AttributeValueRepository;
import org.ip.service.AttributeValueService;
import org.ipro.data.CanonicalWriteDeniedException;
import org.ipro.data.EntityDataAccess;
import org.ipro.data.WriteTelemetry;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsTestFixture;
import org.ipro.rls.c5.C5AccessDeniedException;
import org.ipro.rls.c5.C5DecisionReason;
import org.ipro.rls.c5.C5PermissionConfigurationException;
import org.ipro.rls.c5.C5PermissionKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C5.6b: именованная операция владельца на строке — рабочий срез ADR-0019 на полном контексте.
 *
 * <p>Проверяется то, чего не может проверить слайс-тест сервиса: порядок решения (отказ об
 * операции приходит до чтения строки и до SQL, а не вместо него), носитель права ({@code ACTION}-
 * строка, а не ключ типа), сохранение запрета generic update/delete и то, что создание значения
 * идёт canonical create и требует права {@code CREATE}.</p>
 *
 * <p>Права выдаются явно, как это делает первичная выдача: без строки на {@code
 * ACTION:ATTRIBUTE_VALUE:RENAME} переименование отказывает даже субъекту, который видит строку и
 * имеет {@code CREATE} на тип. Это не ослабление проверки ради теста, а тот же fail-closed вход,
 * которым пользуется приложение.</p>
 */
@SpringBootTest(classes = org.ip.Application.class)
class AttributeValueRenameOperationIT {

    private final List<Fixture> fixtures = new ArrayList<>();

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private EntityDataAccess access;

    @Autowired
    private AttributeValueService valueService;

    @Autowired
    private AttributeValueRepository attributeValueRepository;

    @Autowired
    private AccessGrantRepository accessGrantRepository;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private EntityManager entityManager;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        for (Fixture fixture : List.copyOf(fixtures)) {
            RlsTestFixture.runAsSuperuser(accessGrantRepository, () -> cleanup(fixture));
        }
        fixtures.clear();
        SecurityContextHolder.clearContext();
    }

    /**
     * Субъект без права на операцию, но с правом чтения строки: отказ обязан называть именованную
     * операцию, а не доступ к строке. Право чтения выдано намеренно — без него отказ нельзя было
     * бы отличить от отказа в чтении, и тест проверял бы не операцию. То, что решение принято до
     * загрузки строки и до SQL, видно по телеметрии отказа ({@code sql=0}), а не выводится из
     * текста ошибки.
     */
    @Test
    void renameWithoutActionGrantIsDeniedByTheNamedDecisionBeforeTheRowIsLoaded() {
        Fixture fixture = fixture();
        loginAs(fixture.username(), false);

        assertThatThrownBy(() -> valueService.renameValue(fixture.value().getId(), "Алый", null))
            .isInstanceOf(C5AccessDeniedException.class)
            .hasMessageContaining("ATTRIBUTE_VALUE:RENAME")
            .satisfies(thrown -> assertThat(((C5AccessDeniedException) thrown).decision().reason())
                .isEqualTo(C5DecisionReason.ACTION_PERMISSION_DENIED));

        AttributeValue untouched = attributeValueRepository.findById(fixture.value().getId())
            .orElseThrow();
        assertThat(untouched.getCode()).isEqualTo(fixture.value().getCode());
    }

    /** Право на операцию открывает её: id строки не меняется, слот старого кода освобождается. */
    @Test
    void renameWithActionGrantUpdatesCodeAndKeepsTheRow() {
        Fixture fixture = fixture();
        loginAs(fixture.username(), true);

        AttributeValue renamed = valueService.renameValue(fixture.value().getId(), "Алый", null);

        assertThat(renamed.getId()).isEqualTo(fixture.value().getId());
        assertThat(renamed.getCode()).isEqualTo("Алый");
        assertThat(renamed.getCodeUp()).isEqualTo("АЛЫЙ");
        assertThat(renamed.getName()).isEqualTo("Алый");
        assertThat(attributeValueRepository.findByAttrTypeAndCodeUp(fixture.type(), "АЛЫЙ"))
            .isPresent();
        assertThat(attributeValueRepository.findByAttrTypeAndCodeUp(
            fixture.type(), fixture.value().getCodeUp()))
            .isEmpty();
    }

    /**
     * Незарегистрированное определение — ошибка конфигурации, а не allow: ключ, которого не
     * объявил ни один владелец, не может быть выдан, значит, и решиться не может.
     */
    @Test
    void unregisteredNamedOperationIsAConfigurationError() {
        Fixture fixture = fixture();
        loginAs(fixture.username(), false);

        assertThatThrownBy(() -> access.executeNamed(AttributeValue.class, "ATTRIBUTE_VALUE:NOPE",
            fixture.value().getId(), value -> value.setCode("Нет")))
            .isInstanceOf(C5PermissionConfigurationException.class)
            .hasMessageContaining("ATTRIBUTE_VALUE:NOPE");
    }

    /** Именованная операция — тоже запись: тип без write handle её не получает. */
    @Test
    void namedOperationOnATypeWithoutCanonicalWriteHandleIsRejected() {
        assertThatThrownBy(() -> access.executeNamed(SklNomOpa.class,
            AttributeValuePermissions.RENAME_PERMISSION_ID, 1L, value -> { }))
            .isInstanceOf(CanonicalWriteDeniedException.class)
            .satisfies(thrown -> assertThat(((CanonicalWriteDeniedException) thrown).kind())
                .isEqualTo(WriteTelemetry.DenialKind.CAPABILITY));
    }

    /** Публикация именованной операции не открывает generic-записи типа (ADR-0019 §3). */
    @Test
    void genericUpdateAndDeleteOfAttributeValueRemainRefused() {
        Fixture fixture = fixture();
        loginAs(fixture.username(), true);
        AttributeValue row = attributeValueRepository.findById(fixture.value().getId())
            .orElseThrow();

        assertThatThrownBy(() -> access.update(AttributeValue.class, row))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("UPDATE", "@InternedContract");
        assertThatThrownBy(() -> access.delete(AttributeValue.class, fixture.value().getId()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("DELETE");
    }

    /**
     * Создание значения идёт canonical create: без права {@code CREATE} на ключ типа отказ, с
     * правом — успех и прежний дедуп по каноническому ключу.
     */
    @Test
    void valueCreationRequiresCreateRightAndKeepsInterning() {
        Fixture fixture = fixture();
        loginAs(fixture.username(), false);

        assertThatThrownBy(() -> valueService.getOrCreate(fixture.type(), "Синий"))
            .isInstanceOf(C5AccessDeniedException.class)
            .satisfies(thrown -> assertThat(((C5AccessDeniedException) thrown).decision().reason())
                .isEqualTo(C5DecisionReason.ENTITY_PERMISSION_DENIED));

        RlsTestFixture.grantC5EntityOperations(accessGrantRepository, fixture.username(),
            AttributeValue.class);
        AttributeValue first = valueService.getOrCreate(fixture.type(), " Синий ");
        AttributeValue second = valueService.getOrCreate(fixture.type(), "синий");

        assertThat(first.getId()).isNotNull();
        assertThat(second.getId()).isEqualTo(first.getId());
    }

    // === Фикстура ===

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = "value-rename-" + suffix;
        AttributeType type = createType(suffix);
        AttributeValue value = RlsTestFixture.callAsSuperuser(accessGrantRepository,
            () -> valueService.getOrCreate(type, "Красный"));
        Fixture fixture = new Fixture(username, type, value);
        fixtures.add(fixture);
        return fixture;
    }

    private AttributeType createType(String suffix) {
        return RlsTestFixture.callAsSuperuser(accessGrantRepository, () -> {
            RlsTestFixture.grantC5EntityRead(accessGrantRepository, currentSubject(),
                AttributeType.class);
            return access.create(AttributeType.class,
                new AttributeType("REN-" + suffix, "Цвет " + suffix, AttributeValueType.STRING));
        });
    }

    /**
     * Субъект проверки. Read-ключ строки значения выдаётся всегда: иначе отказ нельзя было бы
     * отличить от отказа в чтении, и тест проверял бы не операцию. {@code withRenameRight} решает,
     * получит ли субъект строку на именованную операцию.
     */
    private void loginAs(String username, boolean withRenameRight) {
        RlsTestFixture.grantC5EntityRead(accessGrantRepository, username, AttributeValue.class);
        if (withRenameRight) {
            RlsTestFixture.grantC5Action(accessGrantRepository, username,
                AttributeValuePermissions.RENAME_PERMISSION_ID);
        }
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
    }

    private static String currentSubject() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    /**
     * Уборка фикстуры: строки значения снимаются bulk-запросом (canonical delete у типа нет
     * by design, ADR-0019 §3), тип снимается canonical path, как и в соседних фикстурах.
     */
    private void cleanup(Fixture fixture) {
        transactions.executeWithoutResult(status -> entityManager.createQuery(
                "delete from AttributeValue v where v.attrType.id = :typeId")
            .setParameter("typeId", fixture.type().getId())
            .executeUpdate());
        RlsTestFixture.grantC5EntityRead(accessGrantRepository, currentSubject(),
            AttributeType.class);
        access.delete(AttributeType.class, fixture.type().getId());
        for (String dimension : List.of(
                C5PermissionKeys.entityRead(AttributeValue.class),
                C5PermissionKeys.entityOperations(AttributeValue.class),
                C5PermissionKeys.action(AttributeValuePermissions.RENAME_PERMISSION_ID))) {
            accessGrantRepository.deleteAll(accessGrantRepository
                .findBySubjectTypeAndSubjectKeyAndDimension(
                    AccessGrant.SubjectType.USER, fixture.username(), dimension));
        }
        accessGrantRepository.flush();
    }

    private record Fixture(String username, AttributeType type, AttributeValue value) {
    }
}
