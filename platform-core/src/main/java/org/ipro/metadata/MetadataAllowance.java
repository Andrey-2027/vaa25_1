package org.ipro.metadata;

import java.util.Objects;
import java.util.Set;

/**
 * Объявленное исключение для известной диагностики метаданных (C4.2, ADR-0007 §6).
 *
 * <p>Проверка метаданных запускается на старте и по умолчанию останавливает приложение на
 * {@code ERROR}. Иногда расхождение осознанное (например, UI требует поле, которое сервер
 * допускает пустым). Тогда исключение объявляет прикладной слой: он называет причину, а
 * проверка понижает диагностику до {@code INFO}. Если условия больше нет, исключение
 * становится {@code STALE_ALLOWANCE} — «разрешено» не превращается в вечное молчание,
 * снятый конфликт обязан быть снят и в списке.</p>
 *
 * <p><b>Граница исключения.</b> Исключением гасится только согласованный перечень
 * warning-кодов ({@link #ALLOWABLE_CODES}), то есть осознанно принятые ограничения UI.
 * Настоящие конфликты контракта поля — {@code TYPE_CONFLICT}, {@code REFERENCE_CONFLICT},
 * {@code UI_OPTIONAL_SERVER_REQUIRED} — остаются ошибками старта: иначе «разрешено»
 * превратило бы контракт платформы в договорённость. Попытка погасить такой код не
 * замолкает, а сообщает {@code DISALLOWED_ALLOWANCE}.</p>
 *
 * @param entity  полное имя сущности/строки
 * @param field   имя поля
 * @param code    код диагностики, которую разрешает исключение
 * @param reason  почему расхождение принято
 */
public record MetadataAllowance(String entity,
                                String field,
                                String code,
                                String reason) {

    /**
     * Коды, которые исключение вправе понизить до {@code INFO}. Пока это единственный
     * осознанный класс расхождений — UI строже серверного контракта; он уже WARNING, поэтому
     * исключение не ослабляет enforcement, а фиксирует намерение и ловит устаревание
     * ({@code STALE_ALLOWANCE}).
     */
    public static final Set<String> ALLOWABLE_CODES =
        Set.of(MetadataDiagnosticCodes.UI_REQUIRED_SERVER_OPTIONAL);

    public MetadataAllowance {
        Objects.requireNonNull(entity, "entity must not be null");
        Objects.requireNonNull(field, "field must not be null");
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("allowance reason must not be blank");
        }
    }

    /** Входит ли разрешаемый код в согласованный перечень. */
    public boolean isAllowable() {
        return ALLOWABLE_CODES.contains(code);
    }
}
