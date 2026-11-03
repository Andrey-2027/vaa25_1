package org.ipro.design.source;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PhysicalNamesTest {
    /** Pinned against Hibernate 7.2.12 CamelCaseToUnderscoresNamingStrategy. */
    @Test void followsTheHibernateNamingStrategy() {
        String[][] expected = {
            {"ItemRate", "item_rate"}, {"XMLParser", "xmlparser"}, {"nomAttrType", "nom_attr_type"}, {"attrType2", "attr_type2"},
            {"getURLValue", "geturlvalue"}, {"A", "a"}, {"AB", "ab"}, {"aB", "ab"}, {"aBc", "a_bc"}, {"Order", "order"},
            {"ABc", "abc"}, {"OrderLine", "order_line"}, {"line1Item", "line1_item"}, {"URL", "url"},
            {"UserFormSettings", "user_form_settings"}, {"x_Y", "x_y"}, {"teamId", "team_id"}, {"aBC", "abc"},
            {"aB1c", "a_b1c"}, {"ab1C", "ab1c"}, {"aB1", "a_b1"}, {"aB_c", "ab_c"}, {"a1Bc", "a1_bc"}, {"aBcD", "a_bcd"},
            {"aBcDe", "a_bc_de"}};
        for (String[] pair : expected) assertEquals(pair[1], PhysicalNames.implicit(pair[0]), pair[0]);
    }

    @Test void joinColumnsAddTheIdentifierSuffixBeforeConversion() {
        assertEquals("unit_id", PhysicalNames.implicitJoinColumn("unit"));
        assertEquals("transferring_workshop_id", PhysicalNames.implicitJoinColumn("transferringWorkshop"));
        assertEquals("team_id", PhysicalNames.implicitColumn("teamId"));
    }
}
