package org.ip.application.user;

import org.ip.application.catalog.NamedOperationOwner;
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
 * Владелец именованной операции смены пароля пользователя (ADR-0017 §12, ADR-0019 §1, §5).
 *
 * <p>Смена пароля — отдельное определение, а не побочная часть generic-записи пользователя:
 * держатель изменения строки {@code User} мог бы иначе переписать учётные данные любого, включая
 * себя, не имея на то названного права. Пароль нового пользователя — тот же случай: создание
 * учётной записи включает пароль, поэтому оно проверяется этой же операцией, а не считается
 * бесплатным приложением к {@code CREATE}.</p>
 *
 * <p>Ответ формы берётся у этого же владельца ({@link #canChangePassword()}) и складывается из
 * одного решения — {@code EXECUTE} по ключу операции: в отличие от назначения ролей, смена
 * пароля не читает сохранённое состояние, поэтому чтения пользователя ей не требуется.</p>
 */
@Component
public class UserPasswordChangePermission implements InitializingBean, NamedOperationOwner {

    /** Стабильный идентификатор операции — тот же, что уходит в {@code C5PermissionKeys.action}. */
    public static final String PERMISSION_ID = "USER:CHANGE_PASSWORD";

    /** Название операции для человека: его показывает админ-экран там, где операция выдаётся. */
    public static final String TITLE = "Смена пароля пользователя";

    private final C5OperationPermissionRegistry operationRegistry;
    private final RlsDimensionRegistry dimensionRegistry;
    private final C5PermissionDecisionService decisions;
    private final RlsCurrentUser currentUser;

    public UserPasswordChangePermission(C5OperationPermissionRegistry operationRegistry,
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
        dimensionRegistry.registerDynamicDimension(definition.key(), RlsDimensionKind.CHECK_ONLY);
        operationRegistry.register(definition);
    }

    /** Требует право на смену пароля; отказ приходит как C5-отказ, а не как тихий пропуск. */
    public void requireChangePassword(String username) {
        decisions.requireAction(username, PERMISSION_ID);
    }

    /** Доступна ли смена пароля текущему субъекту — тот же ответ, что спрашивает серверный путь. */
    public boolean canChangePassword() {
        return permits(C5PermissionKeys.action(PERMISSION_ID), C5Operation.EXECUTE);
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
