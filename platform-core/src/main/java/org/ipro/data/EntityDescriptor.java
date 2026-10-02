package org.ipro.data;

import org.ipro.metadata.FactOrigin;

import java.util.Objects;

/**
 * Классифицированный descriptor persistence type (C4, ADR-0007 §2).
 *
 * <p>Строится поверх существующих {@code ManagedEntityCatalog} и
 * {@code SectionMetadataRegistry} без нового classpath scan. Хранит JPA/metadata-признаки,
 * экспозицию, capabilities и причину решения.</p>
 *
 * <p><b>Две оси происхождения, а не одна.</b> Экспозиция и набор сценариев — два разных
 * решения, и каждое несёт своё происхождение: экспозиция — {@code exposureOrigin}/
 * {@code exposureSymbol} (E3.1), набор read-сценариев и write-намерений —
 * {@code capabilitiesOrigin}/{@code capabilitiesSymbol} (E3.2.0). Так «тип стандартный,
 * потому что metadata» и «этому типу разрешён только {@code CREATE}, потому что так решило
 * приложение» читаются раздельно, а не сливаются в одну причину.</p>
 *
 * <p><b>Набор, равный правилу, — всё равно объявление.</b> {@link EntityCapabilityOverride}
 * заменяет выведенные capabilities целиком, поэтому типы с прикладным объявлением получают
 * {@link FactOrigin#REGISTRATION}, даже если набор совпадает с платформенным. Сравнение
 * значений здесь не измерение: карточка обязана показать решение, а не его совпадение.</p>
 *
 * <p><b>Символ платформенного правила пуст по решению.</b> Символ — это место объявления в
 * коде приложения; у правила такого места нет, а производящее правило названо причиной
 * ({@code reason}). Прикладное объявление сценариев сегодня тоже даёт пустой символ: каталог
 * ядра строится без Spring и класса-объявления бина не знает — та же названная граница, что у
 * {@code exposureSymbol} у {@link EntityExposureOverride}.</p>
 *
 * @param type               классифицируемый persistence type
 * @param exposure           экспозиция типа
 * @param jpaManaged         управляется ли тип JPA
 * @param metadataDriven     есть ли у типа эффективная metadata
 * @param capabilities       разрешённые read-сценарии и write-намерения canonical path
 * @param reason             причина решения об экспозиции
 * @param exposureOrigin     происхождение экспозиции
 * @param exposureSymbol     место объявления экспозиции (пусто — объявления нет)
 * @param capabilitiesOrigin происхождение набора сценариев и намерений
 * @param capabilitiesSymbol место объявления набора (пусто — объявления нет)
 */
public record EntityDescriptor(Class<?> type,
                               EntityExposure exposure,
                               boolean jpaManaged,
                               boolean metadataDriven,
                               EntityCapabilities capabilities,
                               String reason,
                               FactOrigin exposureOrigin,
                               String exposureSymbol,
                               FactOrigin capabilitiesOrigin,
                               String capabilitiesSymbol) {

    /** Совместимый конструктор для callers, которым происхождение ещё неизвестно. */
    public EntityDescriptor(Class<?> type, EntityExposure exposure, boolean jpaManaged,
                            boolean metadataDriven, EntityCapabilities capabilities, String reason) {
        this(type, exposure, jpaManaged, metadataDriven, capabilities, reason,
            FactOrigin.UNKNOWN, "", FactOrigin.UNKNOWN, "");
    }

    /**
     * Совместимый конструктор для callers, знающих происхождение экспозиции, но не набора
     * сценариев (E3.1 → E3.2.0): набор получает {@link FactOrigin#UNKNOWN} без символа, а не
     * выдуманное происхождение.
     */
    public EntityDescriptor(Class<?> type, EntityExposure exposure, boolean jpaManaged,
                            boolean metadataDriven, EntityCapabilities capabilities, String reason,
                            FactOrigin exposureOrigin, String exposureSymbol) {
        this(type, exposure, jpaManaged, metadataDriven, capabilities, reason,
            exposureOrigin, exposureSymbol, FactOrigin.UNKNOWN, "");
    }

    public EntityDescriptor {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(exposure, "exposure must not be null");
        Objects.requireNonNull(capabilities, "capabilities must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(exposureOrigin, "exposureOrigin must not be null");
        Objects.requireNonNull(capabilitiesOrigin, "capabilitiesOrigin must not be null");
        exposureSymbol = exposureSymbol == null ? "" : exposureSymbol;
        capabilitiesSymbol = capabilitiesSymbol == null ? "" : capabilitiesSymbol;
        if (exposureOrigin == FactOrigin.UNKNOWN && !exposureSymbol.isEmpty()) {
            throw new IllegalArgumentException("UNKNOWN exposure origin must not have a symbol");
        }
        if (capabilitiesOrigin == FactOrigin.UNKNOWN && !capabilitiesSymbol.isEmpty()) {
            throw new IllegalArgumentException("UNKNOWN capability origin must not have a symbol");
        }
    }
}
