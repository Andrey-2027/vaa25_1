package org.ip.application.catalog;

import jakarta.persistence.EntityManager;
import org.ip.config.DataInitializer;
import org.ip.model.AttributeType;
import org.ip.model.AttributeValue;
import org.ip.model.AttributeValueType;
import org.ip.repository.AttributeValueRepository;
import org.ip.service.AttributeValueService;
import org.ipro.data.EntityDataAccess;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsAccessDeniedException;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsTestFixture;
import org.ipro.rls.c5.C5AccessDeniedException;
import org.ipro.rls.c5.C5DecisionReason;
import org.ipro.rls.c5.C5Operation;
import org.ipro.rls.c5.C5OperationPermissionRegistry;
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
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C5.6b (ADR-0017 §9, [ADR-0019](../../../../docs/architecture/decisions/ADR-0019-owner-named-row-operation.md)):
 * тип {@code AttributeValue} вооружён — прямых записей этого типа в production-коде не осталось,
 * поэтому граница для него обязательна, а не снята.
 *
 * <p>Проверяются три вещи, которых не может проверить ни слайс-тест сервиса, ни IT именованной
 * операции на невооружённом типе. Первое: вооружён ровно названный тип, соседний {@code
 * AttributeType} остаётся снятым — пошаговость видна, а не подразумевается. Второе: прямой
 * {@code EntityManager} по этому типу отклоняется на flush, то есть обход закрыт. Третье:
 * <b>гейт кнопки и серверный путь отвечают одинаково</b> — кнопка формы спрашивает то же решение
 * ({@link AttributeValuePermissions}), что и canonical path, поэтому каждая пара «кнопка
 * выключена — сервер отказал» и «кнопка включена — сервер выполнил» проверяется как пара, а не
 * по отдельности: расхождение между ними и есть дефект, который такая пара ловит.</p>
 *
 * <p>Вооружён только один тип: список свойств в этом контексте называет {@code AttributeValue},
 * поэтому соседи сняты и это проверяется через {@code isWriteGoverned}, а не предполагается.</p>
 */
@SpringBootTest(classes = org.ip.Application.class, properties = {
    "rls.write-boundary.enforced-types=org.ip.model.AttributeValue"})
class AttributeValueWriteBoundaryIT {

    private final List<Fixture> fixtures = new ArrayList<>();

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private EntityDataAccess access;

    @Autowired
    private AttributeValueService valueService;

    @Autowired
    private AttributeValuePermissions permissions;

    @Autowired
    private AttributeValueRepository attributeValueRepository;

    @Autowired
    private AccessGrantRepository accessGrantRepository;

    @Autowired
    private RlsDimensionRegistry registry;

    @Autowired
    private C5OperationPermissionRegistry operations;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactions;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        for (Fixture fixture : List.copyOf(fixtures)) {
            RlsTestFixture.runAsSuperuser(accessGrantRepository, () -> cleanup(fixture));
        }
        fixtures.clear();
        SecurityContextHolder.clearContext();
    }

    /** Вооружён ровно названный тип: соседний тип остаётся снятым — это следующий шаг, а не забытый. */
    @Test
    void onlyTheNamedTypeIsArmed() {
        assertThat(registry.isWriteGoverned(AttributeValue.class)).isTrue();
        assertThat(registry.isWriteGoverned(AttributeType.class))
            .as("неназванный тип остаётся снятым: список — признак завершённой миграции, а не режим")
            .isFalse();
    }

    /**
     * Прямая запись мимо canonical path: авторизация не фиксировалась, поэтому отказ на flush —
     * именно то, что граница обязана делать после перевода production-путей на canonical create.
     */
    @Test
    void aDirectRepositoryWriteIsDeniedAtFlush() {
        Fixture fixture = fixture();
        loginAs(fixture.username());
        String directCode = "ПРЯМАЯ-" + UUID.randomUUID().toString().substring(0, 6);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            // Тип берётся в этой же транзакции: ссылка на detached-строку дала бы отказ
            // Hibernate, а не границы, и тест проверял бы не то.
            AttributeType managedType = entityManager.find(AttributeType.class,
                fixture.type().getId());
            AttributeValue direct = new AttributeValue();
            direct.setAttrType(managedType);
            direct.setCode(directCode);
            entityManager.persist(direct);
            entityManager.flush();
        }))
            .isInstanceOf(RlsAccessDeniedException.class)
            .hasMessageContaining("canonical write preflight")
            .hasMessageContaining(AttributeValue.class.getName());

        assertThat(attributeValueRepository.findByAttrTypeAndCodeUp(fixture.type(),
            directCode.toUpperCase(Locale.ROOT)))
            .as("отказ на flush обязан не оставить строку: иначе граница сообщала бы об отказе, а запись состоялась")
            .isEmpty();
    }

    /**
     * Пара «кнопка и сервер» для создания значения: кнопка «Добавить значение» отвечает решением
     * {@code canCreateValue()}, серверный путь — canonical create. Без права CREATE оба отказывают,
     * с правом оба выполняют — и дедуп по каноническому ключу при этом сохраняется.
     */
    @Test
    void theCreateButtonAndTheServerPathAgree() {
        Fixture fixture = fixture();
        loginAs(fixture.username());

        assertThat(permissions.canCreateValue())
            .as("кнопка не может быть открыта без права, которое сервер не исполнит")
            .isFalse();
        assertThatThrownBy(() -> valueService.getOrCreate(fixture.type(), "Синий"))
            .isInstanceOf(C5AccessDeniedException.class)
            .satisfies(thrown -> assertThat(((C5AccessDeniedException) thrown).decision().reason())
                .isEqualTo(C5DecisionReason.ENTITY_PERMISSION_DENIED));

        RlsTestFixture.grantC5EntityOperations(accessGrantRepository, fixture.username(),
            AttributeValue.class);
        assertThat(permissions.canCreateValue()).isTrue();
        AttributeValue first = valueService.getOrCreate(fixture.type(), " Синий ");
        AttributeValue second = valueService.getOrCreate(fixture.type(), "синий");
        assertThat(first.getId()).isNotNull();
        assertThat(second.getId()).isEqualTo(first.getId());
    }

    /**
     * Пара «кнопка и сервер» для переименования: кнопка отвечает решением
     * {@code canRenameValue()}, серверный путь — именованной операцией владельца. Кнопка требует
     * и чтения строки (canonical read исходного состояния), поэтому пара проверяется дважды:
     * без права на операцию и с ним.
     */
    @Test
    void theRenameButtonAndTheServerPathAgree() {
        Fixture fixture = fixture();
        loginAs(fixture.username());
        RlsTestFixture.grantC5EntityRead(accessGrantRepository, fixture.username(),
            AttributeValue.class);

        assertThat(permissions.canRenameValue())
            .as("чтения строки мало: без права на операцию кнопка обязана быть закрыта")
            .isFalse();
        assertThatThrownBy(() -> valueService.renameValue(fixture.value().getId(), "Алый", null))
            .isInstanceOf(C5AccessDeniedException.class)
            .satisfies(thrown -> assertThat(((C5AccessDeniedException) thrown).decision().reason())
                .isEqualTo(C5DecisionReason.ACTION_PERMISSION_DENIED));

        RlsTestFixture.grantC5Action(accessGrantRepository, fixture.username(),
            AttributeValuePermissions.RENAME_PERMISSION_ID);
        assertThat(permissions.canRenameValue()).isTrue();
        AttributeValue renamed = valueService.renameValue(fixture.value().getId(), "Алый", null);
        assertThat(renamed.getCode()).isEqualTo("Алый");
        assertThat(renamed.getId()).isEqualTo(fixture.value().getId());
    }

    /**
     * Вооружение не публикует generic-операции типа: у него нет ни UPDATE, ни DELETE определения,
     * поэтому кнопка generic-пути не появится, а попытка пройдёт отказ, а не тихую запись.
     */
    @Test
    void armingDoesNotPublishGenericOperations() {
        Fixture fixture = fixture();
        loginAs(fixture.username());
        RlsTestFixture.grantC5EntityOperations(accessGrantRepository, fixture.username(),
            AttributeValue.class);

        assertThat(operations.isRegistered(C5PermissionKeys.entityOperations(AttributeValue.class)))
            .as("операционный ключ типа существует, но объявляет только CREATE")
            .isTrue();
        assertThat(operations.requireOperations(
            C5PermissionKeys.entityOperations(AttributeValue.class)))
            .containsExactly(C5Operation.CREATE);

        AttributeValue row = attributeValueRepository.findById(fixture.value().getId()).orElseThrow();
        assertThatThrownBy(() -> access.update(AttributeValue.class, row))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("бессмертно");
        assertThatThrownBy(() -> access.delete(AttributeValue.class, fixture.value().getId()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("DELETE");
    }

    // === Фикстура ===

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = "value-armed-" + suffix;
        AttributeType type = createType(suffix);
        AttributeValue value = RlsTestFixture.callAsSuperuser(accessGrantRepository,
            () -> valueService.getOrCreate(type, "Красный"));
        Fixture fixture = new Fixture(username, type, value);
        fixtures.add(fixture);
        return fixture;
    }

    /** Тип заводится canonical path: суперпользователь фикстуры имеет право на его создание. */
    private AttributeType createType(String suffix) {
        return RlsTestFixture.callAsSuperuser(accessGrantRepository, () ->
            access.create(AttributeType.class, new AttributeType("ARM-" + suffix,
                "Цвет " + suffix, AttributeValueType.STRING)));
    }

    private void loginAs(String username) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
    }

    /**
     * Уборка фикстуры: строки значения снимаются bulk-запросом (canonical delete у типа нет
     * by design, ADR-0019 §3), тип снимается canonical path.
     */
    private void cleanup(Fixture fixture) {
        transactions.executeWithoutResult(status -> entityManager.createQuery(
                "delete from AttributeValue v where v.attrType.id = :typeId")
            .setParameter("typeId", fixture.type().getId())
            .executeUpdate());
        RlsTestFixture.grantC5EntityRead(accessGrantRepository,
            SecurityContextHolder.getContext().getAuthentication().getName(), AttributeType.class);
        access.delete(AttributeType.class, fixture.type().getId());
        for (String dimension : List.of(
                C5PermissionKeys.entityRead(AttributeValue.class),
                C5PermissionKeys.entityOperations(AttributeValue.class),
                C5PermissionKeys.action(AttributeValuePermissions.RENAME_PERMISSION_ID))) {
            accessGrantRepository.deleteAll(accessGrantRepository
                .findBySubjectTypeAndSubjectKeyAndDimension(
                    org.ipro.rls.AccessGrant.SubjectType.USER, fixture.username(), dimension));
        }
        accessGrantRepository.flush();
    }

    private record Fixture(String username, AttributeType type, AttributeValue value) {
    }
}
