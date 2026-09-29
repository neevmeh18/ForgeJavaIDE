package dev.forge.core;

public sealed interface Id permits WorkspaceId, UserId, SessionId, DocumentId, EditorId,
        TerminalId, TaskExecutionId, DebugSessionId, ExtensionId {
    String value();
}
