package org.ipro.design;

/** Shared by validation and future editors; resolving never changes the draft. */
public final class DraftSuggestions {
    private DraftSuggestions() {}
    public static String rootPackage(EntityDraft draft, ProjectDesignContext context) {
        return draft.entity().packageName().resolve(context.defaultPackage() == null ? "" : context.defaultPackage());
    }
    public static String rootTable(EntityDraft draft) { return draft.entity().tableName().resolve(RecipeCatalog.snake(draft.entity().className())); }
    public static String ownerColumn(OwnerDraft owner) { return owner.columnName().resolve(RecipeCatalog.snake(owner.fieldName()) + "_id"); }
    public static String rowPackage(SectionDraft section, String rootPackage) { return section.packageName().resolve(rootPackage); }
    public static String rowTable(SectionDraft section) { return section.tableName().resolve(RecipeCatalog.snake(section.rowClassName())); }
    public static String parentField(SectionDraft section) { return section.parentFieldName().resolve("owner"); }
    public static String parentColumn(SectionDraft section) { return section.parentColumnName().resolve(RecipeCatalog.snake(parentField(section)) + "_id"); }
    public static String column(FieldDraft field) {
        return field.columnName().resolve(RecipeCatalog.snake(field.name()) + (field.type().equals("REFERENCE") ? "_id" : ""));
    }
}
