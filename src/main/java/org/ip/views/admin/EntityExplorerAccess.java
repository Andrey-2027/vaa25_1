package org.ip.views.admin;

import org.ipro.form.link.OpenResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Единственное место правила доступа к диагностической структуре (E3.0): её видит только
 * {@code ROLE_ADMIN}.
 *
 * <p><b>Почему отдельный тип, а не {@code isAdmin()} в каждом входе.</b> У диагностической
 * структуры несколько входов — пункт меню, адрес Explorer и сам вид (вместе с диалогом
 * «Структура сущности»), — и до этого среза каждый спрашивал роль сам. Копии правила расходятся
 * молча: новый вход забывает проверку, а старый меняет её в одиночку. Здесь правило одно, и
 * route-ветка спрашивает его же, поэтому ответ на адрес не зависит от того, каким входом пришли.</p>
 *
 * <p><b>Отказ не различает роль и ключ.</b> {@link #REFUSAL_TEXT} — одна константа для случая
 * «не ADMIN» и «неизвестный ключ»: иначе адрес Explorer стал бы инструментом проверки
 * существования типов. Ключ не попадает ни в текст, ни в журнал.</p>
 */
@Component
public class EntityExplorerAccess {

    /** Текст отказа — один и тот же для роли и для неизвестного ключа. */
    public static final String REFUSAL_TEXT = "Адрес не найден";

    private static final String ADMIN_ROLE = "ROLE_ADMIN";

    /** Доступ текущего пользователя: спрашивается пунктом меню, route-веткой и видом. */
    public boolean allows() {
        return allows(SecurityContextHolder.getContext().getAuthentication());
    }

    /**
     * Проверка без обращения к {@code SecurityContext} — для тестов и мест, где аутентификация
     * уже получена. {@code null} означает «не аутентифицирован»: доступа нет.
     */
    public static boolean allows(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
            .anyMatch(authority -> ADMIN_ROLE.equals(authority.getAuthority()));
    }

    /**
     * Отказ: одно значение для не-ADMIN и для неизвестного ключа. Отдаётся как {@link OpenResult},
     * потому что показывается страницей состояния — той же, что и отказ формы.
     */
    public static OpenResult refusal() {
        return OpenResult.invalidRoute(REFUSAL_TEXT);
    }
}
