package org.ipro.rls;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.SessionScope;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * getReadableIds() по измерению для текущего залогиненного пользователя — считается
 * через {@link AccessService} максимум один раз за Vaadin-сессию (не на каждый
 * round-trip), инвалидируется целиком, когда меняется {@link AccessGrantVersion} —
 * то есть при ЛЮБОМ изменении любого AccessGrant, не только тех, что касаются именно
 * этого пользователя (см. AccessGrantVersion — почему так, а не точечно).
 *
 * @SessionScope — бин привязан к HTTP/Vaadin-сессии текущего логина; на каждого
 * пользователя своя копия, живёт до логаута/истечения сессии.
 */
@Component
@SessionScope
public class RlsReadableIdsCache {

    /** Оборачивает результат AccessService.getReadableIds(), который сам может быть null
     *  (безграничный доступ) — ConcurrentHashMap не хранит null-значения напрямую. */
    private record CachedIds(List<Long> ids) {
    }

    private final AccessService accessService;
    private final Map<String, CachedIds> byDimension = new ConcurrentHashMap<>();
    private volatile long cachedVersion = -1;
    private volatile String cachedUsername;

    public RlsReadableIdsCache(AccessService accessService) {
        this.accessService = accessService;
    }

    /**
     * см. {@link AccessService#getReadableIds(String, String)} — null означает доступ
     * без ограничений по этому измерению.
     */
    public synchronized List<Long> getReadableIds(String dimension, String username) {
        long currentVersion = AccessGrantVersion.current();
        if (currentVersion != cachedVersion || !Objects.equals(username, cachedUsername)) {
            byDimension.clear();
            cachedVersion = currentVersion;
            cachedUsername = username;
        }
        return byDimension
            .computeIfAbsent(dimension, d -> new CachedIds(accessService.getReadableIds(d, username)))
            .ids();
    }
}