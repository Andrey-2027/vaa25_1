package org.ipro.rest.api;

import org.ipro.rest.api.RestResourceDeclarationException.Code;
import org.ipro.rest.api.RestResourceDefinition.Field;
import org.ipro.rest.api.RestResourceDefinition.Filter;
import org.ipro.rest.api.RestResourceDefinition.Sort;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Mutable declaration builder. Each call to {@link #build()} returns an independent snapshot. */
public final class RestResourceBuilder<T> {

    /** Hard upper bound for page size in the first REST declaration slice. */
    public static final int PLATFORM_MAX_PAGE_SIZE = 200;

    private static final Pattern RESOURCE_KEY = Pattern.compile("^[a-z][a-z0-9-]*$");
    private static final Pattern PUBLIC_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");
    private static final Set<String> RESERVED_FILTER_NAMES = Set.of("fields", "page", "size", "sort");

    private final String resourceKey;
    private final int majorVersion;
    private final Class<T> resourceType;
    private final List<FieldDraft> fieldDeclarations = new ArrayList<>();
    private final List<FilterDraft> filterDeclarations = new ArrayList<>();

    private List<String> listFields;
    private List<String> listDefaultFields;
    private List<String> detailFields;
    private List<String> detailDefaultFields;
    private List<String> sortFields = List.of();
    private final List<SortDraft> defaultSorts = new ArrayList<>();
    private int defaultPageSize = 50;
    private int maxPageSize = PLATFORM_MAX_PAGE_SIZE;
    private int listFieldsCalls;
    private int listDefaultFieldsCalls;
    private int detailFieldsCalls;
    private int detailDefaultFieldsCalls;
    private int sortFieldsCalls;
    private int pageSizeCalls;

    RestResourceBuilder(String resourceKey, int majorVersion, Class<T> resourceType) {
        this.resourceKey = resourceKey;
        this.majorVersion = majorVersion;
        this.resourceType = resourceType;
    }

    public RestResourceBuilder<T> field(String name, RestFieldType type, RestPropertyPath source,
                                        RestNullability nullability) {
        return field(name, type, RestFieldFormat.NONE, source, nullability);
    }

    public RestResourceBuilder<T> field(String name, RestFieldType type, RestFieldFormat format,
                                        RestPropertyPath source, RestNullability nullability) {
        fieldDeclarations.add(new FieldDraft(name, type, format, source, nullability));
        return this;
    }

    public RestResourceBuilder<T> listFields(String... names) {
        listFieldsCalls++;
        listFields = copy(names);
        return this;
    }

    public RestResourceBuilder<T> listDefaultFields(String... names) {
        listDefaultFieldsCalls++;
        listDefaultFields = copy(names);
        return this;
    }

    public RestResourceBuilder<T> detailFields(String... names) {
        detailFieldsCalls++;
        detailFields = copy(names);
        return this;
    }

    public RestResourceBuilder<T> detailDefaultFields(String... names) {
        detailDefaultFieldsCalls++;
        detailDefaultFields = copy(names);
        return this;
    }

    public RestResourceBuilder<T> filter(String name, RestFilterOperator operator,
                                         RestFieldType valueType, RestPropertyPath source) {
        filterDeclarations.add(new FilterDraft(name, operator, valueType, source));
        return this;
    }

    public RestResourceBuilder<T> sortBy(String... names) {
        sortFieldsCalls++;
        sortFields = copy(names);
        return this;
    }

    public RestResourceBuilder<T> defaultSort(String field, RestSortDirection direction) {
        defaultSorts.add(new SortDraft(field, direction));
        return this;
    }

    public RestResourceBuilder<T> pageSize(int defaultSize, int maximumSize) {
        pageSizeCalls++;
        defaultPageSize = defaultSize;
        maxPageSize = maximumSize;
        return this;
    }

    /** Builds a structurally validated immutable snapshot without resolving any JPA metadata. */
    public RestResourceDefinition<T> build() {
        validateResourceIdentity();
        validateSetterCounts();

        Map<String, Field> fields = buildFields();
        Map<String, Filter> filters = buildFilters(fields);
        List<String> listMaximum = validateFieldSet("list.fields", listFields, fields);
        List<String> listDefaults = validateFieldSet("list.defaultFields", listDefaultFields, fields);
        List<String> detailMaximum = validateFieldSet("detail.fields", detailFields, fields);
        List<String> detailDefaults = validateFieldSet("detail.defaultFields", detailDefaultFields, fields);

        validateDefaultSubset("list.defaultFields", listDefaults, listMaximum);
        validateDefaultSubset("detail.defaultFields", detailDefaults, detailMaximum);
        validateRequiredId(fields, listMaximum, listDefaults, detailMaximum, detailDefaults);

        List<String> allowedSortFields = validateSortFields(fields, listMaximum);
        Sort defaultSort = validateDefaultSort(allowedSortFields);
        validatePageSize();

        return new RestResourceDefinition<>(resourceKey, majorVersion, resourceType,
            fields, listMaximum, listDefaults, detailMaximum, detailDefaults, filters,
            allowedSortFields, defaultSort, defaultPageSize, maxPageSize);
    }

    private void validateResourceIdentity() {
        if (resourceKey == null || !RESOURCE_KEY.matcher(resourceKey).matches()) {
            fail(Code.INVALID_RESOURCE_KEY, null, "expected ^[a-z][a-z0-9-]*$");
        }
        if (majorVersion < 1) {
            fail(Code.INVALID_MAJOR_VERSION, null, "major version must be positive");
        }
        if (resourceType == null) {
            fail(Code.RESOURCE_TYPE_REQUIRED, null, "resource Java type is required");
        }
    }

    private void validateSetterCounts() {
        if (listFieldsCalls > 1) fail(Code.DUPLICATE_CONFIGURATION, "list.fields", "configured more than once");
        if (listDefaultFieldsCalls > 1) fail(Code.DUPLICATE_CONFIGURATION, "list.defaultFields", "configured more than once");
        if (detailFieldsCalls > 1) fail(Code.DUPLICATE_CONFIGURATION, "detail.fields", "configured more than once");
        if (detailDefaultFieldsCalls > 1) fail(Code.DUPLICATE_CONFIGURATION, "detail.defaultFields", "configured more than once");
        if (sortFieldsCalls > 1) fail(Code.DUPLICATE_CONFIGURATION, "sort.fields", "configured more than once");
        if (defaultSorts.size() > 1) fail(Code.DUPLICATE_CONFIGURATION, "sort.default", "configured more than once");
        if (pageSizeCalls > 1) fail(Code.DUPLICATE_CONFIGURATION, "page.size", "configured more than once");
    }

    private Map<String, Field> buildFields() {
        Map<String, Field> fields = new LinkedHashMap<>();
        for (FieldDraft draft : fieldDeclarations) {
            validatePublicName(draft.name, Code.INVALID_FIELD_NAME, draft.name);
            if (fields.containsKey(draft.name)) {
                fail(Code.DUPLICATE_FIELD, draft.name, "field names must be unique");
            }
            if (draft.type == null) fail(Code.FIELD_TYPE_REQUIRED, draft.name, "field type is required");
            if (draft.nullability == null) {
                fail(Code.FIELD_NULLABILITY_REQUIRED, draft.name, "nullability is required");
            }
            if (!validPath(draft.source)) {
                fail(Code.INVALID_PROPERTY_PATH, draft.name, "expected a dotted Java-property path");
            }
            if (draft.format == null || !formatMatches(draft.type, draft.format)) {
                fail(Code.INCOMPATIBLE_FIELD_FORMAT, draft.name,
                    "format must be NONE or a string-compatible UUID, DATE or DATE_TIME hint");
            }
            fields.put(draft.name, new Field(draft.name, draft.type, draft.nullability,
                draft.format, draft.source));
        }
        if (!fields.containsKey("id")) {
            fail(Code.MISSING_ID_FIELD, "id", "declare the mandatory public id field");
        }
        return fields;
    }

    private Map<String, Filter> buildFilters(Map<String, Field> fields) {
        Map<String, Filter> filters = new LinkedHashMap<>();
        for (FilterDraft draft : filterDeclarations) {
            validatePublicName(draft.name, Code.INVALID_FILTER_NAME, draft.name);
            if (RESERVED_FILTER_NAMES.contains(draft.name.toLowerCase(Locale.ROOT))) {
                fail(Code.RESERVED_FILTER_NAME, draft.name, "name conflicts with a system query parameter");
            }
            if (filters.containsKey(draft.name)) {
                fail(Code.DUPLICATE_FILTER, draft.name, "filter names must be unique");
            }
            if (draft.operator == null) {
                fail(Code.FILTER_OPERATOR_REQUIRED, draft.name, "filter operator is required");
            }
            if (draft.valueType == null) {
                fail(Code.FILTER_VALUE_TYPE_REQUIRED, draft.name, "filter value type is required");
            }
            Field sameNamedField = fields.get(draft.name);
            if (sameNamedField != null && sameNamedField.type() != draft.valueType) {
                fail(Code.FILTER_FIELD_TYPE_MISMATCH, draft.name,
                    "filter value type must match the same-named public field");
            }
            if (!validPath(draft.source)) {
                fail(Code.INVALID_FILTER_PATH, draft.name, "expected a dotted Java-property path");
            }
            filters.put(draft.name, new Filter(draft.name, draft.operator, draft.valueType, draft.source));
        }
        return filters;
    }

    private List<String> validateFieldSet(String operation, List<String> names, Map<String, Field> fields) {
        if (names == null || names.isEmpty()) {
            fail(Code.EMPTY_FIELD_SET, operation, "field set must not be empty");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String name : names) {
            validatePublicName(name, Code.INVALID_FIELD_NAME, operation);
            if (!seen.add(name)) {
                fail(Code.DUPLICATE_FIELD, operation + "." + name, "field set contains a duplicate");
            }
            if (!fields.containsKey(name)) {
                fail(Code.UNKNOWN_FIELD, operation + "." + name, "field was not declared");
            }
        }
        return List.copyOf(names);
    }

    private void validateDefaultSubset(String operation, List<String> defaults, List<String> maximum) {
        if (!maximum.containsAll(defaults)) {
            String missing = defaults.stream().filter(name -> !maximum.contains(name)).findFirst().orElse("?");
            fail(Code.DEFAULT_FIELDS_NOT_SUBSET, operation + "." + missing,
                "default fields must be included in the maximum field set");
        }
    }

    private void validateRequiredId(Map<String, Field> fields, List<String> listMaximum,
                                    List<String> listDefaults, List<String> detailMaximum,
                                    List<String> detailDefaults) {
        if (!fields.containsKey("id")) fail(Code.MISSING_ID_FIELD, "id", "declare the mandatory public id field");
        if (!listMaximum.contains("id") || !detailMaximum.contains("id")) {
            fail(Code.ID_NOT_IN_MAX_FIELDS, "id", "id must be in list and detail maximum field sets");
        }
        if (!listDefaults.contains("id") || !detailDefaults.contains("id")) {
            fail(Code.ID_NOT_IN_DEFAULT_FIELDS, "id", "id must be in list and detail default field sets");
        }
    }

    private List<String> validateSortFields(Map<String, Field> fields, List<String> listMaximum) {
        List<String> names = sortFields == null ? List.of() : sortFields;
        Set<String> seen = new LinkedHashSet<>();
        for (String name : names) {
            validatePublicName(name, Code.INVALID_FIELD_NAME, "sort.fields");
            if (!seen.add(name)) {
                fail(Code.DUPLICATE_SORT_FIELD, name, "sort field names must be unique");
            }
            if (!fields.containsKey(name) || !listMaximum.contains(name)) {
                fail(Code.UNSORTABLE_FIELD, name, "sort field must be declared and available in list");
            }
        }
        return List.copyOf(names);
    }

    private Sort validateDefaultSort(List<String> allowedSortFields) {
        if (defaultSorts.isEmpty()) return null;
        SortDraft draft = defaultSorts.getFirst();
        if (draft.field == null || draft.direction == null || !allowedSortFields.contains(draft.field)) {
            fail(Code.INVALID_DEFAULT_SORT, "sort.default", "default sort must use one declared sort field and direction");
        }
        return new Sort(draft.field, draft.direction);
    }

    private void validatePageSize() {
        if (defaultPageSize < 1 || maxPageSize < 1 || defaultPageSize > maxPageSize
            || maxPageSize > PLATFORM_MAX_PAGE_SIZE) {
            fail(Code.INVALID_PAGE_SIZE, "page.size",
                "require 1 <= default <= maximum <= " + PLATFORM_MAX_PAGE_SIZE);
        }
    }

    private void validatePublicName(String name, Code code, String element) {
        if (name == null || !PUBLIC_NAME.matcher(name).matches()) {
            fail(code, element, "expected ^[A-Za-z][A-Za-z0-9_]*$");
        }
    }

    private boolean validPath(RestPropertyPath path) {
        if (path == null || path.value() == null || path.value().isEmpty()) return false;
        String[] segments = path.value().split("\\.", -1);
        for (String segment : segments) {
            if (segment.isEmpty()) return false;
            int[] codePoints = segment.codePoints().toArray();
            if (codePoints.length == 0 || !Character.isJavaIdentifierStart(codePoints[0])) return false;
            for (int i = 1; i < codePoints.length; i++) {
                if (!Character.isJavaIdentifierPart(codePoints[i])) return false;
            }
        }
        return true;
    }

    private boolean formatMatches(RestFieldType type, RestFieldFormat format) {
        return format == RestFieldFormat.NONE
            || (type == RestFieldType.STRING && (format == RestFieldFormat.UUID
                || format == RestFieldFormat.DATE || format == RestFieldFormat.DATE_TIME));
    }

    private void fail(Code code, String element, String detail) {
        throw new RestResourceDeclarationException(code, resourceKey, element, detail);
    }

    private List<String> copy(String[] names) {
        return names == null ? null : new ArrayList<>(Arrays.asList(names.clone()));
    }

    private record FieldDraft(String name, RestFieldType type, RestFieldFormat format,
                              RestPropertyPath source, RestNullability nullability) {
    }

    private record FilterDraft(String name, RestFilterOperator operator, RestFieldType valueType,
                               RestPropertyPath source) {
    }

    private record SortDraft(String field, RestSortDirection direction) {
    }
}
