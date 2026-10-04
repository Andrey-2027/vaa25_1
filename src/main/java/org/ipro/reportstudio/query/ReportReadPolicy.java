package org.ipro.reportstudio.query;

import java.util.List;
import java.util.Set;

/** Дополнительные ограничения предметного источника, без зависимости reportstudio от модели приложения. */
public interface ReportReadPolicy {
    List<String> violations(Set<Class<?>> entityTypes);
}
