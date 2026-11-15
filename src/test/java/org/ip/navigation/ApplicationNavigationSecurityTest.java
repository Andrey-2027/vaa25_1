package org.ip.navigation;

import org.ip.model.User;
import org.ip.repository.UserRepository;
import org.ipro.data.EntityDataAccess;
import org.ipro.navigation.NavigationAdministrationAccess;
import org.ipro.navigation.NavigationUserDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplicationNavigationSecurityTest {
    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    private static void authenticate(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            username, "", List.of(new SimpleGrantedAuthority(role))));
    }

    @Test
    void onlyAuthenticatedAdminGetsAdministrationAccessAndIdentityUsesImmutableUserId() {
        var user = new User("old-login", "unused");
        user.setId(7L);
        var data = proxy(EntityDataAccess.class, (p, m, a) -> Optional.of(user));
        var repository = proxy(UserRepository.class, (p, m, a) ->
            user.getUsername().equals(a[0]) ? Optional.of(user) : Optional.empty());
        new ApplicationContextRunner().withUserConfiguration(ApplicationNavigationSecurityConfiguration.class)
            .withBean(EntityDataAccess.class, () -> data).withBean(UserRepository.class, () -> repository)
            .run(context -> {
                assertThat(context).hasNotFailed();
                var access = context.getBean(NavigationAdministrationAccess.class);
                var users = context.getBean(NavigationUserDirectory.class);
                assertThat(access.isAdministrator()).isFalse();
                assertThatThrownBy(users::currentUserKey).isInstanceOf(AccessDeniedException.class);
                authenticate("old-login", "ROLE_USER");
                assertThat(access.isAdministrator()).isFalse();
                assertThat(users.currentUserKey()).isEqualTo("7");
                authenticate("old-login", "ROLE_ADMIN");
                assertThat(access.isAdministrator()).isTrue();
                user.setUsername("new-login");
                authenticate("new-login", "ROLE_ADMIN");
                assertThat(users.currentUserKey()).isEqualTo("7");
                authenticate("deleted-user", "ROLE_ADMIN");
                assertThatThrownBy(users::currentUserKey).isInstanceOf(AccessDeniedException.class);
                SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("anonymous",
                    "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
                assertThat(access.isAdministrator()).isFalse();
                assertThatThrownBy(users::currentUserKey).isInstanceOf(AccessDeniedException.class);
            });
    }

    @Test
    void userLookupAndExistenceUseCanonicalAccessWithABoundAndNeverAcceptLoginAsAKey() {
        var user = new User("visible-user", "unused");
        user.setId(7L);
        var requestedLimit = new AtomicInteger();
        var requestedId = new AtomicReference<>();
        var data = proxy(EntityDataAccess.class, (p, m, a) -> {
            assertThat(a[0]).isEqualTo(User.class);
            if (m.getName().equals("lookup")) {
                requestedLimit.set((int) a[2]);
                return List.of(user);
            }
            if (m.getName().equals("detail")) {
                requestedId.set(a[1]);
                return a[1].equals(7L) ? Optional.of(user) : Optional.empty();
            }
            throw new AssertionError("Unexpected data operation: " + m.getName());
        });
        var users = new ApplicationNavigationUserDirectory(data, proxy(UserRepository.class,
            (p, m, a) -> { throw new AssertionError("Lookup must not use raw repositories"); }));
        assertThat(users.lookup("visible", 10)).containsExactly(new NavigationUserDirectory.UserOption("7", "visible-user"));
        assertThat(requestedLimit.get()).isEqualTo(10);
        assertThat(users.exists("7")).isTrue();
        assertThat(requestedId.get()).isEqualTo(7L);
        assertThat(users.exists("8")).isFalse();
        for (String invalid : List.of("visible-user", "-1", "007", "999999999999999999999")) {
            assertThat(users.exists(invalid)).isFalse();
        }
        assertThatThrownBy(() -> users.lookup("", 51)).isInstanceOf(IllegalArgumentException.class);
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> {
            if (m.getDeclaringClass() == Object.class) {
                return switch (m.getName()) {
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == a[0];
                    default -> type.getSimpleName() + " fixture";
                };
            }
            return handler.invoke(p, m, a);
        }));
    }
}
