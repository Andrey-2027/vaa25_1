package com.example.materialrequests.config;

import com.example.materialrequests.model.*;
import org.ipro.data.EntityDataAccess;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.c5.C5PermissionKeys;
import org.ipro.form.FieldFactory;
import org.ipro.form.MetadataDrivenItemFormSaveAdapter;
import org.ipro.form.builtin.ItemForm;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import java.util.List;

/** Opt-in fixture setup; business writes use normal authorization and canonical access. */
@Component
@ConditionalOnProperty(name = "consumer.fixtures.enabled", havingValue = "true")
public class DemoData implements CommandLineRunner {
    public static final String SEED_SUBJECT = "consumer-fixture-initializer";
    private final EntityDataAccess access;
    private final AccessGrantRepository grants;
    private final FieldFactory fields;
    private final MetadataDrivenItemFormSaveAdapter saves;
    public DemoData(EntityDataAccess access, AccessGrantRepository grants, FieldFactory fields,
                    MetadataDrivenItemFormSaveAdapter saves) {
        this.access = access; this.grants = grants; this.fields = fields; this.saves = saves;
    }
    @Override @Transactional
    public void run(String... args) {
        var previous = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(SEED_SUBJECT, null, List.of()));
        SecurityContextHolder.setContext(context);
        try {
            for (Class<?> type : List.of(Material.class, MaterialRequest.class)) {
                grant(AccessGrant.SubjectType.ROLE, "EDITOR", C5PermissionKeys.entityRead(type), null, true, false, false);
                grant(AccessGrant.SubjectType.ROLE, "READER", C5PermissionKeys.entityRead(type), null, true, false, false);
                grant(AccessGrant.SubjectType.ROLE, "EDITOR", C5PermissionKeys.entityOperations(type), null, true, true, true);
                grant(AccessGrant.SubjectType.USER, SEED_SUBJECT, C5PermissionKeys.entityRead(type), null, true, false, false);
                grant(AccessGrant.SubjectType.USER, SEED_SUBJECT, C5PermissionKeys.entityOperations(type), null, true, true, false);
            }
            grant(AccessGrant.SubjectType.USER, "alice", MaterialRequest.DEPARTMENT, 1L, true, true, true);
            grant(AccessGrant.SubjectType.USER, "bob", MaterialRequest.DEPARTMENT, 2L, true, false, false);
            for (long department : List.of(1L, 2L)) {
                grant(AccessGrant.SubjectType.USER, SEED_SUBJECT, MaterialRequest.DEPARTMENT, department, true, true, false);
            }
            grants.flush();
            if (access.list(Material.class, null, PageRequest.of(0, 1)).isEmpty()) {
                access.create(Material.class, new Material("MAT-001", "Бумага"));
                access.create(Material.class, new Material("MAT-002", "Картридж"));
            }
            // P2 fixtures exercise protected reads. Aggregate rows and numbering follow in P3.
            for (long department : List.of(1L, 2L)) {
                if (access.list(MaterialRequest.class, (root, query, cb) ->
                        cb.equal(root.get("departmentId"), department), PageRequest.of(0, 1)).isEmpty()) {
                    var request = new MaterialRequest();
                    request.setNumber("FIXTURE-" + department);
                    request.setDepartmentId(department);
                    request.setComment("Данные P2: подразделение " + department);
                    // Public form adapter reaches the platform aggregate boundary. No row replacement
                    // is requested in this read fixture; attached-section save acceptance is P3.
                    var form = new ItemForm<>(MaterialRequest.class, List.of(), fields);
                    form.setEntity(request);
                    saves.save(form);
                }
            }
            // The non-login fixture subject holds no lasting grants after initialization.
            grants.deleteAll(grants.findAll().stream()
                    .filter(g -> g.getSubjectType() == AccessGrant.SubjectType.USER
                            && SEED_SUBJECT.equals(g.getSubjectKey())).toList());
            grants.flush();
        } finally { SecurityContextHolder.setContext(previous); }
    }
    private void grant(AccessGrant.SubjectType kind, String subject, String dimension, Long value,
                       boolean read, boolean update, boolean delete) {
        // READ and operations share the canonical ENTITY key. Accumulate their flags
        // on the same row instead of silently dropping the later operation grant.
        var grant = grants.findBySubjectTypeAndSubjectKeyAndDimension(kind, subject, dimension).stream()
            .filter(g -> java.util.Objects.equals(g.getDimensionValueId(), value)).findFirst()
            .orElseGet(AccessGrant::new);
        grant.setSubjectType(kind); grant.setSubjectKey(subject); grant.setDimension(dimension);
        grant.setDimensionValueId(value);
        grant.setCanRead(grant.isCanRead() || read);
        grant.setCanUpdate(grant.isCanUpdate() || update);
        grant.setCanDelete(grant.isCanDelete() || delete);
        grants.save(grant);
    }
}
