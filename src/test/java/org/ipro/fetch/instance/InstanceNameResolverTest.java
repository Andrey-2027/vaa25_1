package org.ipro.fetch.instance;

import org.ip.model.Journal;
import org.ip.model.Nomenclature;
import org.ip.model.ReceivingDocument;
import org.ipro.metadata.HasDisplayName;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.EntityMetadata;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Приоритет и startup-валидация {@link InstanceNameResolver}:
 * объявленные paths → metadata-derived default → HasDisplayName compatibility,
 * а ошибки конфигурации падают при построении резолвера (то есть при старте).
 */
class InstanceNameResolverTest {

    private final MetadataResolver metadataResolver = new MetadataResolver();

    @Test
    void declaredPathsOverrideMetadataAndToString() {
        InstanceNameResolver resolver = resolver(ReceivingDocument.class);

        ReceivingDocument document =
            new ReceivingDocument("РН-7", LocalDate.of(2026, 9, 13), null, null);

        assertThat(resolver.declaredName(document)).isEqualTo("РН-7 от 2026-09-13");
        assertThat(resolver.resolve(document)).isEqualTo("РН-7 от 2026-09-13");
        // Формат не зависит от toString().
        assertThat(resolver.resolve(document)).isNotEqualTo(document.toString());
    }

    @Test
    void emptyDeclarationDerivesNameFromDisplaySortFields() {
        InstanceNameResolver resolver = resolver(Nomenclature.class);

        Nomenclature nomenclature = new Nomenclature("N-1", "Деталь", null);

        assertThat(resolver.hasDeclaration(Nomenclature.class)).isTrue();
        assertThat(resolver.declaredName(nomenclature)).isEqualTo("N-1 Деталь");
        assertThat(resolver.declaredName(nomenclature))
            .isEqualTo(nomenclature.getDisplayName());
    }

    @Test
    void entityWithoutDeclarationKeepsLegacyResolution() {
        InstanceNameResolver resolver = resolver(LegacyNamed.class);

        LegacyNamed value = new LegacyNamed();

        assertThat(resolver.hasDeclaration(LegacyNamed.class)).isFalse();
        assertThat(resolver.declaredName(value)).isNull();
        assertThat(resolver.resolve(value)).isEqualTo("Legacy name");
    }

    /**
     * Пустое ОБЪЯВЛЕННОЕ имя не откатывает сущность на legacy-ветку. Раньше оно
     * возвращало {@code null} из declaredName, и потребители молча уходили на второй
     * алгоритм (HasDisplayName): один объект показывался по-разному в UI и в аудите.
     */
    @Test
    void emptyCompositionYieldsSafeReferenceNotLegacyName() {
        InstanceNameResolver resolver = resolver(BlankNamed.class);

        BlankNamed blank = new BlankNamed();

        assertThat(resolver.hasDeclaration(BlankNamed.class)).isTrue();
        assertThat(resolver.declaredName(blank)).isEqualTo("BlankNamed");
        assertThat(resolver.resolve(blank)).isEqualTo("BlankNamed");
        assertThat(resolver.resolve(blank))
            .as("декларация есть — legacy getDisplayName не применяется")
            .isNotEqualTo(blank.getDisplayName());
    }

    @Test
    void nullResolvesToEmptyRepresentation() {
        assertThat(resolver(Journal.class).resolve(null)).isEmpty();
        assertThat(InstanceNameResolver.compatibleDisplayName(null)).isEmpty();
    }

    /**
     * Скаляр не является сущностью: его нельзя превращать в ссылку {@code Type#id}.
     */
    @Test
    void scalarKeepsItsOwnText() {
        assertThat(InstanceNameResolver.compatibleDisplayName("РН-1")).isEqualTo("РН-1");
        assertThat(InstanceNameResolver.compatibleDisplayName(42L)).isEqualTo("42");
    }

    /**
     * Bridge не содержит своей лестницы: без установленного провайдера он использует ту же
     * статическую форму резолвера, с провайдером — делегирует ему.
     */
    @Test
    void bridgeDelegatesToProviderAndKeepsPlatformAnalysis() {
        InstanceNameResolver analysis = resolver(Journal.class);
        InstanceNameProvider custom = entity -> "CUSTOM";

        inOwnRegistration(() -> {
            InstanceNameBridge.install(custom, analysis);
            assertThat(InstanceNameBridge.displayName(new Journal())).isEqualTo("CUSTOM");
            assertThat(InstanceNameBridge.declaredName(new Journal()))
                .as("анализ состава имени остаётся платформенным")
                .isNull();
        }, custom, analysis);

        assertThat(InstanceNameBridge.displayName(journal())).isEqualTo("J-1 Журнал");
    }

    /**
     * Регистрация контекста, который НЕ знает тип, не должна перехватывать его отображение.
     * Мост выбирал активную регистрацию по одной очереди установки, поэтому контекст с
     * частичным представлением metadata (поднявшийся позже) становился авторитетным для
     * всей JVM: UI и аудит получали fallback-ссылку, хотя живой контекст объявление знал
     * (наблюдалось как {@code ReceivingDocument} вместо имени накладной в полном прогоне).
     * Прокси здесь не разворачивается: тип берётся из lazy initializer — это проверяет
     * {@code InstanceNamePilotIT} на настоящих Hibernate-прокси.
     */
    @Test
    void registrationThatDoesNotKnowTypeCannotShadowOneThatDoes() {
        InstanceNameResolver knowsDocuments = resolver(ReceivingDocument.class, NamedReference.class);
        InstanceNameProvider custom = entity -> "CUSTOM";
        InstanceNameResolver knowsNomenclatureOnly = resolver(Nomenclature.class);
        InstanceNameProvider blind = entity -> "BLIND";

        InstanceNameProvider before = InstanceNameBridge.installedProvider();
        InstanceNameResolver beforeAnalysis = InstanceNameBridge.installedAnalysis();
        try {
            InstanceNameBridge.install(custom, knowsDocuments);
            InstanceNameBridge.install(blind, knowsNomenclatureOnly);

            assertThat(InstanceNameBridge.displayName(
                new ReceivingDocument("РН-7", LocalDate.of(2026, 9, 13), null, null)))
                .as("тип знает первая регистрация — её провайдер и отвечает")
                .isEqualTo("CUSTOM");
            assertThat(InstanceNameBridge.displayName(new Nomenclature("N-1", "Деталь", null)))
                .as("для типа, который знает только новейшая регистрация, активна она")
                .isEqualTo("BLIND");
            assertThat(InstanceNameBridge.declaredName(
                new ReceivingDocument("РН-7", LocalDate.of(2026, 9, 13), null, null)))
                .as("анализ имени использует регистрацию, которая знает тип")
                .isEqualTo("РН-7 от 2026-09-13");
            assertThat(InstanceNameBridge.instanceNamePaths(ReceivingDocument.class))
                .containsExactly("number", "date");
            assertThat(InstanceNameBridge.instanceNameFetchPaths(NamedReference.class))
                .containsExactly("target");
        } finally {
            InstanceNameBridge.uninstall(custom, knowsDocuments);
            InstanceNameBridge.uninstall(blind, knowsNomenclatureOnly);
            if (before != null) {
                InstanceNameBridge.install(before, beforeAnalysis);
            }
        }
    }

    /**
     * Закрывающийся старый контекст не должен снимать регистрацию нового: иначе UI после
     * перезапуска контекста остался бы без единого источника имени (и без явных ошибок).
     */
    @Test
    void staleRegistrationCannotUninstallANewerOne() {
        InstanceNameResolver first = resolver(Journal.class);
        InstanceNameResolver second = resolver(Journal.class);

        inOwnRegistration(() -> {
            InstanceNameBridge.install(first);
            InstanceNameBridge.install(second);
            InstanceNameBridge.uninstall(first, first); // закрывается первый контекст
            assertThat(InstanceNameBridge.displayName(journal())).isEqualTo("J-1 Журнал");
            assertThat(InstanceNameBridge.installedProvider()).isSameAs(second);
        }, second, second);
    }

    /**
     * Мост глобальный, поэтому тест обязан вернуть состояние, которое было до него: иначе
     * уже созданный кэшированный контекст приложения остался бы без провайдера, и это
     * было бы не ошибкой кода, а порядко-зависимостью набора тестов.
     */
    private static void inOwnRegistration(Runnable scenario, InstanceNameProvider installed,
                                         InstanceNameResolver installedAnalysis) {
        InstanceNameProvider before = InstanceNameBridge.installedProvider();
        InstanceNameResolver beforeAnalysis = InstanceNameBridge.installedAnalysis();
        try {
            scenario.run();
        } finally {
            InstanceNameBridge.uninstall(installed, installedAnalysis);
            if (before != null) {
                InstanceNameBridge.install(before, beforeAnalysis);
            }
        }
    }

    private static Journal journal() {
        Journal journal = new Journal();
        journal.setCode("J-1");
        journal.setName("Журнал");
        return journal;
    }

    @Test
    void invalidDeclaredPathFailsAtConstruction() {
        assertThatThrownBy(() -> resolver(InvalidPathEntity.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("invalid attribute path 'doesNotExist'");
    }

    @Test
    void emptyDeclarationWithoutDisplaySortFieldsFailsAtConstruction() {
        assertThatThrownBy(() -> resolver(EmptyDerivationEntity.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("displaySortFields is empty");
    }

    @Test
    void declarationWithoutEntityMetadataFailsAtConstruction() {
        assertThatThrownBy(() -> resolver(NoMetadataEntity.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no @EntityMetadata");
    }

    private InstanceNameResolver resolver(Class<?>... entityTypes) {
        return new InstanceNameResolver(List.of(entityTypes), metadataResolver);
    }

    // ------------------------------------------------------------------ fixtures

    @InstanceName(value = "doesNotExist")
    static class InvalidPathEntity {
    }

    @InstanceName
    @EntityMetadata(listFormTitle = "Пустая деривация")
    static class EmptyDerivationEntity {
    }

    @InstanceName
    static class NoMetadataEntity {
    }

    @InstanceName("target.name")
    static class NamedReference {
        private final ReferenceTarget target = new ReferenceTarget("target name");
    }

    static class ReferenceTarget {
        private final String name;

        ReferenceTarget(String name) {
            this.name = name;
        }
    }

    /** Мигрированная сущность, у которой объявленное имя пусто: идёт ссылка, не legacy. */
    @InstanceName(value = "name")
    static class BlankNamed implements HasDisplayName {
        private Long id;
        private String name;

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        @Override
        public String getDisplayName() {
            return "LEGACY";
        }
    }

    static class LegacyNamed implements HasDisplayName {
        private Long id;

        public Long getId() {
            return id;
        }

        @Override
        public String getDisplayName() {
            return "Legacy name";
        }
    }
}
