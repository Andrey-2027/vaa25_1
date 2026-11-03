package org.ipro.design.source;

/**
 * The names Hibernate derives when a mapping gives none: the entity name for a table, the field
 * name for a column and {@code <field>_id} for a join column, then Hibernate's
 * {@code CamelCaseToUnderscoresNamingStrategy}.
 */
public final class PhysicalNames {
    private PhysicalNames() {}

    /** An underscore goes before an upper-case letter that follows a lower-case letter or digit and precedes a lower-case letter or digit. */
    public static String implicit(String name) {
        var builder = new StringBuilder(name.replace('.', '_'));
        for (int i = 1; i < builder.length() - 1; i++) {
            if (underscoreRequired(builder.charAt(i - 1), builder.charAt(i), builder.charAt(i + 1))) builder.insert(i++, '_');
        }
        return builder.toString().toLowerCase(java.util.Locale.ROOT);
    }

    public static String implicitTable(String entityName) { return implicit(entityName); }
    public static String implicitColumn(String fieldName) { return implicit(fieldName); }
    public static String implicitJoinColumn(String fieldName) { return implicit(fieldName + "_id"); }

    private static boolean underscoreRequired(char before, char current, char after) {
        return (Character.isLowerCase(before) || Character.isDigit(before)) && Character.isUpperCase(current)
            && (Character.isLowerCase(after) || Character.isDigit(after));
    }
}
