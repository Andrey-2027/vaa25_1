package com.example.entitytypes;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.server.VaadinSession;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
final class VaadinHarness {
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
                    application, VaadinHarness.class, parameters);
            var servlet = new com.vaadin.flow.server.VaadinServlet() {
                @Override public jakarta.servlet.ServletContext getServletContext() { return servletContext; }
                @Override public String getServletName() { return "consumer-forms-test"; }
                @Override public jakarta.servlet.ServletConfig getServletConfig() {
                    return new org.springframework.mock.web.MockServletConfig(servletContext, getServletName());
                }
            };
            service = new com.vaadin.flow.server.VaadinServletService(servlet, configuration);
            service.setClassLoader(VaadinHarness.class.getClassLoader());
            try { service.init(); }
            catch (com.vaadin.flow.server.ServiceException failure) { throw new IllegalStateException(failure); }
        }
        @Override public com.vaadin.flow.server.VaadinService getService() { return service; }
        @Override public com.vaadin.flow.function.DeploymentConfiguration getConfiguration() { return configuration; }
        @Override public ReentrantLock getLockInstance() { return lock; }
        @Override public boolean hasLock() { return lock.isHeldByCurrentThread(); }
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
                .distinct().flatMap(VaadinHarness::descendants));
    }
}
