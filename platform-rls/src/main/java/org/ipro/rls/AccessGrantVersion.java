package org.ipro.rls;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Глобальный счётчик изменений {@link AccessGrant} — источник инвалидации
 * для {@link RlsReadableIdsCache}.
 *
 * Сознательно грубый: любое изменение ЛЮБОГО гранта сбрасывает кэш readable-ids ВСЕХ
 * пользователей, а не только тех, кого затронуло конкретное изменение. Точечная
 * инвалидация ("это изменение касается ролей X и Y — сбросить кэш только их
 * пользователей") — тот же класс риска "забыли один путь", который уже несколько раз
 * проявлялся в разговоре про RLS, только вместо утечки данных — риск не сбросить кэш
 * там, где обязаны. Гранты меняются редко (админская операция), лишний пересчёт
 * readable-ids у активных пользователей не в момент самого изменения — цена, которую
 * можно платить.
 */
public final class AccessGrantVersion {

    private static final AtomicLong VERSION = new AtomicLong(0);

    private AccessGrantVersion() {
    }

    public static long current() {
        return VERSION.get();
    }

    /** Вызывается только из {@link AccessGrantChangeListener}. */
    public static void bump() {
        VERSION.incrementAndGet();
    }
}