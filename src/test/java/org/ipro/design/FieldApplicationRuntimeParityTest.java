package org.ipro.design;

import jakarta.persistence.*;
import org.ipro.crud.BaseEntity;
import org.ipro.crud.StandardCatalogEntity;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.ManagedEntityTypes;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.annotation.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Explicit annotation fixtures validate the portable mapping; no generated files or compile approval. */
class FieldApplicationRuntimeParityTest {
    private FieldDraft field(String name,boolean form,boolean list,boolean selection,boolean table) {
        return new FieldDraft(UUID.randomUUID(),name,name,"STRING",DraftText.auto(),false,FieldDraft.UiRequired.AUTO,
            new ConstraintDraft("100","",""),null,new FieldApplications(form,list,selection,table));
    }
    private CheckedStructure checked() {
        var entity=new EntityProperties("FutureCatalog",DraftText.explicit("example"),"Future",EntityProperties.Titles.auto(),DraftText.auto(),null,"FILE");
        var root=List.of(field("none",false,false,false,false),field("formOnly",true,false,false,false),field("listOnly",false,true,false,false),
            field("selectionOnly",false,false,true,false),field("formList",true,true,false,false),field("formSelection",true,false,true,false),
            field("listSelection",false,true,true,false),field("all",true,true,true,false));
        var row=List.of(field("none",false,false,false,false),field("formOnly",true,false,false,false),field("tableOnly",false,false,false,true),field("both",true,false,false,true));
        var section=new SectionDraft(UUID.randomUUID(),"Rows","FutureRow",DraftText.auto(),DraftText.auto(),DraftText.auto(),DraftText.auto(),row);
        var draft=new EntityDraft(1,RecipeCatalog.STANDARD_CATALOG,UUID.randomUUID(),entity,root,List.of(section));
        var empty=ProjectDesignContext.Names.known(Set.of());
        var context=new ProjectDesignContext("fixture","v1","example",empty,empty,empty,ProjectDesignContext.Names.known(Set.of("FILE")),Map.of(),true);
        var result=new DraftValidator().validate(draft,context);
        assertThat(result.problems()).isEmpty(); return result.checkedStructure().orElseThrow();
    }
    @Test void rootApplicationsMatchEffectiveFormListAndSelectionIncludingInheritedDefaults() {
        var mapping=checked().applicationProjection(); var metadata=new MetadataResolver().resolve(ProjectionProbe.class);
        assertThat(metadata.getListColumnPaths().stream().map(p -> p.getKey()).toList()).isEqualTo(mapping.listColumns());
        assertThat(metadata.getSelectColumnPaths().stream().map(p -> p.getKey()).toList()).isEqualTo(mapping.selectColumns());
        var forms=metadata.getFormFields().stream().map(f -> f.getName()).toList();
        for (var field : mapping.fields()) assertThat(forms.contains(field.property())).isEqualTo(!field.hidden());
        assertThat(forms).contains("code","name").doesNotContain("none","listOnly","selectionOnly","listSelection");
    }
    @Test void rowApplicationsAndOwnershipMatchValidatedSectionMetadata() {
        var resolver=new MetadataResolver();
        var registry=new SectionMetadataRegistry(new ManagedEntityTypes(List.of(ProjectionProbe.class,ProjectionRow.class)),resolver);
        registry.rebuild(); var section=registry.forOwner(ProjectionProbe.class).getFirst();
        var mapping=checked().applicationProjection().sections().getFirst();
        assertThat(section.getParentFieldName()).isEqualTo("owner");
        assertThat(section.getPersistenceMode().name()).isEqualTo(mapping.persistence());
        assertThat(section.getRlsPolicy().name()).isEqualTo(mapping.rlsPolicy());
        for (var field : mapping.fields()) {
            assertThat(section.getFormFields().stream().anyMatch(f -> f.getName().equals(field.property()))).isEqualTo(!field.hidden());
            assertThat(section.getGridFields().stream().anyMatch(f -> f.getName().equals(field.property()))).isEqualTo(field.gridVisible());
        }
    }
    @Test void plainProbeKeepsItsOwnNamesWithoutInheritedCodeOrName() {
        var metadata=new MetadataResolver().resolve(PlainProbe.class);
        assertThat(metadata.getEntityKind()).isEqualTo(EntityKind.PLAIN);
        assertThat(metadata.getFormFields().stream().map(f -> f.getName()).toList()).containsExactly("subjectKey");
    }
    @Entity @Table(name="et9_projection_probe") @TableSections(ProjectionRow.class)
    @EntityMetadata(listColumns={"code","name","listOnly","formList","listSelection","all"},
        selectColumns={"code","name","selectionOnly","formSelection","listSelection","all"})
    static class ProjectionProbe extends StandardCatalogEntity {
        @Column String none;
        @Column @FieldMetadata(grid=@GridColumn(visible=false)) String formOnly;
        @Column String listOnly;
        @Column String selectionOnly;
        @Column @FieldMetadata(grid=@GridColumn(visible=false)) String formList;
        @Column @FieldMetadata(grid=@GridColumn(visible=false)) String formSelection;
        @Column String listSelection;
        @Column @FieldMetadata(grid=@GridColumn(visible=false)) String all;
    }
    @Entity @Table(name="et9_projection_row")
    @TableSectionMetadata(parentEntity=ProjectionProbe.class,parentField="owner")
    static class ProjectionRow extends BaseEntity {
        @ManyToOne @JoinColumn(name="owner_id",nullable=false) ProjectionProbe owner;
        @Column String none;
        @Column @FieldMetadata(grid=@GridColumn(visible=false)) String formOnly;
        @Column @FieldMetadata(hidden=true,grid=@GridColumn(visible=true)) String tableOnly;
        @Column @FieldMetadata(grid=@GridColumn(visible=true)) String both;
    }
    @Entity @Table(name="et9_plain_probe") @EntityMetadata
    static class PlainProbe extends BaseEntity { @Column @FieldMetadata String subjectKey; }
}
