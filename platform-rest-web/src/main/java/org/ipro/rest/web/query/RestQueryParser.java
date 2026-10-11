package org.ipro.rest.web.query;

import org.ipro.rest.api.RestSortDirection;
import org.ipro.rest.gateway.RestDetailContract;
import org.ipro.rest.gateway.RestFieldFact;
import org.ipro.rest.gateway.RestFilterFact;
import org.ipro.rest.gateway.RestListContract;
import org.ipro.rest.service.RestDetailQuery;
import org.ipro.rest.service.RestListQuery;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Strict v1 query parser (F-REST-READ-4 §2.2). It is the gateway parser callback: it runs only
 * after authentication, resource resolution and API/entity grants, so an authenticated caller
 * without read grants sees 403 rather than 400 for a malformed query.
 *
 * <p>Multi-value parameters are never resolved by picking the first or the last value, integers
 * are signed decimal only (overflow is a rejection, not a truncation), strings are not trimmed and
 * an empty string stays the literal value, while the text {@code null} is never a SQL NULL.</p>
 */
public final class RestQueryParser {

    private static final String FIELDS = "fields";
    private static final String PAGE = "page";
    private static final String SIZE = "size";
    private static final String SORT = "sort";
    private static final String ID = "id";

    private static final Pattern SIGNED_INTEGER = Pattern.compile("[+-]?[0-9]+");
    private static final Pattern DECIMAL = Pattern.compile("[+-]?[0-9]+(\\.[0-9]+)?");

    /** Parse the LIST query; an absent {@code fields} selection means the resource default. */
    public RestListQuery parseList(RestListContract contract, Map<String, String[]> query) {
        Objects.requireNonNull(contract, "contract must not be null");
        Objects.requireNonNull(query, "query must not be null");

        List<String> requestedFields = null;
        int page = 0;
        int size = 0;
        String sortAlias = null;
        RestSortDirection sortDirection = null;
        Map<String, Object> filters = new LinkedHashMap<>();

        for (Map.Entry<String, String[]> entry : query.entrySet()) {
            String key = entry.getKey();
            String value = singleValue(key, entry.getValue());
            switch (key) {
                case FIELDS -> requestedFields = parseFields(publicAliases(contract), value);
                case PAGE -> page = parsePage(value);
                case SIZE -> size = parseSize(contract, value);
                case SORT -> {
                    SortRequest sort = parseSort(contract, value);
                    sortAlias = sort.alias();
                    sortDirection = sort.direction();
                }
                default -> filters.put(key, parseFilter(contract, key, value));
            }
        }

        int effectiveSize = size == 0 ? contract.defaultPageSize() : size;
        long windowEnd = (long) page * effectiveSize + effectiveSize;
        if (windowEnd > contract.maxWindow()) {
            throw new RestQueryException(PAGE, RestQueryReason.OUT_OF_WINDOW,
                "Requested page end exceeds the configured window");
        }
        return new RestListQuery(contract.resource(), contract.major(), requestedFields, filters,
            sortAlias, sortDirection, page, size);
    }

    /** Parse the DETAIL query: only {@code fields} is part of the operation grammar. */
    public RestDetailQuery parseDetail(RestDetailContract contract, Map<String, String[]> query, String id) {
        Objects.requireNonNull(contract, "contract must not be null");
        Objects.requireNonNull(query, "query must not be null");

        List<String> requestedFields = null;
        for (Map.Entry<String, String[]> entry : query.entrySet()) {
            String key = entry.getKey();
            String value = singleValue(key, entry.getValue());
            if (FIELDS.equals(key)) {
                requestedFields = parseFields(publicAliases(contract), value);
            } else {
                throw new RestQueryException(key, RestQueryReason.UNKNOWN_PARAMETER,
                    "Parameter is not part of the DETAIL grammar");
            }
        }
        Object typedId = parseId(contract.idField(), id);
        return new RestDetailQuery(contract.resource(), contract.major(), requestedFields, typedId);
    }

    private static String singleValue(String key, String[] values) {
        if (values == null || values.length == 0) {
            throw new RestQueryException(key, RestQueryReason.UNKNOWN_PARAMETER,
                "Query parameter has no value");
        }
        if (values.length > 1) {
            throw new RestQueryException(key, RestQueryReason.DUPLICATE_PARAMETER,
                "Query parameter must be supplied exactly once");
        }
        return values[0];
    }

    private static List<String> publicAliases(RestListContract contract) {
        List<String> aliases = new ArrayList<>(contract.maximumFields().size());
        contract.maximumFields().forEach(field -> aliases.add(field.alias()));
        return aliases;
    }

    private static List<String> publicAliases(RestDetailContract contract) {
        List<String> aliases = new ArrayList<>(contract.maximumFields().size());
        contract.maximumFields().forEach(field -> aliases.add(field.alias()));
        return aliases;
    }

    private static List<String> parseFields(List<String> maximum, String value) {
        if (value.isEmpty()) {
            throw new RestQueryException(FIELDS, RestQueryReason.EMPTY_FIELDS,
                "Field selection must not be empty");
        }
        List<String> fields = new ArrayList<>();
        for (String token : value.split(",", -1)) {
            if (token.isEmpty() || !maximum.contains(token)) {
                throw new RestQueryException(FIELDS, RestQueryReason.UNKNOWN_FIELD,
                    "Field alias is not published for this operation");
            }
            if (fields.contains(token)) {
                throw new RestQueryException(FIELDS, RestQueryReason.DUPLICATE_FIELD,
                    "Field alias must be selected at most once");
            }
            fields.add(token);
        }
        return List.copyOf(fields);
    }

    private static int parsePage(String value) {
        int page = strictInteger(value, PAGE, RestQueryReason.INVALID_PAGE, "Page index");
        if (page < 0) {
            throw new RestQueryException(PAGE, RestQueryReason.INVALID_PAGE,
                "Page index must not be negative");
        }
        return page;
    }

    private static int parseSize(RestListContract contract, String value) {
        int size = strictInteger(value, SIZE, RestQueryReason.INVALID_SIZE, "Page size");
        if (size == 0) {
            return 0;
        }
        if (size < 1 || size > contract.effectiveMaxPageSize()) {
            throw new RestQueryException(SIZE, RestQueryReason.INVALID_SIZE,
                "Page size is outside the published range");
        }
        return size;
    }

    private static SortRequest parseSort(RestListContract contract, String value) {
        if (value.isEmpty()) {
            throw new RestQueryException(SORT, RestQueryReason.UNKNOWN_SORT,
                "Sort alias is not declared for this resource");
        }
        String[] parts = value.split(",", -1);
        if (parts.length > 2) {
            throw new RestQueryException(SORT, RestQueryReason.MULTIPLE_SORT_KEYS,
                "Only one sort key is allowed");
        }
        String alias = parts[0];
        if (alias.isEmpty() || !contract.sortAliases().contains(alias)) {
            throw new RestQueryException(SORT, RestQueryReason.UNKNOWN_SORT,
                "Sort alias is not declared for this resource");
        }
        RestSortDirection direction = null;
        if (parts.length == 2) {
            direction = switch (parts[1]) {
                case "asc" -> RestSortDirection.ASC;
                case "desc" -> RestSortDirection.DESC;
                default -> throw new RestQueryException(SORT, RestQueryReason.INVALID_SORT_DIRECTION,
                    "Sort direction must be 'asc' or 'desc'");
            };
        }
        return new SortRequest(alias, direction);
    }

    private static Object parseFilter(RestListContract contract, String key, String value) {
        RestFilterFact filter = contract.filters().get(key);
        if (filter == null) {
            RestQueryReason reason = isPublicField(contract, key)
                ? RestQueryReason.UNKNOWN_FILTER
                : RestQueryReason.UNKNOWN_PARAMETER;
            throw new RestQueryException(key, reason,
                reason == RestQueryReason.UNKNOWN_FILTER
                    ? "Field is not enabled as a filter"
                    : "Query parameter is not recognized for this resource");
        }
        return switch (filter.type()) {
            case STRING -> value;
            case LONG -> strictLong(value, key, RestQueryReason.INVALID_FILTER_VALUE);
            case INTEGER -> strictInteger(value, key, RestQueryReason.INVALID_FILTER_VALUE, "Filter value");
            case BOOLEAN -> strictBoolean(value, key, RestQueryReason.INVALID_FILTER_VALUE);
            case DECIMAL -> strictDecimal(value, key, RestQueryReason.INVALID_FILTER_VALUE);
        };
    }

    private static boolean isPublicField(RestListContract contract, String key) {
        return contract.maximumFields().stream().anyMatch(field -> field.alias().equals(key))
            || contract.defaultFields().stream().anyMatch(field -> field.alias().equals(key));
    }

    private static Object parseId(RestFieldFact idField, String id) {
        if (id == null || id.isEmpty()) {
            throw new RestQueryException(ID, RestQueryReason.INVALID_ID, "Detail id must not be empty");
        }
        return switch (idField.type()) {
            case STRING -> id;
            case LONG -> strictLong(id, ID, RestQueryReason.INVALID_ID);
            case INTEGER -> strictInteger(id, ID, RestQueryReason.INVALID_ID, "Detail id");
            case BOOLEAN -> strictBoolean(id, ID, RestQueryReason.INVALID_ID);
            case DECIMAL -> strictDecimal(id, ID, RestQueryReason.INVALID_ID);
        };
    }

    private static Long strictLong(String value, String param, RestQueryReason reason) {
        if (!SIGNED_INTEGER.matcher(value).matches()) {
            throw new RestQueryException(param, reason, "Value must be a signed decimal integer");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException overflow) {
            throw new RestQueryException(param, reason, "Value is outside the published range");
        }
    }

    private static int strictInteger(String value, String param, RestQueryReason reason, String subject) {
        if (!SIGNED_INTEGER.matcher(value).matches()) {
            throw new RestQueryException(param, reason, subject + " must be a signed decimal integer");
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException overflow) {
            throw new RestQueryException(param, reason, subject + " is outside the published range");
        }
    }

    private static Boolean strictBoolean(String value, String param, RestQueryReason reason) {
        return switch (value) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> throw new RestQueryException(param, reason, "Value must be 'true' or 'false'");
        };
    }

    private static BigDecimal strictDecimal(String value, String param, RestQueryReason reason) {
        if (!DECIMAL.matcher(value).matches()) {
            throw new RestQueryException(param, reason, "Value must be a decimal number");
        }
        return new BigDecimal(value);
    }

    private record SortRequest(String alias, RestSortDirection direction) { }
}
