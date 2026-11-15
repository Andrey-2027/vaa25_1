package org.ipro.query.list;

/** Один список, объявленный в коде потребителя платформы. */
@FunctionalInterface
public interface QueryListDeclaration {
    QueryListDefinition definition();
}
