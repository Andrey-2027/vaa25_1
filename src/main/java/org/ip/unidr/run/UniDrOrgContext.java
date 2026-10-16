package org.ip.unidr.run;

import org.ip.unidr.model.UniDrParameter;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsDimensionRegistry;

/** Same single-organisation rule as UDR, resolved from server grants rather than input values. */
public final class UniDrOrgContext {
    public record Value(Class<?> type,Long id) {}
    private final AccessService access;
    private final RlsDimensionRegistry dimensions;
    private final String dimension;
    public UniDrOrgContext(AccessService access,RlsDimensionRegistry dimensions,String dimension){this.access=access;this.dimensions=dimensions;this.dimension=dimension;}
    public Value resolve(String user) {
        var ids=access.getReadableIds(dimension,user);Class<?> type=dimensions.grantValueType(dimension);
        return ids!=null && ids.size()==1 && ids.getFirst()>0 && type!=null?new Value(type,ids.getFirst()):null;
    }
    public static boolean compatible(UniDrParameter parameter,Value value) {
        return value!=null && (parameter.kind()==UniDrParameter.Kind.ENTITY && value.type().getName().equals(parameter.entityType())
            || parameter.kind()==UniDrParameter.Kind.SCALAR && parameter.scalarType()==UniDrParameter.ScalarType.INTEGER);
    }
}
