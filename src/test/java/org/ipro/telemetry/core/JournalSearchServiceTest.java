package org.ipro.telemetry.core;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.ipro.telemetry.model.OperationLogEntity;
import org.ipro.telemetry.repository.OperationLogRepository;
import org.mockito.Mockito;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JournalSearchServiceTest {

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------- доступ

    @Test
    void searchWithoutAdminThrows() {
        // Гость: SecurityContext пуст -> AccessDeniedException до похода в репозиторий.
        SecurityContextHolder.clearContext();
        OperationLogRepository repository = mock(OperationLogRepository.class);
        JournalSearchService service = new JournalSearchService(repository, mock(JdbcTemplate.class));

        assertThrows(AccessDeniedException.class,
                () -> service.search(null, PageRequest.of(0, 20)));
        verifyNoInteractions(repository);
    }

    @Test
    void errorGroupsWithoutAdminThrows() {
        SecurityContextHolder.clearContext();
        JournalSearchService service = new JournalSearchService(
                mock(OperationLogRepository.class), mock(JdbcTemplate.class));

        assertThrows(AccessDeniedException.class,
                () -> service.errorGroups(new JournalSearchService.ErrorGroupFilter(null, null, null, null)));
    }

    @Test
    void searchWithAdminDelegatesToRepository() {
        authenticateAsAdmin();
        OperationLogEntity entity = new OperationLogEntity();
        OperationLogRepository repository = mock(OperationLogRepository.class);
        Pageable pageable = PageRequest.of(0, 50);
        when(repository.findAll(Mockito.<Specification<OperationLogEntity>>any(), Mockito.eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(entity)));

        JournalSearchService service = new JournalSearchService(repository, mock(JdbcTemplate.class));
        var page = service.search(JournalSearchService.levelEq("ERROR"), pageable);

        assertEquals(1, page.getContent().size());
    }

    // ------------------------------------------------- группировка ошибок

    @Test
    void errorGroupsMapsFingerprintAndCounts() {
        authenticateAsAdmin();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DataSource dataSource = mock(DataSource.class);
        // Приложение целится в PostgreSQL: getClass/toString содержат "postgres".
        when(dataSource.toString()).thenReturn("HikariDataSource (jdbc:postgresql://localhost/app)");
        when(jdbc.getDataSource()).thenReturn(dataSource);
        // Перехватываем RowMapper и подменяем результат маппинга тестовой строкой.
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getString("operation")).thenReturn("svc.ReceivingDocument.save");
                    when(rs.getString("fingerprint")).thenReturn("Constraint violation: uk_doc_number");
                    when(rs.getLong("cnt")).thenReturn(7L);
                    when(rs.getLong("users")).thenReturn(2L);
                    when(rs.getTimestamp(anyString())).thenReturn(null);
                    return List.of(mapper.mapRow(rs, 0));
                });

        JournalSearchService service = new JournalSearchService(
                mock(OperationLogRepository.class), jdbc);
        List<JournalSearchService.ErrorGroupRow> rows = service.errorGroups(
                new JournalSearchService.ErrorGroupFilter(null, null, null, null));

        assertEquals(1, rows.size());
        assertEquals(7L, rows.get(0).count());
        assertEquals(2L, rows.get(0).distinctUsers());
        assertEquals("svc.ReceivingDocument.save", rows.get(0).operation());
        assertEquals("Constraint violation: uk_doc_number", rows.get(0).errorFingerprint());
    }

    @Test
    void errorGroupsRejectsNonPostgresDialect() {
        authenticateAsAdmin();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.toString()).thenReturn("jdbc:h2:mem:test");
        when(jdbc.getDataSource()).thenReturn(dataSource);
        JournalSearchService service = new JournalSearchService(
                mock(OperationLogRepository.class), jdbc);

        assertThrows(IllegalStateException.class,
                () -> service.errorGroups(new JournalSearchService.ErrorGroupFilter(null, null, null, null)));
    }

    // ------------------------------------------------------------- утилиты

    private static void authenticateAsAdmin() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "admin", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
