package org.ipro.reportstudio.query;

/** Ссылка вычисляемого выражения на объявленный параметр запроса. */
public record VisualQueryParameter(String name) {
    public VisualQueryParameter {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Некорректное имя параметра: " + name);
        }
    }
}
