package org.ip.application.user;

import org.ip.model.User;
import org.ipro.data.CanonicalWritePropertyPolicy;
import org.ipro.rls.RlsCurrentUser;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Правило владельца: хэш пароля {@link User} не переносится generic-записью — любое изменение
 * требует решения именованной операции {@code USER:CHANGE_PASSWORD} (ADR-0017 §12,
 * {@link UserPasswordChangePermission}).
 *
 * <p>Зачем: держатель изменения строки {@code User} мог бы иначе переписать учётные данные любого,
 * включая себя, не имея названного права, — а «изменение строки пользователя» и «смена пароля» это
 * разные операции. Форма и {@code UserService} спрашивают это право на своём пути; здесь то же
 * решение спрашивается у <b>любого</b> канала canonical write, потому что свойство переносится
 * сущностью, а не отдельным вызовом.</p>
 *
 * <p><b>Это не полевое право и не ATTR-ключ</b> (ADR-0018): новых определений и флагов на поля не
 * появляется — спрашивается тот же {@code ACTION:USER:CHANGE_PASSWORD}, который уже решает
 * серверный путь, и только тогда, когда запись действительно несёт изменение хэша. Обновление,
 * которое хэш не трогает (например, выключение учётной записи), этим правилом не затрагивается.</p>
 *
 * <p>Сравниваются предсостояние строки и цель записи: равные, в том числе оба отсутствующие, —
 * изменения нет. Запись, в которой у сохранённой строки хэш есть, а в цели его нет, считается
 * изменением (затирание пароля) и без права отклоняется — fail-closed, а не «пустое значит не
 * менять».</p>
 */
@Component
public class UserPasswordChangePolicy implements CanonicalWritePropertyPolicy {

    private final UserPasswordChangePermission permissions;
    private final RlsCurrentUser currentUser;

    public UserPasswordChangePolicy(UserPasswordChangePermission permissions,
                                    RlsCurrentUser currentUser) {
        this.permissions = Objects.requireNonNull(permissions, "permissions must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
    }

    @Override
    public void requireAuthorizedChange(Class<?> type, Object original, Object target) {
        if (!User.class.equals(type) || !(target instanceof User incoming)) {
            return;
        }
        String savedHash = original instanceof User saved ? saved.getPassword() : null;
        if (!Objects.equals(savedHash, incoming.getPassword())) {
            permissions.requireChangePassword(currentUser.username());
        }
    }
}
