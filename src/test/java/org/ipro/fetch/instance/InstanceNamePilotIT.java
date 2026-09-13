package org.ipro.fetch.instance;

import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
import org.ip.config.DataInitializer;
import org.ip.model.Journal;
import org.ip.model.Nomenclature;
import org.ip.model.ReceivingDocument;
import org.ipro.form.FieldRenderer;
import org.ipro.search.GlobalSearchSource;
import org.ipro.search.JpaGlobalSearchProvider;
import org.ipro.telemetry.core.EntitySnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Пилот InstanceName (C3.2): декларации валидируются при старте, lookup/search/audit
 * дают одно представление мигрированной сущности, а неинициализированная ссылка не
 * загружается.
 */
@SpringBootTest(classes = org.ip.Application.class)
class InstanceNamePilotIT {

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private InstanceNameResolver resolver;

    @Autowired
    private EntityManager entityManager;

    // Мост здесь намеренно НЕ устанавливается вручную: регистрацию делает
    // InstanceNameBridgeInstaller при старте контекста, и именно это проверяется
    // (раньше каждому тесту приходилось переустанавливать глобальное состояние самому).

    @Test
    void pilotsAreDeclaredAndValidatedAtStartup() {
        assertThat(resolver.hasDeclaration(ReceivingDocument.class)).isTrue();
        assertThat(resolver.hasDeclaration(Nomenclature.class)).isTrue();
    }

    @Test
    void lookupSearchAndAuditUseTheSamePilotName() {
        ReceivingDocument document =
            new ReceivingDocument("РН-ПИЛОТ", LocalDate.of(2026, 9, 13), null, null);
        String expected = "РН-ПИЛОТ от 2026-09-13";

        // lookup / grid render
        assertThat(FieldRenderer.entityReference().apply(document)).isEqualTo(expected);
        // audit snapshot
        assertThat(EntitySnapshot.displayNameOf(document)).isEqualTo(expected);
        // global search
        GlobalSearchSource source = new GlobalSearchSource(0, ReceivingDocument.class,
            List.of("number"), List.of("number", "date"), "id", "Накладные");
        assertThat(new JpaGlobalSearchProvider<>(ReceivingDocument.class, resolver)
            .displayValue(document, source)).isEqualTo(expected);
        // единый источник
        assertThat(resolver.resolve(document)).isEqualTo(expected);
    }

    @Test
    void nonPilotEntityKeepsItsLegacySource() {
        Journal journal = new Journal();
        journal.setCode("J-1");
        journal.setName("Журнал");

        assertThat(resolver.hasDeclaration(Journal.class)).isFalse();
        assertThat(resolver.declaredName(journal)).isNull();
        assertThat(resolver.resolve(journal)).isEqualTo(journal.getDisplayName());
    }

    @Test
    void metadataDerivedPilotNameMatchesCurrentDisplayName() {
        Nomenclature nomenclature = new Nomenclature("N-9", "Деталь", null);

        assertThat(Nomenclature.class.getAnnotation(InstanceName.class).value()).isEmpty();
        assertThat(resolver.resolve(nomenclature)).isEqualTo(nomenclature.getDisplayName());
    }

    @Test
    @Transactional
    void uninitializedReferenceIsNotLoaded() {
        Journal reference = entityManager.getReference(Journal.class, 1L);
        assertThat(Hibernate.isInitialized(reference)).isFalse();

        String name = resolver.resolve(reference);

        assertThat(name).isNotBlank();
        assertThat(Hibernate.isInitialized(reference))
            .as("резолвер не должен инициировать lazy load")
            .isFalse();
    }

    /**
     * Фактический UI-путь: именно его регрессия осталась незамеченной — тесты проверяли
     * {@code resolver.resolve(proxy)}, а UI и аудит шли через bridge, который после
     * declaredName() напрямую вызывал {@code HasDisplayName} и тем самым разворачивал
     * прокси (SQL внутри сессии, {@code LazyInitializationException} вне её).
     */
    @Test
    @Transactional
    void bridgeDoesNotBypassProxySafetyForNonMigratedEntity() {
        Journal reference = entityManager.getReference(Journal.class, 1L);
        assertThat(Hibernate.isInitialized(reference)).isFalse();

        assertThat(InstanceNameBridge.displayName(reference)).isEqualTo("Journal#1");
        assertThat(Hibernate.isInitialized(reference))
            .as("bridge не должен разворачивать прокси ради имени")
            .isFalse();
    }

    /** То же для мигрированной сущности: состав имени читал бы поля прокси. */
    @Test
    @Transactional
    void bridgeDoesNotBypassProxySafetyForMigratedEntity() {
        ReceivingDocument reference = entityManager.getReference(ReceivingDocument.class, 424242L);
        assertThat(Hibernate.isInitialized(reference)).isFalse();

        assertThat(InstanceNameBridge.displayName(reference))
            .isEqualTo("ReceivingDocument#424242");
        assertThat(Hibernate.isInitialized(reference)).isFalse();
    }

    /**
     * Отсоединённый прокси: инициализация уже невозможна, поэтому имени остаётся только
     * безопасная ссылка. Прежний bridge здесь падал на {@code getDisplayName()}, если
     * сущность реализует {@code HasDisplayName}.
     */
    @Test
    @Transactional
    void bridgeRendersDetachedProxyAsSafeReference() {
        Journal reference = entityManager.getReference(Journal.class, 1L);
        entityManager.clear();
        assertThat(Hibernate.isInitialized(reference)).isFalse();

        assertThat(InstanceNameBridge.displayName(reference)).isEqualTo("Journal#1");
        assertThat(Hibernate.isInitialized(reference)).isFalse();
    }
}
