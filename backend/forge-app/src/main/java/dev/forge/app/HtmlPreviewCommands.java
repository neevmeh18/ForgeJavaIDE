package dev.forge.app;

import dev.forge.core.command.CommandDescriptor;
import dev.forge.core.command.CommandRegistry;
import dev.forge.core.html.HtmlSanitizer;
import java.util.Map;

/**
 * Registers the {@code htmlpreview.sanitize} command that allows the frontend to sanitize
 * HTML content via jsoup before displaying it in the preview panel.
 *
 * <p>This is product-level wiring, analogous to {@link WorkbenchQueries}: the command itself
 * is simple (a static utility call) but exposed through the framework's command interface so
 * it is accessible from the UI, the CLI, extensions and future automation the same way.
 */
final class HtmlPreviewCommands {

    private HtmlPreviewCommands() {
    }

    static void register(CommandRegistry registry) {
        registry.register(
                CommandDescriptor.of("htmlpreview.sanitize", "HTML Preview", "Sanitize HTML")
                        .describedAs("Sanitizes raw HTML using jsoup, stripping scripts and event handlers")
                        .withArguments(
                                new CommandDescriptor.Argument("html", "string",
                                        "The raw HTML content to sanitize")),
                ctx -> {
                    String rawHtml = ctx.args().requiredString("html");
                    String sanitized = HtmlSanitizer.sanitize(rawHtml);
                    return Map.of("sanitized", sanitized);
                });
    }
}