package org.ipro.unidr.query;

import org.ipro.unidr.model.UniDrQuerySource;
import org.ipro.query.document.ReportComputedValue;
import org.ipro.query.document.ReportParamKind;
import org.ipro.query.document.ReportParamSource;
import org.ipro.query.document.ReportQuerySource;
import org.ipro.query.model.QueryComputedValue;
import org.ipro.query.model.QueryDefinition;
import org.ipro.query.model.QueryParameter;
import org.ipro.query.model.QueryParameterKind;
import org.ipro.query.model.QueryScalarType;
import org.ipro.query.model.QuerySource;
import org.ipro.query.model.QueryValueSource;

/**
 * Переходный адаптер UniDR → нейтральное описание запроса.
 *
 * <p>Сохраняет существующий JSON-контракт и имена binding'ов. UniDR-специфичные
 * тип/диапазон/домен остаются в его собственной модели; query backend получает
 * только то, что нужно для анализа, параметров и выполнения.</p>
 */
public final class UniDrQueryDefinitions {

    public static QueryDefinition of(UniDrQuerySource source) {
        if (source == null) {
            throw new IllegalArgumentException("Источник запроса не задан");
        }
        return new QueryDefinition(source.jpql(),
                source.kind() == ReportQuerySource.VISUAL ? QuerySource.VISUAL : QuerySource.MANUAL,
                source.visualQueryJson(), null,
                source.parameters().stream().map(UniDrQueryDefinitions::parameter).toList());
    }

    private static QueryParameter parameter(UniDrQuerySource.Param parameter) {
        return new QueryParameter(
                parameter.name(),
                parameter.name(),
                parameter.caption(),
                kindOf(parameter.kind()),
                QueryScalarType.UNRESOLVED,
                parameter.entityClass(),
                valueSourceOf(parameter.valueSource()),
                parameter.required(),
                parameter.showOnForm(),
                parameter.defaultValue(),
                computedOf(parameter.computed()),
                null,
                parameter.position());
    }

    private static QueryParameterKind kindOf(ReportParamKind kind) {
        return switch (kind) {
            case SCALAR -> QueryParameterKind.SCALAR;
            case PERIOD -> QueryParameterKind.RANGE;
            case ENTITY -> QueryParameterKind.ENTITY;
            case ENTITY_LIST -> QueryParameterKind.ENTITY_LIST;
        };
    }

    private static QueryValueSource valueSourceOf(ReportParamSource source) {
        return switch (source) {
            case FORM -> QueryValueSource.FORM;
            case DEFAULT -> QueryValueSource.FIXED;
            case COMPUTED -> QueryValueSource.COMPUTED;
            case CONTEXT -> QueryValueSource.CONTEXT;
        };
    }

    private static QueryComputedValue computedOf(ReportComputedValue computed) {
        return switch (computed) {
            case NONE -> QueryComputedValue.NONE;
            case NOW -> QueryComputedValue.NOW;
            case CURRENT_USER -> QueryComputedValue.CURRENT_USER;
            case RLS_ORG -> QueryComputedValue.RLS_ORG;
        };
    }

    /** Обратное преобразование: нейтральный запрос → хранимая UniDR-модель. */
    public static UniDrQuerySource source(QueryDefinition definition, String tableEntity) {
        if (definition == null) {
            return null;
        }
        return new UniDrQuerySource(
                definition.source() == QuerySource.VISUAL ? ReportQuerySource.VISUAL : ReportQuerySource.MANUAL,
                definition.jpql(), definition.visualQueryJson(),
                definition.parameters().stream().map(UniDrQueryDefinitions::param).toList(), tableEntity);
    }

    private static UniDrQuerySource.Param param(QueryParameter parameter) {
        return new UniDrQuerySource.Param(parameter.name(), parameter.caption(),
                kindOf(parameter.kind()), parameter.entityClass(), valueSourceOf(parameter.valueSource()),
                parameter.required(), parameter.showOnForm(), parameter.defaultValue(),
                computedOf(parameter.computed()), parameter.position());
    }

    private static ReportParamKind kindOf(QueryParameterKind kind) {
        return switch (kind) {
            case SCALAR -> ReportParamKind.SCALAR;
            case RANGE -> ReportParamKind.PERIOD;
            case ENTITY -> ReportParamKind.ENTITY;
            case ENTITY_LIST -> ReportParamKind.ENTITY_LIST;
        };
    }

    private static ReportParamSource valueSourceOf(QueryValueSource source) {
        return switch (source) {
            case FORM -> ReportParamSource.FORM;
            case FIXED -> ReportParamSource.DEFAULT;
            case COMPUTED -> ReportParamSource.COMPUTED;
            case CONTEXT -> ReportParamSource.CONTEXT;
        };
    }

    private static ReportComputedValue computedOf(QueryComputedValue computed) {
        return switch (computed) {
            case NONE -> ReportComputedValue.NONE;
            case NOW -> ReportComputedValue.NOW;
            case CURRENT_USER -> ReportComputedValue.CURRENT_USER;
            case RLS_ORG -> ReportComputedValue.RLS_ORG;
        };
    }

    private UniDrQueryDefinitions() {
    }
}
