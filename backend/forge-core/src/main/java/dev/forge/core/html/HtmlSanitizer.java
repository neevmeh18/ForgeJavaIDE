package dev.forge.core.html;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import java.util.Set;

/**
 * Sanitizes HTML content using jsoup, providing a safe subset suitable for preview.
 *
 * <p>Built on {@link Safelist#relaxed()} which allows common formatting and structural tags
 * but strips {@code <script>}, {@code <style>}, and other potentially active content.
 * Additional cleaning removes event-handler attributes and {@code javascript:} URLs.
 *
 * <p>This is intentionally a static utility: the sanitization is pure function and has no
 * mutable state or configuration that would need dependency injection.
 */
public final class HtmlSanitizer {

    private static final long MAX_SANITIZED_BYTES = 1L * 1024 * 1024; // 1 MB

    /** Event-handler attribute prefixes that jsoup's safelist does not remove by default. */
    private static final Set<String> EVENT_ATTRIBUTES = Set.of(
            "onabort", "onblur", "onchange", "onclick", "ondblclick", "onerror",
            "onfocus", "oninput", "onkeydown", "onkeypress", "onkeyup",
            "onload", "onmousedown", "onmousemove", "onmouseout", "onmouseover",
            "onmouseup", "onreset", "onresize", "onscroll", "onselect",
            "onsubmit", "onunload", "ontouchstart", "ontouchend", "ontouchmove",
            "ontouchcancel", "ondrag", "ondrop", "oncopy", "oncut", "onpaste");

    private static final Safelist SAFELIST = configureSafelist();

    private HtmlSanitizer() {
    }

    /**
     * Returns sanitized HTML safe for embedding in a sandboxed iframe.
     *
     * <p>Allows common formatting and structural markup while stripping:
     * <ul>
     *   <li>All {@code <script>} tags and their content</li>
     *   <li>All event-handler attributes ({@code onclick}, {@code onload}, etc.)</li>
     *   <li>{@code javascript:} and {@code data:} URLs in {@code href}, {@code src}, etc.</li>
     *   <li>{@code <object>}, {@code <embed>}, {@code <applet>} tags</li>
     * </ul>
     *
     * @param rawHtml the unsanitized HTML to process; may be null or empty
     * @return sanitized HTML safe for display, never null
     * @throws IllegalArgumentException if the sanitized result exceeds the size limit
     */
    public static String sanitize(String rawHtml) {
        if (rawHtml == null || rawHtml.isBlank()) {
            return "";
        }
        // Jsoup.clean already handles most of the heavy lifting
        String cleaned = Jsoup.clean(rawHtml, SAFELIST);
        // Additional pass to strip event handler attributes that might slip through
        cleaned = stripEventAttributes(cleaned);
        // Strip javascript: and data: URIs from remaining href/src attributes
        cleaned = stripDangerousUris(cleaned);
        // Enforce size limit
        byte[] bytes = cleaned.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > MAX_SANITIZED_BYTES) {
            throw new IllegalArgumentException(
                    "Sanitized HTML exceeds the maximum size of " + (MAX_SANITIZED_BYTES / 1024 / 1024) + " MB");
        }
        return cleaned;
    }

    /**
     * Configures a safelist that allows basic formatting, links, images, and structural tags
     * but excludes scripts, iframes, objects, and other active elements.
     */
    private static Safelist configureSafelist() {
        Safelist safelist = Safelist.relaxed()
                .addTags("h1", "h2", "h3", "h4", "h5", "h6", "hr", "br", "div", "span", "section",
                        "article", "header", "footer", "nav", "main", "aside", "figure", "figcaption",
                        "details", "summary", "mark", "time", "code", "pre", "kbd", "samp", "var",
                        "blockquote", "cite", "q", "dfn", "abbr", "small", "sub", "sup")
                .addAttributes("a", "href", "title", "target")
                .addAttributes("img", "src", "alt", "title", "width", "height")
                .addAttributes("td", "colspan", "rowspan")
                .addAttributes("th", "colspan", "rowspan")
                .addAttributes("table", "summary")
                .addProtocols("a", "href", "http", "https", "mailto")
                .addProtocols("img", "src", "http", "https");
        // Remove protocols that could be dangerous
        safelist.removeProtocols("a", "href", "javascript");
        return safelist;
    }

    /**
     * Strips known event-handler attributes from the HTML using a simple regex approach.
     * jsoup is already processing the document; this catches edge cases that survive
     * the safelist pass.
     */
    private static String stripEventAttributes(String html) {
        String result = html;
        for (String attr : EVENT_ATTRIBUTES) {
            // Case-insensitive removal of on*="..." and on*='...'
            result = result.replaceAll("(?i)\\s+" + attr + "\\s*=\\s*\"[^\"]*\"", "");
            result = result.replaceAll("(?i)\\s+" + attr + "\\s*=\\s*'[^']*'", "");
            result = result.replaceAll("(?i)\\s+" + attr + "\\s*=\\s*[^\\s\"'>]+", "");
        }
        return result;
    }

    /**
     * Removes javascript: and data: URIs from href and src attributes.
     * These can slip through the safelist when the tag itself is allowed but the URI protocol is not caught.
     */
    private static String stripDangerousUris(String html) {
        // Remove href="javascript:...", href='javascript:...'
        String result = html.replaceAll("(?i)\\s+href\\s*=\\s*\"javascript:[^\"]*\"", " href=\"\"");
        result = result.replaceAll("(?i)\\s+href\\s*=\\s*'javascript:[^']*'", " href=''");
        // Remove src="javascript:..." (even though img is allowed with http/https only, be thorough)
        result = result.replaceAll("(?i)\\s+src\\s*=\\s*\"javascript:[^\"]*\"", " src=\"\"");
        result = result.replaceAll("(?i)\\s+src\\s*=\\s*'javascript:[^']*'", " src=''");
        // Remove data: URIs in href
        result = result.replaceAll("(?i)\\s+href\\s*=\\s*\"data:[^\"]*\"", " href=\"\"");
        result = result.replaceAll("(?i)\\s+href\\s*=\\s*'data:[^']*'", " href=''");
        return result;
    }
}