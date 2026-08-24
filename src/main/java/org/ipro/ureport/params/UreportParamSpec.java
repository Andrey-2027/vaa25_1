package org.ipro.ureport.params;

/**
 * Спецификация параметра UReport3 для диалога запуска (UnionReport1.md, Ф2).
 *
 * <p>Источник - {@code <param>} датасетов XML-шаблона (ReportParser).
 * Caption-резолюция (п. 5.1.1): override БД (Ф3) -> метаданные -> name;
 * на текущей итерации caption = name.</p>
 */
public record UreportParamSpec(
        String name,
        String caption,
        ParamUiType uiType,
        String defaultValue,
        boolean required) {

    public UreportParamSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("UreportParamSpec: имя параметра обязательно");
        }
        caption = caption == null || caption.isBlank() ? name : caption;
        uiType = uiType == null ? ParamUiType.STRING : uiType;
    }
}
