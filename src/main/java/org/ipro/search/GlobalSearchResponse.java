package org.ipro.search;

import java.util.List;

/** Ответ поиска с признаком, почему выдача может быть пустой или заполнена общим лимитом. */
public record GlobalSearchResponse(
        String term,
        boolean queryTooShort,
        boolean totalLimitReached,
        List<GlobalSearchResult> results) {

    public GlobalSearchResponse {
        term = term == null ? "" : term;
        results = List.copyOf(results);
    }

    public static GlobalSearchResponse tooShort(String term) {
        return new GlobalSearchResponse(term, true, false, List.of());
    }
}
