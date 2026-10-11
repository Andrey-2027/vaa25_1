package org.ipro.register;

import org.ipro.rls.RlsPolicyEnforcer;
import org.ipro.registerfixtures.RecorderPersistenceFixtures.RecordedPeriodicRegisterFixture;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RlsRecorderSetWriteAuthorizerTest {
    @Test
    void checksDeleteAndWriteRightsForEveryOldAndNewRegisterRow() {
        RlsPolicyEnforcer enforcer = mock(RlsPolicyEnforcer.class);
        RlsRecorderSetWriteAuthorizer authorizer = new RlsRecorderSetWriteAuthorizer(enforcer);
        var document = new org.ipro.registerfixtures.RecorderPersistenceFixtures.RecorderFixtureDocumentA();
        var recorder = new RecorderRef("fixture-document-a", 7L);
        var oldFirst = new RecordedPeriodicRegisterFixture("item-a", recorder, 1, 1L,
            java.time.LocalDateTime.of(2026, 10, 5, 0, 0), 1);
        var oldSecond = new RecordedPeriodicRegisterFixture("item-a", recorder, 2, 1L,
            java.time.LocalDateTime.of(2026, 10, 5, 0, 0), 2);
        var newFirst = new RecordedPeriodicRegisterFixture("item-a", recorder, 1, 2L,
            java.time.LocalDateTime.of(2026, 10, 6, 0, 0), 3);
        var newSecond = new RecordedPeriodicRegisterFixture("item-a", recorder, 2, 2L,
            java.time.LocalDateTime.of(2026, 10, 6, 0, 0), 4);

        authorizer.authorize(new RecorderSetWriteRequest(RecordedPeriodicRegisterFixture.class,
            document, recorder, List.of(oldFirst, oldSecond), List.of(newFirst, newSecond)));

        verify(enforcer).requireDelete(oldFirst);
        verify(enforcer).requireDelete(oldSecond);
        verify(enforcer).requireUpdate(newFirst);
        verify(enforcer).requireUpdate(newSecond);
    }
}
