package com.example.materialrequests.acceptance;

import com.example.materialrequests.model.*;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.server.VaadinSession;
import org.ipro.data.EntityDataAccess;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.coordinator.FormNavigator;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "consumer.demo-password=bootstrap-test-password")
@ActiveProfiles(resolver = ConsumerTestProfiles.class)
class FormsTest {
    @Autowired ApplicationContext context;
    @Autowired EntityDataAccess access;
    private UI ui;
    private TestSession session;
    // Real Vaadin state with a local lock, no servlet or browser transport is simulated here.
    static class TestSession extends VaadinSession {
        private final ReentrantLock lock = new ReentrantLock();
        private final com.vaadin.flow.function.DeploymentConfiguration configuration;
        private final com.vaadin.flow.server.VaadinService service;
        TestSession() {
            super(null);
            var parameters = new java.util.Properties(); parameters.setProperty("productionMode", "true");
            var servletContext = new org.springframework.mock.web.MockServletContext();
            var vaadinContext = new com.vaadin.flow.server.VaadinServletContext(servletContext);
            try {
                new com.vaadin.flow.di.LookupInitializer().initialize(vaadinContext, new java.util.HashMap<>(),
                        lookup -> vaadinContext.setAttribute(com.vaadin.flow.di.Lookup.class, lookup));
            } catch (jakarta.servlet.ServletException failure) { throw new IllegalStateException(failure); }
            var application = com.vaadin.flow.server.startup.ApplicationConfiguration.get(vaadinContext);
            configuration = new com.vaadin.flow.server.DefaultDeploymentConfiguration(
                    application, FormsTest.class, parameters);
            var servlet = new com.vaadin.flow.server.VaadinServlet() {
                @Override public jakarta.servlet.ServletContext getServletContext() { return servletContext; }
                @Override public String getServletName() { return "consumer-forms-test"; }
                @Override public jakarta.servlet.ServletConfig getServletConfig() {
                    return new org.springframework.mock.web.MockServletConfig(servletContext, getServletName());
                }
            };
            service = new com.vaadin.flow.server.VaadinServletService(servlet, configuration);
            service.setClassLoader(FormsTest.class.getClassLoader());
            try { service.init(); }
            catch (com.vaadin.flow.server.ServiceException failure) { throw new IllegalStateException(failure); }
        }
        @Override public com.vaadin.flow.server.VaadinService getService() { return service; }
        @Override public com.vaadin.flow.function.DeploymentConfiguration getConfiguration() { return configuration; }
        @Override public ReentrantLock getLockInstance() { return lock; }
        @Override public boolean hasLock() { return lock.isHeldByCurrentThread(); }
    }
    @BeforeEach void setup() {
        session = new TestSession(); session.getLockInstance().lock();
        VaadinSession.setCurrent(session);
        ui = new UI(); ui.getInternals().setSession(session); UI.setCurrent(ui);
    }
    @AfterEach void cleanup() {
        UI.setCurrent(null); VaadinSession.setCurrent(null);
        if (session != null) {
            try { session.getService().destroy(); }
            finally { if (session.hasLock()) session.getLockInstance().unlock(); }
        }
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
    @Test void standardListsAndRequestCustomizationOpenWithOwnedSectionAndLookup() {
        ProtectedReadTest.login("alice");
        var navigator = context.getBean(FormNavigator.class);
        var materials = navigator.createListForm(Material.class);
        var requests = navigator.createListForm(MaterialRequest.class);
        ui.add(materials, requests);
        assertThat(materials.getGrid().getColumns()).extracting(com.vaadin.flow.component.grid.Grid.Column::getKey)
                .containsExactly("code", "name");
        assertThat(requests.getGrid().getColumns()).extracting(com.vaadin.flow.component.grid.Grid.Column::getKey)
                .containsExactly("number", "date", "departmentId", "status");
        var metadata = context.getBean(org.ipro.metadata.MetadataResolver.class);
        assertThat(metadata.resolve(Material.class).getSelectColumnPaths()).extracting(org.ipro.metadata.ColumnPath::getKey)
                .containsExactly("code", "name");
        assertThat(metadata.resolve(MaterialRequest.class).getSelectColumnPaths()).extracting(org.ipro.metadata.ColumnPath::getKey)
                .containsExactly("number", "date", "departmentId", "status");
        assertThat(materials.getAddButton().isEnabled()).isTrue();
        assertThat(requests.getAddButton().isEnabled()).isTrue();
        Material material = access.lookup(Material.class, "MAT-001", 10).getFirst();
        navigator.openItemForm(Material.class, null, material.getId(), null, null);
        assertThat(findForm(Material.class).getField("name")).isNotNull();
        Long id = access.list(MaterialRequest.class, null, PageRequest.of(0, 10)).getContent().getFirst().getId();
        navigator.openItemForm(MaterialRequest.class, null, id, null, null);
        ItemForm<?> form = findForm(MaterialRequest.class);
        assertThat(form.getEntityClass()).isEqualTo(MaterialRequest.class);
        assertThat(form.getField("comment")).isNotNull();
        assertThat(form.hasAttachedSection(MaterialRequestLine.class)).isTrue();
        assertThat(form.isReadOnly()).isFalse();
        var number = (com.vaadin.flow.component.textfield.TextField) form.getField("number");
        assertThat(number.isReadOnly()).isTrue();
        form.setReadOnly(true);
        form.setReadOnly(false);
        assertThat(number.isReadOnly()).isTrue();
        assertThat(((com.vaadin.flow.component.textfield.TextField) form.getField("comment")).isReadOnly()).isFalse();
        assertThat(form.tableSection(MaterialRequestLine.class).getRowClass()).isEqualTo(MaterialRequestLine.class);
        descendants(form.tableSection(MaterialRequestLine.class)).filter(Button.class::isInstance)
                .map(Button.class::cast).filter(button -> button.getText().equals("Добавить"))
                .findFirst().orElseThrow().click();
        ItemForm<?> row = findForm(MaterialRequestLine.class);
        var lookup = row.<Material>entityField("material");
        descendants(lookup).filter(com.vaadin.flow.component.textfield.TextField.class::isInstance)
                .map(com.vaadin.flow.component.textfield.TextField.class::cast)
                .findFirst().orElseThrow().setValue("MAT-001");
        descendants(lookup).filter(com.vaadin.flow.component.grid.Grid.class::isInstance)
                .map(component -> (com.vaadin.flow.component.grid.Grid<Material>) component)
                .findFirst().orElseThrow().select(material);
        assertThat(lookup.getValue().getId()).isEqualTo(material.getId());
        assertThat(((MaterialRequestLine) row.getEntity()).getMaterial().getId()).isEqualTo(material.getId());
    }
    @Test void readerGetsReadOnlyCardAndHiddenIdDoesNotOpenDialog() {
        ProtectedReadTest.login("alice");
        Long hidden = access.list(MaterialRequest.class, null, PageRequest.of(0, 10)).getContent().getFirst().getId();
        ProtectedReadTest.login("bob");
        var navigator = context.getBean(FormNavigator.class);
        var list = navigator.createListForm(MaterialRequest.class); ui.add(list);
        assertThat(list.getAddButton().isEnabled()).isFalse();
        navigator.openItemForm(MaterialRequest.class, null, hidden, null, null);
        ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();
        assertThat(descendants(ui).filter(Dialog.class::isInstance)).isEmpty();
        Long own = access.list(MaterialRequest.class, null, PageRequest.of(0, 10)).getContent().getFirst().getId();
        navigator.openItemForm(MaterialRequest.class, null, own, null, null);
        ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();
        ItemForm<?> form = descendants(ui).filter(ItemForm.class::isInstance).map(ItemForm.class::cast)
                .findFirst().orElseThrow();
        assertThat(form.isReadOnly()).isTrue();
        assertThat(form.tableSection(MaterialRequestLine.class).isReadOnly()).isTrue();
    }
    static Stream<Component> descendants(Component root) {
        var virtual = new java.util.ArrayList<Component>();
        var node = root.getElement().getNode();
        if (node.hasFeature(com.vaadin.flow.internal.nodefeature.VirtualChildrenList.class)) {
            node.getFeature(com.vaadin.flow.internal.nodefeature.VirtualChildrenList.class).iterator()
                    .forEachRemaining(child -> com.vaadin.flow.internal.nodefeature.ComponentMapping
                            .getComponent(child).ifPresent(virtual::add));
        }
        return Stream.concat(Stream.of(root), Stream.concat(root.getChildren(), virtual.stream())
                .distinct().flatMap(FormsTest::descendants));
    }
    private ItemForm<?> findForm(Class<?> type) {
        // Vaadin auto-attaches open overlays at the response boundary.
        ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();
        return descendants(ui).filter(ItemForm.class::isInstance).map(ItemForm.class::cast)
                .filter(form -> form.getEntityClass().equals(type)).findFirst().orElseThrow();
    }
}
