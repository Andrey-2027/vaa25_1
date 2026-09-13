package org.ip.config;

import org.ip.model.ReceivingDocument;
import org.ipro.metadata.MetadataAllowance;
import org.ipro.metadata.MetadataDiagnosticCodes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Прикладные исключения из сквозной проверки метаданных (C4.2, ADR-0007 §6).
 *
 * <p>Проверка останавливает старт на противоречиях контракта поля. Единственное известное
 * расхождение в дереве — {@code ReceivingDocument.journal}: форма требует журнал
 * ({@code order = 0}, он входит в уникальность {@code (journal_id, number)} и задаёт
 * нумерацию), а колонка объявлена {@code nullable = true} и {@code @NotNull} на поле
 * закомментирован. Это осознанное ограничение UI, а не рассинхрон: серверный контракт
 * остаётся прежним, и запись вне UI (импорт, фоновая задача) по-прежнему возможна.</p>
 *
 * <p>Исключение названо здесь явно, а не «прощено» проверкой: если условие исчезнет
 * (например, {@code @NotNull} вернут), проверка сообщит {@code STALE_ALLOWANCE} и потребует
 * убрать объявление.</p>
 */
@Configuration(proxyBeanMethods = false)
public class MetadataDiagnosticsConfig {

    @Bean
    public MetadataAllowance receivingDocumentJournalIsUiRequired() {
        return new MetadataAllowance(ReceivingDocument.class.getName(), "journal",
            MetadataDiagnosticCodes.UI_REQUIRED_SERVER_OPTIONAL,
            "журнал обязателен в форме накладной (order=0 и уникальность (journal_id, number)),"
                + " но сервер допускает NULL: осознанное ограничение UI до решения по"
                + " серверному контракту");
    }
}
