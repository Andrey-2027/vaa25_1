package org.ip.form.config;

import java.nio.file.Files;
import java.nio.file.Path;
import org.ipro.form.config.FormColumnDeclarationsCollector;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.FormConfigurationFor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;

/** Reads real application declarations through the production collector without constructing forms. */
public final class ApplicationColumnsTestSupport {
    private ApplicationColumnsTestSupport() { }

    public static MetadataResolver resolver() throws Exception {
        var beans = new DefaultListableBeanFactory();
        Path forms = Path.of("src/main/java/org/ip/views/forms");
        try (var files = Files.list(forms)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                String name = file.getFileName().toString().replace(".java", "");
                Class<?> type = Class.forName("org.ip.views.forms." + name);
                if (type.isAnnotationPresent(FormConfigurationFor.class)) {
                    beans.registerBeanDefinition(name, new RootBeanDefinition(type));
                }
            }
        }
        new FormColumnDeclarationsCollector().postProcessBeanFactory(beans);
        var resolver = new MetadataResolver();
        resolver.setFormColumnDeclarations(beans.getBean(org.ipro.metadata.FormColumnDeclarations.class));
        return resolver;
    }
}
