package dev.forge.core.query;

import dev.forge.core.Args;
import dev.forge.core.ForgeException;
import dev.forge.core.Ids.ExtensionId;
import dev.forge.core.Lifecycle.Disposable;
import dev.forge.core.RequestContext;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads, kept strictly apart from commands.
 *
 * <p>"Give me the file tree" is not an action, and modelling it as {@code GetFileTreeCommand}
 * would put reads through undo, audit and availability machinery they have no use for. Queries
 * are side-effect free, synchronous and cheap; anything expensive or cancellable — a workspace
 * search, a clone — is a command.
 */
public final class QueryRegistry {

    /** A query's signature. Handlers get the same {@link RequestContext} commands do. */
    @FunctionalInterface
    public interface QueryHandler {
        Object handle(RequestContext ctx, Args args) throws Exception;
    }

    /** Decides whether this caller may run this query at all. */
    @FunctionalInterface
    public interface Authorizer {
        void authorize(QueryDescriptor descriptor, RequestContext ctx);

        Authorizer PERMISSIVE = (descriptor, ctx) -> {
        };
    }

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
