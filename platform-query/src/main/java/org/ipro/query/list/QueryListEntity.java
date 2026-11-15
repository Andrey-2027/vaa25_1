package org.ipro.query.list;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "query_list_definition")
class QueryListEntity {
    @Id
    @Column(name = "list_key", length = 100)
    String key;
    @Column(nullable = false, length = 200)
    String title;
    @Column(nullable = false, columnDefinition = "text")
    String body;
    @Column(nullable = false)
    boolean enabled;
    @Version
    @Column(nullable = false)
    Long version;
    @Column(name = "modified_by", nullable = false, length = 200)
    String modifiedBy;
    @Column(name = "modified_at", nullable = false)
    Instant modifiedAt;

    protected QueryListEntity() {}
}
