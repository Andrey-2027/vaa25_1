package com.example.entitytypes;

import com.example.entitytypes.config.FixtureData;
import com.example.entitytypes.model.*;
import com.example.entitytypes.model.Order;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.VaadinSession;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.ipro.crud.*;
import org.ipro.data.*;
import org.ipro.events.EventSource;
import org.ipro.form.*;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.coordinator.FormNavigator;
import org.ipro.lifecycle.*;
import org.ipro.metadata.*;
import org.ipro.metadata.annotation.*;
import org.ipro.query.QueryBuilderMetadataCatalog;
import org.ipro.rls.*;
import org.ipro.rls.c5.C5PermissionKeys;
import org.ipro.vaadin.explorer.EntitySummaryAssembler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"vaadin.productionMode=true", "consumer.demo-password=et5-test-only"})
@Import(EntityTypesAcceptanceTest.FaultConfiguration.class)
class EntityTypesAcceptanceTest {
    @Autowired EntityDataAccess data;
    @Autowired NaturalKeyCreateSupport interning;
    @Autowired MetadataDrivenAggregateSaveService aggregates;
    @Autowired GenericOwnedSectionService sections;
    @Autowired SectionMetadataRegistry sectionMetadata;
    @Autowired MetadataResolver metadata;
    @Autowired EntityDescriptorCatalog descriptors;
    @Autowired ManagedEntityTypes types;
    @Autowired AccessGrantRepository grants;
    @Autowired org.ipro.rls.c5.C5PermissionEvaluator permissions;
    @Autowired QueryBuilderMetadataCatalog query;
    @Autowired EntitySummaryAssembler explorer;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired LateFault fault;
    @PersistenceContext EntityManager em;
    private UI ui;
    private VaadinHarness.TestSession session;

    static void login(String user) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            user, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }
    @BeforeEach void setup() {
        login("writer"); fault.enabled = false;
        session = new VaadinHarness.TestSession(); session.getLockInstance().lock();
        VaadinSession.setCurrent(session); ui = new UI(); ui.getInternals().setSession(session); UI.setCurrent(ui);
    }
    @AfterEach void cleanup() {
        fault.enabled = false;
        grants.deleteAll(grants.findAll().stream().filter(g -> g.getSubjectKey().equals("denied")).toList());
        UI.setCurrent(null); VaadinSession.setCurrent(null);
        try { session.getService().destroy(); }
        finally { session.getLockInstance().unlock(); SecurityContextHolder.clearContext(); }
    }

    @Test void completeInternedProfileWorksFromBinaryApiWithoutCapabilityBeans() {
        assertThat(context.getBeansOfType(EntityCapabilityOverride.class)).isEmpty();
        assertThat(descriptors.descriptorOf(InternedLabel.class).capabilities().writes()).containsExactly(DataOperation.CREATE);
        java.util.function.Function<String,InternedLabel> findOrCreate = input -> {
            var key=InternedLabel.of(input);
            return interning.getOrCreate(InternedLabel.class,key,
                () -> data.list(InternedLabel.class,(root,q,cb) -> cb.equal(root.get("canonical"),key.getCanonical()),
                    PageRequest.of(0,1)).stream().findFirst(),
                () -> data.create(InternedLabel.class,InternedLabel.of(input)));
        };
        var first=findOrCreate.apply("  ET8 label  ");
        assertThat(findOrCreate.apply("et8 LABEL").getId()).isEqualTo(first.getId());
        assertThatThrownBy(() -> data.update(InternedLabel.class,first)).hasMessageContaining("UPDATE");
        assertThatThrownBy(() -> data.delete(InternedLabel.class,first.getId())).hasMessageContaining("DELETE");
    }

    @Test void externalBinaryConsumerNeedsNoEntityMetadataRepositoryServiceOrSaveHandler() throws Exception {
        assertThatThrownBy(() -> Class.forName("org.ip.Application")).isInstanceOf(ClassNotFoundException.class);
        assertThat(StandardCatalogEntity.class.getProtectionDomain().getCodeSource().getLocation().toString()).endsWith(".jar");
        assertThat(context.getBeansOfType(FormSaveHandler.class)).containsOnlyKeys("defaultFormSaveHandler");
        for (Class<?> type : List.of(Product.class, ExtendedProduct.class, Note.class, Order.class, TwoSectionDocument.class)) {
            assertThat(type.getAnnotation(EntityMetadata.class)).isNull();
            assertThat(types.contains(type)).isTrue();
            assertThat(metadata.resolve(type).getAnnotation()).isNull();
            assertThat(descriptors.descriptorOf(type).exposure()).isEqualTo(EntityExposure.STANDARD_ROOT);
            assertThat(query.roots()).anyMatch(root -> root.javaType() == type);
            assertThat(explorer.entities()).anyMatch(entity -> entity.entityClass() == type);
        }
        assertThat(context.getBeansOfType(org.ipro.crud.BaseService.class)).isEmpty();
        assertThat(query.root("OrderLine").associations()).anyMatch(link ->
            link.name().equals("product") && link.targetType() == Product.class);
    }

    @Test void catalogCreatesUpdatesSearchesNumbersAndDeletesThroughTheDefaultForm() {
        var form = open(Product.class, null);
        form.getEntityField("name", String.class).setValue("ET5 ёж " + UUID.randomUUID());
        assertThat(form.getEntity().getCode()).isNullOrEmpty();
        assertThat(form.save().success()).isTrue();
        Product product = form.getEntity();
        assertThat(product.getId()).isNotNull(); assertThat(product.getCode()).isNotBlank();
        assertThat(product.getDisplayName()).contains(product.getCode(), "ET5 ёж");
        String code = product.getCode();
        form.getEntityField("name", String.class).setValue("ET5 Обновлён " + code);
        assertThat(form.save().success()).isTrue();
        assertThat(form.getEntity().getCode()).isEqualTo(code);
        assertThat(data.lookup(Product.class, "Обновлён", 50)).anyMatch(p -> p.getId().equals(product.getId()));
        assertThat(open(Product.class, product.getId()).getEntity().getName()).contains("Обновлён");
        data.delete(Product.class, product.getId());
        assertThat(data.detail(Product.class, product.getId())).isEmpty();
    }

    @Test void persistentUnpublishedFieldStaysOutsideFormsQueriesAndRest() throws Exception {
        var product = new ExtendedProduct(); product.setName("Hidden " + UUID.randomUUID());
        product.setDescription("Published"); product.setStorageOnly("Stored, not published");
        var saved = data.create(ExtendedProduct.class, product);
        assertThat(data.detail(ExtendedProduct.class, saved.getId()).orElseThrow().getStorageOnly()).isEqualTo("Stored, not published");
        assertThat(metadata.resolve(ExtendedProduct.class).getFormFields()).extracting(FieldMetadataInfo::getName)
            .contains("description").doesNotContain("storageOnly", "id", "version", "createdAt");
        var summary = explorer.summarize(ExtendedProduct.class);
        assertThat(summary.structure().fields()).anyMatch(f -> f.identity().fieldName().equals("storageOnly") && !f.published());
        assertThat(query.root("ExtendedProduct").fields()).extracting(QueryBuilderMetadataCatalog.Field::name).doesNotContain("storageOnly");
        assertThatThrownBy(() -> Class.forName("org.ipro.rest.catalog.RestResourceCatalog")).isInstanceOf(ClassNotFoundException.class);
    }

    @Test void documentsWithZeroOneAndTwoSectionsSaveAndReopen() {
        var note = open(Note.class, null); assertThat(note.save().success()).isTrue();
        assertThat(open(Note.class, note.getEntity().getId()).getTableSections()).isEmpty();
        var order = new Order();
        var row = orderLine();
        var saved = aggregates.save(order, List.of(attached(OrderLine.class, row)), EventSource.UI).aggregate();
        assertThat(saved.getNumber()).isNotBlank();
        assertThat(open(Order.class, saved.getId()).tableSection(OrderLine.class).getRows()).hasSize(1);
        var two = new TwoSectionDocument();
        var result = aggregates.save(two, List.of(attached(Position.class, position()), attached(CommentRow.class, comment("First"))), EventSource.UI);
        assertThat(result.sections()).hasSize(2);
        var reopened = open(TwoSectionDocument.class, result.aggregate().getId());
        assertThat(reopened.tableSection(Position.class).getRows()).hasSize(1);
        assertThat(reopened.tableSection(CommentRow.class).getRows()).hasSize(1);
    }

    @Test void absentSectionPreservesRowsWhileAttachedEmptyClearsOnlyThatSection() {
        var saved = aggregates.save(new TwoSectionDocument(), List.of(attached(Position.class, position()),
            attached(CommentRow.class, comment("Keep"))), EventSource.UI).aggregate();
        var next = aggregates.save(saved, List.of(MetadataDrivenAggregateSaveService.SectionInput.attached(Position.class, List.of())), EventSource.UI).aggregate();
        assertThat(rows(next, Position.class)).isEmpty(); assertThat(rows(next, CommentRow.class)).hasSize(1);
        next = aggregates.save(next, List.of(), EventSource.UI).aggregate();
        assertThat(rows(next, CommentRow.class)).hasSize(1);
    }

    @Test void lateFailureRollsBackHeaderAndBothSectionsAndAllowsRetry() {
        long before = jdbc.queryForObject("select count(*) from two_section_document", Long.class);
        fault.enabled = true;
        assertThatThrownBy(() -> aggregates.save(new TwoSectionDocument(), List.of(attached(Position.class, position()),
            attached(CommentRow.class, comment("Rollback"))), EventSource.UI)).hasMessageContaining("ET5 late failure");
        assertThat(fault.observedRows).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from two_section_document", Long.class)).isEqualTo(before);
        fault.enabled = false;
        var saved = aggregates.save(new TwoSectionDocument(), List.of(attached(Position.class, position()),
            attached(CommentRow.class, comment("Retry"))), EventSource.UI).aggregate();
        assertThat(rows(saved, Position.class)).hasSize(1); assertThat(rows(saved, CommentRow.class)).hasSize(1);
    }

    @Test void deletingAggregateRemovesBothSectionsAndReferencedCatalogIsProtected() {
        var product = new Product(); product.setName("Referenced " + UUID.randomUUID()); product = data.create(Product.class, product);
        var row = position(); row.setProduct(product);
        var saved = aggregates.save(new TwoSectionDocument(), List.of(attached(Position.class, row),
            attached(CommentRow.class, comment("Delete"))), EventSource.UI).aggregate();
        Long productId = product.getId();
        assertThatThrownBy(() -> data.delete(Product.class, productId)).hasMessageContaining("ссылки");
        Long documentId = saved.getId(); data.delete(TwoSectionDocument.class, documentId);
        assertThat(jdbc.queryForObject("select count(*) from position where owner_id=?", Long.class, documentId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from comment_row where owner_id=?", Long.class, documentId)).isZero();
        data.delete(Product.class, productId);
    }

    @Test void customProfilesKeepTheirLocalNamesLengthsAndDateTimeType() {
        var catalog = new CustomCatalog(); catalog.setExternalKey(UUID.randomUUID().toString().substring(0, 10)); catalog.setCaption("Custom");
        assertThat(data.create(CustomCatalog.class, catalog).getId()).isNotNull();
        var doc = new CustomDocument(); doc.setOccurredAt(LocalDateTime.of(2026, 10, 26, 12, 34)); doc.setExternalNumber("External");
        var saved = data.create(CustomDocument.class, doc);
        assertThat(data.detail(CustomDocument.class, saved.getId()).orElseThrow().getOccurredAt()).isEqualTo(doc.getOccurredAt());
        assertThat(metadata.resolve(CustomDocument.class).getFormFields()).extracting(FieldMetadataInfo::getName).containsExactly("occurredAt", "externalNumber");
        assertThat(metadata.resolve(CustomDocument.class).getDisplaySortFields()).isEmpty();
    }

    @Test void noGrantsRejectsReadsAndWritesThenExplicitEntityAndScopeGrantsEnableTheSameUser() {
        login("denied");
        assertThat(permissions.isEntityReadPermitted("denied", Product.class)).isFalse();
        assertThatThrownBy(() -> data.list(Product.class, null, PageRequest.of(0, 10))).isInstanceOf(AccessDeniedException.class);
        var deniedProduct = new Product(); deniedProduct.setName("Denied first");
        assertThatThrownBy(() -> data.create(Product.class, deniedProduct)).isInstanceOf(AccessDeniedException.class);
        FixtureData.grant(grants, "denied", C5PermissionKeys.entity(Product.class), null, true); grants.flush();
        var product = new Product(); product.setName("Allowed later");
        assertThat(data.create(Product.class, product).getId()).isNotNull();
        FixtureData.grant(grants, "denied", C5PermissionKeys.entity(Order.class), null, true); grants.flush();
        assertThatThrownBy(() -> aggregates.save(new Order(), List.of(), EventSource.UI)).isInstanceOf(RlsAccessDeniedException.class);
        FixtureData.grant(grants, "denied", Order.TEAM, 1L, true); grants.flush();
        assertThat(aggregates.save(new Order(), List.of(), EventSource.UI).aggregate().getId()).isNotNull();
        assertThat(grants.findBySubjectTypeAndSubjectKeyAndDimension(AccessGrant.SubjectType.USER, "denied", "*")).isEmpty();
    }

    @Test void readerCannotWriteAndRowScopeFiltersListsAndDirectIds() {
        var writerOrder = data.list(Order.class, null, PageRequest.of(0, 100)).getContent().getFirst();
        login("reader");
        assertThat(data.list(Order.class, null, PageRequest.of(0, 100))).allMatch(order -> order.getTeamId().equals(2L));
        assertThat(data.detail(Order.class, writerOrder.getId())).isEmpty();
        var product = data.lookup(Product.class, "ET5-001", 10).getFirst(); product.setName("Forbidden update");
        assertThatThrownBy(() -> data.update(Product.class, product)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> data.delete(Product.class, product.getId())).isInstanceOf(AccessDeniedException.class);
        assertThat(data.detail(Product.class, product.getId()).orElseThrow().getName()).isEqualTo("Тестовый товар ёж");
        login("writer"); var foreign = new Order(); foreign.setTeamId(2L);
        assertThatThrownBy(() -> aggregates.save(foreign, List.of(), EventSource.UI)).isInstanceOf(RlsAccessDeniedException.class);
    }

    @Test void internalEntityPersistsButNeverBecomesAStandalonePublishedRoot() {
        var memo = new InternalMemo(); memo.setPayload("internal");
        new TransactionTemplate(transactions).executeWithoutResult(tx -> { em.persist(memo); em.flush(); });
        assertThat(memo.getId()).isNotNull();
        assertThat(descriptors.descriptorOf(InternalMemo.class).exposure()).isEqualTo(EntityExposure.INTERNAL_STORE);
        assertThat(query.roots()).noneMatch(root -> root.javaType() == InternalMemo.class);
        assertThat(explorer.entities()).noneMatch(root -> root.entityClass() == InternalMemo.class);
        assertThatThrownBy(() -> data.detail(InternalMemo.class, memo.getId())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> data.create(OrderLine.class, new OrderLine())).isInstanceOf(IllegalStateException.class);
    }

    @Test void invalidRowCannotLeaveAnInsertedHeader() {
        var row = orderLine(); row.setQuantity(BigDecimal.ZERO);
        long before = jdbc.queryForObject("select count(*) from et5_order", Long.class);
        assertThatThrownBy(() -> aggregates.save(new Order(), List.of(attached(OrderLine.class, row)), EventSource.UI))
            .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("select count(*) from et5_order", Long.class)).isEqualTo(before);
    }

    @SuppressWarnings("unchecked")
    private <T extends BaseEntity> ItemForm<T> open(Class<T> type, Long id) {
        context.getBean(FormNavigator.class).openItemForm(type, null, id, null, null);
        ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();
        return (ItemForm<T>) VaadinHarness.descendants(ui).filter(ItemForm.class::isInstance).map(ItemForm.class::cast)
            .filter(form -> form.getEntityClass().equals(type)).toList().getLast();
    }
    private Product product() { return data.lookup(Product.class, "ET5-001", 10).getFirst(); }
    private OrderLine orderLine() { var row = new OrderLine(); row.setProduct(product()); row.setQuantity(BigDecimal.ONE); return row; }
    private Position position() { var row = new Position(); row.setProduct(product()); row.setQuantity(BigDecimal.ONE); return row; }
    private CommentRow comment(String text) { var row = new CommentRow(); row.setText(text); return row; }
    private <R extends BaseEntity> MetadataDrivenAggregateSaveService.SectionInput attached(Class<R> type, R row) {
        return MetadataDrivenAggregateSaveService.SectionInput.attached(type, List.of(row));
    }
    private <R extends BaseEntity> List<R> rows(TwoSectionDocument document, Class<R> type) {
        return sections.findByParent(document, sectionMetadata.findByRow(type).orElseThrow());
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class FaultConfiguration {
        @Bean LateFault lateFault(JdbcTemplate jdbc) { return new LateFault(jdbc); }
    }
    static class LateFault implements EntityLifecycle<TwoSectionDocument> {
        private final JdbcTemplate jdbc; boolean enabled; long observedRows;
        LateFault(JdbcTemplate jdbc) { this.jdbc = jdbc; }
        @Override public Class<TwoSectionDocument> entityType() { return TwoSectionDocument.class; }
        @Override public void onSave(EntitySaveContext<TwoSectionDocument> context) {
            if (!enabled) return;
            observedRows = jdbc.queryForObject("select count(*) from position where owner_id=?", Long.class, context.entity().getId())
                + jdbc.queryForObject("select count(*) from comment_row where owner_id=?", Long.class, context.entity().getId());
            throw new IllegalStateException("ET5 late failure after SQL");
        }
    }
}
