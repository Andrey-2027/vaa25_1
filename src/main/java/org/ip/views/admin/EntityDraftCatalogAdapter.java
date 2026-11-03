package org.ip.views.admin;

import jakarta.persistence.*;
import org.ipro.design.ProjectDesignContext;
import org.ipro.design.RuntimeDesignSnapshot;
import org.ipro.design.ReferenceTargetDescriptor;
import org.ipro.design.ReferenceTargetDescriptor.Eligibility;
import org.ipro.design.ReferenceTargetDescriptor.Fact;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.form.registry.FormType;
import org.ipro.vaadin.explorer.ExplorerSnapshot;
import java.util.*;

/** Configuration projection of the existing inventory. Never resolves a draft FQN to a Class. */
final class EntityDraftCatalogAdapter {
    DraftProjectCatalog adapt(ExplorerTreeModel.CatalogView snapshot, Set<Class<?>> managed,
            Map<String,String> subsystems, String project, String defaultPackage, RuntimeDesignSnapshot runtime) {
        var catalog=adapt(snapshot,managed,subsystems,project,defaultPackage);
        var context = catalog.context();
        var targets = new TreeMap<>(context.referenceTargets());
        runtime.types().values().stream().filter(f -> "INFORMATION_REGISTER".equals(f.kind())).forEach(f -> {
            var old = targets.get(f.typeId());
            if (old != null) targets.put(f.typeId(), new ReferenceTargetDescriptor(old.typeId(), old.caption(), old.exposure(),
                old.identityType(), old.representation(), old.selection(), Eligibility.UNSUPPORTED,
                "Регистр не может быть целью ссылки в этом профиле", old.sourceTypeName()));
        });
        return new DraftProjectCatalog(new ProjectDesignContext(context.projectId(), context.version(), context.defaultPackage(),
            context.existingTypes(), context.existingTables(), context.subsystemIds(), context.iconIds(), targets,
            context.referencesComplete(), runtime), catalog.subsystems());
    }
    DraftProjectCatalog adapt(ExplorerTreeModel.CatalogView snapshot, Set<Class<?>> managed,
                              Map<String, String> subsystems, String project, String defaultPackage) {
        Set<String> names = new TreeSet<>(), tables = new TreeSet<>();
        Map<String, ReferenceTargetDescriptor> targets = new TreeMap<>();
        Set<Class<?>> known = new LinkedHashSet<>(managed);
        snapshot.entries().forEach(e -> { known.add(e.type()); e.ownedSections().forEach(s -> known.add(s.rowType())); });
        boolean storageKnown = true;
        for (Class<?> type : known) {
            names.add(type.getName());
            Table table = type.getAnnotation(Table.class);
            if (table == null || table.name().isBlank()) storageKnown = false;
            else tables.add(table.name());
            for (SecondaryTable secondary : type.getAnnotationsByType(SecondaryTable.class)) tables.add(secondary.name());
            // Explicit auxiliary storage declarations also reserve their names.
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (var field : c.getDeclaredFields()) {
                    JoinTable join = field.getAnnotation(JoinTable.class);
                    CollectionTable collection = field.getAnnotation(CollectionTable.class);
                    if (join != null) { if (join.name().isBlank()) storageKnown = false; else tables.add(join.name()); }
                    if (collection != null) { if (collection.name().isBlank()) storageKnown = false; else tables.add(collection.name()); }
                }
            }
        }
        for (var entry : snapshot.entries()) {
            var descriptor = entry.descriptor(); var summary = snapshot.summaryOf(entry.type()).orElse(null);
            String exposure = descriptor == null ? "UNKNOWN" : descriptor.exposure().name();
            String identity = identity(entry.type());
            Fact representation = summary != null && summary.structure().available() && summary.structure().presentation() != null ? Fact.YES : Fact.UNKNOWN;
            Fact selection = descriptor == null || summary == null ? Fact.UNKNOWN :
                descriptor.capabilities().allows(FetchScenario.LOOKUP) && summary.forms().stream().anyMatch(f -> f.formType() == FormType.SELECTION && (f.variant() == null || f.variant().isEmpty() || f.variant().equals("default"))) ? Fact.YES : Fact.NO;
            Eligibility eligible; String reason;
            if (descriptor == null || entry.state() != ExplorerSnapshot.EntryState.READY) { eligible = Eligibility.UNKNOWN; reason = "Факты типа не подтверждены"; }
            else if (!exposure.equals("STANDARD_ROOT") || !descriptor.jpaManaged()) { eligible = Eligibility.UNSUPPORTED; reason = "Тип не является самостоятельным root"; }
            else if (!identity.equals("java.lang.Long")) { eligible = identity.isEmpty() ? Eligibility.UNKNOWN : Eligibility.UNSUPPORTED; reason = "Не подтверждена совместимая Long-идентичность"; }
            else if (representation == Fact.UNKNOWN || selection == Fact.UNKNOWN) { eligible = Eligibility.UNKNOWN; reason = "Представление или выбор не подтверждены"; }
            else if (selection == Fact.NO) { eligible = Eligibility.UNSUPPORTED; reason = "Выбор по умолчанию не поддержан"; }
            else { eligible = Eligibility.SUPPORTED; reason = "Самостоятельный тип, Long-ID, представление и выбор подтверждены"; }
            targets.put(entry.typeFqn(), new ReferenceTargetDescriptor(entry.typeFqn(), entry.displayName().value(), exposure, identity, representation, selection, eligible, reason,
                    Objects.requireNonNullElse(entry.type().getCanonicalName(), "")));
        }
        for (Class<?> type : known) targets.putIfAbsent(type.getName(), new ReferenceTargetDescriptor(type.getName(), type.getSimpleName(),
            "UNKNOWN", identity(type), Fact.UNKNOWN, Fact.UNKNOWN, Eligibility.UNKNOWN, "Тип отсутствует в подтверждённом metadata-каталоге"));
        var icons = new TreeSet<>(Arrays.stream(com.vaadin.flow.component.icon.VaadinIcon.values()).map(Enum::name).toList());
        // Equal configuration yields equal context; checked results also compare all facts, not just this version label.
        String version = "catalog-v1:" + Integer.toHexString(Objects.hash(names, tables, targets, subsystems, defaultPackage, storageKnown));
        var context = new ProjectDesignContext(project, version, defaultPackage.isBlank() ? null : defaultPackage,
            ProjectDesignContext.Names.known(names), new ProjectDesignContext.Names(tables, storageKnown, storageKnown ? "" : "Есть типы с неявным SQL-именем"),
            ProjectDesignContext.Names.known(subsystems.keySet()), ProjectDesignContext.Names.known(icons), targets, true);
        return new DraftProjectCatalog(context, subsystems);
    }
    private String identity(Class<?> type) {
        var ids = new ArrayList<Class<?>>(); boolean composite = false;
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            composite |= c.isAnnotationPresent(IdClass.class);
            for (var field : c.getDeclaredFields()) {
                if (field.isAnnotationPresent(Id.class)) ids.add(field.getType());
                if (field.isAnnotationPresent(EmbeddedId.class)) composite = true;
            }
            for (var method : c.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Id.class)) ids.add(method.getReturnType());
                if (method.isAnnotationPresent(EmbeddedId.class)) composite = true;
            }
        }
        return composite || ids.size() > 1 ? "COMPOSITE" : ids.isEmpty() ? "" : ids.getFirst().getName();
    }
}
