package org.ip.unidr.model;

import org.ipro.unidr.model.UniDrDefinition;
import org.ipro.unidr.model.UniDrDefinition.Aggregation;
import org.ipro.unidr.model.UniDrDefinition.Column;
import org.ipro.unidr.model.UniDrDefinition.DefaultRule;
import org.ipro.unidr.model.UniDrDefinition.Direction;
import org.ipro.unidr.model.UniDrDefinition.Filter;
import org.ipro.unidr.model.UniDrDefinition.Literal;
import org.ipro.unidr.model.UniDrDefinition.Measure;
import org.ipro.unidr.model.UniDrDefinition.Mode;
import org.ipro.unidr.model.UniDrDefinition.Operator;
import org.ipro.unidr.model.UniDrDefinition.Parameter;
import org.ipro.unidr.model.UniDrDefinition.ParameterRef;
import org.ipro.unidr.model.UniDrDefinition.Sort;
import org.ipro.unidr.model.UniDrDefinition.ValueType;
import org.ipro.unidr.model.UniDrParameters;
import org.ip.unidr.query.UniDrQueryAdapter;
import org.ipro.unidr.query.UniDrReferenceCatalog;
import org.ipro.unidr.query.UniDrReferenceCatalog.Option;
import org.ipro.unidr.source.UniDrSourceCatalog;
import org.ip.unidr.source.UniDrReceivingItemsSource;

import java.util.List;

/** Starting recipe; CURRENT_MONTH is resolved at run time in the user's time zone. */
public final class UniDrDefinitions {
    private UniDrDefinitions() {}

    /** Installed catalog for the pilot source, wired to the application reference port. */
    public static UniDrSourceCatalog catalog(UniDrReferenceCatalog references) {
        var catalog=new UniDrSourceCatalog(List.of(UniDrReceivingItemsSource.definition()));
        catalog.useReferences(references);
        catalog.useSourcePlans(source -> UniDrQueryAdapter.plan(source,references));
        return catalog;
    }

    /** Catalog for config/tests that only validate definitions; reference reads are unavailable. */
    public static UniDrSourceCatalog catalog() { return catalog(referenceTypes()); }

    /** Field-type mapping only; search/selection are deliberately unavailable in model tests. */
    public static UniDrReferenceCatalog referenceTypes() {
        return new UniDrReferenceCatalog() {
            @Override public Class<?> referenceType(String fieldId) {
                return org.ip.unidr.query.UniDrReferenceService.referenceTypeForField(fieldId);
            }
            @Override public List<Option> search(String fieldId,String term,int limit) { throw new UnsupportedOperationException(); }
            @Override public Option selected(String fieldId,Long id) { throw new UnsupportedOperationException(); }
            @Override public void requireVisible(String fieldId,Long id) { throw new UnsupportedOperationException(); }
            @Override public Object entity(String name,Long id) { throw new UnsupportedOperationException(); }
            @Override public Class<?> entityType(String name) { throw new UnsupportedOperationException(); }
            @Override public void requireEntity(String name,Long id) { throw new UnsupportedOperationException(); }
        };
    }

    public static UniDrDefinition receivingItemsSummary() {
        var definition=new UniDrDefinition(UniDrDefinition.SCHEMA_VERSION, UniDrReceivingItemsSource.ID,
            UniDrReceivingItemsSource.VERSION, "Количество по цехам и номенклатуре",
            List.of(new Column("date", null), new Column("document", null),
                new Column("nomenclature", null), new Column("quantity", null)),
            List.of(new Measure("quantity", Aggregation.SUM, null), new Measure("rowCount", Aggregation.COUNT_ROWS, null)),
            List.of("receivingWorkshop", "nomenclature"), List.of(),
            List.of(new Parameter("period", "date", Operator.BETWEEN, "Период", DefaultRule.CURRENT_MONTH, null),
                new Parameter("unit", "unit", Operator.EQ, "Единица измерения", DefaultRule.NONE, null),
                new Parameter("recipient", "receivingWorkshop", Operator.EQ, "Цех-приёмщик", DefaultRule.NONE, null)),
            List.of(new Sort("date", Direction.ASC)), Mode.SUMMARY, true);
        return definition.withParameters(UniDrParameters.fromLegacy(UniDrReceivingItemsSource.definition(),
            definition.parameters(),definition.query()));
    }
}
