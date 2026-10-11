package org.ipro.rls;

import org.ipro.rls.c5.C5PermissionEvaluator;

/**
 * Строгий read-гейт CHECK_ONLY: решение "можно ли вообще читать сущности этого класса
 * текущему пользователю".
 *
 * <p>Правило: если у класса есть измерение рода {@link RlsDimensionKind#CHECK_ONLY}
 * ("доступ к виду документа целиком", напр. "ENTITY:ReceivingDocument"), то чтение
 * разрешено, только если субъект держит право чтения сущности по C5
 * ({@link C5PermissionEvaluator#isEntityReadPermitted}, ключ {@code ENTITY:<FQN>}).
 * Отказ означает "нет доступа" целиком: findById → пусто, findAll → пустой список,
 * независимо от того, проходят ли строки по построчным FILTERABLE-измерениям
 * (JOURNAL/BRANCH), и независимо от того, открыт список через меню или по прямой
 * ссылке.</p>
 *
 * <p><b>Почему C5, а не флаг CHECK_ONLY.</b> До C5.7 гейт читал
 * {@code AccessService#hasAnyAccess} по короткому ключу {@code ENTITY:<ИмяКласса>} —
 * то же самое, что решает гейт видимости плитки в UI. Это было дублирование:
 * {@code ENTITY:<ИмяКласса>} — ключ <b>операций</b> типа (CREATE/UPDATE/DELETE,
 * ADR-0017 §3), а чтение сущности решается отдельным ключом {@code ENTITY:<FQN>} и
 * ровно один раз. Пока флаг чтения короткого ключа что-то решал, столбец «Чтение»
 * этого ключа в админ-экране описывал не операцию; после C5.7 короткий ключ чисто
 * операционный, а чтение типа — ответ C5. Построчный доступ (JOURNAL/BRANCH) остаётся
 * у RLS и здесь не решается.</p>
 *
 * <p>Измерения берутся из политики класса (аннотации {@code @RlsDimension}), поэтому
 * гейт действует на типы, у которых доступ объявлен целиком; тип без CHECK_ONLY и без
 * политики читается по общим правилам. {@link RlsContext#isBypassed()} — фоновая
 * задача: чтение разрешено (системный контекст, фильтры/гейты не действуют).</p>
 */
public class RlsReadGate {

    private final C5PermissionEvaluator c5PermissionEvaluator;
    private final RlsDimensionRegistry dimensionRegistry;

    public RlsReadGate(C5PermissionEvaluator c5PermissionEvaluator,
                       RlsDimensionRegistry dimensionRegistry) {
        this.c5PermissionEvaluator = c5PermissionEvaluator;
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
        RlsPolicyDescriptor policy = dimensionRegistry.policyOf(entityClass);
        if (!policy.protectedEntity()) {
            return true;
        }
        if (username == null || username.isBlank() || "system".equals(username)) {
            return false;
        }
        if (!policy.checkOnlyDimensions().isEmpty()
                && !c5PermissionEvaluator.isEntityReadPermitted(username, entityClass)) {
            return false;
        }
        return true;
    }
}
