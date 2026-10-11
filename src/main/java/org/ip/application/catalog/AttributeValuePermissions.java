package org.ip.application.catalog;

import org.ip.model.AttributeValue;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsDimensionKind;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.c5.C5Operation;
import org.ipro.rls.c5.C5OperationPermissionRegistry;
import org.ipro.rls.c5.C5PermissionDecision;
import org.ipro.rls.c5.C5PermissionDecisionService;
import org.ipro.rls.c5.C5PermissionDefinition;
import org.ipro.rls.c5.C5PermissionKeys;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Владелец именованной операции переименования значения атрибута: определение, её регистрирующее,
 * и тот же ответ на вопрос «доступно ли это текущему субъекту», которым пользуется UI (ADR-0019 §1,
 * §5; ADR-0017 §5).
 *
 * <p>Определение регистрирует владелец операции, а не descriptor типа: {@code AttributeValue}
 * объявлен CREATE-only, generic update/delete у него нет by design, поэтому переименование
 * выражается ключом {@code ACTION:ATTRIBUTE_VALUE:RENAME} с единственной операцией
 * {@code EXECUTE}. Регистрация никого не пускает и не закрывает: она делает ключ узнаваемым и
 * назначаемым — строка в {@code access_grant} остаётся единственным источником права, а
 * незарегистрированный ключ отказывает при старте, а не превращается в allow.</p>
 *
 * <p>Ответ UI берётся из того же решения, что спрашивает серверный путь, а не из собственного
 * условия формы: кнопка, открытая без права, была бы обещанием, которое сервер не исполняет.
 * Переименование дополнительно требует чтения строки (canonical read исходного состояния —
 * ADR-0019 §2), поэтому спрашиваются оба ответа, и UI не расходится с enforcement.</p>
 *
 * <p>Первичная выдача строк на этот ключ — отдельный шаг (C5.8): без неё операция закрыта и для
 * держателя прежнего {@code ENTITY:AttributeType}, и это названное состояние, а не тихий обход.</p>
 */
@Component
public class AttributeValuePermissions implements InitializingBean, NamedOperationOwner {

    /**
     * Стабильный идентификатор операции: выведен из владельца и операции, не из имени bean'а или
     * подписи метода ({@code C5PermissionKeys.action} даёт из него ключ {@code ACTION:...}).
     */
    public static final String RENAME_PERMISSION_ID = "ATTRIBUTE_VALUE:RENAME";

    /**
     * Название операции для человека: его показывает админ-экран там, где операция выдаётся
     * ({@link NamedOperationOwner}). Без него администратор выдавал бы сырой ключ
     * {@code ACTION:...} и не мог бы объяснить, что именно открывает.
     */
    public static final String RENAME_TITLE = "Переименование значения атрибута";

    private final C5OperationPermissionRegistry operationRegistry;
    private final RlsDimensionRegistry dimensionRegistry;
    private final C5PermissionDecisionService decisions;
    private final RlsCurrentUser currentUser;

    public AttributeValuePermissions(C5OperationPermissionRegistry operationRegistry,
                                     RlsDimensionRegistry dimensionRegistry,
                                     C5PermissionDecisionService decisions,
                                     RlsCurrentUser currentUser) {
        this.operationRegistry = Objects.requireNonNull(operationRegistry,
            "operationRegistry must not be null");
        this.dimensionRegistry = Objects.requireNonNull(dimensionRegistry,
            "dimensionRegistry must not be null");
        this.decisions = Objects.requireNonNull(decisions, "decisions must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
    }

    /** Имя операции для человека — рядом с её определением, а не в экране выдачи. */
    @Override
    public String permissionId() {
        return RENAME_PERMISSION_ID;
    }

    @Override
    public String title() {
        return RENAME_TITLE;
    }

    /** Определение операции объявляется при старте, до первого решения о ней. */
    @Override
    public void afterPropertiesSet() {
        C5PermissionDefinition definition = C5PermissionDefinition.action(RENAME_PERMISSION_ID);
        // Ключ становится измерением того же рода, что ключи операций типов: иначе его нельзя
        // было бы назначить субъекту, и определение осталось бы невыдаваемым.
        dimensionRegistry.registerDynamicDimension(definition.key(), RlsDimensionKind.CHECK_ONLY);
        operationRegistry.register(definition);
    }

    /**
     * Доступно ли переименование значения текущему субъекту. Спрашиваются оба ответа серверного
     * пути: операция ({@code ACTION:ATTRIBUTE_VALUE:RENAME}) и чтение строки, без которого
     * canonical read исходного состояния откажет.
     */
    public boolean canRenameValue() {
        return permits(C5PermissionKeys.action(RENAME_PERMISSION_ID), C5Operation.EXECUTE)
            && permits(C5PermissionKeys.entityRead(AttributeValue.class), C5Operation.READ);
    }

    /**
     * Доступно ли создание значения: значения создаются canonical create (ADR-0019 §4), поэтому
     * кнопка «Добавить значение» отвечает тем же решением, что спрашивает серверный путь.
     */
    public boolean canCreateValue() {
        return permits(C5PermissionKeys.entityOperations(AttributeValue.class), C5Operation.CREATE);
    }

    /** Решение C5 для текущего субъекта; неаутентифицированный субъект получает отказ. */
    private boolean permits(String key, C5Operation operation) {
        String username = currentUser.username();
        if (username == null || username.isBlank()) {
            return false;
        }
        C5PermissionDecision decision = decisions.decide(username, key, operation);
        return decision != null && decision.allowed();
    }
}
