package org.ip.service;

import org.ip.model.GridFormView;
import org.ip.repository.GridFormViewRepository;
import org.ipro.data.CanonicalEntityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * C4.6 волна F: сервис видов — предметный доступ к видам реестра плюс делегирование
 * стандартной CRUD-поверхности canonical handle. Свой write/read путь (и, вместе с ним,
 * собственная копия проверок) из класса убран.
 */
class GridFormViewServiceTest {

    @SuppressWarnings("unchecked")
    private final CanonicalEntityService<GridFormView> canonical =
        mock(CanonicalEntityService.class);
    private final GridFormViewRepository repository = mock(GridFormViewRepository.class);
    private final GridFormViewService service = new GridFormViewService(repository, canonical,
        // Тот же SPI, что в проде: имя читается из Spring Security context, который
        // authenticateAs() выставляет перед каждым сценарием.
        () -> SecurityContextHolder.getContext().getAuthentication() == null
            ? "system"
            : SecurityContextHolder.getContext().getAuthentication().getName());

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void standardSurfaceDelegatesToCanonicalHandle() {
        GridFormView view = new GridFormView("ownIt.formKey", "Вид", "[]", false);
        view.setId(7L);
        when(canonical.update(view)).thenReturn(view);
        when(canonical.create(view)).thenReturn(view);

        assertThat(service.update(view)).isSameAs(view);
        assertThat(service.create(view)).isSameAs(view);
        service.delete(7L);
        service.findById(7L);
        service.search("needle");

        verify(canonical).update(view);
        verify(canonical).create(view);
        verify(canonical).delete(7L);
        verify(canonical).findById(7L);
        verify(canonical).search("needle");
        verifyNoInteractions(repository);
    }

    @Test
    void createViewGoesThroughTheCanonicalHandle() {
        authenticateAs("view-owner");
        GridFormView created = new GridFormView("ownIt.formKey", "Новый вид", "[]", true);
        created.setId(9L);
        when(canonical.create(org.mockito.ArgumentMatchers.any(GridFormView.class)))
            .thenReturn(created);

        assertThat(service.createView("ownIt.formKey", "Новый вид", "[]", true))
            .isSameAs(created);
        verify(canonical).create(org.mockito.ArgumentMatchers.any(GridFormView.class));
    }

    @Test
    void visibleViewsComeFromTheDedicatedQueryForTheCurrentUser() {
        authenticateAs("view-owner");
        when(repository.findVisibleViews("ownIt.formKey", "view-owner")).thenReturn(List.of());

        assertThat(service.findVisibleViews("ownIt.formKey")).isEmpty();

        verify(repository).findVisibleViews("ownIt.formKey", "view-owner");
        verifyNoInteractions(canonical);
    }

    private static void authenticateAs(String username) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
        SecurityContextHolder.setContext(context);
    }
}
