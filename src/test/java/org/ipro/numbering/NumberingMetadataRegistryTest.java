package org.ipro.numbering;

import org.ip.model.Nomenclature;
import org.ip.model.Oper;
import org.ip.model.ReceivingDocument;
import org.ipro.crud.StandardCatalogEntity;
import org.ipro.crud.StandardDocumentEntity;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.numbering.annotation.NumberingPolicy;
import org.ipro.numbering.annotation.NumberingRole;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Юнит-тест каталога {@code @Numbered}-полей: скан {@code @EntityMetadata}-классов приложения
 * (basePackage {@code org.ip}), сортировка по ключу, отличие GLOBAL-серий от scoped.
 */
class NumberingMetadataRegistryTest {

    @Test
    void catalogsNumberedFieldsOfEntityMetadataClassesSortedByKey() {
        NumberingMetadataRegistry registry = new NumberingMetadataRegistry("org.ip");
        registry.afterPropertiesSet();

        List<NumberingMetadataRegistry.NumberedFieldInfo> all = registry.all();

        assertThat(all).extracting(NumberingMetadataRegistry.NumberedFieldInfo::key)
            .containsExactly(
                "AttributeType.code",
                "Nomenclature.code",
                "Oper.code",
                "ReceivingDocument.number");
    }

    @Test
    void exposesScopeForScopedSeriesAndEmptyForGlobal() {
        NumberingMetadataRegistry registry = new NumberingMetadataRegistry("org.ip");
        registry.afterPropertiesSet();

        NumberingMetadataRegistry.NumberedFieldInfo document =
            registry.all().stream()
                .filter(f -> f.entityClass() == ReceivingDocument.class)
                .findFirst().orElseThrow();
        assertThat(document.annotation().scope()).containsExactly("JOURNAL");
        assertThat(document.annotation().period()).isEqualTo(NumberingPeriod.YEAR);

        NumberingMetadataRegistry.NumberedFieldInfo global =
            registry.all().stream()
                .filter(f -> f.entityClass() == Oper.class)
                .findFirst().orElseThrow();
        assertThat(global.annotation().scope()).isEmpty();
        assertThat(global.annotation().period()).isEqualTo(NumberingPeriod.NEVER);

        assertThat(registry.all().stream()
            .filter(f -> f.entityClass() == Nomenclature.class)
            .findFirst().orElseThrow().annotation().scope()).isEmpty();
    }

    @Test
    void scanWithoutEntityMetadataClassesIsEmpty() {
        NumberingMetadataRegistry registry = new NumberingMetadataRegistry("org.ipro.numbering.none");
        registry.afterPropertiesSet();

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void discoversStandardNumberedFieldsInMappedSuperclass() {
        NumberingMetadataRegistry registry = new NumberingMetadataRegistry("org.ipro.numbering");
        registry.afterPropertiesSet();

        NumberingMetadataRegistry.NumberedFieldInfo catalog = registry.all().stream()
            .filter(info -> info.entityClass() == DefaultCatalog.class)
            .findFirst().orElseThrow();
        assertThat(catalog.fieldName()).isEqualTo("code");
        assertThat(catalog.field().getDeclaringClass()).isEqualTo(StandardCatalogEntity.class);
        assertThat(catalog.definition().role()).isEqualTo(NumberingRole.CATALOG_CODE);

        NumberingMetadataRegistry.NumberedFieldInfo document = registry.all().stream()
            .filter(info -> info.entityClass() == PolicyDocument.class)
            .findFirst().orElseThrow();
        assertThat(document.fieldName()).isEqualTo("number");
        assertThat(document.field().getDeclaringClass()).isEqualTo(StandardDocumentEntity.class);
        assertThat(document.definition().role()).isEqualTo(NumberingRole.DOCUMENT_NUMBER);
    }

    @Test
    void classPolicyOverridesInheritedDocumentNumberWithoutFieldRedeclaration() {
        NumberingMetadataRegistry registry = new NumberingMetadataRegistry("org.ipro.numbering");
        registry.afterPropertiesSet();

        NumberingDefinition definition = registry.all().stream()
            .filter(info -> info.entityClass() == PolicyDocument.class)
            .findFirst().orElseThrow().definition();

        assertThat(definition.scope()).containsExactly("JOURNAL");
        assertThat(definition.period()).isEqualTo(NumberingPeriod.YEAR);
        assertThat(definition.prefix()).isEqualTo("ДОК-");
        assertThat(definition.pattern()).isEqualTo("{prefix}{yyyy}-{seq:0000}");
        assertThat(definition.dateField()).isEqualTo("date");
    }

    @Test
    void saveHookCanAssignInheritedStandardCatalogCode() {
        NumberingMetadataRegistry registry = new NumberingMetadataRegistry("org.ipro.numbering");
        registry.afterPropertiesSet();
        NumberingRuleRepository rules = mock(NumberingRuleRepository.class);
        when(rules.findByEntityClassAndFieldName(anyString(), anyString()))
            .thenReturn(Optional.empty());
        NumberingCounterService counters = mock(NumberingCounterService.class);
        when(counters.allocate(anyString(), anyLong())).thenReturn(1L);
        NumberingScopeResolver scopes = new NumberingScopeResolver() {
            @Override
            public Long scopeValue(String dimension, Object entity) {
                return 1L;
            }

            @Override
            public boolean canResolve(String dimension) {
                return true;
            }
        };
        NumberingService service = new NumberingService(
            new NumberingRuleService(rules), counters, scopes, registry);

        DefaultCatalog catalog = new DefaultCatalog();
        service.assignAutoValues(catalog);

        assertThat(catalog.getCode()).isEqualTo("000001");
    }

    @EntityMetadata(listFormTitle = "Тестовый справочник")
    static class DefaultCatalog extends StandardCatalogEntity {
    }

    @EntityMetadata(listFormTitle = "Тестовый документ")
    @NumberingPolicy(
        role = NumberingRole.DOCUMENT_NUMBER,
        scope = "JOURNAL",
        period = NumberingPeriod.YEAR,
        prefix = "ДОК-",
        pattern = "{prefix}{yyyy}-{seq:0000}"
    )
    static class PolicyDocument extends StandardDocumentEntity {
    }
}
