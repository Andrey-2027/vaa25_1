package org.ip.application.register;

import org.ip.repository.AccountingObjectRegisterRepository;
import org.ip.model.*;
import org.ipro.crud.AggregateCommandPayload;
import org.ipro.crud.MetadataDrivenAggregateSaveService;
import org.ipro.crud.ValidationException;
import org.ipro.data.EntityDataAccess;
import org.ipro.events.AggregateSection;
import org.ipro.events.EventSource;
import org.ipro.rls.RlsPolicyEnforcer;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.c5.C5PermissionDecisionService;
import org.ipro.rls.c5.C5Operation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class AccountingObjectSaveService {
    private final AccountingObjectRegisterRepository persistence;
    private final EntityDataAccess access;
    private final MetadataDrivenAggregateSaveService aggregateSave;
    private final RlsPolicyEnforcer rights;
    private final AccountingObjectRegisterService registerReader;
    private final C5PermissionDecisionService permissions;
    private final RlsCurrentUser currentUser;

    public AccountingObjectSaveService(AccountingObjectRegisterRepository persistence, EntityDataAccess access,
            MetadataDrivenAggregateSaveService aggregateSave, RlsPolicyEnforcer rights,
            AccountingObjectRegisterService registerReader, C5PermissionDecisionService permissions,
            RlsCurrentUser currentUser) {
        this.persistence = persistence; this.access = access; this.aggregateSave = aggregateSave;
        this.rights = rights;
        this.registerReader = registerReader;
        this.permissions = permissions; this.currentUser = currentUser;
    }

    @Transactional
    public <D extends AccountingObjectDocument> MetadataDrivenAggregateSaveService.AggregateSaveResult<D> save(
            D document, List<MetadataDrivenAggregateSaveService.SectionInput> sections, EventSource source) {
        Objects.requireNonNull(document, "document");
        Class<? extends AccountingObjectItem> itemClass;
        if (document.getClass() == AccountingObjectInstallation.class) itemClass = AccountingObjectInstallationItem.class;
        else if (document.getClass() == AccountingObjectCorrection.class) itemClass = AccountingObjectCorrectionItem.class;
        else throw new ValidationException("Неподдерживаемый документ объекта учёта");
        if (sections.size() != 1 || sections.getFirst().rowType() != itemClass) {
            throw new ValidationException("Сохранение объекта учёта требует полного списка позиций документа");
        }
        String username = currentUser.requireAuthenticatedUsername();
        permissions.requireEntity(username, document.getClass(), document.getId() == null ? C5Operation.CREATE : C5Operation.UPDATE);
        permissions.requireEntity(username, NomenclatureAccountingObject.class, C5Operation.CREATE);
        rights.requireUpdate(document);
        if (document.getWorkshop() == null || document.getWorkshop().getId() == null || document.getDate() == null) {
            throw new ValidationException("Укажите сохранённый цех и дату документа");
        }
        document.setDate(document.getDate());
        Workshop workshop = requireReference(Workshop.class, document.getWorkshop().getId(), "Цех");
        // Every command locks the same existing workshop before inspecting history.
        // This also serializes two first installations that initially see no register row.
        persistence.lockWorkshop(workshop.getId());
        document.setWorkshop(workshop);

        List<AccountingObjectItem> positions = new ArrayList<>();
        List<AccountingObjectCorrectionItem> corrections = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (Object input : sections.getFirst().rows()) {
            if (!itemClass.isInstance(input)) throw new ValidationException("Неверный тип позиции документа");
            AccountingObjectItem item = (AccountingObjectItem) input;
            if (item.getNomenclature() == null || item.getNomenclature().getId() == null
                    || item.getAccountingType() == null || item.getAccountingType().getId() == null) {
                throw new ValidationException("В каждой позиции укажите номенклатуру и тип учёта");
            }
            Long nomenclatureId = item.getNomenclature().getId();
            if (!seen.add(nomenclatureId)) throw new ValidationException("Номенклатура повторяется в позициях документа");
            item.setNomenclature(requireReference(Nomenclature.class, nomenclatureId, "Номенклатура"));
            item.setAccountingType(requireReference(AccountingType.class, item.getAccountingType().getId(), "Тип учёта"));
            if (item instanceof AccountingObjectCorrectionItem correctionItem) corrections.add(correctionItem);
            positions.add(item);
        }
        if (positions.isEmpty()) throw new ValidationException("Добавьте хотя бы одну позицию документа");
        if (!corrections.isEmpty()) {
            // Один batch-срез на весь документ вместо чтения на каждую строку (Р9).
            Map<Long, AccountingType> currentTypes =
                registerReader.currentTypes((AccountingObjectCorrection) document, seen);
            for (AccountingObjectCorrectionItem item : corrections) {
                AccountingType current = currentTypes.get(item.getNomenclature().getId());
                if (current == null) {
                    throw new ValidationException("Номенклатура отсутствует в срезе выбранного цеха на дату корректировки");
                }
                item.setCurrentAccountingType(current);
            }
        }
        // ADR-0026: позиции объявляются один раз и для секции, и для lifecycle-хуков документа
        // (beforeSave — вето, onSave — генерация строк). Объявление даёт только закрытие, а
        // управляемую копию на обновлении связывает с ним платформа.
        try (var command = AggregateCommandPayload.open(document,
                List.of(new AggregateSection(itemClass, positions)))) {
            return aggregateSave.save(document, sections, source);
        }
    }

    private <T> T requireReference(Class<T> type, Long id, String label) {
        return access.detail(type, id).orElseThrow(() -> new ValidationException(label + " отсутствует или недоступен"));
    }
}

