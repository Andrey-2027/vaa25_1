package org.ipro.query;

import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import org.ipro.crud.BaseEntity;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.FormColumnDeclarations;
import org.ipro.metadata.ManagedEntityTypes;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.rls.RlsReadGate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class QueryFieldLabelsTest {
    @Entity @EntityMetadata
    static class Target extends BaseEntity { @FieldMetadata(label = "Имя цели") String name; }

    @Entity @EntityMetadata(listColumns = {"code", "target.name"})
    static class Item extends BaseEntity {
        @FieldMetadata(label = "Артикул") String code;
        @FieldMetadata(label = "Наименование") String name;
        @ManyToOne @FieldMetadata(label = "Ссылка") Target target;
    }

    private QueryBuilderMetadataCatalog catalog() {
        var declarations = new FormColumnDeclarations();
        var columns = FormColumnDeclarations.explicit("code", "target.name");
        columns.column("code").setLabel("Экранный код");
        columns.column("target.name").setLabel("Чужая подпись");
        declarations.register(new FormColumnDeclarations.Declaration(Item.class,
            FormColumnDeclarations.Kind.LIST, null, columns, "", "ItemListConfig"));
        declarations.freeze();
        var metadata = new MetadataResolver();
        metadata.setFormColumnDeclarations(declarations);
        assertThat(metadata.resolve(Item.class).getListColumnPaths()).extracting(ColumnPath::getLabel)
            .containsExactly("Экранный код", "Чужая подпись");
        var gate = mock(RlsReadGate.class);
        when(gate.canRead(any(), eq("reader"))).thenReturn(true);
        return new QueryBuilderMetadataCatalog(new ManagedEntityTypes(List.of(Item.class, Target.class)),
            metadata, gate, () -> "reader");
    }

    @Test
    void fieldAndAssociationCaptionsComeFromFieldMetadataRegardlessOfListCompositionAndLabels() {
        var entity = catalog().root("Item");
        assertThat(entity.fields()).extracting(QueryBuilderMetadataCatalog.Field::caption)
            .containsExactly("Артикул", "Наименование");
        assertThat(entity.fields()).allSatisfy(field -> assertThat(field.aliases()).isEmpty());
        assertThat(entity.associations()).extracting(QueryBuilderMetadataCatalog.Association::caption)
            .containsExactly("Ссылка");
    }

    @Test
    void textualInputUsesCanonicalCaptionAndDoesNotRecognizeFormOnlyCaption() {
        var parser = new NaturalLanguageQueryParser(catalog()).addSourceAlias("товара", "Item");
        var canonical = parser.parse("Выбери артикул из товара");
        assertThat(canonical.warnings()).isEmpty();
        assertThat(canonical.definition().selectFields()).extracting(VisualQueryDefinition.SelectField::path)
            .containsExactly("e.code");
        assertThat(parser.parse("Выбери Экранный код из товара").warnings()).isNotEmpty();
    }

    @Test
    void savedTechnicalPathsAndExplicitResultNamesCompileIdenticallyWithLegacyAliases() {
        var current = catalog();
        var legacy = mock(QueryBuilderMetadataCatalog.class);
        var currentItem = current.root("Item");
        var oldFields = currentItem.fields().stream().map(field -> new QueryBuilderMetadataCatalog.Field(
            field.name(), field.caption(), field.javaType(), field.technical(), List.of("Экранный код"))).toList();
        when(legacy.root("Item")).thenReturn(new QueryBuilderMetadataCatalog.Entity("Item", Item.class,
            oldFields, currentItem.associations()));
        var codec = new VisualQueryDefinitionJsonCodec();
        var definition = new VisualQueryDefinition("Item", "i",
            List.of(new VisualQueryDefinition.SelectField("i.code", "saved_code")));
        var json = codec.write(definition);
        var before = VisualQueryCompiler.compile(codec.read(json), legacy);
        var after = VisualQueryCompiler.compile(codec.read(json), current);
        assertThat(after).isEqualTo(before);
        assertThat(after.jpql()).isEqualTo("select i.code as saved_code from Item i");
        assertThat(after.fields()).extracting(org.ipro.report.data.QueryField::caption).containsExactly("Артикул");
    }
}
