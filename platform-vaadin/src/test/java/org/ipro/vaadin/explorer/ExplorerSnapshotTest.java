package org.ipro.vaadin.explorer;

import org.ipro.data.EntityCapabilities;
import org.ipro.data.EntityDescriptor;
import org.ipro.data.EntityExposure;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.metadata.FactOrigin;
import org.ipro.metadata.MetadataDiagnostic;
import org.ipro.metadata.annotation.EntityKind;
import org.ipro.metadata.facet.FacetKey;
import org.ipro.metadata.facet.FacetKind;
import org.ipro.metadata.facet.ResolvedValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E3.2.2 §9.1: снимок каталога Explorer на синтетических источниках. Здесь проверяются границы,
 * которых нет в инвентаре приложения: root без ключа, одинаковые simpleName строк, owned-строка
 * без подтверждённого владельца, недоступная диагностика, отказ сборки одного типа и отсутствие
 * descriptor'а. Источники подставляются функциями, поэтому фикстуры не зависят ни от реестров,
 * ни от UI.
 */
class ExplorerSnapshotTest {

    /** Два разных класса с одним simpleName {@code Row} — идентичность секции не может быть simpleName. */
    static class Alpha {
        static class Row {
        }
    }

    static class Beta {
        static class Row {
        }
    }

    /** Root без опубликованного ключа. */
    static class Gamma {
    }

    /** Владелец секции. */
    static class Owner {
    }

    /** Тип, сборка сводки которого отказывает. */
    static class Delta {
    }

    // ------------------------------------------------------------ одна попытка на тип

    @Test
    void oneSummarizeAttemptPerTypeAndAFailureStaysInItsEntry() {
        Fixture fixture = new Fixture();
        fixture.inventory = List.of(ref(Alpha.class, "Альфа"), ref(Delta.class, "Дельта"));
        List<Class<?>> attempts = new ArrayList<>();
        fixture.summary = type -> {
            attempts.add(type);
            if (type == Delta.class) {
                throw new IllegalStateException("нет источника фактов");
            }
            return summary(type, List.of(), List.of());
        };

        ExplorerSnapshot snapshot = fixture.build();

        assertThat(attempts)
            .as("сводка собирается ровно один раз на тип — повторной попытки нет")
            .containsExactly(Alpha.class, Delta.class);
        assertThat(snapshot.stats().typeCount()).isEqualTo(2);
        assertThat(snapshot.stats().summarizeAttempts()).isEqualTo(2);
        assertThat(snapshot.stats().summarizeFailures()).isEqualTo(1);

        assertThat(snapshot.entryOf(Alpha.class)).get()
            .satisfies(entry -> assertThat(entry.state()).isEqualTo(ExplorerSnapshot.EntryState.READY));
        ExplorerSnapshot.Entry failed = snapshot.entryOf(Delta.class).orElseThrow();
        assertThat(failed.state()).isEqualTo(ExplorerSnapshot.EntryState.BUILD_FAILED);
        assertThat(failed.failureReason()).contains("нет источника фактов");
        assertThat(failed.diagnostics()).singleElement()
            .satisfies(row -> assertThat(row.code()).isEqualTo("EXPLORER_FACTS_UNAVAILABLE"));
        assertThat(failed.countsKnown())
            .as("отказ сборки — не «ошибок нет», а «счётчики неизвестны»")
            .isFalse();
        assertThat(snapshot.summaryOf(Delta.class)).isEmpty();
        assertThat(snapshot.roots())
            .as("отказавший тип не исчезает из инвентаря")
            .extracting(ExplorerSnapshot.Entry::type)
            .containsExactly(Alpha.class, Delta.class);
    }

    // ------------------------------------------------------------ root без ключа

    @Test
    void aRootWithoutAPublishedKeyStaysAVisibleRoot() {
        Fixture fixture = new Fixture();
        fixture.inventory = List.of(ref(Gamma.class, "Гамма"));
        fixture.publishedKey = type -> Optional.empty();

        ExplorerSnapshot snapshot = fixture.build();

        ExplorerSnapshot.Entry entry = snapshot.entryOf(Gamma.class).orElseThrow();
        assertThat(entry.publishedKey()).isEmpty();
        assertThat(entry.rootCandidate()).isTrue();
        assertThat(snapshot.roots()).extracting(ExplorerSnapshot.Entry::type).containsExactly(Gamma.class);
        assertThat(snapshot.searchTerms())
            .as("ключ не выдумывается, а подпись и имя типа ищутся")
            .extracting(ExplorerSnapshot.SearchTerm::term)
            .contains("гамма", "gamma");
    }

    // ------------------------------------------------------------ недоступный descriptor

    @Test
    void anUnavailableDescriptorStaysUnknownInsteadOfUnclassified() {
        Fixture fixture = new Fixture();
        fixture.inventory = List.of(ref(Gamma.class, "Гамма"));
        fixture.descriptor = type -> null;

        ExplorerSnapshot snapshot = fixture.build();

        ExplorerSnapshot.Entry entry = snapshot.entryOf(Gamma.class).orElseThrow();
        assertThat(entry.descriptor()).as("экспозиции нет — это не UNCLASSIFIED").isNull();
        assertThat(entry.kind()).isEqualTo(EntityKind.PLAIN);
        assertThat(entry.rootCandidate()).isTrue();
    }

    // ------------------------------------------------------------ одинаковые simpleName

    @Test
    void equalRowSimpleNamesKeepDistinctSectionIdentities() {
        Fixture fixture = new Fixture();
        fixture.inventory = List.of(ref(Owner.class, "Владелец"));
        fixture.sections = type -> type == Owner.class
            ? List.of(
                section(Owner.class, "firstRows", Alpha.Row.class, "Первая", 0),
                section(Owner.class, "secondRows", Beta.Row.class, "Вторая", 1))
            : List.of();

        ExplorerSnapshot snapshot = fixture.build();

        List<ExplorerSnapshot.OwnedSection> sections = snapshot.sectionsOf(Owner.class);
        assertThat(sections).hasSize(2);
        assertThat(sections)
            .as("простой rowClass не различает строки — simpleName совпадает")
            .extracting(ExplorerSnapshot.OwnedSection::rowSimpleName)
            .containsExactly("Row", "Row");
        assertThat(sections)
            .as("идентичность — root + поле связи + FQN строки")
            .extracting(ExplorerSnapshot.OwnedSection::id)
            .doesNotHaveDuplicates();
        assertThat(snapshot.ownersOf(Alpha.Row.class)).singleElement()
            .satisfies(section -> assertThat(section.sectionField()).isEqualTo("firstRows"));
        assertThat(snapshot.ownersOf(Beta.Row.class)).singleElement()
            .satisfies(section -> assertThat(section.sectionField()).isEqualTo("secondRows"));
    }

    // ------------------------------------------------------------ owned-строка без владельца

    @Test
    void anOwnedRowWithoutAConfirmedSectionIsNotAttachedToARoot() {
        Fixture fixture = new Fixture();
        fixture.inventory = List.of(ref(Owner.class, "Владелец"), ref(Alpha.Row.class, "Строка"));
        fixture.descriptor = type -> descriptor(type,
            type == Alpha.Row.class ? EntityExposure.OWNED_ROW : EntityExposure.STANDARD_ROOT);
        fixture.sections = type -> List.of();
        fixture.summary = type -> summary(type, List.of(field("code")), List.of());

        ExplorerSnapshot snapshot = fixture.build();

        assertThat(snapshot.ownersOf(Alpha.Row.class))
            .as("подтверждённой секции нет — владелец не выдумывается")
            .isEmpty();
        assertThat(snapshot.ownedRows())
            .extracting(ExplorerSnapshot.Entry::type)
            .containsExactly(Alpha.Row.class);
        assertThat(snapshot.roots())
            .as("owned-строка не становится самостоятельным root'ом")
            .extracting(ExplorerSnapshot.Entry::type)
            .containsExactly(Owner.class);
        assertThat(snapshot.searchTerms())
            .as("без подтверждённого владельца признаки строки не ведут ни в чужую карточку")
            .noneMatch(term -> term.rootType() == Alpha.Row.class);
        assertThat(snapshot.unassignedDiagnostics()).singleElement().satisfies(row -> {
            assertThat(row.code()).isEqualTo("EXPLORER_OWNER_MISSING");
            assertThat(row.entityFqn()).isEqualTo(Alpha.Row.class.getName());
        });
    }

    @Test
    void aSectionSourceFailureKeepsOtherFactsAndNamesTheIncompleteState() {
        Fixture fixture = new Fixture();
        fixture.inventory = List.of(ref(Owner.class, "Владелец"), ref(Gamma.class, "Гамма"));
        fixture.sections = type -> {
            if (type == Owner.class) {
                throw new IllegalStateException("sections offline");
            }
            return List.of();
        };
        ExplorerSnapshot snapshot = fixture.build();
        ExplorerSnapshot.Entry failed = snapshot.entryOf(Owner.class).orElseThrow();
        assertThat(failed.state()).isEqualTo(ExplorerSnapshot.EntryState.BUILD_FAILED);
        assertThat(failed.failureReason()).contains("sections offline");
        assertThat(failed.countsKnown()).isFalse();
        assertThat(failed.errorCount()).isEqualTo(1);
        assertThat(snapshot.summaryOf(Owner.class)).get().satisfies(summary ->
            assertThat(summary.diagnostics()).isEqualTo(failed.diagnostics()));
        assertThat(snapshot.entryOf(Gamma.class)).get().satisfies(entry ->
            assertThat(entry.state()).isEqualTo(ExplorerSnapshot.EntryState.READY));
        assertThat(snapshot.stats().summarizeAttempts()).isEqualTo(2);
    }

    @Test
    void aMetadataFailureDoesNotSkipTheSummaryOrTheOtherFactSources() {
        Fixture fixture = new Fixture();
        fixture.inventory = List.of(ref(Owner.class, "Владелец"));
        fixture.kind = type -> { throw new IllegalStateException("kind offline"); };
        fixture.publishedKey = type -> Optional.of("owner");
        ExplorerSnapshot snapshot = fixture.build();
        assertThat(snapshot.summaryOf(Owner.class)).isPresent();
        assertThat(snapshot.entryOf(Owner.class)).get().satisfies(entry -> {
            assertThat(entry.kind()).isNull();
            assertThat(entry.descriptor()).isNotNull();
            assertThat(entry.publishedKey()).contains("owner");
            assertThat(entry.failureReason()).contains("kind offline");
        });
        assertThat(snapshot.stats().summarizeAttempts()).isEqualTo(1);
    }

    @Test
    void anAmbiguousOwnerIsNamedWithoutChoosingOneRoot() {
        Fixture fixture = new Fixture();
        fixture.inventory = List.of(ref(Owner.class, "Владелец"), ref(Gamma.class, "Гамма"),
            ref(Alpha.Row.class, "Строка"));
        fixture.descriptor = type -> descriptor(type,
            type == Alpha.Row.class ? EntityExposure.OWNED_ROW : EntityExposure.STANDARD_ROOT);
        fixture.sections = type -> type == Alpha.Row.class ? List.of()
            : List.of(section(type, "rows", Alpha.Row.class, "Строки", 0));
        ExplorerSnapshot snapshot = fixture.build();
        assertThat(snapshot.ownersOf(Alpha.Row.class)).hasSize(2);
        assertThat(snapshot.unassignedDiagnostics()).singleElement().satisfies(row -> {
            assertThat(row.code()).isEqualTo("EXPLORER_OWNER_AMBIGUOUS");
            assertThat(row.value().value()).contains(Owner.class.getName(), Gamma.class.getName());
        });
    }

    @Test
    void anUnavailableGlobalDiagnosticSourceDoesNotAbortTheCatalog() {
        Fixture fixture = new Fixture();
        fixture.inventory = List.of(ref(Owner.class, "Владелец"));
        ExplorerSnapshot snapshot = ExplorerSnapshot.build(new ExplorerSnapshot.Sources(
            fixture.inventory, fixture.kind, fixture.descriptor, fixture.subsystem, fixture.sections,
            fixture.publishedKey, fixture.summary, true,
            () -> { throw new IllegalStateException("diagnostics offline"); }));
        assertThat(snapshot.summaryOf(Owner.class)).isPresent();
        assertThat(snapshot.diagnosticsAvailable()).isFalse();
        assertThat(snapshot.entries().get(0).countsKnown()).isFalse();
        assertThat(snapshot.unassignedDiagnostics()).singleElement().satisfies(row ->
            assertThat(row.code()).isEqualTo("EXPLORER_DIAGNOSTICS_UNAVAILABLE"));
    }

    @Test
    void duplicateInventoryAndDiagnosticsAreCountedOnce() {
        Fixture fixture = new Fixture();
        fixture.inventory = List.of(ref(Owner.class, "Владелец"), ref(Owner.class, "Владелец"));
        EntitySummary.DiagnosticRow row = new EntitySummary.DiagnosticRow(
            MetadataDiagnostic.Severity.ERROR, "TYPE_CONFLICT", Owner.class.getName(), "code", "",
            null, fact("conflict"), "fixture");
        fixture.summary = type -> summary(type, List.of(), List.of(row, row));
        ExplorerSnapshot snapshot = fixture.build();
        assertThat(snapshot.stats().typeCount()).isEqualTo(1);
        assertThat(snapshot.stats().summarizeAttempts()).isEqualTo(1);
        assertThat(snapshot.entries().get(0).errorCount()).isEqualTo(1);
        assertThat(snapshot.summaryOf(Owner.class)).get().satisfies(summary ->
            assertThat(summary.diagnostics()).containsExactly(row));
    }

    // ------------------------------------------------------------ owned-поля у секции

    @Test
    void ownedFieldsLeadToTheConfirmedOwnerSection() {
        Fixture fixture = new Fixture();
        fixture.inventory = List.of(ref(Owner.class, "Владелец"), ref(Alpha.Row.class, "Строка"));
        fixture.descriptor = type -> descriptor(type,
            type == Alpha.Row.class ? EntityExposure.OWNED_ROW : EntityExposure.STANDARD_ROOT);
        fixture.sections = type -> type == Owner.class
            ? List.of(section(Owner.class, "rows", Alpha.Row.class, "Строки", 0))
            : List.of();
        fixture.summary = type -> summary(type, List.of(field("code", "Код")), List.of());

        ExplorerSnapshot snapshot = fixture.build();

        String sectionId = snapshot.sectionsOf(Owner.class).get(0).id();
        assertThat(snapshot.searchTerms()).anySatisfy(term -> {
            assertThat(term.rootType()).isEqualTo(Owner.class);
            assertThat(term.kind()).isEqualTo(ExplorerSnapshot.SearchKind.FIELD);
            assertThat(term.term()).isEqualTo("код");
            assertThat(term.fieldName()).isEqualTo("code");
            assertThat(term.sectionId()).isEqualTo(sectionId);
        });
        assertThat(snapshot.searchTerms())
            .as("собственного root'а у owned-строки нет")
            .noneMatch(term -> term.rootType() == Alpha.Row.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> snapshot.ownersOf(Alpha.Row.class).clear())
            .isInstanceOf(UnsupportedOperationException.class);
        assertThat(snapshot.ownersOf(Alpha.Row.class)).hasSize(1);
    }

    // ------------------------------------------------------------ недоступная диагностика

    @Test
    void unavailableDiagnosticsKeepCountsUnknownInsteadOfZero() {
        Fixture fixture = new Fixture();
        fixture.inventory = List.of(ref(Alpha.class, "Альфа"));
        fixture.diagnosticsAvailable = false;
        fixture.unassigned = List.of(new EntitySummary.DiagnosticRow(
            MetadataDiagnostic.Severity.INFO, "DIAGNOSTICS_UNAVAILABLE", "", "",
            "Диагностика недоступна", null, fact("Стартовая проверка не подключена"), ""));

        ExplorerSnapshot snapshot = fixture.build();

        assertThat(snapshot.diagnosticsAvailable()).isFalse();
        ExplorerSnapshot.Entry entry = snapshot.entryOf(Alpha.class).orElseThrow();
        assertThat(entry.countsKnown())
            .as("недоступный скан не выдаётся за ноль ошибок")
            .isFalse();
        assertThat(snapshot.unassignedDiagnostics())
            .extracting(EntitySummary.DiagnosticRow::code)
            .containsExactly("DIAGNOSTICS_UNAVAILABLE");
    }

    // ------------------------------------------------------------ детерминизм

    @Test
    void theSameSourcesProduceEqualSnapshots() {
        Fixture fixture = new Fixture();
        fixture.inventory = List.of(ref(Owner.class, "Владелец"), ref(Alpha.Row.class, "Строка"));
        fixture.descriptor = type -> descriptor(type,
            type == Alpha.Row.class ? EntityExposure.OWNED_ROW : EntityExposure.STANDARD_ROOT);
        fixture.sections = type -> type == Owner.class
            ? List.of(section(Owner.class, "rows", Alpha.Row.class, "Строки", 0))
            : List.of();
        fixture.summary = type -> summary(type, List.of(field("code", "Код")), List.of());

        ExplorerSnapshot first = fixture.build();
        ExplorerSnapshot second = fixture.build();

        assertThat(second.entries()).isEqualTo(first.entries());
        assertThat(second.searchTerms()).isEqualTo(first.searchTerms());
        assertThat(second.sectionsOf(Owner.class)).isEqualTo(first.sectionsOf(Owner.class));
    }

    // ------------------------------------------------------------ фикстуры

    /** Источники снимка с безопасными значениями по умолчанию: тест меняет только нужное. */
    private static final class Fixture {

        List<EntitySummaryAssembler.EntityRef> inventory = List.of();
        Function<Class<?>, EntityKind> kind = type -> EntityKind.PLAIN;
        Function<Class<?>, EntityDescriptor> descriptor =
            type -> descriptor(type, EntityExposure.STANDARD_ROOT);
        Function<Class<?>, Optional<ExplorerSnapshot.SubsystemRef>> subsystem =
            type -> Optional.empty();
        Function<Class<?>, List<ExplorerSnapshot.OwnedSection>> sections = type -> List.of();
        Function<Class<?>, Optional<String>> publishedKey = type -> Optional.empty();
        Function<Class<?>, EntitySummary> summary =
            type -> summary(type, List.of(), List.of());
        boolean diagnosticsAvailable = true;
        List<EntitySummary.DiagnosticRow> unassigned = List.of();

        ExplorerSnapshot build() {
            return ExplorerSnapshot.build(new ExplorerSnapshot.Sources(inventory, kind, descriptor,
                subsystem, sections, publishedKey, summary, diagnosticsAvailable,
                () -> unassigned));
        }
    }

    private static EntitySummaryAssembler.EntityRef ref(Class<?> type, String title) {
        return new EntitySummaryAssembler.EntityRef(type, type.getSimpleName(),
            ResolvedValue.fact(title, FactOrigin.EXPLICIT, type.getName()));
    }

    private static ResolvedValue fact(String value) {
        return ResolvedValue.fact(value, FactOrigin.EXPLICIT, "org.ipro.vaadin.explorer.ExplorerSnapshotTest");
    }

    private static EntityDescriptor descriptor(Class<?> type, EntityExposure exposure) {
        return new EntityDescriptor(type, exposure, true, true,
            new EntityCapabilities(Set.of(FetchScenario.ROW), Set.of(), "fixture"), "fixture");
    }

    private static ExplorerSnapshot.OwnedSection section(Class<?> owner, String field,
                                                         Class<?> row, String label, int order) {
        return new ExplorerSnapshot.OwnedSection(owner, owner.getName(), field, row, row.getName(),
            row.getSimpleName(), fact(label), order);
    }

    private static EntitySummary.FieldRow field(String name, String label) {
        return new EntitySummary.FieldRow(FacetKey.of(FacetKind.FIELD_LABEL, Owner.class, name),
            name, "String", fact(label), false, false, FactOrigin.EXPLICIT, FactOrigin.JPA_MAPPING);
    }

    private static EntitySummary.FieldRow field(String name) {
        return field(name, name);
    }

    private static EntitySummary summary(Class<?> type, List<EntitySummary.FieldRow> fields,
                                         List<EntitySummary.DiagnosticRow> diagnostics) {
        return new EntitySummary(type, type.getSimpleName(), fact(type.getSimpleName()),
            List.of(), fields, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), diagnostics, List.of(), List.of(),
            List.of(), List.of());
    }
}
