package org.ip.application.register;

import org.ip.repository.AccountingObjectRegisterRepository;
import org.ip.model.AccountingObjectDocument;
import org.ip.model.AccountingObjectInstallation;
import org.ip.model.AccountingObjectCorrection;
import org.ip.model.AccountingObjectItem;
import org.ip.model.NomenclatureAccountingObject;
import org.ip.model.AccountingType;
import org.ipro.crud.ValidationException;
import org.ipro.lifecycle.EntitySaveContext;
import org.ipro.register.RecorderRef;
import org.ipro.register.RecorderTypeRegistry;
import org.ipro.register.RegisterRecords;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class AccountingObjectRegisterGeneration {
    private final AccountingObjectRegisterRepository persistence;
    private final RecorderTypeRegistry recorderTypes;
    private final AccountingObjectRegisterService reader;
    private final RegisterRecords registerRecords;

    public AccountingObjectRegisterGeneration(AccountingObjectRegisterRepository persistence, RecorderTypeRegistry recorderTypes,
            AccountingObjectRegisterService reader,
            RegisterRecords registerRecords) {
        this.persistence = persistence; this.recorderTypes = recorderTypes; this.reader = reader;
        this.registerRecords = registerRecords;
    }
    public void onSave(EntitySaveContext<? extends AccountingObjectDocument> context) {
        AccountingObjectDocument document = context.entity();
        // Позиции — из объявленного командой payload'а (ADR-0026). Это тот же список, который
        // команда передала секцией, поэтому «объявленные позиции» и «сохранённые строки» не могут
        // разойтись, а управляемую копию документа на обновлении связала с объявлением платформа.
        List<? extends AccountingObjectItem> positions =
            AccountingObjectPositions.require(context, AccountingObjectPositions.itemType(document.getClass()));
        RecorderRef recorder = recorderTypes.referenceFor(document.getClass(), document.getId());
        // Один batch-срез на весь документ вместо чтения на каждую строку (Р9).
        Map<Long, AccountingType> currentTypes = document instanceof AccountingObjectCorrection correction
            ? reader.currentTypes(correction, positions.stream()
                .map(position -> position.getNomenclature().getId()).toList())
            : Map.of();
        List<NomenclatureAccountingObject> rows = new ArrayList<>();
        for (AccountingObjectItem position : positions) {
            Long nomenclatureId = position.getNomenclature().getId();
            if (document instanceof AccountingObjectInstallation) validateInstallation(document, recorder, nomenclatureId);
            else if (!currentTypes.containsKey(nomenclatureId)) {
                throw new ValidationException("Номенклатура отсутствует в срезе выбранного цеха на дату корректировки");
            }
            NomenclatureAccountingObject row = new NomenclatureAccountingObject();
            row.setRecorder(recorder); row.setPeriod(document.getDate()); row.setWorkshop(document.getWorkshop());
            row.setNomenclature(persistence.nomenclatureReference(nomenclatureId));
            row.setAccountingType(persistence.accountingTypeReference(position.getAccountingType().getId()));
            rows.add(row);
        }
        var records = registerRecords.forRecorder(context);
        records.clear(NomenclatureAccountingObject.class);
        records.add(NomenclatureAccountingObject.class, rows);
    }

    private void validateInstallation(AccountingObjectDocument document, RecorderRef own, Long nomenclatureId) {
        if (persistence.hasOtherInstallation(document.getWorkshop().getId(), nomenclatureId, own)) throw new ValidationException(
            "Объект учёта этой номенклатуры уже существует в истории выбранного цеха");
    }
}

