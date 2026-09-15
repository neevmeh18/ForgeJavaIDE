package dev.forge.core;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Framework-wide identifier types.
 *
 * <p>These live together in one file on purpose. Each is a two-line value type; giving each
 * its own file would buy no boundary and cost nine files. Nested types can still be imported
 * directly ({@code import dev.forge.core.Ids.WorkspaceId;}) so call sites read naturally.
 *
 * <p>Identifiers are opaque. In particular a {@link WorkspaceId} is <em>not</em> a filesystem
 * path: where a workspace physically lives is the business of a workspace provider alone.
 */
public final class Ids {

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._:@-]{1,128}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    /** Common supertype so context plumbing can carry identifiers generically. */
    public sealed interface Id permits WorkspaceId, UserId, SessionId, DocumentId, EditorId,
            TerminalId, TaskExecutionId, DebugSessionId, ExtensionId {
        String value();
    }

    public record WorkspaceId(String value) implements Id {
        public WorkspaceId {
            value = check(value, "workspaceId");
        }

        public static WorkspaceId of(String value) {
            return new WorkspaceId(value);
        }

        @Override
        public String toString() {
            return value;
        }
    }

    public record UserId(String value) implements Id {
        public UserId {
            value = check(value, "userId");
        }

        public static UserId of(String value) {
            return new UserId(value);
        }

        @Override
        public String toString() {
            return value;
        }
    }

    public record SessionId(String value) implements Id {
        public SessionId {
            value = check(value, "sessionId");
        }

        public static SessionId of(String value) {
            return new SessionId(value);
        }

        @Override
        public String toString() {
            return value;
        }
    }

    /** Identity of an open document. Stable for as long as the document stays open. */
    public record DocumentId(String value) implements Id {
        public DocumentId {
            value = check(value, "documentId");
        }

        public static DocumentId of(String value) {
            return new DocumentId(value);
        }

        @Override
        public String toString() {
            return value;
        }
    }

    public record EditorId(String value) implements Id {
        public EditorId {
            value = check(value, "editorId");
        }

        public static EditorId of(String value) {
            return new EditorId(value);
        }

        @Override
        public String toString() {
            return value;
        }
    }

    public record TerminalId(String value) implements Id {
        public TerminalId {
            value = check(value, "terminalId");
        }

        public static TerminalId of(String value) {
            return new TerminalId(value);
        }

        @Override
        public String toString() {
            return value;
        }
    }

    public record TaskExecutionId(String value) implements Id {
        public TaskExecutionId {
            value = check(value, "taskExecutionId");
        }

        public static TaskExecutionId of(String value) {
            return new TaskExecutionId(value);
        }

        @Override
        public String toString() {
            return value;
        }
    }

    public record DebugSessionId(String value) implements Id {
        public DebugSessionId {
            value = check(value, "debugSessionId");
        }

        public static DebugSessionId of(String value) {
            return new DebugSessionId(value);
        }

        @Override
        public String toString() {
            return value;
        }
    }

    public record ExtensionId(String value) implements Id {
        public ExtensionId {
            value = check(value, "extensionId");
        }

        public static ExtensionId of(String value) {
            return new ExtensionId(value);
        }

        @Override
        public String toString() {
            return value;
        }
    }

    /** A short, unguessable identifier suitable for runtime objects such as sessions. */
    public static String random(String prefix) {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        return prefix + "-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String check(String value, String what) {
        if (value == null || !VALID.matcher(value).matches()) {
            throw ForgeException.invalidArgument("Invalid " + what + ": " + value);
        }
        return value;
    }
}
