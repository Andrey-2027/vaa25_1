package com.example.materialrequests.application;

import com.example.materialrequests.model.MaterialRequest;
import com.example.materialrequests.model.MaterialRequestLine;
import jakarta.validation.ValidationException;
import org.ipro.lifecycle.AggregateSaveContext;
import org.ipro.lifecycle.EntityChangedContext;
import org.ipro.lifecycle.EntityLifecycle;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import java.util.HashSet;

@Component
public class MaterialRequestLifecycle implements EntityLifecycle<MaterialRequest> {
    private final ApplicationEventPublisher events;
    public MaterialRequestLifecycle(ApplicationEventPublisher events) { this.events = events; }
    @Override public Class<MaterialRequest> entityType() { return MaterialRequest.class; }

    @Override public void beforeAggregateSave(AggregateSaveContext<MaterialRequest> context) {
        var materials = new HashSet<Long>();
        for (MaterialRequestLine row : context.section(MaterialRequestLine.class)) {
            // Null/unsaved references are validated by the platform's row contract.
            if (row.getMaterial() != null && row.getMaterial().getId() != null
                    && !materials.add(row.getMaterial().getId())) {
                throw new ValidationException("Материал не должен повторяться в строках заявки");
            }
        }
    }

    @Override public void afterCommit(EntityChangedContext<MaterialRequest> context) {
        MaterialRequest request = context.entity();
        events.publishEvent(new RequestCommitted(request.getId(), request.getNumber(), request.getStatus()));
    }
}
