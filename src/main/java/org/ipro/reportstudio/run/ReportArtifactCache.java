package org.ipro.reportstudio.run;

import net.sf.jasperreports.engine.JasperPrint;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.param.ReportContext;
import org.ipro.reportstudio.param.ResolvedParams;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Run-кэш артефакта отчёта: LRU по числу артефактов. Ключ учитывает шаблон
 * (id + версия), пользователя, все резолвнутые bindings, контекст запуска
 * и локаль/таймзону — по одинаковому ключу артефакт переиспользуется
 * (render once / export many).
 */
public class ReportArtifactCache {

    private final Map<String, JasperPrint> entries;

    public ReportArtifactCache() {
        this(8);
    }

    public ReportArtifactCache(int maxEntries) {
        int size = Math.max(1, maxEntries);
        entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, JasperPrint> eldest) {
                return size() > size;
            }
        };
    }

    public synchronized void put(String key, JasperPrint print) {
        entries.put(key, print);
    }

    public synchronized Optional<JasperPrint> get(String key) {
        return Optional.ofNullable(entries.get(key));
    }

    public synchronized void clear() {
        entries.clear();
    }

    public synchronized int size() {
        return entries.size();
    }

    /**
     * Ключ артефакта. Параметры — в каноническом виде (имя=значение по порядку),
     * значения строковые — разных типов (id, даты) склеиваются через ':'.
     */
    public static String key(ReportTemplate template, ResolvedParams params, ReportContext context,
            String localeTag, String zoneId) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("tpl=").append(template.getId()).append('/').append(template.getVersion());
        sb.append("|user=").append(context.user());
        if (context.entityClass() != null) {
            sb.append("|ctx=").append(context.entityClass().getName())
              .append(':').append(context.entityId())
              .append(':').append(context.selectedIds())
              .append(':').append(context.viewId());
        }
        if (params != null && !params.bindings().isEmpty()) {
            sb.append("|p=");
            params.bindings().forEach((name, value) -> sb.append(name).append('=')
                .append(String.valueOf(value)).append(';'));
        }
        sb.append("|loc=").append(localeTag).append(':').append(zoneId);
        return sb.toString();
    }
}
