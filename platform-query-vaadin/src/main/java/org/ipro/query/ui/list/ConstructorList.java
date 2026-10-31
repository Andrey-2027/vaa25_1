package org.ipro.query.ui.list;

import org.ipro.query.*;
import java.util.List;

/** Editable list intention; generated stages are verified before being hidden. */
public record ConstructorList(String name, String sectionEntity, String parentEntity, String parentField,
                              String valuePath, String separator, boolean distinct,
                              VisualQueryOrder.Direction direction, List<VisualQueryPackage.Cte> stages,
                              QueryBuilderMetadataCatalog.Entity entity, String settingsJson,
                              VisualQueryPackage sourceQuery, String keyColumn, String valueColumn) {
    public ConstructorList { stages = List.copyOf(stages); }
    public ConstructorList(String name, String sectionEntity, String parentEntity, String parentField,
                           String valuePath, String separator, boolean distinct, VisualQueryOrder.Direction direction,
                           List<VisualQueryPackage.Cte> stages, QueryBuilderMetadataCatalog.Entity entity, String settingsJson) {
        this(name, sectionEntity, parentEntity, parentField, valuePath, separator, distinct, direction, stages, entity,
                settingsJson, null, null, null);
    }
}
