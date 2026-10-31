package org.ipro.design.apply;

import org.ipro.design.EntityDraft;
import org.ipro.design.ProjectDesignContext;
import org.ipro.design.generation.GenerationPlan;

/**
 * Rebuilds the control plan from the reviewed plan and current inputs. Production uses the
 * public validator/generator; the package-private D self-test can inject a control document
 * plan until the document builder exists in E1.A.
 */
@FunctionalInterface
interface PlanRebuilder {
    GenerationPlan rebuild(GenerationPlan reviewed, EntityDraft draft,
                           ProjectDesignContext context) throws ApplyFailure;
}
