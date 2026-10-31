package com.example.generated.harness;

import com.example.generated.Matrix;
import java.util.List;
import org.ipro.data.EntityDataAccess;
import org.ipro.crud.ValidationException;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.c5.C5PermissionKeys;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;

/** Extra user-scenario checks on the source actually published by D, in isolated H2. */
@SpringBootTest(classes = GenerationHarnessApplication.class)
class MatrixRuntimeTest {
    @Autowired EntityDataAccess data;
    @Autowired AccessGrantRepository grants;
    @BeforeEach void grant() {
        grants.deleteAll();
        var grant = new AccessGrant(); grant.setSubjectType(AccessGrant.SubjectType.USER); grant.setSubjectKey("matrix-writer");
        grant.setDimension(C5PermissionKeys.entity(Matrix.class)); grant.setDimensionValueId(null);
        grant.setCanRead(true); grant.setCanUpdate(true); grant.setCanDelete(true); grants.saveAndFlush(grant);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("matrix-writer", null, List.of()));
    }
    @AfterEach void cleanup() { grants.deleteAll(); SecurityContextHolder.clearContext(); }
    Matrix create(Long idSys, String draftAgk, Boolean active) {
        var matrix = new Matrix(); matrix.setName("Матрица испытательная"); matrix.setIdSys(idSys); matrix.setDraftAgk(draftAgk); matrix.setActive(active);
        return data.create(Matrix.class, matrix);
    }
    @Test void createUpdateAndReopenAllThreeFields() {
        var created = create(4294967296L, "АгК-001", true); String code = created.getCode();
        var read = data.detail(Matrix.class, created.getId()).orElseThrow();
        assertEquals(4294967296L, read.getIdSys()); assertEquals("АгК-001", read.getDraftAgk()); assertEquals(true, read.getActive());
        read.setIdSys(Long.MAX_VALUE); read.setDraftAgk("АгК-002"); read.setActive(false); read.setName("Матрица изменённая");
        data.update(Matrix.class, read);
        var reopened = data.detail(Matrix.class, created.getId()).orElseThrow();
        assertEquals(Long.MAX_VALUE, reopened.getIdSys()); assertEquals("АгК-002", reopened.getDraftAgk()); assertEquals(false, reopened.getActive());
        assertEquals("Матрица изменённая", reopened.getName()); assertEquals(code, reopened.getCode()); assertFalse(code.isBlank());
    }
    @Test void ownFieldsRemainNullableAndFalseIsDistinctFromNull() {
        var created = create(null, null, null); var read = data.detail(Matrix.class, created.getId()).orElseThrow();
        assertNull(read.getIdSys()); assertNull(read.getDraftAgk()); assertNull(read.getActive());
        read.setActive(false); data.update(Matrix.class, read);
        assertEquals(false, data.detail(Matrix.class, created.getId()).orElseThrow().getActive());
    }
    @Test void stringBoundary150IsAccepted151RejectedAndEmptyStringIsAllowed() {
        var accepted = create(17L, "Я".repeat(150), true);
        assertEquals("Я".repeat(150), data.detail(Matrix.class, accepted.getId()).orElseThrow().getDraftAgk());
        assertThrows(ValidationException.class, () -> create(18L, "Я".repeat(151), true));
        var empty = create(19L, "", false);
        assertEquals("", data.detail(Matrix.class, empty.getId()).orElseThrow().getDraftAgk());
    }
}
