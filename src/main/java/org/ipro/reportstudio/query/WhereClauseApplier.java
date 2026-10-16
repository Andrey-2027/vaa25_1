package org.ipro.reportstudio.query;

/** Quote/parenthesis-aware вставка условия в верхнеуровневый JPQL-запрос. */
public final class WhereClauseApplier {
    private WhereClauseApplier() {
    }

    public static String apply(String jpql, String predicate) {
        if (jpql == null || jpql.isBlank()) throw new IllegalArgumentException("JPQL не может быть пустым");
        if (predicate == null || predicate.isBlank()) return jpql;
        int insertion = firstTopLevelClause(jpql, "group", "having", "order");
        String before = insertion < 0 ? jpql : jpql.substring(0, insertion).stripTrailing();
        String after = insertion < 0 ? "" : jpql.substring(insertion).stripLeading();
        int where=firstTopLevelClause(before,"where");
        if (where>=0) return before.substring(0,where)+"where ("+before.substring(where+5).strip()+") and ("+predicate+") "+after;
        return before + " where (" + predicate + ") " + after;
    }

    static int firstTopLevelClause(String text, String... words) {
        int depth = 0;
        for (var token:JpqlLexer.tokenize(text)) {
            if(token.type()==JpqlLexer.Type.LPAREN)depth++;
            else if(token.type()==JpqlLexer.Type.RPAREN)depth--;
            else if(depth==0)for(String word:words)if(token.word(word))return token.position();
        }
        return -1;
    }
}
