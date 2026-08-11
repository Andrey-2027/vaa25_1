package org.ipro.rls;

/**
 * Строгий read-гейт CHECK_ONLY (Фаза 5 RLS-плана): решение "можно ли вообще читать
 * сущности этого класса текущему пользователю".
 *
 * Правило: если у класса есть измерение рода {@link RlsDimensionKind#CHECK_ONLY}
 * ("доступ к виду документа целиком", напр. "ENTITY:ReceivingDocument") и у
 * пользователя нет НИ ОДНОГО гранта на чтение этого измерения
 * ({@link AccessService#hasAnyAccess} == false) — чтение запрещено ВООБЩЕ:
 * findById → пусто, findAll → пустой список, независимо от того, проходят ли строки
 * по построчным FILTERABLE-измерениям (JOURNAL/BRANCH). Этим закрывается дыра, когда
 * список/запись открывались в обход меню (прямая ссылка, lookup), хотя плитка меню
 * скрыта — "нет доступа" теперь реальный запрет чтения на уровне сервиса, а не
 * только скрытая навигация.
 *
 * Единая точка принятия решения для всех путей чтения (AbstractBaseService,
 * LookupService) — не дублируем hasAnyAccess в каждом сервисе.
 *
 * {@link RlsContext#isBypassed()} — фоновая задача: чтение разрешено (системный
 * контекст, фильтры/гейты не действуют).
 */
public class RlsReadGate {

    private final AccessService accessService;
    private final RlsDimensionRegistry dimensionRegistry;

    public RlsReadGate(AccessService accessService, RlsDimensionRegistry dimensionRegistry) {
        this.accessService = accessService;
        this.dimensionRegistry = dimensionRegistry;
    }

    /**
     * @param entityClass класс сущности
     * @param username    имя пользователя (из прикладного RlsCurrentUser/CurrentUser)
     * @return true — чтение разрешено; false — класс обязан отдавать пустые результаты
     */
    public boolean canRead(Class<?> entityClass, String username) {
        if (RlsContext.isBypassed()) {
            return true;
        }
        for (String dimension : dimensionRegistry.dimensionsOf(entityClass)) {
            if (dimensionRegistry.kindOf(dimension) == RlsDimensionKind.CHECK_ONLY
                && !accessService.hasAnyAccess(dimension, username)) {
                return false;
            }
        }
        return true;
    }
}