package dev.forge.core.extension;

/**
 * The entry point an extension implements.
 *
 * <p>An extension is ordinary Java compiled against {@code forge-core} only. It never sees a
 * feature implementation, a transport or the frontend: everything it can do, it does through
 * {@link ExtensionContext} — which is the same command and query layer the UI and CLI use.
 */
public interface Extension {

    /**
     * Called once when an activation condition is met. Anything registered here should be
     * attached to {@link ExtensionContext#onDispose} so deactivation leaves nothing behind.
     */
    void activate(ExtensionContext ctx) throws Exception;

    /** Called before the extension's registrations are torn down. Must not throw to be useful. */
    default void deactivate() {
    }
}
