package org.ip.application.user;

import org.ip.application.catalog.NamedOperationOwner;
import org.ip.model.User;
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
 * Владелец именованной операции назначения ролей пользователя: отдельное определение, его
 * регистрирующее, и тот же ответ на вопрос «можно ли это текущему субъекту», которым пользуется
 * форма (ADR-0017 §12, ADR-0019 §1, §5).
 *
 * <p>Почему отдельное определение, а не generic-CRUD {@code User}: {@code ENTITY:...User}
 * разрешает запись строки пользователя, но не отвечает на вопрос «кому назначать роли» — иначе
 * любой держатель изменения пользователя распоряжался бы ролями, то есть правами. Набор ролей
 * меняется только по строке {@code ACTION:USER:ASSIGN_ROLES}, выданной отдельно, и по той же
 * строке решает серверный путь ({@code UserService}), а не только форма.</p>
 *
 * <p>Ответ формы складывается из двух решений того же движка, что спрашивает серверный путь:
 * сама операция ({@code EXECUTE}) и чтение пользователя, без которого назначение не может
 * сравнить набор ролей с сохранённым и потому не может отличить назначение от обычного
 * обновления. Поле, открытое без права, было бы обещанием, которое сервер не исполняет.</p>
 *
 * <p>Своего владельца операция не открывает никому: набор ролей своей учётной записи меняет
 * другой держатель права (см. {@code UserService}). Форма берёт и это из владельца операции
 * ({@link #canAssignRolesFor(String)}), а не выводит из собственного условия.</p>
 *
 * <p>Первичная выдача (C5.8): это определение входит в административный минимум, который стартер
 * {@code InitialGrantIssuance} материализует ролям базовой выдачи (`app.security-baseline.roles`,
 * ключи в `app.security-baseline.operations`), — без него свежий контур закрыл бы управление
 * пользователями и для администратора. Остальные определения {@code ACTION:} остаются точечной
 * выдачей. Пока строка не выдана (выдача выключена по умолчанию), экран доступа показывает это
 * определение в отчёте «определение зарегистрировано, грантов нет», и назначение закрыто
 * <b>включая администратора</b> — это названное состояние, а не тихий обход.</p>
 */
@Component
public class UserRoleAssignmentPermission implements InitializingBean, NamedOperationOwner {

    /**
     * Стабильный идентификатор операции: выведен из владельца и операции, не из имени bean'а или
     * подписи метода ({@code C5PermissionKeys.action} даёт из него ключ {@code ACTION:...}).
     */
    public static final String PERMISSION_ID = "USER:ASSIGN_ROLES";

    /** Название операции для человека: его показывает админ-экран там, где операция выдаётся. */
    public static final String TITLE = "Назначение ролей пользователя";

    private final C5OperationPermissionRegistry operationRegistry;
    private final RlsDimensionRegistry dimensionRegistry;
    private final C5PermissionDecisionService decisions;
    private final RlsCurrentUser currentUser;

    public UserRoleAssignmentPermission(C5OperationPermissionRegistry operationRegistry,
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

    @Override
    public String permissionId() {
        return PERMISSION_ID;
    }

    @Override
    public String title() {
        return TITLE;
    }

    /** Определение операции объявляется при старте, до первого решения о ней. */
    @Override
    public void afterPropertiesSet() {
        C5PermissionDefinition definition = C5PermissionDefinition.action(PERMISSION_ID);
        // Ключ становится измерением того же рода, что ключи операций типов: иначе его нельзя
        // было бы назначить субъекту, и определение осталось бы невыдаваемым.
        dimensionRegistry.registerDynamicDimension(definition.key(), RlsDimensionKind.CHECK_ONLY);
        operationRegistry.register(definition);
    }

    /** Требует право на назначение ролей; отказ приходит как C5-отказ, а не как тихий пропуск. */
    public void requireAssignRoles(String username) {
        decisions.requireAction(username, PERMISSION_ID);
    }

    /**
     * Доступно ли назначение ролей текущему субъекту: операция и чтение пользователя — те же два
     * ответа, которые спрашивает серверный путь назначения.
     */
    public boolean canAssignRoles() {
        return permits(C5PermissionKeys.action(PERMISSION_ID), C5Operation.EXECUTE)
            && permits(C5PermissionKeys.entityRead(User.class), C5Operation.READ);
    }

    /**
     * Доступно ли назначение ролей для конкретной учётной записи: право есть и цель не своя.
     * Свой набор ролей не меняет никто — то же правило, что проверяет серверный путь;
     * {@code targetUsername == null} (новая, ещё не названная запись) своей быть не может.
     */
    public boolean canAssignRolesFor(String targetUsername) {
        return canAssignRoles() && !isOwnRecord(targetUsername);
    }

    private boolean isOwnRecord(String targetUsername) {
        String username = currentUser.username();
        return targetUsername != null && username != null && !username.isBlank()
            && targetUsername.equals(username);
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

