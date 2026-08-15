package org.ipro.reportstudio.query.editor;

import com.vaadin.flow.data.provider.hierarchy.TreeData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;

class QueryMetadataNodeTest {

    @Test
    void permitsEquivalentFieldDescriptorsUnderDifferentEntityRoots() {
        QueryMetadataNode firstCode = property("Код");
        QueryMetadataNode secondCode = property("Код");
        QueryMetadataNode firstEntity = entity("First", firstCode);
        QueryMetadataNode secondEntity = entity("Second", secondCode);

        assertThatCode(() -> {
            TreeData<QueryMetadataNode> data = new TreeData<>();
            data.addItems(List.of(firstEntity, secondEntity), QueryMetadataNode::children);
        }).doesNotThrowAnyException();
    }

    private static QueryMetadataNode entity(String name, QueryMetadataNode field) {
        return new QueryMetadataNode(QueryMetadataNode.Kind.ENTITY, name, name, name,
                true, List.of(field));
    }

    private static QueryMetadataNode property(String name) {
        return new QueryMetadataNode(QueryMetadataNode.Kind.PROPERTY, name, "code", "String",
                true, List.of());
    }
}
