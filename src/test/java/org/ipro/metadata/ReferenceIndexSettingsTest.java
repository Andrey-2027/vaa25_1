package org.ipro.metadata;

import org.ip.model.Journal;
import org.ip.model.User;
import org.ipro.settings.SettingValue;
import org.ipro.settings.SettingsReverseReferenceSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Обратные ссылки из настроек (§4.3): {@code @Setting(type = ENTITY_REFERENCE, entityClass = ...)}
 * даёт ссылку-колонку {@code SettingValue.entityRefId} на целевую сущность, и
 * {@code ReferenceIndex} с этим источником знает о ней (удаление сущности с такой ссылкой
 * блокируется ReferenceCheckService — см. RlsIntegrationTest.deletionBlockedWhenSettingReferencesEntity).
 */
class ReferenceIndexSettingsTest {

    @Test
    void settingsReverseReferenceSourceFindsEntityRefSettings() {
        SettingsReverseReferenceSource source = new SettingsReverseReferenceSource("org.ip.settings");
        source.afterPropertiesSet();

        assertThat(source.references())
            .containsExactly(new ReferenceIndex.ReverseReference(
                User.class, SettingValue.class, "entityRefId", true));
    }

    @Test
    void referenceIndexIncludesSettingsColumnRefsForTargetEntity() {
        ReferenceIndex index = new ReferenceIndex("org.ip",
            List.of(new SettingsReverseReferenceSource("org.ip.settings")));
        index.afterPropertiesSet();

        List<ReferenceIndex.ReverseReference> userRefs = index.getReverseReferences(User.class);
        assertThat(userRefs).anyMatch(r -> r.columnRef()
            && r.referencingClass() == SettingValue.class
            && "entityRefId".equals(r.fieldName()));

        // настройки не ссылаются на Journal — битая ссылка-страшилка исключена
        assertThat(index.getReverseReferences(Journal.class))
            .noneMatch(r -> r.referencingClass() == SettingValue.class);
    }

    @Test
    void emptyScanYieldsNoReferences() {
        SettingsReverseReferenceSource source = new SettingsReverseReferenceSource("org.ipro.settings");
        source.afterPropertiesSet();

        assertThat(source.references()).isEmpty();
    }
}