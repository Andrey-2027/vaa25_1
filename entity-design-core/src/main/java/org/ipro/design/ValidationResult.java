package org.ipro.design;

import java.util.*;

public final class ValidationResult {
    private final List<DraftProblem> problems;
    private final CheckedStructure checked;
    ValidationResult(List<DraftProblem> problems, CheckedStructure checked) {
        this.problems = List.copyOf(problems); this.checked = checked;
        if (checked != null && hasErrors()) throw new IllegalArgumentException("Checked structure cannot contain errors");
    }
    public List<DraftProblem> problems() { return problems; }
    public boolean hasErrors() { return problems.stream().anyMatch(p -> p.severity() == DraftProblem.Severity.ERROR); }
    public Optional<CheckedStructure> checkedStructure() { return Optional.ofNullable(checked); }
}
