package dev.forge.core.command;

import dev.forge.core.Ids.ExtensionId;
import java.util.Optional;

/**
 * What a command <em>is</em>, separate from what it does.
 *
 * <p>This metadata is the single source of truth behind the command palette, menus, keybindings
 * and toolbars, and it is what {@link CommandExecutor} enforces before any handler runs.
 *
 * <p>{@code availability} is advisory for the UI and authoritative in the backend: the frontend
 * may grey a menu item out, but the executor re-checks the same condition, because frontend
 * visibility is presentation, not authorisation.
 */
public record CommandDescriptor(
        CommandId id,
        String title,
        String category,
        String description,
        boolean requiresSession,
        boolean requiresWorkspace,
        boolean undoable,
        boolean sensitive,
        String source,
        Availability availability,
        java.util.List<Argument> arguments) {

    public record Argument(String name, String type, String description) { }

    /** Compatibility constructor for existing trusted extensions. */
    public CommandDescriptor(CommandId id, String title, String category, String description,
            boolean requiresSession, boolean requiresWorkspace, boolean undoable, boolean sensitive,
            String source, Availability availability) {
        this(id, title, category, description, requiresSession, requiresWorkspace, undoable, sensitive,
                source, availability, java.util.List.of());
    }

    public CommandDescriptor withArguments(Argument... declared) {
        return new CommandDescriptor(id, title, category, description, requiresSession, requiresWorkspace,
                undoable, sensitive, source, availability, java.util.List.of(declared));
    }

    /** Source marker for commands contributed by the product itself rather than an extension. */
    public static final String BUILTIN = "builtin";

    /**
     * A precondition such as "a debug session is running" or "the document is dirty".
     * Returns the reason the command cannot run, or empty when it can.
     */
    @FunctionalInterface
    public interface Availability {
        Optional<String> unavailableReason(CommandContext ctx);

        Availability ALWAYS = ctx -> Optional.empty();
    }

    public CommandDescriptor {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Command " + id + " needs a title");
        }
        arguments = arguments == null ? java.util.List.of() : java.util.List.copyOf(arguments);
        description = description == null ? "" : description;
        category = category == null ? "" : category;
        source = source == null ? BUILTIN : source;
        availability = availability == null ? Availability.ALWAYS : availability;
    }

    /** A command that requires an authenticated session — the default for anything meaningful. */
    public static CommandDescriptor of(String id, String category, String title) {
        return new CommandDescriptor(CommandId.of(id), title, category, "",
                true, false, false, false, BUILTIN, Availability.ALWAYS);
    }

    public CommandDescriptor describedAs(String text) {
        return new CommandDescriptor(id, title, category, text,
                requiresSession, requiresWorkspace, undoable, sensitive, source, availability, arguments);
    }

    /** The command only makes sense against an open workspace. */
    public CommandDescriptor workspaceScoped() {
        return new CommandDescriptor(id, title, category, description,
                requiresSession, true, undoable, sensitive, source, availability, arguments);
    }

    /** The command may run without an authenticated session (only {@code auth.login} today). */
    public CommandDescriptor anonymous() {
        return new CommandDescriptor(id, title, category, description,
                false, requiresWorkspace, undoable, sensitive, source, availability, arguments);
    }

    public CommandDescriptor asUndoable() {
        return new CommandDescriptor(id, title, category, description,
                requiresSession, requiresWorkspace, true, sensitive, source, availability, arguments);
    }

    /**
     * Marks a command an extension must not replace — authentication, process execution,
     * filesystem writes. Sensitive handlers bypass extension interceptors.
     */
    public CommandDescriptor asSensitive() {
        return new CommandDescriptor(id, title, category, description,
                requiresSession, requiresWorkspace, undoable, true, source, availability, arguments);
    }

    public CommandDescriptor availableWhen(Availability condition) {
        return new CommandDescriptor(id, title, category, description,
                requiresSession, requiresWorkspace, undoable, sensitive, source, condition, arguments);
    }

    public CommandDescriptor contributedBy(ExtensionId extension) {
        return new CommandDescriptor(id, title, category, description,
                requiresSession, requiresWorkspace, undoable, sensitive, extension.value(), availability, arguments);
    }

    public boolean isBuiltin() {
        return BUILTIN.equals(source);
    }
}
