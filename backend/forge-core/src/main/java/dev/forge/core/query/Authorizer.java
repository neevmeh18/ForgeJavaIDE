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

@FunctionalInterface
public interface Authorizer {
    void authorize(QueryDescriptor descriptor, RequestContext ctx);

    Authorizer PERMISSIVE = (descriptor, ctx) -> {
    };
}
