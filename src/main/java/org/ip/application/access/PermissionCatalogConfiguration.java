package org.ip.application.access;

import java.util.List;
import java.util.Set;
import org.ip.web.ReportJpqlPreviewController;
import org.ipro.rls.c5.C5Operation;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PermissionCatalogConfiguration {
    @Bean
    public LegacyPermissionDefinition jpqlPreviewPermissionMetadata() {
        String key = ReportJpqlPreviewController.JPQL_PREVIEW_DIMENSION;
        var binding = new PermissionBinding(key, PermissionBinding.Authority.RLS,
            PermissionBinding.Flag.READ, Set.of(C5Operation.READ));
        return new LegacyPermissionDefinition(key, "Предпросмотр JPQL", "aux:reports", "Отчёты",
            List.of(new PermissionObjectRow.Column("Доступ", binding)));
    }
}
