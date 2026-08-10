package org.ip.rls;

import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;

/**
 * Инвалидация кэша readable-ids (см. {@link RlsReadableIdsCache}) на
 * любое создание/изменение/удаление {@link AccessGrant} — без этого сессия с уже
 * закэшированными правами не увидела бы отзыв доступа до перелогина.
 *
 * Инстанцируется провайдером персистентности (не Spring), поэтому обращается только
 * к статике {@link AccessGrantVersion}, а не к бинам через DI.
 */
public class AccessGrantChangeListener {

    @PostPersist
    @PostUpdate
    @PostRemove
    public void onChange(AccessGrant grant) {
        AccessGrantVersion.bump();
    }
}