package org.ip.service;

import org.ip.model.User;
import org.ipro.crud.ValidationException;
import org.ipro.data.CanonicalEntityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Нормализация пароля — единственное, что {@code UserService} добавляет к canonical handle,
 * поэтому тест проверяет ровно этот шов: что хэшируется, что пустое поле не перетирает
 * существующий хэш, что новый пользователь без пароля отклоняется, и что дальше вызов
 * уходит в границу без изменений.
 */
class UserServiceTest {

    private CanonicalEntityService<User> canonical;
    private PasswordEncoder encoder;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        canonical = mock(CanonicalEntityService.class);
        encoder = mock(PasswordEncoder.class);
        when(encoder.encode(any())).thenAnswer(invocation -> "hash:" + invocation.getArgument(0));
    }

    @Test
    void saveHashesRawPasswordAndClearsItBeforeDelegating() {
        UserService service = new UserService(canonical, encoder);
        User user = new User("ivan", null);
        user.setRawPassword("secret123");

        service.save(user);

        assertThat(user.getPassword()).isEqualTo("hash:secret123");
        assertThat(user.getRawPassword()).isNull();
        verify(canonical).save(user);
    }

    @Test
    void updateWithoutRawPasswordKeepsExistingHash() {
        UserService service = new UserService(canonical, encoder);
        User existing = new User("ivan", "old-hash");
        existing.setId(7L);

        service.update(existing);

        assertThat(existing.getPassword()).isEqualTo("old-hash");
        verify(encoder, never()).encode(any());
        verify(canonical).update(existing);
    }

    @Test
    void newUserWithoutPasswordIsRejectedBeforeTheBoundary() {
        UserService service = new UserService(canonical, encoder);
        User user = new User("ivan", null);

        assertThatThrownBy(() -> service.save(user))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("Укажите пароль");
        verify(canonical, never()).save(any());
    }

    @Test
    void createHashesRawPassword() {
        UserService service = new UserService(canonical, encoder);
        User user = new User("petr", null);
        user.setRawPassword("  newpass  ");

        service.create(user);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(canonical).create(captor.capture());
        assertThat(captor.getValue().getRawPassword()).isNull();
        assertThat(captor.getValue().getPassword()).isEqualTo("hash:  newpass  ");
    }

    @Test
    void readsGoToTheSameBoundaryUntouched() {
        UserService service = new UserService(canonical, encoder);

        service.findById(1L);
        service.findAll();
        service.search("iva");
        service.delete(2L);

        verify(canonical).findById(1L);
        verify(canonical).findAll();
        verify(canonical).search("iva");
        verify(canonical).delete(2L);
    }
}
