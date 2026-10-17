package org.ip.application.access;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.ip.application.catalog.NamedOperationOwner;
import org.ip.application.catalog.NamedOperationSource;
import org.ip.model.Role;
import org.ip.model.User;
import org.ip.application.access.PermissionBinding.Authority;
import org.ip.application.access.PermissionBinding.Flag;
import org.ip.application.access.PermissionCatalog.Diagnostic;
import org.ip.application.access.PermissionObjectRow.Column;
import org.ip.application.access.PermissionObjectRow.Kind;
import org.ipro.data.DataOperation;
import org.ipro.data.EntityDescriptor;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.EntityExposure;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.SubsystemNode;
import org.ipro.metadata.SubsystemRegistry;
import org.ipro.metadata.annotation.Subsystem;
import org.ipro.rest.catalog.RestReadOperation;
import org.ipro.rest.catalog.RestResourceCatalog;
import org.ipro.rls.RlsDimensionKind;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.c5.C5Operation;
import org.ipro.rls.c5.C5OperationPermissionRegistry;
import org.ipro.rls.c5.C5PermissionKeys;
import org.ipro.rls.c5.C5ReadPermissionRegistry;
import org.ipro.settings.SettingsRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/** Administrative metadata projection. It neither issues grants nor duplicates permission decisions. */
@Service
@PreAuthorize("hasRole('ADMIN')")
public class PermissionCatalogService {
    private final EntityDescriptorCatalog descriptors;
    private final MetadataResolver metadata;
    private final SubsystemRegistry subsystems;
    private final RlsDimensionRegistry dimensions;
    private final C5ReadPermissionRegistry reads;
    private final C5OperationPermissionRegistry operations;
    private final SettingsRegistry settings;
    /** Названия операций: одиночные владельцы плюс источники, объявляющие несколько (S5(а)). */
    private final List<NamedOperationOwner> actionOwners;
    private final List<LegacyPermissionDefinition> legacyOwners;
    private final ObjectProvider<RestResourceCatalog> rest;
    @PersistenceContext private EntityManager entityManager;

    public PermissionCatalogService(EntityDescriptorCatalog descriptors, MetadataResolver metadata,
                                    SubsystemRegistry subsystems, RlsDimensionRegistry dimensions,
                                    C5ReadPermissionRegistry reads, C5OperationPermissionRegistry operations,
                                    SettingsRegistry settings, List<NamedOperationOwner> actionOwners,
                                    List<NamedOperationSource> actionSources,
                                    List<LegacyPermissionDefinition> legacyOwners, ObjectProvider<RestResourceCatalog> rest) {
        this.descriptors = descriptors;
        this.metadata = metadata;
        this.subsystems = subsystems;
        this.dimensions = dimensions;
        this.reads = reads;
        this.operations = operations;
        this.settings = settings;
        this.actionOwners = NamedOperationSource.merge(actionOwners, actionSources);
        this.legacyOwners = List.copyOf(legacyOwners);
        this.rest = rest;
    }

    /** No database reads: rebuild from current registries, so later registered definitions are visible. */
    public PermissionCatalog catalog() {
        return new Builder().build();
    }

    /** One distinct-key projection; full grants, values and subjects are not loaded here. */
    public List<Diagnostic> storedKeyDiagnostics() {
        List<String> keys = entityManager.createQuery("select distinct g.dimension from AccessGrant g", String.class)
            .getResultList();
        return diagnosticsForStoredKeys(keys);
    }

    public List<Diagnostic> diagnosticsForStoredKeys(Collection<String> keys) {
        PermissionCatalog catalog = catalog();
        Set<String> covered = new HashSet<>(catalog.representedKeys());
        covered.addAll(catalog.diagnosticKeys());
        List<Diagnostic> result = new ArrayList<>(catalog.diagnostics());
        for (String key : new TreeSet<>(keys)) {
            if (key.startsWith("ATTR:")) {
                result.add(new Diagnostic("OBSOLETE_FIELD_GRANT", key, null, "Устаревшее право поля не участвует в допуске"));
            } else if (key.equals("*")) {
                result.add(new Diagnostic("RLS_WILDCARD", key, null, "Общий RLS-источник; не является правом C5"));
            } else if (!covered.contains(key)) {
                result.add(new Diagnostic("UNREGISTERED_STORED_KEY", key, null, "Грант не имеет зарегистрированного определения; требуется диагностика"));
            }
        }
        return List.copyOf(result);
    }

    private final class Builder {
        private final Map<String, GroupBuilder> groups = new LinkedHashMap<>();
        private final List<Diagnostic> diagnostics = new ArrayList<>();
        private final Set<String> handled = new HashSet<>();
        private final Map<Class<?>, EntityDescriptor> types = new HashMap<>();
        private final Map<Class<?>, EntityMetadataInfo> meta = new HashMap<>();

        PermissionCatalog build() {
            subsystems.getRoots().forEach(this::subsystem);
            descriptors.all().forEach(descriptor -> types.put(descriptor.type(), descriptor));
            descriptors.all().stream().sorted(Comparator.comparing(descriptor -> descriptor.type().getName()))
                .forEach(this::entity);
            apiResources();
            remainingC5Definitions();
            settingsOwners();
            legacyOwners();
            for (String key : new TreeSet<>(dimensions.dimensions())) {
                if (!handled.contains(key) && dimensions.kindOf(key) == RlsDimensionKind.CHECK_ONLY) {
                    diagnose("CHECK_WITHOUT_OWNER", key, null,
                        "Нет подтверждённого владельца проверки; доступен технический диагностический путь");
                }
            }
            List<PermissionCatalog.Group> orderedGroups = new ArrayList<>();
            appendGroups(null, orderedGroups);
            List<PermissionObjectRow> rows = orderedGroups.stream().flatMap(group -> group.rows().stream()).toList();
            List<PermissionCatalog.Scope> scopes = new ArrayList<>();
            for (String key : new TreeSet<>(dimensions.grantValueDimensions())) {
                Class<?> source = dimensions.grantValueType(key);
                EntityDescriptor sourceDescriptor = types.get(source);
                if (dimensions.kindOf(key) != RlsDimensionKind.FILTERABLE || sourceDescriptor == null || !sourceDescriptor.jpaManaged()) {
                    diagnose("INVALID_SCOPE_SOURCE", key, null, "Источник значений должен быть зарегистрированным FILTERABLE JPA-типом");
                    continue;
                }
                List<String> affected = rows.stream().filter(row -> row.kind() == Kind.ENTITY && row.scopeIds().contains(key))
                    .map(PermissionObjectRow::id).sorted().toList();
                scopes.add(new PermissionCatalog.Scope(key, title(source), source,
                    columns(key, Authority.RLS, Set.of(C5Operation.READ, C5Operation.CREATE, C5Operation.UPDATE, C5Operation.DELETE)), affected));
                handled.add(key);
            }
            for (var row : rows) for (String key : row.scopeIds()) {
                if (!dimensions.grantValueDimensions().contains(key)) {
                    diagnose("MISSING_SCOPE_SOURCE", key, row.id(), "Ограничение применяется к объекту, но источник значений не зарегистрирован");
                }
            }
            diagnostics.sort(Comparator.comparing(Diagnostic::code).thenComparing(diagnostic -> diagnostic.key() == null ? "" : diagnostic.key()));
            return new PermissionCatalog(orderedGroups, scopes, diagnostics);
        }

        private void subsystem(SubsystemNode node) {
            String id = subsystemId(node.getMarkerClass());
            group(id, node.getTitle(), node.getParent() == null ? null : subsystemId(node.getParent().getMarkerClass()),
                node.getMarkerClass(), node.getOrder());
            node.getChildren().forEach(this::subsystem);
        }

        private EntityMetadataInfo info(Class<?> type) {
            EntityDescriptor descriptor = types.get(type);
            if (descriptor == null || !descriptor.metadataDriven()) return null;
            return meta.computeIfAbsent(type, metadata::resolve);
        }
        private String title(Class<?> type) {
            var info = info(type);
            return info == null || info.getListFormTitle() == null || info.getListFormTitle().isBlank()
                ? type.getSimpleName() : info.getListFormTitle();
        }
        private String entityGroup(Class<?> type) {
            var info = info(type);
            Class<?> marker = info == null ? Subsystem.NoSubsystem.class : info.getAnnotation().subsystem();
            if (marker != Subsystem.NoSubsystem.class) {
                String id = subsystemId(marker);
                if (!groups.containsKey(id)) throw new IllegalStateException("Unknown subsystem marker " + marker.getName());
                return id;
            }
            boolean administration = type == User.class || type == Role.class;
            String id = administration ? "aux:administration" : "aux:system";
            group(id, administration ? "Администрирование" : "Системные объекты", null, null, administration ? 900 : 910);
            return id;
        }

        private void entity(EntityDescriptor descriptor) {
            Class<?> type = descriptor.type();
            String readKey = C5PermissionKeys.entityRead(type);
            String operationKey = C5PermissionKeys.entityOperations(type);
            String rowId = PermissionObjectRow.entityId(type);
            if (descriptor.exposure() != EntityExposure.STANDARD_ROOT) {
                if (reads.isRegisteredReadKey(readKey)) diagnose("NON_ROOT_ENTITY_DEFINITION", readKey, null, "Внутренний тип/строка агрегата не имеет самостоятельной строки выдачи CRUD");
                if (operations.isRegistered(operationKey)) diagnose("NON_ROOT_ENTITY_DEFINITION", operationKey, null, "Внутренний тип/строка агрегата не имеет самостоятельной строки выдачи CRUD");
                return;
            }
            List<Column> columns = new ArrayList<>();
            boolean hasRead = !descriptor.capabilities().readScenarios().isEmpty();
            if (hasRead && reads.isRegisteredReadKey(readKey)) {
                columns.add(column(readKey, Authority.C5, Flag.READ, Set.of(C5Operation.READ), "Чтение"));
                handled.add(readKey);
            } else if (hasRead || reads.isRegisteredReadKey(readKey)) {
                diagnose("READ_CAPABILITY_DEFINITION_MISMATCH", readKey, rowId, "Capability чтения и реестр C5 не согласованы");
            }
            Set<C5Operation> expected = EnumSet.noneOf(C5Operation.class);
            for (DataOperation operation : descriptor.capabilities().writes()) expected.add(C5Operation.valueOf(operation.name()));
            Set<C5Operation> declared = operations.operationsOf(operationKey);
            Set<C5Operation> declaredWrites = EnumSet.noneOf(C5Operation.class);
            declaredWrites.addAll(declared);
            declaredWrites.remove(C5Operation.READ);
            if (!expected.equals(declaredWrites)) diagnose("WRITE_CAPABILITY_DEFINITION_MISMATCH", operationKey, rowId, "Capability записи и определение C5 не согласованы");
            Set<C5Operation> supported = EnumSet.noneOf(C5Operation.class);
            supported.addAll(expected);
            supported.retainAll(declared);
            columns.addAll(columns(operationKey, Authority.C5, supported));
            if (operations.isRegistered(operationKey)) handled.add(operationKey);
            var info = info(type);
            add(new PermissionObjectRow(rowId, Kind.ENTITY, title(type), type, entityGroup(type),
                info == null ? Integer.MAX_VALUE : info.getOrder(), columns,
                dimensions.policyOf(type).filterableDimensions().stream().sorted().toList()));
        }

        private void apiResources() {
            RestResourceCatalog catalog = rest.getIfAvailable();
            if (catalog == null) return;
            for (var resource : catalog.resources()) {
                Class<?> type = resource.definition().resourceType();
                for (RestReadOperation operation : RestReadOperation.values()) {
                    // Construct from a validated declaration, using the registrar's documented key contract.
                    String key = String.format(Locale.ROOT, "REST:%s:v%d:%s", resource.key().resource(), resource.key().major(), operation);
                    if (!reads.isRegisteredReadKey(key)) continue;
                    String caption = title(type) + " — API v" + resource.key().major() + ": "
                        + (operation == RestReadOperation.LIST ? "список" : "карточка");
                    add(new PermissionObjectRow("api:" + key, Kind.API, caption, type, entityGroup(type), 800,
                        List.of(column(key, Authority.C5, Flag.READ, Set.of(C5Operation.READ), "Чтение")),
                        dimensions.policyOf(type).filterableDimensions().stream().sorted().toList()));
                    handled.add(key);
                }
            }
        }

        private void remainingC5Definitions() {
            Map<String, String> titles = new HashMap<>();
            for (var owner : actionOwners) {
                String key = C5PermissionKeys.action(owner.permissionId());
                String previous = titles.putIfAbsent(key, owner.title());
                if (previous != null && !previous.equals(owner.title())) throw new IllegalStateException("Conflicting action titles for " + key);
            }
            for (var definition : operations.definitions()) {
                String key = definition.key();
                if (handled.contains(key)) continue;
                if (key.startsWith("ATTR:")) { diagnose("OBSOLETE_FIELD_DEFINITION", key, null, "Права полей отменены"); continue; }
                boolean named = definition.operations().equals(Set.of(C5Operation.EXECUTE));
                boolean titled = named && titles.containsKey(key);
                String groupId = titled ? "aux:actions" : "aux:technical";
                group(groupId, titled ? "Действия" : "Дополнительные определения", null, null, titled ? 920 : 990);
                Set<C5Operation> operative = EnumSet.noneOf(C5Operation.class);
                operative.addAll(definition.operations());
                operative.remove(C5Operation.READ);
                List<Column> definitionColumns = new ArrayList<>();
                if (reads.isRegisteredReadKey(key)) definitionColumns.add(
                    column(key, Authority.C5, Flag.READ, Set.of(C5Operation.READ), "Чтение"));
                definitionColumns.addAll(columns(key, Authority.C5, operative));
                if (!definitionColumns.isEmpty()) {
                    add(new PermissionObjectRow("definition:" + key, titled ? Kind.ACTION : Kind.TECHNICAL,
                        titled ? titles.get(key) : key, null, groupId, 0, definitionColumns, List.of()));
                    handled.add(key);
                    if (named && !titled) diagnose("MISSING_ACTION_TITLE", key, "definition:" + key, "Нет пользовательского названия владельца действия");
                }
            }
            for (String key : new TreeSet<>(reads.registeredKeys())) {
                if (handled.contains(key)) continue;
                if (key.startsWith("ATTR:")) { diagnose("OBSOLETE_FIELD_DEFINITION", key, null, "Права полей отменены"); continue; }
                group("aux:technical", "Дополнительные определения", null, null, 990);
                add(new PermissionObjectRow("read-definition:" + key, Kind.TECHNICAL, key, null, "aux:technical", 0,
                    List.of(column(key, Authority.C5, Flag.READ, Set.of(C5Operation.READ), "Чтение")), List.of()));
                handled.add(key);
            }
        }

        private void settingsOwners() {
            Map<String, List<SettingsRegistry.GroupInfo>> owners = new LinkedHashMap<>();
            settings.groups().forEach(owner -> owners.computeIfAbsent(owner.rlsDimension(), ignored -> new ArrayList<>()).add(owner));
            for (var entry : owners.entrySet()) {
                String key = entry.getKey();
                var owner = entry.getValue().getFirst();
                if (entry.getValue().stream().anyMatch(other -> other.subsystemMarker() != owner.subsystemMarker())) {
                    throw new IllegalStateException("Settings key belongs to multiple subsystems: " + key);
                }
                String groupId = subsystemId(owner.subsystemMarker());
                if (!groups.containsKey(groupId)) throw new IllegalStateException("Unknown settings subsystem " + groupId);
                if (!legacyAvailable(key)) continue;
                add(new PermissionObjectRow("settings:" + key, Kind.SETTINGS, "Настройки — " + groups.get(groupId).title,
                    null, groupId, 900, List.of(column(key, Authority.RLS, Flag.UPDATE, Set.of(C5Operation.UPDATE), "Изменение настроек")), List.of()));
                handled.add(key);
            }
        }

        private void legacyOwners() {
            for (var owner : legacyOwners) {
                if (handled.contains(owner.key())) throw new IllegalStateException("Conflicting permission owner " + owner.key());
                if (!legacyAvailable(owner.key())) continue;
                group(owner.groupId(), owner.groupTitle(), null, null, 950);
                add(new PermissionObjectRow("legacy:" + owner.key(), Kind.LEGACY, owner.title(), null,
                    owner.groupId(), 0, owner.columns(), List.of()));
                handled.add(owner.key());
            }
        }
        private boolean legacyAvailable(String key) {
            if (reads.isRegisteredReadKey(key) || operations.isRegistered(key)) {
                throw new IllegalStateException("Legacy and C5 owners conflict for " + key);
            }
            if (!dimensions.dimensions().contains(key) || dimensions.kindOf(key) != RlsDimensionKind.CHECK_ONLY) {
                diagnose("UNREGISTERED_LEGACY_OWNER", key, null, "Владелец объявлен, но CHECK_ONLY-определение не зарегистрировано");
                return false;
            }
            return true;
        }

        private void add(PermissionObjectRow row) { groups.get(row.groupId()).rows.add(row); }
        private void diagnose(String code, String key, String rowId, String message) {
            diagnostics.add(new Diagnostic(code, key, rowId, message));
            handled.add(key);
        }
        private void group(String id, String title, String parent, Class<?> marker, int order) {
            var existing = groups.putIfAbsent(id, new GroupBuilder(id, title, parent, marker, order));
            if (existing != null && (!existing.title.equals(title) || !java.util.Objects.equals(existing.parent, parent))) {
                throw new IllegalStateException("Conflicting group metadata " + id);
            }
        }
        private void appendGroups(String parent, List<PermissionCatalog.Group> output) {
            groups.values().stream().filter(group -> java.util.Objects.equals(group.parent, parent))
                .sorted(Comparator.comparingInt((GroupBuilder group) -> group.order).thenComparing(group -> group.id))
                .forEach(group -> {
                    group.rows.sort(Comparator.comparingInt(PermissionObjectRow::order).thenComparing(PermissionObjectRow::title).thenComparing(PermissionObjectRow::id));
                    output.add(new PermissionCatalog.Group(group.id, group.title, group.parent, group.marker, group.order, group.rows));
                    appendGroups(group.id, output);
                });
        }
    }

    public static String subsystemId(Class<?> marker) { return "subsystem:" + marker.getName(); }

    private static Column column(String key, Authority authority, Flag flag, Set<C5Operation> operations, String title) {
        return new Column(title, new PermissionBinding(key, authority, flag, operations));
    }
    private static List<Column> columns(String key, Authority authority, Set<C5Operation> operations) {
        List<Column> result = new ArrayList<>();
        if (operations.contains(C5Operation.READ)) result.add(column(key, authority, Flag.READ, Set.of(C5Operation.READ), "Чтение"));
        Set<C5Operation> write = EnumSet.noneOf(C5Operation.class);
        for (C5Operation operation : List.of(C5Operation.CREATE, C5Operation.UPDATE, C5Operation.EXECUTE)) {
            if (operations.contains(operation)) write.add(operation);
        }
        if (!write.isEmpty()) {
            List<String> labels = new ArrayList<>();
            if (write.contains(C5Operation.CREATE) && write.contains(C5Operation.UPDATE)) labels.add("Создание и изменение");
            else if (write.contains(C5Operation.CREATE)) labels.add("Создание");
            else if (write.contains(C5Operation.UPDATE)) labels.add("Изменение");
            if (write.contains(C5Operation.EXECUTE)) labels.add("Выполнение");
            result.add(column(key, authority, Flag.UPDATE, write, String.join(" / ", labels)));
        }
        if (operations.contains(C5Operation.DELETE)) result.add(column(key, authority, Flag.DELETE, Set.of(C5Operation.DELETE), "Удаление"));
        return List.copyOf(result);
    }

    private static final class GroupBuilder {
        final String id;
        final String title;
        final String parent;
        final Class<?> marker;
        final int order;
        final List<PermissionObjectRow> rows = new ArrayList<>();
        GroupBuilder(String id, String title, String parent, Class<?> marker, int order) {
            this.id = id; this.title = title; this.parent = parent; this.marker = marker; this.order = order;
        }
    }
}
