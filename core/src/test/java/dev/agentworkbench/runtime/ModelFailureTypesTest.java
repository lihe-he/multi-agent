package dev.agentworkbench.runtime;

import java.io.IOException;
import java.net.ConnectException;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelFailureTypesTest {
    @Test
    void wrappedTransportFailureKeepsTypesWithoutMessages() {
        Throwable failure = new ExecutionException("Bearer private-key", new IOException("private response", new ConnectException("private-url")));
        assertEquals("java.util.concurrent.ExecutionException -> java.io.IOException -> java.net.ConnectException", WorkspaceRuntime.failureTypes(failure));
    }

    @Test
    void cyclicCausesStopAtTheOriginalIdentity() {
        Throwable first = new IOException(), second = new ConnectException();
        first.initCause(second); second.initCause(first);
        assertEquals("java.io.IOException -> java.net.ConnectException -> [cycle]", WorkspaceRuntime.failureTypes(first));
    }

    @Test
    void deepChainsRetainAnExplicitBound() {
        Throwable failure = new IOException();
        for (int index = 0; index < 20; index++) failure = new IOException("private", failure);
        String types = WorkspaceRuntime.failureTypes(failure);
        assertEquals(9, types.split(" -> ").length);
        assertTrue(types.endsWith(" -> [depth limit]"));
    }

    @Test
    void diagnosticDoesNotInvokeMessageFormatting() {
        Throwable failure = new IOException() {
            @Override public String getMessage() { throw new AssertionError("Message must not be read"); }
            @Override public String toString() { throw new AssertionError("Throwable must not be formatted"); }
        };
        assertEquals(failure.getClass().getName(), WorkspaceRuntime.failureTypes(failure));
    }
}
