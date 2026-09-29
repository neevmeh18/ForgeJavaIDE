package dev.forge.core;

import java.lang.System.Logger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;











public final class Log {

    private static final Set<String> REDACTED = Set.of(
            "password", "token", "secret", "apikey", "api_key", "authorization",
            "credential", "credentials", "privatekey", "private_key", "cookie", "passphrase");

    private final Logger delegate;
    private final Map<String, String> context;

    private Log(Logger delegate, Map<String, String> context) {
        this.delegate = delegate;
        this.context = context;
    }

    public static Log of(Class<?> owner) {
        return new Log(System.getLogger(owner.getName()), Map.of());
    }


    public Log with(String key, Object value) {
        if (value == null) {
            return this;
        }
        Map<String, String> merged = new LinkedHashMap<>(context);
        merged.put(sanitize(key), redact(key, sanitize(String.valueOf(value))));
        return new Log(delegate, Map.copyOf(merged));
    }

    public Log with(RequestContext ctx) {
        return with("userId", ctx.userId())
                .with("sessionId", ctx.sessionId())
                .with("workspaceId", ctx.workspaceId());
    }

    public void debug(String message) {
        delegate.log(Logger.Level.DEBUG, format(message));
    }

    public void info(String message) {
        delegate.log(Logger.Level.INFO, format(message));
    }

    public void warn(String message) {
        delegate.log(Logger.Level.WARNING, format(message));
    }

    public void warn(String message, Throwable t) {
        delegate.log(Logger.Level.WARNING, format(message), t);
    }

    public void error(String message, Throwable t) {
        delegate.log(Logger.Level.ERROR, format(message), t);
    }

    private static String sanitize(String value) {
        String bounded = value.length() > 2048 ? value.substring(0, 2048) : value;
        return bounded.replaceAll("[\\p{Cntrl}\\p{Cf}\\p{Zl}\\p{Zp}]", "?");
    }

    private String format(String message) {
        message = sanitize(message);
        if (context.isEmpty()) {
            return message;
        }
        StringBuilder sb = new StringBuilder(message);
        sb.append(" [");
        boolean first = true;
        for (Map.Entry<String, String> entry : context.entrySet()) {
            if (!first) {
                sb.append(' ');
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
            first = false;
        }
        return sb.append(']').toString();
    }

    private static String redact(String key, String value) {
        return REDACTED.contains(key.toLowerCase(java.util.Locale.ROOT)) ? "***" : value;
    }
}
