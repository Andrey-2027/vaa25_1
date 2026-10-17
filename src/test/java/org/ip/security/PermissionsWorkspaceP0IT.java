package org.ip.security;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.data.provider.DataProvider;
import com.vaadin.flow.data.provider.Query;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.ip.model.AttributeValue;
import org.ip.model.Branch;
import org.ip.model.Journal;
import org.ip.model.PrdSpec;
import org.ip.model.ReceivingDocument;
import org.ip.model.Role;
import org.ip.model.SklNomOpa;
import org.ip.model.User;
import org.ip.model.Workshop;
import org.ip.application.catalog.NamedOperationOwner;
import org.ipro.data.DataOperation;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.EntityDataAccess;
import org.ipro.filtergrid.TextFilter;
import org.ipro.filtergrid.inmemory.InMemoryFilterGrid;
import org.ipro.filtergrid.jpa.JpaFilterGrid;
import org.ipro.form.builtin.ListForm;
import org.ipro.form.coordinator.FormCoordinator;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.SubsystemNode;
import org.ipro.metadata.SubsystemRegistry;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.Subsystem;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsTestFixture;
import org.ipro.rls.RlsDimensionKind;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.c5.C5Operation;
import org.ipro.rls.c5.C5OperationPermissionRegistry;
import org.ipro.rls.c5.C5PermissionDecisionService;
import org.ipro.rls.c5.C5PermissionKeys;
import org.ipro.rls.c5.C5ReadPermissionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P0 compatibility spike and registry snapshot. Every write uses isolated H2, never local PostgreSQL. */
@SpringBootTest(properties = {
    "spring.config.location=classpath:/application.properties",
    "spring.datasource.url=jdbc:h2:mem:permissions_p0;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.demo-data.enabled=false", "app.security-baseline.enabled=false",
    "platform.rest.read.enabled=true", "platform.rest.web.enabled=true"
})
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class PermissionsWorkspaceP0IT {
    @Autowired EntityDescriptorCatalog descriptors;
    @Autowired RlsDimensionRegistry dimensions;
    @Autowired C5OperationPermissionRegistry operations;
    @Autowired C5ReadPermissionRegistry reads;
    @Autowired C5PermissionDecisionService decisions;
    @Autowired DataSource dataSource;
    @Autowired EntityDataAccess canonical;
    @Autowired AccessGrantRepository grants;
    @Autowired SubsystemRegistry subsystems;
    @Autowired List<NamedOperationOwner> actionOwners;
    @Autowired List<org.ip.application.catalog.NamedOperationSource> actionSources;
    @PersistenceContext EntityManager entityManager;
    private UI previousUi;

    @BeforeEach
    void createUi() {
        previousUi = UI.getCurrent();
        UI.setCurrent(new UI());
    }

    @AfterEach
    void restoreUi() {
        UI.setCurrent(previousUi);
    }

    @Test
    void exportActualRegistryInventoryAndCapabilities() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL())
                .as("this spike must never use the local application database").startsWith("jdbc:h2:");
        }
        assertThat(descriptors.descriptorOf(AttributeValue.class).capabilities().writes())
            .containsExactly(DataOperation.CREATE);
        assertThat(descriptors.descriptorOf(SklNomOpa.class).capabilities().writes()).isEmpty();
        assertThat(reads.isRegisteredReadKey(C5PermissionKeys.entityRead(User.class))).isTrue();
        assertThat(reads.registeredKeys()).contains("REST:nomenclature:v1:LIST", "REST:nomenclature:v1:DETAIL",
            "REST:specifications:v1:LIST", "REST:specifications:v1:DETAIL");
        assertThat(operations.operationsOf(C5PermissionKeys.entityOperations(User.class)))
            .containsExactlyInAnyOrder(C5Operation.READ, C5Operation.CREATE, C5Operation.UPDATE, C5Operation.DELETE);

        Path output = Path.of(System.getProperty("permissions.p0.output", "outputs/permissions-p0"));
        Files.createDirectories(output);
        List<String> objects = new ArrayList<>();
        objects.add("type\ttitle\tsubsystem\texposure\tread_registered\toperations\trls_dimensions");
        for (var descriptor : descriptors.all()) {
            Class<?> type = descriptor.type();
            EntityMetadata metadata = type.getAnnotation(EntityMetadata.class);
            String marker = metadata == null || metadata.subsystem() == Subsystem.NoSubsystem.class
                ? "[no subsystem]" : metadata.subsystem().getName();
            objects.add(String.join("\t", type.getName(), metadata == null ? "" : metadata.listFormTitle(),
                marker, descriptor.exposure().name(),
                Boolean.toString(reads.isRegisteredReadKey(C5PermissionKeys.entityRead(type))),
                new TreeSet<>(operations.operationsOf(C5PermissionKeys.entityOperations(type))).toString(),
                new TreeSet<>(dimensions.dimensionsOf(type)).toString()));
        }
        Files.write(output.resolve("objects.tsv"), objects, StandardCharsets.UTF_8);
        Map<String, String> actionTitles = new HashMap<>();
        // Источники (регистровые подписи из платформы, ADR-0024 §2.4) идут в тот же снимок:
        // иначе выгрузка показывала бы безымянную операцию там, где название есть.
        org.ip.application.catalog.NamedOperationSource.merge(actionOwners, actionSources)
            .forEach(owner -> actionTitles.put(C5PermissionKeys.action(owner.permissionId()), owner.title()));
        List<String> definitions = new ArrayList<>(List.of("key\toperations\ttitle"));
        operations.definitions().forEach(definition -> definitions.add(definition.key() + "\t"
            + new TreeSet<>(definition.operations()) + "\t" + actionTitles.getOrDefault(definition.key(), "")));
        new TreeSet<>(reads.registeredKeys()).forEach(key -> definitions.add(key + "\t[READ]\t"));
        Files.write(output.resolve("definitions.tsv"), definitions, StandardCharsets.UTF_8);
        List<String> subsystemRows = new ArrayList<>(List.of("marker\tparent\ttitle\torder\town_entities"));
        subsystems.getRoots().forEach(node -> appendSubsystem(node, subsystemRows));
        Files.write(output.resolve("subsystems.tsv"), subsystemRows, StandardCharsets.UTF_8);
        List<String> scopes = new ArrayList<>(List.of("dimension\tkind\tvalue_source"));
        for (String dimension : new TreeSet<>(dimensions.dimensions())) {
            String source = dimensions.grantValueDimensions().contains(dimension)
                ? dimensions.grantValueType(dimension).getName() : "";
            scopes.add(dimension + "\t" + dimensions.kindOf(dimension) + "\t" + source);
        }
        Files.write(output.resolve("dimensions.tsv"), scopes, StandardCharsets.UTF_8);
    }

    private static void appendSubsystem(SubsystemNode node, List<String> rows) {
        rows.add(String.join("\t", node.getMarkerClass().getName(),
            node.getParent() == null ? "" : node.getParent().getMarkerClass().getName(), node.getTitle(),
            Integer.toString(node.getOrder()), Integer.toString(node.getOwnEntities().size())));
        node.getChildren().forEach(child -> appendSubsystem(child, rows));
    }

    @Test
    void embeddedStandardListsHaveIndependentSelectionEvenWithTheSameNumericId() {
        User user = new User();
        user.setId(7L);
        user.setUsername("p0-user");
        Role role = new Role("p0-role");
        role.setId(7L);
        var users = standardList(User.class, user, "username");
        var roles = standardList(Role.class, role, "name");
        FormCoordinator coordinator = mock(FormCoordinator.class);
        doReturn(users).when(coordinator).createListForm(User.class);
        doReturn(roles).when(coordinator).createListForm(Role.class);
        HorizontalLayout layout = embed(coordinator);
        AtomicReference<String> selectedSubject = new AtomicReference<>();
        users.getGrid().asSingleSelect().addValueChangeListener(event ->
            selectedSubject.set(event.getValue() == null ? null : "USER:" + event.getValue().getId()));
        roles.getGrid().asSingleSelect().addValueChangeListener(event ->
            selectedSubject.set(event.getValue() == null ? null : "ROLE:" + event.getValue().getId()));
        users.getGrid().select(user);
        assertThat(selectedSubject).hasValue("USER:7");
        assertThat(users.getSelectedItem()).isSameAs(user);
        users.getGrid().deselectAll();
        assertThat(selectedSubject.get()).isNull();
        assertThat(users.getSelectedItem()).isNull();
        roles.getGrid().select(role);
        assertThat(selectedSubject).hasValue("ROLE:7");
        assertThat(layout.getChildren()).containsExactly(users, roles);
        verify(coordinator).createListForm(User.class);
        verify(coordinator).createListForm(Role.class);
    }

    /** Compiles against the actual installed coordinator API; runtime selection uses real ListForm components. */
    private static HorizontalLayout embed(FormCoordinator coordinator) {
        ListForm<User, Long> users = coordinator.createListForm(User.class);
        ListForm<Role, Long> roles = coordinator.createListForm(Role.class);
        return new HorizontalLayout(users, roles);
    }

    private static <T extends org.ipro.identity.IdentifiableEntity<Long>> ListForm<T, Long> standardList(
            Class<T> type, T value, String column) {
        EntityMetadataInfo metadata = mock(EntityMetadataInfo.class);
        doReturn(type).when(metadata).getEntityClass();
        when(metadata.getListColumnPaths()).thenReturn(List.of(ColumnPath.resolve(type, column)));
        return new ListForm<>(metadata, new InMemoryFilterGrid<>(type, List.of(value)));
    }

    @Test
    void jpaGridCombinesTheBuiltinTextFilterWithTheAdditionalConditionForFetchAndCount() {
        Branch[] values = RlsTestFixture.callAsSuperuserWithRead(grants, new Class<?>[]{Branch.class},
            () -> new Branch[]{branch("P0-A"), branch("P0-B"), branch("OTHER-A")});
        Branch first = values[0];
        Branch second = values[1];
        Branch other = values[2];
        entityManager.flush();
        AtomicInteger calls = new AtomicInteger();
        JpaFilterGrid<Branch> grid = new JpaFilterGrid<>(Branch.class, (specification, pageable) -> {
            calls.incrementAndGet();
            return page(specification, pageable);
        });
        TextFilter<Branch> code = new TextFilter<>();
        grid.addColumnFilter("code", "Код", Branch::getCode, code);
        grid.build();
        code.getTextField().setValue("P0-");
        @SuppressWarnings("unchecked")
        DataProvider<Branch, Void> provider = (DataProvider<Branch, Void>) grid.getGrid().getDataProvider();
        assertThat(provider.size(new Query<>())).isEqualTo(2);
        assertThat(provider.fetch(new Query<>(1, 1, List.of(), null, null)).map(Branch::getCode))
            .containsExactly(second.getCode());
        grid.setAdditionalSpecification((root, query, cb) -> root.get("id").in(Set.of(first.getId(), other.getId())));
        assertThat(provider.size(new Query<>())).isEqualTo(1);
        assertThat(provider.fetch(new Query<>(0, 1, List.of(), null, null)).map(Branch::getId))
            .containsExactly(first.getId());
        assertThat(calls.get()).isGreaterThanOrEqualTo(4);
    }

    private Branch branch(String code) {
        Branch branch = new Branch();
        branch.setCode(code);
        branch.setName("P0 " + code);
        return canonical.create(Branch.class, branch);
    }

    private Page<Branch> page(Specification<Branch> specification, Pageable pageable) {
        var builder = entityManager.getCriteriaBuilder();
        var query = builder.createQuery(Branch.class);
        var root = query.from(Branch.class);
        query.select(root).where(specification.toPredicate(root, query, builder)).orderBy(builder.asc(root.get("code")));
        List<Branch> rows = entityManager.createQuery(query).setFirstResult((int) pageable.getOffset())
            .setMaxResults(pageable.getPageSize()).getResultList();
        var countQuery = builder.createQuery(Long.class);
        var countRoot = countQuery.from(Branch.class);
        countQuery.select(builder.count(countRoot)).where(specification.toPredicate(countRoot, countQuery, builder));
        long count = entityManager.createQuery(countQuery).getSingleResult();
        return new PageImpl<>(rows, pageable, count);
    }

    @Test
    void componentRightsColumnChangesOnlyDraftAndRoleButtonDoesNotToggleTheCheckbox() {
        Branch branch = new Branch();
        branch.setId(42L);
        branch.setCode("P0-INLINE");
        branch.setName("P0 inline");
        Map<Long, Boolean> draft = new HashMap<>();
        AtomicInteger explanations = new AtomicInteger();
        JpaFilterGrid<Branch> grid = new JpaFilterGrid<>(Branch.class,
            (specification, pageable) -> new PageImpl<>(List.of(branch), pageable, 1));
        var column = grid.addComponentColumn("read", "Чтение", row -> {
            Checkbox checkbox = new Checkbox();
            checkbox.addValueChangeListener(event -> draft.put(row.getId(), event.getValue()));
            Button sources = new Button("Р", event -> explanations.incrementAndGet());
            return new HorizontalLayout(checkbox, sources);
        });
        @SuppressWarnings("unchecked")
        var renderer = (ComponentRenderer<HorizontalLayout, Branch>) column.getRenderer();
        HorizontalLayout cell = renderer.createComponent(branch);
        Checkbox checkbox = (Checkbox) cell.getComponentAt(0);
        Button sources = (Button) cell.getComponentAt(1);
        checkbox.setValue(true);
        sources.click();
        assertThat(draft).containsEntry(42L, true);
        assertThat(checkbox.getValue()).isTrue();
        assertThat(explanations.get()).isEqualTo(1);
        assertThat(branch.getCode()).isEqualTo("P0-INLINE");
        assertThat(entityManager.createQuery("select count(g) from AccessGrant g", Long.class).getSingleResult()).isZero();
    }

    @Test
    void fullC5ExplanationIncludesAllAssignedRolesForExactlyTheCheckedOperation() {
        Role operator = new Role("P0_OPERATOR");
        Role observer = new Role("P0_OBSERVER");
        entityManager.persist(operator);
        entityManager.persist(observer);
        User user = new User("p0-sources", "p0-fixture-password");
        user.setRoles(Set.of(operator, observer));
        entityManager.persist(user);
        String read = C5PermissionKeys.entityRead(Branch.class);
        String write = C5PermissionKeys.entityOperations(Branch.class);
        grant(operator.getName(), read, true, false);
        grant(observer.getName(), read, true, false);
        grant(operator.getName(), write, false, true);
        entityManager.flush();
        assertThat(decisions.decideForSubject(AccessGrant.SubjectType.USER, user.getUsername(), read, C5Operation.READ).sources())
            .containsExactlyInAnyOrder("роль: P0_OPERATOR", "роль: P0_OBSERVER");
        for (C5Operation operation : List.of(C5Operation.CREATE, C5Operation.UPDATE)) {
            assertThat(decisions.decideForSubject(AccessGrant.SubjectType.USER, user.getUsername(), write, operation).sources())
                .containsExactly("роль: P0_OPERATOR");
        }
    }

    private void grant(String role, String key, boolean read, boolean update) {
        AccessGrant grant = grants.findBySubjectTypeAndSubjectKeyAndDimensionAndDimensionValueIdIsNull(
            AccessGrant.SubjectType.ROLE, role, key).stream().findFirst().orElseGet(AccessGrant::new);
        grant.setSubjectType(AccessGrant.SubjectType.ROLE);
        grant.setSubjectKey(role);
        grant.setDimension(key);
        grant.setCanRead(grant.isCanRead() || read);
        grant.setCanUpdate(grant.isCanUpdate() || update);
        grants.save(grant);
    }

    @Test
    void journalsAndBranchesAreSharedRestrictionsWithDeclaredCanonicalSources() {
        assertThat(dimensions.grantValueType("JOURNAL")).isEqualTo(Journal.class);
        assertThat(dimensions.grantValueType("BRANCH")).isEqualTo(Branch.class);
        assertThat(dimensions.kindOf("BRANCH")).isEqualTo(RlsDimensionKind.FILTERABLE);
        assertThat(dimensions.policyOf(ReceivingDocument.class).filterableDimensions())
            .containsExactlyInAnyOrder("JOURNAL", "BRANCH");
        assertThat(dimensions.policyOf(PrdSpec.class).filterableDimensions()).containsExactly("JOURNAL");
        assertThat(dimensions.policyOf(Workshop.class).filterableDimensions()).containsExactly("BRANCH");
    }

}
