package org.ipro.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Типизированное свойство сканирования прикладных подсистем. Единственный источник
 * {@code platform.subsystem-scan-package} для metadata-конфигурации и (в D3.5) Vaadin
 * explorer: раньше каждый bean-метод читал его собственным {@code @Value}, то есть знание
 * об имени свойства размножалось по местам использования.
 *
 * <p>Класс живёт в autoconfigure-модуле, а не в core: доменные типы не знают о конфигурации
 * контейнера, а это bean настроек wiring.</p>
 */
@ConfigurationProperties("platform")
public class PlatformProperties {

    /**
     * Корень сканирования {@code @Subsystem}/{@code @EntityMetadata}/{@code @TableSections}
     * классов приложения. Обязателен и не имеет значения по умолчанию: приложение само
     * называет свой корневой пакет, платформа его не угадывает. Отсутствие значения —
     * fail-fast на старте, как у прежнего {@code @Value} без default (см. уточнение 1.7
     * в {@code docs/architecture/d3-core-extraction.md}).
     */
    private String subsystemScanPackage;

    public String getSubsystemScanPackage() {
        return subsystemScanPackage;
    }

    public void setSubsystemScanPackage(String subsystemScanPackage) {
        this.subsystemScanPackage = subsystemScanPackage;
    }

    /**
     * Валидированное значение для bean-методов: пустое/отсутствующее значение останавливает
     * старт с названной причиной, а не уходит в сканирование по пустому пакету.
     */
    public String requiredSubsystemScanPackage() {
        if (subsystemScanPackage == null || subsystemScanPackage.isBlank()) {
            throw new IllegalStateException(
                "Property 'platform.subsystem-scan-package' is required: it defines the application base "
                    + "package scanned for @Subsystem/@EntityMetadata/@TableSections markers. "
                    + "Set it in application.properties — the application owns its package name.");
        }
        return subsystemScanPackage;
    }
}
