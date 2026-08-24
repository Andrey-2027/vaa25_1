package org.ipro.crud.config;

import org.ipro.crud.LookupService;
import org.ipro.crud.ReferenceCheckService;
import org.ipro.crud.ServiceLocator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Бины CRUD-слоя платформы.
 *
 * <p>Классы зарегистрированы через {@code @Import} (а не сканированием):
 * платформа сознательно исключена из component-scan приложения. {@code @Import}
 * сохраняет полную аннотационную обработку — в том числе {@code @PersistenceContext}
 * в {@link ReferenceCheckService}. Приложение может переопределить любой бин,
 * объявив собственный (типовые {@code @ConditionalOnMissingBean} здесь не нужны:
 * повторное определение того же типа просто выигрывает у импорта).</p>
 */
@AutoConfiguration
@Import({ServiceLocator.class, ReferenceCheckService.class, LookupService.class})
public class CrudAutoConfiguration {
}
