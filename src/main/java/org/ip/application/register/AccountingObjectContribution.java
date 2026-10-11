package org.ip.application.register;

import org.ipro.crud.AggregateSaveContribution;
import java.util.List;

/** Immutable application input; recorder identity is assigned after saving the header. */
record AccountingObjectContribution(List<Line> lines) implements AggregateSaveContribution {
    AccountingObjectContribution { lines = List.copyOf(lines); }
    record Line(Long nomenclatureId, Long accountingTypeId) {}
    @Override public String key() { return "accounting-object-records"; }
}

