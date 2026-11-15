package org.ipro.query.list;

import org.ipro.filtergrid.projection.QueryProvenance;
import org.ipro.query.QueryProjectionCompiler;
import org.ipro.rls.RlsAccessDeniedException;
import org.ipro.rls.RlsCurrentUser;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/** Объявленные списки и проверенный снимок определений из БД. */
public final class QueryListRegistry {
    /**
     * @param entities сущности, которые читает запрос списка: по ним определяется, кто его увидит; пусто,
     *                 если определение не прошло проверку
     */
    public record Entry(String key, String title, QueryListDefinition definition, boolean enabled, Long version,
                        String modifiedBy, Instant modifiedAt, String diagnostic, List<String> entities) {
        public Entry {
            entities = List.copyOf(entities == null ? List.of() : entities);
        }

        /** Список объявлен в коде: только для чтения, без автора и версии. */
        public boolean declared() {
            return version == null;
        }

        public String launchKey() {
            return "querylist." + key + ":" + (version == null ? "declared" : version + ":" + modifiedAt);
        }
    }

    /**
     * Результат проверки определения без сохранения.
     *
     * @param diagnostic причина отказа; {@code null}, если определение проходит проверку
     * @param entities   сущности, которые читает запрос
     * @param columns    алиасы колонок результата в порядке запроса
     * @param rowKey     заголовки колонок ключа строки; пусто, если ключ определить нельзя
     */
    public record Check(String diagnostic, List<String> entities, List<String> columns, List<String> rowKey) {
        public Check {
            columns = List.copyOf(columns == null ? List.of() : columns);
            entities = List.copyOf(entities == null ? List.of() : entities);
            rowKey = List.copyOf(rowKey == null ? List.of() : rowKey);
        }

        public boolean ok() {
            return diagnostic == null;
        }
    }

    private final QueryListRepository repository;
    private final QueryListAdminAccess access;
    private final RlsCurrentUser users;
    private final QueryProjectionCompiler compiler;
    private final QueryListDefinitionCodec codec = new QueryListDefinitionCodec();
    private final TransactionTemplate writeTransaction;
    private final Map<String, QueryListDefinition> declarations;
    /** Запись и полная перезагрузка идут под этим замком; чтение списков и открытие замка не берут. */
    private final Object writeLock = new Object();
    private final ConcurrentMap<String, Entry> stored = new ConcurrentHashMap<>();
    private volatile Map<String, Entry> declaredEntries = Map.of();
    private volatile boolean loaded;
    private volatile Instant checkedAt;

    public QueryListRegistry(QueryListRepository repository, QueryListAdminAccess access, RlsCurrentUser users,
            QueryProjectionCompiler compiler, List<QueryListDeclaration> declarations,
            PlatformTransactionManager transactions) {
        this.repository = repository;
        this.access = access;
        this.users = users;
        this.compiler = compiler;
        this.writeTransaction = new TransactionTemplate(transactions);
        writeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Map<String, QueryListDefinition> byKey = new LinkedHashMap<>();
        for (var declaration : declarations) {
            var definition = declaration.definition();
            if (definition.provenance() != QueryProvenance.DECLARED) {
                throw new IllegalArgumentException("Объявление из кода требует DECLARED: " + definition.key());
            }
            if (byKey.putIfAbsent(definition.key(), definition) != null) {
                throw new IllegalArgumentException("Повторный ключ списка в коде: " + definition.key());
            }
        }
        this.declarations = Map.copyOf(byKey);
    }

    public void initialize() {
        synchronized (writeLock) {
            reload();
        }
    }

    private void reload() {
        Map<String, Entry> checked = new LinkedHashMap<>();
        declarations.forEach((key, definition) -> {
            var check = inspect(definition);
            checked.put(key, new Entry(key, definition.title(), definition, true, null, null, null,
                    check.diagnostic(), check.entities()));
        });
        declaredEntries = Map.copyOf(checked);
        Map<String, Entry> rows = loadStored();
        stored.keySet().retainAll(rows.keySet());
        stored.putAll(rows);
        checkedAt = Instant.now();
        loaded = true;
    }

    public List<Entry> recheck() {
        requireAdmin();
        initialize();
        return administrativeEntries();
    }

    /** Перечень читает таблицу заново: добавленное или изменённое другим экземпляром видно сразу. */
    public List<Entry> administrativeEntries() {
        requireAdmin();
        ensureLoaded();
        var rows = repository.findAll();
        var keys = new java.util.HashSet<String>();
        List<Entry> current = new java.util.ArrayList<>(declaredEntries.values());
        for (var row : rows) {
            keys.add(row.key);
            current.add(freshEntry(row));
        }
        stored.keySet().retainAll(keys);
        return current.stream().sorted(Comparator.comparing(Entry::key)).toList();
    }

    /**
     * Проверяет определение так же, как перед сохранением, но ничего не записывает. Ключ при этом не
     * сверяется с занятыми: об этом скажет сохранение.
     */
    public Check check(QueryListDefinition definition) {
        requireAdmin();
        return inspect(Objects.requireNonNull(definition, "definition"));
    }

    /** Допуск проверяется под текущим пользователем при каждом открытии. */
    public Optional<Entry> findOpenable(String key) {
        if (key == null) return Optional.empty();
        Entry entry = openingEntry(key);
        if (entry == null || !entry.enabled() || entry.diagnostic() != null) return Optional.empty();
        try {
            compiler.requireReadAccess(entry.definition().query());
            return Optional.of(entry);
        } catch (IllegalArgumentException | org.hibernate.HibernateException | RlsAccessDeniedException denied) {
            return Optional.empty();
        }
    }

    public Optional<String> openingDiagnostic(String key) {
        if (key == null || !access.isAdministrator()) return Optional.empty();
        var entry = openingEntry(key);
        return entry != null && entry.enabled() ? Optional.ofNullable(entry.diagnostic()) : Optional.empty();
    }

    public Entry create(QueryListDefinition definition, boolean enabled) {
        requireAdmin();
        requireRegistered(definition);
        return write(() -> {
            if (repository.existsById(definition.key())) throw duplicate(definition.key());
            var row = new QueryListEntity();
            row.key = definition.key();
            try {
                return save(row, definition, enabled);
            } catch (DataIntegrityViolationException violation) {
                if (violation.getMostSpecificCause() instanceof java.sql.SQLException sql
                        && "23505".equals(sql.getSQLState())) throw duplicate(definition.key());
                throw violation;
            }
        });
    }

    public Entry update(QueryListDefinition definition, long expectedVersion, boolean enabled) {
        requireAdmin();
        requireRegistered(definition);
        return write(() -> save(requireVersion(definition.key(), expectedVersion), definition, enabled));
    }

    /**
     * Включает или выключает список, не пересматривая его определение: сломанный список можно
     * выключить, а выключенный — включить, чтобы он сам показал причину отказа.
     */
    public Entry setEnabled(String key, long expectedVersion, boolean enabled) {
        requireAdmin();
        return write(() -> {
            var row = requireVersion(key, expectedVersion);
            row.enabled = enabled;
            row.modifiedBy = users.requireAuthenticatedUsername();
            row.modifiedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            return entry(repository.saveAndFlush(row));
        });
    }

    /** Когда последний раз проверялись определения: при запуске приложения, по «Проверить все» и после записи. */
    public Instant checkedAt() {
        return checkedAt;
    }

    public void delete(String key, long expectedVersion) {
        requireAdmin();
        write(() -> {
            repository.delete(requireVersion(key, expectedVersion));
            repository.flush();
            stored.remove(key);
            return null;
        });
    }

    private Entry save(QueryListEntity row, QueryListDefinition definition, boolean enabled) {
        row.title = definition.title();
        row.body = codec.encode(definition);
        row.enabled = enabled;
        row.modifiedBy = users.requireAuthenticatedUsername();
        row.modifiedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var saved = repository.saveAndFlush(row);
        return entry(saved);
    }

    private QueryListEntity requireVersion(String key, long expectedVersion) {
        var row = repository.findById(key).orElseThrow(() -> new IllegalArgumentException("Список не найден: " + key));
        if (row.version != expectedVersion) {
            throw new org.springframework.dao.OptimisticLockingFailureException("Список изменён: " + key);
        }
        return row;
    }

    private void requireRegistered(QueryListDefinition definition) {
        if (definition.provenance() != QueryProvenance.REGISTERED) {
            throw new IllegalArgumentException("В БД сохраняются определения REGISTERED");
        }
        if (declarations.containsKey(definition.key())) throw duplicate(definition.key());
        var diagnostic = inspect(definition).diagnostic();
        if (diagnostic != null) throw new IllegalArgumentException(diagnostic);
    }

    /**
     * Запись идёт под замком записи; кэш обновляется только записанным списком, остальные не
     * перепроверяются. Читатели замок не берут.
     */
    private <T> T write(Supplier<T> operation) {
        synchronized (writeLock) {
            ensureLoaded();
            T result = writeTransaction.execute(status -> operation.get());
            if (result instanceof Entry written) remember(written);
            return result;
        }
    }

    private void ensureLoaded() {
        if (loaded) return;
        synchronized (writeLock) {
            if (!loaded) reload();
        }
    }

    /** Кэш хранит более новую версию: запоздавший читатель не затирает свежую запись. */
    private Entry remember(Entry entry) {
        return stored.merge(entry.key(), entry, (old, current) ->
                old.version() != null && current.version() != null && old.version() > current.version() ? old : current);
    }

    private Entry freshEntry(QueryListEntity row) {
        var cached = stored.get(row.key);
        if (cached != null && Objects.equals(cached.version(), row.version)
                && Objects.equals(cached.modifiedAt(), row.modifiedAt)) {
            return cached;
        }
        return remember(entry(row));
    }

    private Map<String, Entry> loadStored() {
        Map<String, Entry> snapshot = new LinkedHashMap<>();
        for (var row : repository.findAll()) snapshot.put(row.key, entry(row));
        return snapshot;
    }

    private Entry openingEntry(String key) {
        ensureLoaded();
        var declared = declaredEntries.get(key);
        if (declared != null) return declared;

        // Another instance can disable or replace a list after this snapshot was loaded.
        var row = repository.findById(key).orElse(null);
        if (row == null) {
            stored.remove(key);
            return null;
        }
        return freshEntry(row);
    }

    private Entry entry(QueryListEntity row) {
        QueryListDefinition definition = null;
        String diagnostic;
        List<String> entities = List.of();
        try {
            definition = codec.decode(row.key, row.title, row.body);
            if (declarations.containsKey(row.key)) {
                diagnostic = "Ключ уже объявлен в коде: " + row.key;
            } else if (definition.provenance() != QueryProvenance.REGISTERED) {
                diagnostic = "Запись в БД требует REGISTERED";
            } else {
                var check = inspect(definition);
                diagnostic = check.diagnostic();
                entities = check.entities();
            }
        } catch (IllegalArgumentException invalid) {
            diagnostic = invalid.getMessage();
        }
        return new Entry(row.key, row.title, definition, row.enabled, row.version, row.modifiedBy, row.modifiedAt,
                diagnostic, entities);
    }

    private Check inspect(QueryListDefinition definition) {
        try {
            var inspection = compiler.inspect(definition.query());
            var columns = inspection.columns();
            var object = definition.commandObject();
            String diagnostic = null;
            if (object != null && columns.stream().noneMatch(column -> column.key().equals(object.column())
                    && column.referenceType() != null && column.referenceType().getName().equals(object.entityClass()))) {
                diagnostic = "Колонка объекта команды должна содержать идентификатор сущности: " + object.column();
            }
            var rowKey = columns.stream().filter(column -> inspection.rowKey().contains(column.key()))
                    .map(org.ipro.filtergrid.projection.ColumnMeta::header).toList();
            return new Check(diagnostic, inspection.entities(),
                    columns.stream().map(org.ipro.filtergrid.projection.ColumnMeta::key).toList(), rowKey);
        } catch (IllegalArgumentException | org.hibernate.HibernateException invalid) {
            return new Check(invalid.getMessage() == null ? "Запрос списка не прошёл проверку"
                    : invalid.getMessage(), List.of(), List.of(), List.of());
        }
    }

    private void requireAdmin() {
        if (!access.isAdministrator()) throw new RlsAccessDeniedException("Требуется право администрирования списков");
    }

    private static IllegalArgumentException duplicate(String key) {
        return new IllegalArgumentException("Ключ списка уже занят: " + key);
    }
}
