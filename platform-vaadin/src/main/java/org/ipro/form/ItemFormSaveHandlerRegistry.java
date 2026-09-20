package org.ipro.form;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Registry of application-level save overrides.
 *
 * <p>The registry is intentionally an escape hatch: standard entities use the
 * metadata-driven aggregate save path and do not need a handler. A handler may
 * declare one exact entity class through {@link ItemFormSaveHandler#supportedEntityClass()}.
 * Declared duplicates fail while the registry is built; dynamic handlers which
 * only override {@link ItemFormSaveHandler#supports(Class)} are checked when
 * the dispatcher resolves a concrete form, before any persistence is started.</p>
 */
public final class ItemFormSaveHandlerRegistry {

    private final List<ItemFormSaveHandler<?>> handlers;
    private final Map<Class<?>, ItemFormSaveHandler<?>> declaredHandlers;

    public ItemFormSaveHandlerRegistry(List<? extends ItemFormSaveHandler<?>> handlers) {
        Objects.requireNonNull(handlers, "handlers must not be null");
        this.handlers = List.copyOf(handlers);

        Map<Class<?>, ItemFormSaveHandler<?>> declarations = new LinkedHashMap<>();
        for (ItemFormSaveHandler<?> handler : this.handlers) {
            Objects.requireNonNull(handler, "handlers must not contain null");
            handler.validateDeclaration();
            Class<?> entityClass = handler.supportedEntityClass();
            if (entityClass == null) {
                continue;
            }
            ItemFormSaveHandler<?> previous = declarations.putIfAbsent(entityClass, handler);
            if (previous != null) {
                throw new IllegalStateException(
                    "Несколько ItemFormSaveHandler объявлены для " + entityClass.getName()
                        + ": " + previous.getClass().getName() + " и "
                        + handler.getClass().getName());
            }
        }
        this.declaredHandlers = Map.copyOf(declarations);
    }

    /**
     * Найти ровно один custom override для типа сущности.
     *
     * @throws IllegalStateException если несколько handlers поддерживают тип
     */
    public Optional<ItemFormSaveHandler<?>> find(Class<?> entityClass) {
        Objects.requireNonNull(entityClass, "entityClass must not be null");
        List<ItemFormSaveHandler<?>> matches = new ArrayList<>();
        for (ItemFormSaveHandler<?> handler : handlers) {
            if (handler.supports(entityClass)) {
                matches.add(handler);
            }
        }
        if (matches.size() > 1) {
            throw new IllegalStateException(
                "Неоднозначный ItemFormSaveHandler для " + entityClass.getName()
                    + ": " + matches.stream()
                        .map(handler -> handler.getClass().getName())
                        .toList());
        }
        return matches.stream().findFirst();
    }

    /** Быстрая проверка exact declaration, полезная для diagnostics/startup tests. */
    public Optional<ItemFormSaveHandler<?>> declaredFor(Class<?> entityClass) {
        Objects.requireNonNull(entityClass, "entityClass must not be null");
        return Optional.ofNullable(declaredHandlers.get(entityClass));
    }

    /** Snapshot зарегистрированных handlers в порядке Spring resolution. */
    public List<ItemFormSaveHandler<?>> all() {
        return handlers;
    }

    /** Удобный empty registry для hand-built tests и локального bootstrap. */
    public static ItemFormSaveHandlerRegistry empty() {
        return new ItemFormSaveHandlerRegistry(List.of());
    }
}
