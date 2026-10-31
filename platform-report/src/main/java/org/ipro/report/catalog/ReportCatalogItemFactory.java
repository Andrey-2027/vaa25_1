package org.ipro.report.catalog;

/**
 * Единственный сборочный узел строки каталога (D3.6): движок → {@link ReportCatalogItem}.
 *
 * <p>Сборка одной и той же записи раньше жила в шести местах: три приватных
 * метода {@link ReportCatalogService}, анонимные бриджи обоих variant-каталогов и
 * {@code ContextualReportLauncher}. Из-за этого копия или импортированная запись
 * собиралась не тем же кодом, что запись из списка — и каталог после операции
 * читался второй раз, чтобы «найти» строку, которую только что вернул сервис.</p>
 *
 * <p>Правила сборки, которые обязаны быть в одном месте: {@code fileMissing} —
 * внешний файл шаблона отсутствует в хранилище. UDR-движок удаляется (R3.2),
 * поэтому фабрика не знает его DOM: строку UDR больше никто не строит.</p>
 *
 * <p>{@code fileMissing} передаёт вызывающий: он выводится только из сервиса
 * ({@code fileExists}), и фабрика намеренно не держит состояния — это чистые
 * функции без зависимостей.</p>
 *
 * <p>Правила конкретного движка (что считать включённым) остаются в коде этого
 * движка: фабрика не импортирует UReport и предоставляет механическую сборку
 * через {@link #of(long, ReportEngineType, String, String, boolean, boolean)}.</p>
 */
public final class ReportCatalogItemFactory {
    /** JSON-backed UniDR rows share the same catalog assembly boundary. */
    public static ReportCatalogItem ofUniDr(long id, String name, String description) {
        return new ReportCatalogItem(id, ReportEngineType.UNIDR, name, description, true, false);
    }

    /**
     * Механическая сборка строки движка, чьи правила живут в коде этого движка.
     *
     * <p>Фабрика не решает, что такое «включён»: это значение передаёт
     * вызывающий, а здесь остаётся только инвариант конструкции
     * {@link ReportCatalogItem}.</p>
     */
    public static ReportCatalogItem of(long id, ReportEngineType type, String name,
                                       String description, boolean enabled,
                                       boolean fileMissing) {
        return new ReportCatalogItem(id, type, name, description, enabled, fileMissing);
    }

    private ReportCatalogItemFactory() {
    }
}
