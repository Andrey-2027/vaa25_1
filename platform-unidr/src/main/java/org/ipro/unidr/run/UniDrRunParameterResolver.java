package org.ipro.unidr.run;

import org.ipro.unidr.model.UniDrDefinition.*;
import org.ipro.unidr.model.UniDrDefinitionValidator;
import org.ipro.unidr.model.UniDrValidationException;
import org.ipro.unidr.model.UniDrValidationException.Issue;

import java.time.Instant;
import java.time.YearMonth;
import java.util.*;

/** Pure resolution. An explicit empty optional value clears a configured default. */
public final class UniDrRunParameterResolver {
    private final UniDrDefinitionValidator validator;
    public UniDrRunParameterResolver(UniDrDefinitionValidator validator) { this.validator = Objects.requireNonNull(validator); }

    public Map<String, Literal> resolve(UniDrRunRequest request, Instant now) {
        var definition = validator.requireValid(request.definition());
        Set<String> known = new HashSet<>(); definition.parameters().forEach(parameter -> known.add(parameter.id()));
        List<Issue> issues = new ArrayList<>();
        for (String id : request.values().keySet()) if (!known.contains(id))
            issues.add(new Issue(UniDrValidationException.Code.INVALID_PARAMETER, "values." + id, "Параметр не объявлен"));
        Map<String, Literal> resolved = new LinkedHashMap<>();
        for (Parameter parameter : definition.parameters()) {
            Literal value = request.values().get(parameter.id());
            if (!request.values().containsKey(parameter.id())) {
                if (parameter.defaultRule() == DefaultRule.FIXED) value = parameter.defaultValue();
                else if (parameter.defaultRule() == DefaultRule.CURRENT_MONTH) {
                    YearMonth month = YearMonth.from(now.atZone(request.zone()));
                    value = new Literal(ValueType.DATE, List.of(month.atDay(1).toString(), month.atEndOfMonth().toString()));
                }
            }
            if (value == null || value.values().isEmpty()) {
                if (validator.parameterRequired(definition, parameter.id()))
                    issues.add(new Issue(UniDrValidationException.Code.REQUIRED, "values." + parameter.id(), "Заполните «" + parameter.caption() + "»"));
                continue;
            }
            List<Issue> errors = validator.validateParameterValue(definition, parameter.id(), value);
            issues.addAll(errors);
            if (errors.isEmpty()) resolved.put(parameter.id(), value);
        }
        if (!issues.isEmpty()) throw new UniDrValidationException(issues);
        return Map.copyOf(resolved);
    }
}
