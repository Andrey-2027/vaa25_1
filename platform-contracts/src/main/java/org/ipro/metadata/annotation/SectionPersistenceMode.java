package org.ipro.metadata.annotation;

/**
 * Поддерживаемая платформой семантика сохранения owned-секции.
 * Новые режимы добавляются только вместе с отдельным persistence contract.
 */
public enum SectionPersistenceMode {
    /** Подключённый список является полным состоянием секции и заменяет прежние строки. */
    MUTABLE_REPLACE_ALL
}
