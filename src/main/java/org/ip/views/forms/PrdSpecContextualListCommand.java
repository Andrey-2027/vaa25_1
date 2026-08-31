package org.ip.views.forms;

import org.ip.form.registry.ListCommand;
import org.ip.form.registry.ListCommandContext;
import org.ip.model.Nomenclature;
import org.ip.model.PrdSpec;
import org.ipro.crud.LookupService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Пилот связи source → target для реестра Спецификаций.
 *
 * <p>Из выбранной спецификации берёт два значения контекста — Journal и typeNom её
 * номенклатуры — и открывает именованный составной View с фиксированными ограничениями
 * списка. Вложенный View уже добавляет свою локальную команду.</p>
 */
@Component
public class PrdSpecContextualListCommand implements ListCommand<PrdSpec> {

    private final LookupService lookupService;

    public PrdSpecContextualListCommand(LookupService lookupService) {
        this.lookupService = lookupService;
    }

    @Override
    public Class<PrdSpec> entityClass() {
        return PrdSpec.class;
    }

    @Override
    public String title() {
        return "Контекст: журнал и тип";
    }

    @Override
    public String iconName() {
        return "FILTER";
    }

    @Override
    public boolean requiresSelection() {
        return true;
    }

    /** Команда является точкой входа только для обычного списка спецификаций. */
    @Override
    public boolean appliesToVariant(String variant) {
        return variant == null;
    }

    @Override
    public void execute(ListCommandContext<PrdSpec> context) {
        PrdSpec selected = context.selectedItem();
        if (selected == null || selected.getJournal() == null || selected.getJournal().getId() == null) {
            return;
        }

        Long journalId = selected.getJournal().getId();
        String typeNom = resolveTypeNom(selected);

        List<Map<String, Object>> seedFilters = new ArrayList<>();
        seedFilters.add(Map.of("path", "journal.id", "value", journalId));
        if (typeNom != null && !typeNom.isBlank()) {
            seedFilters.add(Map.of("path", "nomenclature.typeNom", "value", typeNom));
        }

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("sourceId", selected.getId());
        parameters.put("journalId", journalId);
        if (typeNom != null && !typeNom.isBlank()) {
            parameters.put("typeNom", typeNom);
        }
        parameters.put("seedFilters", seedFilters);
        // Внутренний ListForm будет содержать локальную команду View, а не команды
        // внешнего реестра, чтобы не получить рекурсивный вход в этот же View.
        parameters.put("suppressListCommands", true);
        parameters.put("contextView", "prdSpec-contextual");

        context.coordinator().openListForm(PrdSpec.class, "contextual", parameters);
    }

    private String resolveTypeNom(PrdSpec selected) {
        Nomenclature nomenclature = selected.getNomenclature();
        if (nomenclature == null) {
            return null;
        }
        if (nomenclature.getId() == null) {
            return nomenclature.getTypeNom();
        }
        return lookupService.findById(Nomenclature.class, nomenclature.getId())
            .map(Nomenclature::getTypeNom)
            .orElse(nomenclature.getTypeNom());
    }
}
