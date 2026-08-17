package org.ip.views.forms;

import org.ip.model.PrdSpecMtr;

import java.util.Locale;

/**
 * Варианты Формы Элемента строки табличной части {@link PrdSpecMtr} (PR-1.4 «strict variants»).
 *
 * <p>Строка различается дискриминатором {@code PrdSpecMtr.typeMtr}: 0 — материал
 * ({@link #MATERIAL}, поля nomenclature/unit/qt), 1 — продукция ({@link #PRODUCT},
 * поля prdSpecMtr/unit/qt). Единый источник имён вариантов вместо строковых литералов
 * «material»/«product» в нескольких местах ({@link PrdSpecMtrTableCustomization} —
 * selector, {@link PrdSpecMtrFormCustomization} — регистрация форм): переход
 * «enum↔string» выполняется через {@link #key()} / {@link #of(String)}.</p>
 */
public enum PrdSpecMtrVariant {

    MATERIAL,
    PRODUCT;

    /** Канонический строковый ключ для FormRegistry/FormResolver ("material"/"product"). */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Резолв ключа в enum; неизвестный ключ — ошибка (strict-политика PR-1.4). */
    public static PrdSpecMtrVariant of(String key) {
        for (PrdSpecMtrVariant variant : values()) {
            if (variant.key().equals(key)) {
                return variant;
            }
        }
        throw new IllegalArgumentException("Unknown PrdSpecMtr variant key: '" + key + "'");
    }

    /**
     * Дискриминатор typeMtr (null трактуется как материал — тип проставляется при «Добавить»,
     * строка без типа открывается в форме материала). Значения вне 0/1 — ошибка конфигурации.
     */
    public static PrdSpecMtrVariant of(Integer typeMtr) {
        if (typeMtr == null || typeMtr == 0) {
            return MATERIAL;
        }
        if (typeMtr == 1) {
            return PRODUCT;
        }
        throw new IllegalArgumentException("Unknown PrdSpecMtr.typeMtr discriminator: " + typeMtr);
    }
}
