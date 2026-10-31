package org.ipro.design;

public record FieldApplications(boolean form, boolean list, boolean selection, boolean table) {
    public static FieldApplications root() { return new FieldApplications(true, true, true, false); }
    public static FieldApplications row() { return new FieldApplications(true, false, false, true); }
}
