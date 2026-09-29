package dev.forge.core.query;

import dev.forge.core.Args;
import dev.forge.core.ForgeException;
import dev.forge.core.ExtensionId;
import dev.forge.core.Disposable;
import dev.forge.core.RequestContext;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public record QueryDescriptor(
        String id,
        String description,
        boolean requiresSession,
        boolean requiresWorkspace,
        String source) {

    public static QueryDescriptor of(String id, String description) {
        return new QueryDescriptor(id, description, true, false, "builtin");
    }

    public QueryDescriptor workspaceScoped() {
        return new QueryDescriptor(id, description, requiresSession, true, source);
    }

    public QueryDescriptor anonymous() {
        return new QueryDescriptor(id, description, false, requiresWorkspace, source);
    }

    public QueryDescriptor contributedBy(ExtensionId extension) {
        return new QueryDescriptor(id, description, requiresSession, requiresWorkspace, extension.value());
    }
}
