package com.ias.approval;

import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApprovalTransactionTest {
    @Test void successCommitsAndClosesWithoutManualAutocommitRestore() throws Exception {
        DataSource source = mock(DataSource.class); Connection connection = mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);
        assertEquals("ok", new JdbcApprovalStore(source).transact(tx -> "ok"));
        verify(connection).setAutoCommit(false); verify(connection).commit(); verify(connection).close();
        verify(connection, never()).setAutoCommit(true); verify(connection, never()).rollback();
    }
    @Test void businessFailureRollsBackWithoutCommit() throws Exception {
        DataSource source = mock(DataSource.class); Connection connection = mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);
        assertThrows(IllegalStateException.class, () -> new JdbcApprovalStore(source).transact(tx -> { throw new IllegalStateException(); }));
        verify(connection).rollback(); verify(connection).close(); verify(connection, never()).commit();
        verify(connection, never()).setAutoCommit(true);
    }
    @Test void rollbackFailureAbortsAndNeverReturnsUncertainConnection() throws Exception {
        DataSource source = mock(DataSource.class); Connection connection = mock(Connection.class);
        when(source.getConnection()).thenReturn(connection); doThrow(new SQLException()).when(connection).rollback();
        assertThrows(IllegalStateException.class, () -> new JdbcApprovalStore(source).transact(tx -> { throw new IllegalStateException(); }));
        verify(connection).abort(any()); verify(connection, never()).close(); verify(connection, never()).commit();
        verify(connection, never()).setAutoCommit(true);
    }
    @Test void commitFailureIsUnknownAndConnectionIsNotReusedEvenForRollback() throws Exception {
        DataSource source = mock(DataSource.class); Connection connection = mock(Connection.class);
        when(source.getConnection()).thenReturn(connection); doThrow(new SQLException()).when(connection).commit();
        ApprovalFailure failure = assertThrows(ApprovalFailure.class, () -> new JdbcApprovalStore(source).transact(tx -> "ok"));
        assertEquals("OPERATION_RESULT_UNKNOWN", failure.getMessage());
        verify(connection).abort(any()); verify(connection, never()).close(); verify(connection, never()).rollback();
        verify(connection, never()).setAutoCommit(true);
    }
    @Test void beginFailureAbortsAndAbortFailureCannotFallBackToPoolClose() throws Exception {
        DataSource source = mock(DataSource.class); Connection connection = mock(Connection.class);
        when(source.getConnection()).thenReturn(connection); doThrow(new SQLException()).when(connection).setAutoCommit(false);
        doThrow(new SQLException()).when(connection).abort(any());
        assertThrows(ApprovalFailure.class, () -> new JdbcApprovalStore(source).transact(tx -> "unused"));
        verify(connection).abort(any()); verify(connection, never()).close(); verify(connection, never()).commit();
        verify(connection, never()).setAutoCommit(true);
    }
}
