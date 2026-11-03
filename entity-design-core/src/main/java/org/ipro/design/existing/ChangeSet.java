package org.ipro.design.existing;

import java.util.List;
import org.ipro.design.DraftProblem;

/** The supported changes between two drafts and every difference that is not supported. */
public record ChangeSet(List<EntityChange> changes, List<DraftProblem> problems) {
    public ChangeSet {
        changes = List.copyOf(changes); problems = List.copyOf(problems);
    }
    public boolean hasErrors() { return problems.stream().anyMatch(p -> p.severity() == DraftProblem.Severity.ERROR); }
    public boolean isEmpty() { return changes.isEmpty() && problems.isEmpty(); }
}
