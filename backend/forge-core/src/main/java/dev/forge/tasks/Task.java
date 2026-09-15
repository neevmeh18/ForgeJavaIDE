package dev.forge.tasks;

import java.util.List;
import java.util.Map;

/**
 * A named development operation: build, run, test, deploy, generate, or anything a product or
 * workspace defines.
 *
 * <p>Tasks and terminals are different things. A task is a described, repeatable operation with
 * a lifecycle the IDE tracks; a terminal is one way to show it running. Modelling tasks as
 * "whatever someone typed into a shell" loses the description, the status and the ability to
 * offer the same operation from a menu, a keybinding or automation.
 */
public record Task(
        String id,
        String name,
        String type,
        String executable,
        List<String> arguments,
        String cwd,
        Map<String, String> env,
        boolean shell,
        String source) {

    public static final String TYPE_BUILD = "build";
    public static final String TYPE_RUN = "run";
    public static final String TYPE_TEST = "test";

    public Task {
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
        env = env == null ? Map.of() : Map.copyOf(env);
        cwd = cwd == null ? "" : cwd;
        type = type == null || type.isBlank() ? TYPE_RUN : type;
        source = source == null ? "workspace" : source;
    }
}
