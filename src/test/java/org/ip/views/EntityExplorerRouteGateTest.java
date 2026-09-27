package org.ip.views;

import org.ip.model.Nomenclature;
import org.ip.views.admin.EntityExplorerAccess;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E3.0: гейт роли адреса Explorer. Тест фиксирует то, что иначе проверялось бы руками в браузере
 * под двумя учётными записями: отказ по роли и отказ по неизвестному ключу — одно и то же значение,
 * каталог без роли не спрашивают вовсе, а успех возвращает тип и запрошенный адрес.
 *
 * <p>Проверяется чистая функция решения: роль приходит булевым значением, поэтому тест не зависит
 * ни от {@code SecurityContext}, ни от Spring.</p>
 */
class EntityExplorerRouteGateTest {

    /** Каталог-заглушка: ключ есть только у одного типа, остальные — «неизвестный ключ». */
    private final Function<String, Optional<Class<?>>> catalog = key ->
        "nomenclature".equals(key) ? Optional.of(Nomenclature.class) : Optional.empty();

    @Test
    void theAnswerIsTheSameForAnExistingAndAnUnknownKeyWithoutTheRole() {
        MainLayout.ExplorerEntry existing =
            MainLayout.decideExplorerEntry(false, "/entity-explorer/nomenclature", catalog);
        MainLayout.ExplorerEntry unknown =
            MainLayout.decideExplorerEntry(false, "/entity-explorer/nope", catalog);

        assertThat(existing)
            .as("ответ не должен зависеть от того, есть ли ключ: иначе адрес становится проверкой"
                + " существования типов")
            .isEqualTo(unknown);
        assertThat(existing).isInstanceOfSatisfying(MainLayout.ExplorerEntry.Denied.class,
            denied -> assertThat(denied.refusal().message())
                .isEqualTo(EntityExplorerAccess.REFUSAL_TEXT));
    }

    @Test
    void theResolverIsNeverCalledWithoutTheRole() {
        AtomicInteger calls = new AtomicInteger();
        Function<String, Optional<Class<?>>> counting = key -> {
            calls.incrementAndGet();
            return Optional.of(Nomenclature.class);
        };

        MainLayout.decideExplorerEntry(false, "/entity-explorer/nomenclature", counting);
        MainLayout.decideExplorerEntry(false, "/entity-explorer/nope", counting);

        assertThat(calls)
            .as("каталог без роли не спрашивают вовсе: проверка идёт до разбора ключа")
            .hasValue(0);
    }

    @Test
    void anUnknownKeyIsDeniedWithoutEnumeratingKeys() {
        MainLayout.ExplorerEntry.Denied denied = (MainLayout.ExplorerEntry.Denied)
            MainLayout.decideExplorerEntry(true, "/entity-explorer/nope", catalog);

        assertThat(denied.refusal().message())
            .as("отказ не перечисляет похожие ключи и не содержит запрошенный")
            .isEqualTo(EntityExplorerAccess.REFUSAL_TEXT)
            .doesNotContain("nope");
        assertThat(denied.refusal().outcome()).isEqualTo("invalid-route");
    }

    @Test
    void aMalformedAddressIsDeniedTheSameWay() {
        MainLayout.ExplorerEntry withoutKey =
            MainLayout.decideExplorerEntry(true, "/entity-explorer", catalog);
        MainLayout.ExplorerEntry withExtraSegment =
            MainLayout.decideExplorerEntry(true, "/entity-explorer/nomenclature/extra", catalog);

        assertThat(withoutKey)
            .as("адрес, заявленный как Explorer, но без разбираемого ключа, получает тот же отказ")
            .isEqualTo(withExtraSegment);
        assertThat(withoutKey)
            .isEqualTo(MainLayout.decideExplorerEntry(true, "/entity-explorer/nope", catalog));
    }

    @Test
    void anExistingKeyResolvesToTheType() {
        MainLayout.ExplorerEntry entry =
            MainLayout.decideExplorerEntry(true, "/entity-explorer/nomenclature", catalog);

        assertThat(entry).isInstanceOfSatisfying(MainLayout.ExplorerEntry.Resolved.class, resolved -> {
            assertThat(resolved.type()).isEqualTo(Nomenclature.class);
            assertThat(resolved.key()).isEqualTo("nomenclature");
            assertThat(resolved.address())
                .as("адрес остаётся запрошенным: legacy-ключ не канонизируется на входе")
                .isEqualTo("/entity-explorer/nomenclature");
        });
    }

    @Test
    void onlyTheAdminRoleIsAllowed() {
        assertThat(EntityExplorerAccess.allows(authentication("ROLE_ADMIN"))).isTrue();
        assertThat(EntityExplorerAccess.allows(authentication("ROLE_USER"))).isFalse();
        assertThat(EntityExplorerAccess.allows(null))
            .as("не аутентифицирован — доступа нет")
            .isFalse();
    }

    private static Authentication authentication(String authority) {
        return new UsernamePasswordAuthenticationToken("user", "n/a",
            List.of(new SimpleGrantedAuthority(authority)));
    }
}
