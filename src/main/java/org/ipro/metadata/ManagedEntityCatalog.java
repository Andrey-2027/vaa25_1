package org.ipro.metadata;

import jakarta.persistence.EntityManagerFactory;
import org.ipro.crud.ValidationException;

import jakarta.persistence.metamodel.EntityType;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Строгое разрешение имени класса в управляемую JPA-сущность — единственная
 * платформенная граница для {@code string → entity class} (см. DAC-13).
 *
 * <p>Прикладные сервисы не обращаются к {@code EntityManager}/{@code Class.forName}
 * напрямую: динамические ссылки (например, целевой словарь REF-значения) проходят
 * здесь проверку — класс зарегистрирован в текущем persistence unit, это именно
 * JPA-сущность и он реализует требуемый контракт. Неизвестные и неподходящие
 * классы отклоняются предсказуемой доменной ошибкой.</p>
 */
public final class ManagedEntityCatalog {

    private final Map<String, Class<?>> managedEntitiesByName;

    public ManagedEntityCatalog(EntityManagerFactory entityManagerFactory) {
        EntityManagerFactory factory = Objects.requireNonNull(
            entityManagerFactory, "entityManagerFactory must not be null");
        Map<String, Class<?>> entities = new HashMap<>();
        for (EntityType<?> entity : factory.getMetamodel().getEntities()) {
            Class<?> javaType = entity.getJavaType();
            entities.put(javaType.getName(), javaType);
        }
        this.managedEntitiesByName = Map.copyOf(entities);
    }

    /**
     * Разрешить имя класса в управляемую сущность с требуемым контрактом.
     *
     * @param entityClassName полное имя класса (устойчивый строковый контракт)
     * @param requiredContract интерфейс, который сущность обязана реализовывать
     * @return класс сущности, ограниченный требуемым контрактом
     * @throws ValidationException пустое имя, неизвестный класс,
     *                             незарегистрированная сущность или несовместимый контракт
     */
    public <T> Class<? extends T> resolve(String entityClassName, Class<T> requiredContract) {
        Objects.requireNonNull(requiredContract, "requiredContract must not be null");
        String name = entityClassName == null ? "" : entityClassName.trim();
        if (name.isEmpty()) {
            throw new ValidationException("Имя класса сущности не задано.");
        }
        Class<?> candidate = managedEntitiesByName.get(name);
        if (candidate == null) {
            throw new ValidationException(
                "Класс " + name + " не является зарегистрированной JPA-сущностью.");
        }
        if (!requiredContract.isAssignableFrom(candidate)) {
            throw new ValidationException(
                "Класс " + name + " не реализует " + requiredContract.getSimpleName()
                    + " и не может быть использован здесь.");
        }
        @SuppressWarnings("unchecked")
        Class<? extends T> result = (Class<? extends T>) candidate;
        return result;
    }
}
