package rlsfixture;

import org.ipro.rls.RlsDimension;

/**
 * Фикстура для теста fail-fast {@code RlsDimensionRegistry} (Фаза 1.2): FILTERABLE-измерение
 * без {@code @FilterDef}/@{@code @Filter} с тем же именем — такой класс должен ронять rebuild.
 * Пакет намеренно вне {@code org.ip} / {@code org.ipro.rls}, чтобы боевой скан приложения
 * (basePackage={@code org.ip}) не увидел фикстуру в тестовом рантайме.
 */
@RlsDimension("MISSING_FILTER")
public class FilterlessDimensionEntity {
}