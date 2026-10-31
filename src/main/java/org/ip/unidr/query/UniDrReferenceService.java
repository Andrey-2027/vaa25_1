package org.ip.unidr.query;

import org.ip.model.*;
import org.ipro.unidr.query.UniDrReferenceCatalog;
import org.ipro.unidr.run.UniDrAccess;
import org.ipro.unidr.run.UniDrRunException;
import org.ipro.crud.BaseEntity;
import org.ipro.crud.EntityLookup;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.function.Function;

/** The existing canonical lookup is the only reference read path; no caller-supplied Java types. */
public final class UniDrReferenceService implements UniDrReferenceCatalog {
    private final EntityLookup lookup;
    private final UniDrAccess access;
    private final TransactionTemplate reads;
    private java.util.Map<String,Class<?>> entityTypes=java.util.Map.of();
    public void useEntityTypes(java.util.Collection<? extends Class<?>> types) {
        var allowed=new java.util.LinkedHashMap<String,Class<?>>();types.forEach(type->allowed.put(type.getName(),type));entityTypes=java.util.Map.copyOf(allowed);
    }
    @Override public Class<?> entityType(String name) {
        Class<?> type=entityTypes.get(name);if(type==null)throw new IllegalArgumentException("Тип сущности недоступен");return type;
    }
    @Override public Object entity(String name,Long id) {
        access.requireUser();Class<?> type=entityType(name);
        if(id==null || id<=0)throw new UniDrRunException(UniDrRunException.Code.REFERENCE_UNAVAILABLE,"Выбранная ссылка не найдена или недоступна");
        return reads.execute(status->lookup.findSelectedById(type,id).orElseThrow(()->new UniDrRunException(UniDrRunException.Code.REFERENCE_UNAVAILABLE,"Выбранная ссылка не найдена или недоступна")));
    }
    @Override public void requireEntity(String name,Long id){entity(name,id);}

    public UniDrReferenceService(EntityLookup lookup, UniDrAccess access, PlatformTransactionManager transactions) {
        this.lookup = lookup; this.access = access;
        reads = new TransactionTemplate(transactions); reads.setReadOnly(true);
    }

    @Override public List<Option> search(String fieldId, String term, int limit) {
        access.requireUser();
        if (limit < 1 || limit > 100 || term != null && term.length() > 200) throw new IllegalArgumentException("Недопустимый размер поиска");
        return reads.execute(status -> switch (fieldId) {
            case "receivingWorkshop", "transferringWorkshop" -> options(Workshop.class, List.of("code", "name"), term, limit, Workshop::getName);
            case "nomenclature" -> options(Nomenclature.class, List.of("code", "name"), term, limit, Nomenclature::getName);
            case "document" -> options(ReceivingDocument.class, List.of("number"), term, limit, ReceivingDocument::getNumber);
            case "unit" -> options(UnitOfMeasurement.class, List.of("shortCode", "name"), term, limit, UnitOfMeasurement::getShortCode);
            default -> throw new IllegalArgumentException("Поле не является ссылкой источника");
        });
    }

    @Override public void requireVisible(String fieldId, Long id) {
        selected(fieldId, id);
    }

    /** Resolves a configured/default ID through the same guarded lookup as searches. */
    @Override public Option selected(String fieldId, Long id) {
        access.requireUser();
        Class<?> type = referenceType(fieldId);
        if (id == null || id <= 0) throw new UniDrRunException(UniDrRunException.Code.REFERENCE_UNAVAILABLE, "Выбранная ссылка не найдена или недоступна");
        return reads.execute(status -> lookup.findSelectedById(type, id).map(entity -> new Option(id, switch (fieldId) {
            case "document" -> ((ReceivingDocument) entity).getNumber();
            case "unit" -> ((UnitOfMeasurement) entity).getShortCode();
            case "nomenclature" -> ((Nomenclature) entity).getName();
            default -> ((Workshop) entity).getName();
        })).orElseThrow(() -> new UniDrRunException(UniDrRunException.Code.REFERENCE_UNAVAILABLE, "Выбранная ссылка не найдена или недоступна")));
    }

    /** Server-owned mapping for the standard selection form; callers cannot supply entity classes. */
    @Override public Class<? extends BaseEntity> referenceType(String fieldId) {
        return referenceTypeForField(fieldId);
    }

    /** Static form for application UI helpers that have no reference-port instance at hand. */
    public static Class<? extends BaseEntity> referenceTypeForField(String fieldId) {
        return switch (fieldId) {
            case "receivingWorkshop", "transferringWorkshop" -> Workshop.class;
            case "nomenclature" -> Nomenclature.class;
            case "document" -> ReceivingDocument.class;
            case "unit" -> UnitOfMeasurement.class;
            default -> throw new IllegalArgumentException("Поле не является ссылкой источника");
        };
    }

    private <T extends BaseEntity> List<Option> options(Class<T> type, List<String> fields, String term, int limit, Function<T, String> caption) {
        return lookup.search(type, fields, term, limit).stream().map(entity -> new Option(entity.getId(), caption.apply(entity))).toList();
    }
}
