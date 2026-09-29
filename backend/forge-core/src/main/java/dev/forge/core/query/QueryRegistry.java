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









public final class QueryRegistry {









    private record Registration(QueryDescriptor descriptor, QueryHandler handler) {
    }

    private final Map<String, Registration> queries = new ConcurrentHashMap<>();
    private final Authorizer authorizer;

    public QueryRegistry(Authorizer authorizer) {
        this.authorizer = authorizer;
    }

    public Disposable register(QueryDescriptor descriptor, QueryHandler handler) {
        Registration registration = new Registration(descriptor, handler);
        if (queries.putIfAbsent(descriptor.id(), registration) != null) {
            throw ForgeException.conflict("Query already registered: " + descriptor.id());
        }
        return () -> queries.remove(descriptor.id(), registration);
    }

    public Object execute(String id, Args args, RequestContext ctx) {
        Registration registration = queries.get(id);
        if (registration == null) {
            throw ForgeException.notFound("Unknown query: " + id).with("queryId", id);
        }
        QueryDescriptor descriptor = registration.descriptor();
        if (descriptor.requiresSession() && !ctx.isAuthenticated()) {
            throw ForgeException.unauthorized("Query requires an authenticated session: " + id);
        }
        if (descriptor.requiresWorkspace() && ctx.workspaceId() == null) {
            throw ForgeException.invalidArgument("Query requires a workspace: " + id);
        }
        authorizer.authorize(descriptor, ctx);
        try {
            return registration.handler().handle(ctx, args == null ? Args.EMPTY : args);
        } catch (Exception e) {
            throw ForgeException.normalize(e);
        }
    }

    public List<QueryDescriptor> list() {
        return queries.values().stream()
                .map(Registration::descriptor)
                .sorted(Comparator.comparing(QueryDescriptor::id))
                .toList();
    }

    public void unregisterAllFrom(ExtensionId extension) {
        queries.entrySet().removeIf(entry -> extension.value().equals(entry.getValue().descriptor().source()));
    }
}
