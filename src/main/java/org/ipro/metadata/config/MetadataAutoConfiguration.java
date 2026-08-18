package org.ipro.metadata.config;

import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.ReferenceIndex;
import org.ipro.metadata.SubsystemRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-Configuration слоя метаданных ({@code org.ipro.metadata}). Пакет вынесен из
 * {@code org.ip.metadata} в платформу (правило направления зависимостей: ip → ipro,
 * платформа не зависит от приложения — прецедентом этому послужили зависимости
 * reportstudio/settings на org.ip.metadata, что само было нарушением).
 *
 * Классы аннотированы {@code @Component}, но под базовым пакетом scan приложения
 * ({@code org.ip}) они не находятся — бины регистрируются здесь, как в
 * RlsAutoConfiguration. Параметры сканирования те же: маркеры подсистем и
 * @EntityMetadata-сущности сканируются в пакете приложения ({@code org.ip}),
 * т.к. сами аннотации стоят на классах приложения (org.ip.model, org.ip.subsystem).
 */
@AutoConfiguration
public class MetadataAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MetadataResolver metadataResolver() {
        return new MetadataResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    public ReferenceIndex referenceIndex(
            @Value("${platform.subsystem-scan-package:org.ip}") String basePackage) {
        return new ReferenceIndex(basePackage);
    }

    @Bean
    @ConditionalOnMissingBean
    public SubsystemRegistry subsystemRegistry(
            @Value("${platform.subsystem-scan-package:org.ip}") String basePackage) {
        return new SubsystemRegistry(basePackage);
    }
}