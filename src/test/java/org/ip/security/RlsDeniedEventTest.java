package org.ip.security;

import org.ip.config.DataInitializer;
import org.ip.model.Branch;
import org.ip.model.Journal;
import org.ip.model.ReceivingDocument;
import org.ip.model.Workshop;
import org.ip.repository.BranchRepository;
import org.ip.repository.JournalRepository;
import org.ip.repository.WorkshopRepository;
import org.ipro.crud.ServiceLocator;
import org.ipro.data.CanonicalEntityService;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsAccessDeniedException;
import org.ipro.rls.RlsTestFixture;
import org.ipro.telemetry.core.SecurityEventLogger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Фаза 8 RLS-плана: отказ прав на общей RLS-границе (RlsPolicyEnforcer через
 * repository-aspect) фиксируется SECURITY-событием "rls:denied" через durable-путь
 * SecurityEventLogger — до броска RlsAccessDeniedException. Полезно для журнала
 * админки (SECURITY хранится 1 год):
 * видно, кто и по какому измерению пытался изменить запись.
 */
@SpringBootTest
class RlsDeniedEventTest {

    @MockitoBean
    private DataInitializer dataInitializer;

    @MockitoSpyBean
    private SecurityEventLogger securityEventLogger;

    @Autowired
    private ServiceLocator serviceLocator;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private WorkshopRepository workshopRepository;

    @Autowired
    private JournalRepository journalRepository;

    @Autowired
    private AccessGrantRepository accessGrantRepository;

    @org.junit.jupiter.api.AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    /** C4.6 волна B: у {@code ReceivingDocument} нет typed-сервиса — резолв идёт canonical handle. */
    private CanonicalEntityService<ReceivingDocument> documents() {
        return (CanonicalEntityService<ReceivingDocument>) serviceLocator
            .<ReceivingDocument, Long>findService(ReceivingDocument.class);
    }

    /**
     * Отказ построчного RLS-гейта фиксируется SECURITY-событием {@code rls:denied}.
     *
     * <p>C5.6: право на операцию — отдельная строка того же ключа {@code ENTITY:ReceivingDocument}
     * (ADR-0017), поэтому она выдана явно, а отказ приходит именно от RLS: у субъекта нет
     * права на журнал создаваемого документа. Без операционной строки отказ был бы C5 и до
     * RLS-пути — то есть этот тест перестал бы проверять событие RLS вовсе.</p>
     */
    @Test
    void writeGuardDeniedEmitsRlsSecurityEvent() {
        // Субъект уникален на прогон: база тестов разделяется между классами и запусками, а
        // глобальная строка гранта (dimensionValueId = null) пережила бы прогон и разрешила бы
        // то измерение, которое этот тест проверяет как запрет.
        String deniedUser = "rls-denied-event-" + java.util.UUID.randomUUID();
        String journalHolder = "rls-journal-holder-" + java.util.UUID.randomUUID();
        ReceivingDocument doc = RlsTestFixture.callAsSuperuser(accessGrantRepository, () -> {
            Branch branch = new Branch();
            branch.setCode("DE-1");
            branch.setName("Филиал");
            branchRepository.save(branch);

            Workshop receiver = new Workshop("DE-A", "Цех А");
            receiver.setBranch(branch);
            workshopRepository.save(receiver);

            Workshop deliverer = new Workshop("DE-B", "Цех Б");
            deliverer.setBranch(branch);
            workshopRepository.save(deliverer);

            Journal journal = new Journal();
            journal.setCode("DEJ");
            journal.setName("Журнал");
            journalRepository.save(journal);

            // Отказ проверяем по построчному измерению JOURNAL: BRANCH грант выдан, а журнал — нет.
            accessGrantRepository.save(grant(deniedUser, "BRANCH"));
            // Ключ ENTITY:ReceivingDocument владеет сразу двумя решениями — операцией C5 и
            // CHECK_ONLY-воротами RLS (ADR-0017 §3 сохранил прежний ключ), поэтому строка выдана:
            // без неё отказ был бы от C5 и до RLS-пути этот тест не доходил бы вовсе.
            accessGrantRepository.save(grant(deniedUser, "ENTITY:ReceivingDocument"));
            // Bootstrap-правило AccessService: пока по измерению нет НИ ОДНОГО гранта, создание
            // нового его значения разрешено. Закрываем его строкой другого субъекта, иначе на
            // пустой базе запрет по JOURNAL не сработал бы и тест зависел бы от соседей по прогону.
            accessGrantRepository.save(grant(journalHolder, "JOURNAL"));

            ReceivingDocument document = new ReceivingDocument(
                "DE-1", LocalDate.now(), receiver, deliverer);
            document.setJournal(journal);
            return document;
        });

        SecurityContext deniedContext = SecurityContextHolder.createEmptyContext();
        deniedContext.setAuthentication(
            new UsernamePasswordAuthenticationToken(deniedUser, "n/a", java.util.List.of()));
        SecurityContextHolder.setContext(deniedContext);

        assertThatThrownBy(() -> documents().save(doc))
            .isInstanceOf(RlsAccessDeniedException.class)
            .hasMessageContaining("Нет прав на изменение");

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(securityEventLogger)
            .emitSecurityEvent(eq("WARN"), eq("rls:denied"), anyString(),
                org.mockito.ArgumentMatchers.contains("Нет прав на изменение"), payload.capture());

        // Раз только журнал без права, измерение отказа определено однозначно — и оно
        // названо в событии: это и есть проверка, что событие описывает настоящую причину.
        assertThat((String) payload.getValue().get("dimension")).isEqualTo("JOURNAL");
        assertThat(payload.getValue().get("action")).isEqualTo("изменение");
        assertThat(payload.getValue().get("entity")).isEqualTo(ReceivingDocument.class.getName());

        // За собой убираем: разделяемая база — не место для состояния одного теста.
        deleteGrants(deniedUser, "BRANCH", "ENTITY:ReceivingDocument");
        deleteGrants(journalHolder, "JOURNAL");
    }

    private void deleteGrants(String subjectKey, String... dimensions) {
        for (String dimension : dimensions) {
            accessGrantRepository.deleteAll(accessGrantRepository
                .findBySubjectTypeAndSubjectKeyAndDimension(
                    AccessGrant.SubjectType.USER, subjectKey, dimension));
        }
    }

    private static AccessGrant grant(String subjectKey, String dimension) {
        AccessGrant grant = new AccessGrant();
        grant.setSubjectType(AccessGrant.SubjectType.USER);
        grant.setSubjectKey(subjectKey);
        grant.setDimension(dimension);
        grant.setCanRead(true);
        grant.setCanUpdate(true);
        return grant;
    }
}
