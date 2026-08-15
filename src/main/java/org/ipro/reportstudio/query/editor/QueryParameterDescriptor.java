package org.ipro.reportstudio.query.editor;

/**
 * Описание параметра, найденного в JPQL. В первой версии SQM гарантированно
 * возвращает имя параметра; тип может быть неизвестен и тогда выбирается автором.
 */
public record QueryParameterDescriptor(
        String name,
        Class<?> inferredJavaType,
        Class<?> entityClass,
        boolean collection,
        InferenceStatus inferenceStatus,
        String explanation) {

    public enum InferenceStatus {
        INFERRED,
        UNKNOWN,
        AMBIGUOUS
    }

    public static QueryParameterDescriptor unknown(String name) {
        return new QueryParameterDescriptor(name, null, null, false,
                InferenceStatus.UNKNOWN, "Тип не удалось однозначно вывести из JPQL");
    }
}
