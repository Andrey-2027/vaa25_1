package org.ipro.register;

import org.ipro.metadata.ManagedEntityCatalog;

import java.util.*;

/** One immutable declaration snapshot per persistence unit; no second classpath/entity scan. */
public final class RegisterRegistry {
    private final ManagedEntityCatalog catalog;
    private final RecorderTypeRegistry recorderTypes;
    private volatile Map<Class<?>, RegisterDescriptor> descriptors;

    public RegisterRegistry(ManagedEntityCatalog catalog) {
        this(catalog, null);
    }

    public RegisterRegistry(ManagedEntityCatalog catalog, RecorderTypeRegistry recorderTypes) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.recorderTypes = recorderTypes;
    }

    /** Publish only after every declaration is valid; failed builds never expose a partial registry. */
    public synchronized void initialize() {
        if (descriptors != null) return;
        Map<Class<?>, RegisterDescriptor> snapshot = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        List<Class<?>> registerTypes = catalog.managedEntityClasses().stream()
            .filter(type -> type.isAnnotationPresent(RegisterMetadata.class))
            .sorted(Comparator.comparing(Class::getName)).toList();
        boolean hasRecordedRegister = registerTypes.stream()
            .anyMatch(type -> RecordedRegisterEntry.class.isAssignableFrom(type));

        boolean recorderCatalogReady = recorderTypes == null;
        if (recorderTypes != null) {
            try {
                recorderTypes.initialize();
                recorderCatalogReady = true;
            } catch (RuntimeException exception) {
                errors.add("Каталог типов-регистраторов: " + exception.getMessage());
            }
        }
        if (hasRecordedRegister && recorderTypes == null) {
            errors.add("Подчинённые регистры требуют RecorderTypeRegistry для проверки managed DOCUMENT типов.");
        }

        for (Class<?> type : registerTypes) {
            var violations = RegisterMetadataValidator.validate(type);
            for (var violation : violations) {
                String field = violation.field() == null ? "" : " поле '" + violation.field() + "'";
                errors.add(type.getName() + " [" + violation.code() + "]" + field + ": " + violation.message());
            }
            if (!violations.isEmpty()) continue;

            Map<Class<?>, String> allowedCodes = new LinkedHashMap<>();
            RegisterMetadata metadata = type.getAnnotation(RegisterMetadata.class);
            for (Class<?> recorderType : metadata.recorderTypes()) {
                if (recorderTypes == null || !recorderCatalogReady) continue;
                Optional<String> code = recorderTypes.findCode(recorderType);
                if (code.isEmpty()) {
                    errors.add(type.getName() + " [RECORDER_TYPE_NOT_MANAGED]: " + recorderType.getName()
                        + " отсутствует в managed-каталоге разрешённых регистраторов.");
                } else {
                    allowedCodes.put(recorderType, code.get());
                }
            }
            if (allowedCodes.size() == metadata.recorderTypes().length) {
                snapshot.put(type, RegisterDescriptor.validated(type, allowedCodes));
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Некорректные декларации регистров сведений:\n"
                + String.join("\n", errors));
        }
        descriptors = Collections.unmodifiableMap(snapshot);
    }

    public Optional<RegisterDescriptor> find(Class<?> type) {
        Objects.requireNonNull(type, "type");
        initialize();
        return Optional.ofNullable(descriptors.get(type));
    }

    public RegisterDescriptor require(Class<?> type) {
        return find(type).orElseThrow(() -> new IllegalArgumentException(
            type.getName() + " не является managed-регистром текущего persistence unit"));
    }

    public List<RegisterDescriptor> descriptors() {
        initialize();
        return List.copyOf(descriptors.values());
    }
}
