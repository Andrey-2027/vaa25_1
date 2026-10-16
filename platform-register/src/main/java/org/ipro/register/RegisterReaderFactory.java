package org.ipro.register;

import jakarta.persistence.EntityManagerFactory;
import org.ipro.data.EntityDataAccess;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * D-R S1 — фабрика читателей по дескриптору: единственный владелец сборки читателя.
 *
 * <p>До среза читателя собирали двое: приложение объявляло bean
 * {@code RegisterSliceReader<NomenclatureAccountingObject>} с {@code EntityManagerFactory},
 * а форма списка конструировала свой читатель рядом и прикладной bean игнорировала. Два места
 * сборки означали, что проверка mapping'а и выбора измерений проходила дважды по разным путям,
 * и что UI мог разойтись с сервисом по составу читателя, не заметив этого.</p>
 *
 * <p>Фабрика держит и то, что дороже: срезовый читатель читает JPA metamodel и проверяет
 * mapping измерений, периода и ресурсов при создании. Это проверка <b>по типу</b>, а не по
 * вызову, поэтому её результат кэшируется: список регистра, открытый пять раз, не повторяет
 * разбор mapping'а. Точечный читатель дешёв (только descriptor) и создаётся на вызов.</p>
 *
 * <p>Оба читателя берут descriptor из {@link RegisterRegistry}, то есть из проверенного снимка
 * объявлений: тип, не являющийся managed-регистром, падает здесь же, а не на первом запросе.</p>
 */
public final class RegisterReaderFactory {

    private final RegisterRegistry registry;
    private final EntityDataAccess access;
    private final EntityManagerFactory entityManagerFactory;
    private final Map<Class<?>, RegisterSliceReader<?>> slices = new ConcurrentHashMap<>();

    public RegisterReaderFactory(RegisterRegistry registry, EntityDataAccess access,
                                 EntityManagerFactory entityManagerFactory) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.access = Objects.requireNonNull(access, "access");
        this.entityManagerFactory = Objects.requireNonNull(entityManagerFactory, "entityManagerFactory");
    }

    /** Срез «последняя запись на дату»; mapping проверяется один раз на тип. */
    @SuppressWarnings("unchecked")
    public <T> RegisterSliceReader<T> slice(Class<T> type) {
        Objects.requireNonNull(type, "type");
        return (RegisterSliceReader<T>) slices.computeIfAbsent(type,
            key -> new RegisterSliceReader<>(key, access, registry, entityManagerFactory));
    }

    /** Точечное чтение последней записи по полному набору измерений (ADR-0013 §4). */
    public <T> RegisterReader<T> point(Class<T> type) {
        return new RegisterReader<>(type, access, registry);
    }
}
