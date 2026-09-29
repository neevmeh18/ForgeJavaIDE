package dev.forge.search;

import dev.forge.core.Cancellation;
import dev.forge.core.WorkspaceId;
import dev.forge.core.Log;
import dev.forge.filesystem.FileSystem;
import dev.forge.filesystem.Resource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@FunctionalInterface
public interface SymbolSource {
    List<Object> symbols(WorkspaceId workspace, String query, Cancellation.Token cancellation);

    SymbolSource NONE = (workspace, query, cancellation) -> List.of();
}
