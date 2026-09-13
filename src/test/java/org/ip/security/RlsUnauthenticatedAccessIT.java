package org.ip.security;

import org.ip.config.DataInitializer;
import org.ip.model.ReceivingDocument;
import org.ip.repository.JournalRepository;
import org.ip.service.ReceivingDocumentService;
import org.ipro.crud.LookupService;
import org.ipro.rls.RlsAccessDeniedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fail-closed по субъекту: отсутствие authentication — это не «системный доступ», а отказ.
 *
 * <p>До C2 отсутствие аутентификации сводилось к имени {@code system}, и канал мог
 * вернуть полный набор строк. Сейчас защищённая операция без аутентифицированного субъекта
 * не выполняется вообще — и через сервис, и через lookup, и через прямую границу
 * repository. Legacy-fallback {@code CurrentUser -> "system"} остался ради обратной
 * совместимости, но authority больше не даёт, поэтому субъект с именем {@code system}
 * проверяется отдельно.</p>
 *
 * <p>Тесты намеренно утверждают отказ, а не пустой результат: пустой список был бы
 * неотличим от «данных нет», тогда как отказ — это явная граница ответственности.</p>
 */
@SpringBootTest
class RlsUnauthenticatedAccessIT {

    /** Сидирование стартовых данных имеет известный дефект на свежей БД — вне скоупа. */
    @MockitoBean DataInitializer dataInitializer;

    @Autowired ReceivingDocumentService documents;
    @Autowired LookupService lookups;
    @Autowired JournalRepository journals;

    /**
     * Читатели субъекта (CurrentUser) смотрят в статический SecurityContextHolder, поэтому
     * пустой контекст нужно гарантировать и до, и после теста: иначе унаследованная
     * аутентификация другого теста сделала бы проверку недостоверной.
     */
    @BeforeEach
    void clearSubjectBeforeTest() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearSubjectAfterTest() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void serviceReadWithoutAuthenticationIsDeniedInsteadOfReturningEverything() {
        assertThatThrownBy(() -> documents.findAll())
            .isInstanceOf(RlsAccessDeniedException.class)
            .hasMessageContaining("аутентифицированного пользователя");
    }

    @Test
    void legacySystemSubjectIsNotAuthority() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("system", "n/a", List.of()));

        assertThatThrownBy(() -> documents.findAll())
            .isInstanceOf(RlsAccessDeniedException.class)
            .hasMessageContaining("аутентифицированного пользователя");
    }

    @Test
    void lookupWithoutAuthenticationIsDenied() {
        assertThatThrownBy(() -> lookups.findAll(ReceivingDocument.class))
            .isInstanceOf(RlsAccessDeniedException.class)
            .hasMessageContaining("аутентифицированного пользователя");
    }

    @Test
    void directProtectedRepositoryReadWithoutAuthenticationIsDenied() {
        assertThatThrownBy(() -> journals.findAll())
            .isInstanceOf(RlsAccessDeniedException.class)
            .hasMessageContaining("аутентифицированного пользователя");
    }

    @Test
    void protectedRepositoryReadByIdWithoutAuthenticationIsDenied() {
        assertThatThrownBy(() -> journals.findById(1L))
            .isInstanceOf(RlsAccessDeniedException.class)
            .hasMessageContaining("аутентифицированного пользователя");
    }
}
