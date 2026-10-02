package org.ipro.data;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

/**
 * Р РµР°Р»РёР·Р°С†РёСЏ РїСѓР±Р»РёС‡РЅРѕРіРѕ {@link EntityReadAccess} РїРѕРІРµСЂС… РµРґРёРЅРѕРіРѕ canonical executor'Р°
 * РґР»СЏ РЅСѓР¶Рґ REST-Р°РґР°РїС‚РµСЂР° (F-REST-READ-3).
 *
 * <p>Facade РЅРµ РїСЂРёРЅРёРјР°РµС‚ СЂРµС€РµРЅРёР№ Рѕ policy: read РёРґС‘С‚ С‡РµСЂРµР· {@link CanonicalReadExecutor}
 * (RLS gate, СЌРєСЃРїРѕР·РёС†РёСЏ С‚РёРїР°). Р”Р»СЏ Р·Р°РіСЂСѓР·РєРё РёСЃРїРѕР»СЊР·СѓРµС‚СЃСЏ fixedFetchPaths Р±РµР· union СЃ UI.</p>
 */
public class CanonicalEntityReadAccess implements EntityReadAccess {

    private final CanonicalReadExecutor readExecutor;
    private final EntityDataAccessResolver resolver;

    public CanonicalEntityReadAccess(CanonicalReadExecutor readExecutor) {
        this(readExecutor, null);
    }

    public CanonicalEntityReadAccess(CanonicalReadExecutor readExecutor, EntityDataAccessResolver resolver) {
        this.readExecutor = Objects.requireNonNull(readExecutor, "readExecutor must not be null");
        this.resolver = resolver;
    }

    @Override
    public <T> Page<T> list(Class<T> type, Specification<T> filter, Pageable pageable, Collection<String> fixedFetchPaths) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(pageable, "pageable must not be null");
        requireSupportedExposure(type);
        return readExecutor.readApiPage(ApiPageRead.of(type, filter, pageable, fixedFetchPaths));
    }

    @Override
    public <T> Optional<T> detail(Class<T> type, Object id, Collection<String> fixedFetchPaths) {
        Objects.requireNonNull(type, "type must not be null");
        if (id == null) {
            return Optional.empty();
        }
        requireSupportedExposure(type);
        return readExecutor.readApiDetail(ApiDetailRead.of(type, id, fixedFetchPaths));
    }

    private void requireSupportedExposure(Class<?> type) {
        if (resolver != null && resolver.hasCustomPolicy(type)) {
            throw new UnsupportedOperationException("Resource " + type.getSimpleName()
                + " has a custom EntityDataPolicy and cannot be read via canonical generic REST bridge");
        }
    }
}