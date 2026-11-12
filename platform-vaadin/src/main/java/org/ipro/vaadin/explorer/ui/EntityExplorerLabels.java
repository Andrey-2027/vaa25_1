package org.ipro.vaadin.explorer.ui;

import org.ipro.metadata.annotation.EntityKind;

/** Shared Russian UI vocabulary; AUTO and missing classification are never PLAIN. */
public final class EntityExplorerLabels {
    private EntityExplorerLabels() { }
    public static String kind(EntityKind kind, boolean plural) {
        if (kind == null || kind == EntityKind.AUTO) return plural ? "Неизвестный вид" : "Вид не определён";
        return switch (kind) {
            case CATALOG -> plural ? "Справочники" : "Справочник";
            case DOCUMENT -> plural ? "Документы" : "Документ";
            case INFORMATION_REGISTER -> plural ? "Регистры сведений" : "Регистр сведений";
            case PLAIN -> plural ? "Простые сущности" : "Простая сущность";
            case AUTO -> throw new IllegalStateException();
        };
    }
    public static String exposure(String exposure) {
        if (exposure == null) return "Экспозиция неизвестна";
        return switch (exposure) {
            case "STANDARD_ROOT" -> "Основная";
            case "OWNED_ROW" -> "Строка табличной части";
            case "INTERNAL_STORE" -> "Служебное хранилище";
            case "UNCLASSIFIED" -> "Не классифицирована";
            case "UNKNOWN" -> "Экспозиция неизвестна";
            default -> exposure;
        };
    }
    public static String entities(int count) {
        int last = count % 10, hundred = count % 100;
        return count + " " + (last == 1 && hundred != 11 ? "сущность"
            : last >= 2 && last <= 4 && (hundred < 12 || hundred > 14) ? "сущности" : "сущностей");
    }
}
