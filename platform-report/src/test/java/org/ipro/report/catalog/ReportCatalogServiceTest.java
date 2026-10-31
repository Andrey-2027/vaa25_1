package org.ipro.report.catalog;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Каталог — агрегатор: он не знает ни одного движка, поэтому здесь проверяются
 * только сбор вклада адаптеров, порядок строк и диспетчеризация по паре
 * (type, id). Правила конкретного движка живут в тестах его адаптера
 * ({@code UniDrFileStoreTest}, {@code JrCatalogContributorTest}).
 */
class ReportCatalogServiceTest {

    @Test
    void mergesContributionsAndSortsByName() {
        ReportCatalogService catalog = new ReportCatalogService(List.of(
                new StubContributor(ReportEngineType.UNIDR,
                        List.of(row(ReportEngineType.UNIDR, 1L, "Ясли"))),
                new StubContributor(ReportEngineType.JR,
                        List.of(row(ReportEngineType.JR, 1L, "Азбука")))));

        List<ReportCatalogItem> items = catalog.findAll("", true);

        assertThat(items).extracting(ReportCatalogItem::name)
                .containsExactly("Азбука", "Ясли");
        // Коллизия (1, UNIDR) против (1, JR) не склеивает записи: ключ — пара.
        assertThat(items).extracting(ReportCatalogItem::type)
                .containsExactly(ReportEngineType.JR, ReportEngineType.UNIDR);
    }

    @Test
    void passesTermAndIncludeDisabledToEveryContributor() {
        StubContributor unidr = new StubContributor(ReportEngineType.UNIDR, List.of());
        StubContributor jr = new StubContributor(ReportEngineType.JR, List.of());
        ReportCatalogService catalog = new ReportCatalogService(List.of(unidr, jr));

        catalog.findAll("остат", false);

        assertThat(unidr.lastTerm).isEqualTo("остат");
        assertThat(unidr.lastIncludeDisabled).isFalse();
        assertThat(jr.lastTerm).isEqualTo("остат");
        assertThat(jr.lastIncludeDisabled).isFalse();
    }

    @Test
    void sameNameOrderFollowsEngineDeclarationOrder() {
        ReportCatalogService catalog = new ReportCatalogService(List.of(
                new StubContributor(ReportEngineType.UNIDR,
                        List.of(row(ReportEngineType.UNIDR, 7L, "Прайс"))),
                new StubContributor(ReportEngineType.JR,
                        List.of(row(ReportEngineType.JR, 7L, "Прайс")))));

        assertThat(catalog.findAll("", true)).extracting(ReportCatalogItem::type)
                .as("одинаковые имена не должны зависеть от порядка регистрации бинов")
                .containsExactly(ReportEngineType.JR, ReportEngineType.UNIDR);
    }

    @Test
    void findResolvesByTypeAndIdPair() {
        ReportCatalogService catalog = new ReportCatalogService(List.of(
                new StubContributor(ReportEngineType.UNIDR,
                        List.of(row(ReportEngineType.UNIDR, 5L, "UniDR пять"))),
                new StubContributor(ReportEngineType.JR, List.of())));

        assertThat(catalog.find(ReportEngineType.UNIDR, 5L).name()).isEqualTo("UniDR пять");
        // Запись того же id принадлежит другому движку и резолвится его адаптером.
        assertThatThrownBy(() -> catalog.find(ReportEngineType.JR, 5L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void engineWithoutContributorIsReportedAsNotConnected() {
        ReportCatalogService catalog = new ReportCatalogService(List.of(
                new StubContributor(ReportEngineType.JR,
                        List.of(row(ReportEngineType.JR, 1L, "Ясли")))));

        assertThatThrownBy(() -> catalog.find(ReportEngineType.UNIDR, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNIDR");
    }

    @Test
    void catalogWithoutContributorsIsEmptyButUsable() {
        ReportCatalogService catalog = new ReportCatalogService(List.of());

        assertThat(catalog.findAll("", true)).isEmpty();
        assertThatThrownBy(() -> catalog.find(ReportEngineType.JR, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ReportCatalogItem row(ReportEngineType type, long id, String name) {
        return new ReportCatalogItem(id, type, name, null, true, false);
    }

    /** Адаптер-заглушка: запоминает переданный фильтр, чтобы каталог-агрегатор был проверяем. */
    private static final class StubContributor implements ReportCatalogContributor {

        private final ReportEngineType type;
        private final List<ReportCatalogItem> items;
        private String lastTerm;
        private boolean lastIncludeDisabled;

        private StubContributor(ReportEngineType type, List<ReportCatalogItem> items) {
            this.type = type;
            this.items = new ArrayList<>(items);
        }

        @Override
        public ReportEngineType type() {
            return type;
        }

        @Override
        public List<ReportCatalogItem> findAll(String term, boolean includeDisabled) {
            this.lastTerm = term;
            this.lastIncludeDisabled = includeDisabled;
            return List.copyOf(items);
        }

        @Override
        public ReportCatalogItem find(Long id) {
            return items.stream()
                    .filter(item -> item.id().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Нет строки " + type + "#" + id));
        }
    }
}
