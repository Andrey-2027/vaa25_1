package org.ipro.reportstudio.query;

/** Типизированное правило ORDER BY визуального запроса. */
public record VisualQueryOrder(String path, Direction direction) {
    public VisualQueryOrder {
        if (path == null || path.isBlank() || !path.matches("[A-Za-z_][A-Za-z0-9_.]*")) {
            throw new IllegalArgumentException("Некорректное поле сортировки: " + path);
        }
        direction = direction == null ? Direction.ASC : direction;
    }
    public enum Direction { ASC, DESC }
}
