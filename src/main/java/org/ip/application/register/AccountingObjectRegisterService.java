package org.ip.application.register;

import org.ip.model.*;
import org.ipro.crud.ValidationException;
import org.ipro.data.EntityDataAccess;
import org.ipro.register.RecordedRegisterReader;
import org.ipro.register.RecorderRef;
import org.ipro.register.RecorderTypeRegistry;
import org.ipro.register.RegisterReaderFactory;
import org.ipro.register.RegisterRegistry;
import org.ipro.register.RegisterSliceReader;
import org.ipro.register.SliceSpec;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class AccountingObjectRegisterService {
    public static final int PILOT_SELECTION_LIMIT = 200;
    private final RegisterSliceReader<NomenclatureAccountingObject> slice;
    private final RecordedRegisterReader<NomenclatureAccountingObject> history;
    private final RecorderTypeRegistry recorderTypes;

    /**
     * Читатель среза берётся у фабрики по дескриптору, а не приходит готовым бином приложения:
     * S1 сделал сборку читателя одним местом на дерево. Прикладной bean
     * {@code RegisterSliceReader<NomenclatureAccountingObject>} удалён вместе со срезом — иначе
     * сервис и форма списка собирали бы читатель по-разному, и расхождение было бы не видно.
     */
    public AccountingObjectRegisterService(EntityDataAccess access, RegisterRegistry registers,
            RegisterReaderFactory readers, RecorderTypeRegistry recorderTypes) {
        this.slice = readers.slice(NomenclatureAccountingObject.class);
        history = new RecordedRegisterReader<>(NomenclatureAccountingObject.class, access, registers);
        this.recorderTypes = recorderTypes;
    }

    public Page<NomenclatureAccountingObject> latest(Workshop workshop, LocalDateTime asOf, Pageable page) {
        return latest(workshop, asOf, null, null, page);
    }

    public Page<NomenclatureAccountingObject> history(Workshop workshop, Pageable page) {
        return workshop == null ? history.history(page) : history.history(Map.of("workshop", workshop), page);
    }

    /** Точечное чтение: одна строка без count-запроса (Р9). */
    public Optional<AccountingType> currentType(AccountingObjectCorrection document, Long nomenclatureId) {
        if (document.getWorkshop() == null || document.getDate() == null || nomenclatureId == null) return Optional.empty();
        return slice.first(latestSpec(document.getWorkshop(), document.getDate(), nomenclatureId), recorderOf(document))
            .map(NomenclatureAccountingObject::getAccountingType);
    }

    /**
     * Текущие типы учёта для набора номенклатур документа — один batch-срез на весь набор (Р9).
     *
     * <p>Значение каждой номенклатуры то же, что у {@link #currentType}: последняя запись среза
     * цеха на дату документа без строк самого документа. Номенклатура без строки в срезе в
     * результат не попадает — вызывающий обязан на это отказать.</p>
     */
    public Map<Long, AccountingType> currentTypes(AccountingObjectCorrection document, Collection<Long> nomenclatureIds) {
        if (document.getWorkshop() == null || document.getWorkshop().getId() == null || document.getDate() == null
                || nomenclatureIds == null || nomenclatureIds.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> keys = nomenclatureIds.stream().filter(Objects::nonNull).distinct()
            .map(id -> Map.<String, Object>of("workshop", document.getWorkshop().getId(), "nomenclature", id))
            .toList();
        if (keys.isEmpty()) return Map.of();
        Map<Long, AccountingType> types = new LinkedHashMap<>();
        slice.latestForKeys(document.getDate(), keys, recorderOf(document))
            .forEach(row -> types.put(row.getNomenclature().getId(), row.getAccountingType()));
        return Collections.unmodifiableMap(types);
    }

    /** One bounded page is sufficient for the deliberately small first pilot; exceeding it is an explicit refusal. */
    public List<NomenclatureAccountingObject> selection(AccountingObjectCorrection document) {
        if (document.getWorkshop() == null || document.getDate() == null) {
            throw new ValidationException("Сначала укажите цех и дату корректировки");
        }
        var page = latest(document.getWorkshop(), document.getDate(), null, recorderOf(document),
            PageRequest.of(0, PILOT_SELECTION_LIMIT));
        if (page.getTotalElements() > PILOT_SELECTION_LIMIT) {
            throw new ValidationException("Подбор первой итерации рассчитан не более чем на 200 объектов учёта в одном цехе");
        }
        return page.getContent();
    }

    public void refreshCurrentTypes(AccountingObjectCorrection document, List<AccountingObjectCorrectionItem> rows) {
        if (rows.isEmpty()) return;
        // Owned rows may carry detached lazy parent proxies after loading. The editor
        // must use this form's current header, including its unsaved date/workshop.
        rows.forEach(row -> row.setDocument(document));
        Map<Long, AccountingType> types = new HashMap<>();
        if (document.getWorkshop() != null && document.getDate() != null) {
            selection(document).forEach(row -> types.put(row.getNomenclature().getId(), row.getAccountingType()));
        }
        rows.forEach(row -> row.setCurrentAccountingType(row.getNomenclature() == null ? null
            : types.get(row.getNomenclature().getId())));
    }

    private RecorderRef recorderOf(AccountingObjectCorrection document) {
        return document.getId() == null ? null : recorderTypes.referenceFor(AccountingObjectCorrection.class, document.getId());
    }

    private Page<NomenclatureAccountingObject> latest(Workshop workshop, LocalDateTime asOf,
            Long nomenclatureId, RecorderRef excluded, Pageable pageable) {
        SliceSpec spec = latestSpec(workshop, asOf, nomenclatureId);
        return excluded == null ? slice.latest(spec, pageable) : slice.latestExcludingRecorder(spec, excluded, pageable);
    }

    private SliceSpec latestSpec(Workshop workshop, LocalDateTime asOf, Long nomenclatureId) {
        Objects.requireNonNull(asOf, "asOf");
        List<SliceSpec.Condition> conditions = new ArrayList<>();
        if (workshop != null) conditions.add(new SliceSpec.Condition("workshop", SliceSpec.Operator.EQ, workshop.getId()));
        if (nomenclatureId != null) conditions.add(new SliceSpec.Condition("nomenclature", SliceSpec.Operator.EQ, nomenclatureId));
        return new SliceSpec(NomenclatureAccountingObject.class, SliceSpec.Mode.LATEST,
            asOf, conditions, List.of());
    }
}

