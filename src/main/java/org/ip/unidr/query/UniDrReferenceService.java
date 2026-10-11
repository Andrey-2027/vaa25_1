package org.ip.unidr.query;

import org.ip.model.*;
import org.ip.unidr.run.UniDrAccess;
import org.ip.unidr.run.UniDrRunException;
import org.ipro.crud.BaseEntity;
import org.ipro.crud.EntityLookup;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.function.Function;

/** The existing canonical lookup is the only reference read path; no caller-supplied Java types. */
public final class UniDrReferenceService {
    public record Option(Long id, String caption) implements org.ipro.metadata.HasDisplayName {
        @Override public String getDisplayName() {return caption;}
        @Override public String toString() {return java.util.Objects.toString(caption,"");}
    }
    private final EntityLookup lookup;
    private final UniDrAccess access;
    private final TransactionTemplate reads;

    public UniDrReferenceService(EntityLookup lookup, UniDrAccess access, PlatformTransactionManager transactions) {
        this.lookup = lookup; this.access = access;
        reads = new TransactionTemplate(transactions); reads.setReadOnly(true);
    }

    public List<Option> search(String fieldId, String term, int limit) {
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

    public void requireVisible(String fieldId, Long id) {
        selected(fieldId, id);
    }

    /** Resolves a configured/default ID through the same guarded lookup as searches. */
    public Option selected(String fieldId, Long id) {
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
    public static Class<? extends BaseEntity> referenceType(String fieldId) {
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
