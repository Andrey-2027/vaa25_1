package org.ipro.report.catalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.transaction.annotation.Transactional;

/**
 * Единый каталог отчётов: агрегатор строк, которые поставляют движки.
 *
 * <p>Сервис не знает ни одного движка и не содержит {@code switch} по
 * {@link ReportEngineType}: каждый движок объявляет себя
 * {@link ReportCatalogContributor}, а здесь строки только собираются и
 * сортируются. Подключение нового движка и вывод прежнего из эксплуатации —
 * регистрация или снятие одного адаптера, без правок этого класса.</p>
 *
 * <p>Сортировка задаёт и разрешение совпадающих имён: по имени без учёта
 * регистра, затем по движку в порядке объявления типов. Прежде тот же порядок
 * получался из порядка веток каталога (UDR → JR → вклад движков), и он
 * сохранён явно, чтобы одинаковые имена двух движков не зависели от порядка
 * регистрации бинов.</p>
 */
public class ReportCatalogService {

    private final List<ReportCatalogContributor> contributors;

    public ReportCatalogService(List<ReportCatalogContributor> contributors) {
        this.contributors = List.copyOf(contributors);
    }

    @Transactional(readOnly = true)
    public List<ReportCatalogItem> findAll(String term, boolean includeDisabled) {
        List<ReportCatalogItem> result = new ArrayList<>();
        for (ReportCatalogContributor contributor : contributors) {
            result.addAll(contributor.findAll(term, includeDisabled));
        }
        result.sort(Comparator.comparing(ReportCatalogItem::name,
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ReportCatalogItem::type));
        return result;
    }

    @Transactional(readOnly = true)
    public ReportCatalogItem find(ReportEngineType type, Long id) {
        return contributors.stream()
                .filter(contributor -> contributor.type() == type)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Движок отчётов не подключён: " + type))
                .find(id);
    }
}
