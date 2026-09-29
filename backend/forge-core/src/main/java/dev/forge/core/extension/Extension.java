package dev.forge.core.extension;








public interface Extension {





    void activate(ExtensionContext ctx) throws Exception;


    default void deactivate() {
    }
}
