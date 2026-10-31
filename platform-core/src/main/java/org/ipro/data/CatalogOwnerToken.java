package org.ipro.data;

import org.ipro.metadata.CatalogOwnerDescriptor;

import java.util.Objects;

/**
 * Подтверждённое владельцем состояние, снятое preflight'ом до побочных действий.
 *
 * <p>{@code ownerId} — id, который сервер уже проверил и обязался записать. Финальная
 * проверка сравнивает с ним записываемое значение, поэтому lifecycle hook или aggregate
 * callback не могут незаметно перенести запись к другому владельцу. Для update этот id
 * снят с доверенного сохранённого состояния, а не с мутируемого managed-объекта.</p>
 *
 * @param owner   resolved owner-capability; {@code null} — обычный справочник
 * @param ownerId проверенный id владельца; {@code null} — только для {@link #none()}
 */
public record CatalogOwnerToken(CatalogOwnerDescriptor owner, Long ownerId) {

    public CatalogOwnerToken {
        if (owner == null && ownerId != null) {
            throw new IllegalArgumentException("owner-less token must not carry an owner id");
        }
        if (owner != null && ownerId == null) {
            throw new IllegalArgumentException("owner token requires a checked owner id");
        }
    }

    /** Токен типа без owner-capability: guard не вмешивается. */
    public static CatalogOwnerToken none() {
        return new CatalogOwnerToken(null, null);
    }

    public boolean declared() {
        return owner != null;
    }

    public String ownerTypeId() {
        return Objects.requireNonNull(owner, "owner-less token").targetTypeId();
    }
}
