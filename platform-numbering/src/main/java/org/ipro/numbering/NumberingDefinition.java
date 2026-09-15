package org.ipro.numbering;

import org.ipro.numbering.annotation.Numbered;
import org.ipro.numbering.annotation.NumberingPolicy;
import org.ipro.numbering.annotation.NumberingRole;

import java.util.List;

/** Эффективная структурная декларация нумерации после применения role-policy. */
public record NumberingDefinition(
        NumberingRole role,
        List<String> scope,
        boolean allowManual,
        NumberingPeriod period,
        String prefix,
        String pattern,
        String dateField) {

    public NumberingDefinition {
        scope = List.copyOf(scope);
    }

    public static NumberingDefinition from(Numbered annotation) {
        return new NumberingDefinition(
            annotation.role(), List.of(annotation.scope()), annotation.allowManual(),
            annotation.period(), annotation.prefix(), annotation.pattern(), annotation.dateField());
    }

    public static NumberingDefinition from(Numbered fieldDefault, NumberingPolicy policy) {
        String dateField = policy.dateField().isBlank()
            ? fieldDefault.dateField()
            : policy.dateField();
        return new NumberingDefinition(
            policy.role(), List.of(policy.scope()), policy.allowManual(), policy.period(),
            policy.prefix(), policy.pattern(), dateField);
    }
}
