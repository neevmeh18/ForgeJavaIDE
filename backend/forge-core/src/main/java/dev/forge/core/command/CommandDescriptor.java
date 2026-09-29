package dev.forge.core.command;

import dev.forge.core.ExtensionId;
import java.util.Optional;











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


    public static final String BUILTIN = "builtin";







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


    public static CommandDescriptor of(String id, String category, String title) {
        return new CommandDescriptor(CommandId.of(id), title, category, "",
                true, false, false, false, BUILTIN, Availability.ALWAYS);
    }

    public CommandDescriptor describedAs(String text) {
        return new CommandDescriptor(id, title, category, text,
                requiresSession, requiresWorkspace, undoable, sensitive, source, availability, arguments);
    }


    public CommandDescriptor workspaceScoped() {
        return new CommandDescriptor(id, title, category, description,
                requiresSession, true, undoable, sensitive, source, availability, arguments);
    }


    public CommandDescriptor anonymous() {
        return new CommandDescriptor(id, title, category, description,
                false, requiresWorkspace, undoable, sensitive, source, availability, arguments);
    }

    public CommandDescriptor asUndoable() {
        return new CommandDescriptor(id, title, category, description,
                requiresSession, requiresWorkspace, true, sensitive, source, availability, arguments);
    }





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
