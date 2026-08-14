package org.ipro.reportstudio.data;

/**
 * Нормализованная ссылка на сущность в значении ячейки отчёта (Фаза 2):
 * если колонка SELECT возвращает ассоциацию целиком (например, "d.journal"),
 * значение в {@link ReportRow} — это EntityRef(id, caption), а не живой
 * entity-инстанс (не сериализуется в JSON-снапшоты запусков, безопасно
 * покидает транзакцию). caption — displayName сущности, если она его
 * предоставляет (HasDisplayName), иначе null (UI покажет id).
 */
public record EntityRef(Object id, String caption) {

    @Override
    public String toString() {
        return caption != null ? caption + " (" + id + ")" : String.valueOf(id);
    }
}