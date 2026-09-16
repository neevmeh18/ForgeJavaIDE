package dev.forge;

import dev.forge.core.*;
import dev.forge.core.Ids.*;
import dev.forge.core.event.EventBus;
import dev.forge.debug.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class DebugRegressionTest {
    @Test public void startupRollbackAndWorkspaceOwnership() {
        DebugService debug = new DebugService(new EventBus());
        WorkspaceId one = WorkspaceId.of("one"), two = WorkspaceId.of("two");
        AtomicInteger stopped = new AtomicInteger();
        DebugAdapter adapter = (DebugAdapter) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { DebugAdapter.class }, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "type" -> "test";
                case "start" -> { if (((DebugTypes.DebugConfiguration) args[2]).name().equals("fail")) throw new IllegalStateException("startup failed"); yield null; }
                case "stop" -> { stopped.incrementAndGet(); yield null; }
                default -> List.of();
            };
        });
        debug.register(adapter);
        assertThrows(IllegalStateException.class, () -> debug.start(one, new DebugTypes.DebugConfiguration("fail", "test", "launch", Map.of())));
        assertTrue(debug.sessions(one).isEmpty()); assertEquals(1, stopped.get());
        var info = debug.start(one, new DebugTypes.DebugConfiguration("ok", "test", "launch", Map.of()));
        var id = DebugSessionId.of(info.id());
        assertThrows(ForgeException.class, () -> debug.stop(id, two));
        assertEquals(1, debug.sessions(one).size());
        debug.stop(id, one); assertTrue(debug.sessions(one).isEmpty());
        assertThrows(ForgeException.class, () -> debug.toggleBreakpoint(one, "a.java", 0, null));
        debug.dispose();
    }
}
