package org.ip.views.workspace;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.spring.annotation.UIScope;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

@Service
@UIScope
public class WorkspaceManager {

    private final AutowireCapableBeanFactory beanFactory;
    private final Map<String, Component> instances = new LinkedHashMap<>();

    public WorkspaceManager(AutowireCapableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @SuppressWarnings("unchecked")
    public <T extends Component> T getOrCreate(String key, Class<T> type, Consumer<T> initializer) {
        return (T) instances.computeIfAbsent(key, k -> {
            T view = beanFactory.createBean(type);
            initializer.accept(view);
            return view;
        });
    }

    public Component remove(String key) {
        return instances.remove(key);
    }

    public boolean has(String key) {
        return instances.containsKey(key);
    }
}
