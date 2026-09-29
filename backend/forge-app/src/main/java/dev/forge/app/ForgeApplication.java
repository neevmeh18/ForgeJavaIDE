package dev.forge.app;

import dev.forge.auth.AuthCommands;
import dev.forge.auth.AuthEvents;
import dev.forge.auth.AuthenticationProvider;
import dev.forge.auth.Authorizer;
import dev.forge.auth.SessionService;
import dev.forge.core.Ids.SessionId;
import dev.forge.core.Lifecycle;
import dev.forge.core.Log;
import dev.forge.core.command.CommandExecutor;
import dev.forge.core.command.CommandRegistry;
import dev.forge.core.contrib.ContributionRegistry;
import dev.forge.core.event.Event;
import dev.forge.core.event.EventBus;
import dev.forge.core.extension.ExtensionRegistry;
import dev.forge.core.query.QueryRegistry;
import dev.forge.debug.DebugCommands;
import dev.forge.debug.DebugService;
import dev.forge.editor.Document;
import dev.forge.editor.EditorCommands;
import dev.forge.editor.EditorEvents;
import dev.forge.editor.EditorService;
import dev.forge.filesystem.FileCommands;
import dev.forge.filesystem.FileService;
import dev.forge.infra.BufferWordLanguageProvider;
import dev.forge.infra.Config;
import dev.forge.infra.FileStateStore;
import dev.forge.infra.GitSourceControlProvider;
import dev.forge.infra.JarExtensionLoader;
import dev.forge.infra.LocalWorkspaceProvider;
import dev.forge.infra.PasswordAuthenticationProvider;
import dev.forge.infra.ProcessTerminalProvider;
import dev.forge.infra.WorkspaceTaskProvider;
import dev.forge.language.LanguageCommands;
import dev.forge.language.LanguageService;
import dev.forge.language.LanguageTypes;
import dev.forge.scm.ScmCommands;
import dev.forge.scm.SourceControlService;
import dev.forge.search.SearchCommands;
import dev.forge.search.SearchService;
import dev.forge.settings.Settings;
import dev.forge.settings.SettingsCommands;
import dev.forge.settings.SettingsService;
import dev.forge.state.StateCommands;
import dev.forge.state.StateStore;
import dev.forge.tasks.TaskCommands;
import dev.forge.tasks.TaskService;
import dev.forge.terminal.TerminalCommands;
import dev.forge.terminal.TerminalService;
import dev.forge.transport.EventStream;
import dev.forge.transport.Gateway;
import dev.forge.transport.HttpTransport;
import dev.forge.transport.Json;
import dev.forge.transport.StaticAssets;
import dev.forge.workspace.WorkspaceCommands;
import dev.forge.workspace.WorkspaceEvents;
import dev.forge.workspace.WorkspaceService;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The composition root: the one place that knows how the whole IDE is assembled.
 *
 * <p>Everything is constructed explicitly and handed its collaborators through constructors.
 * There is no dependency-injection container, no component scanning and no static service
 * locator — with this many pieces, one readable assembly is easier to follow than annotations
 * scattered across sixty files, and it makes the dependency direction impossible to fake.
 *
 * <p>Reading top to bottom gives the architecture: core, then features, then infrastructure
 * bound to the capability interfaces, then the transport in front of all of it. Swapping a
 * capability — a different filesystem, a different identity provider, a hosted workspace
 * provider — is an edit to one line of this file.
 */
public final class ForgeApplication implements Lifecycle.Component {

    private static final Log log = Log.of(ForgeApplication.class);

    private final Config config;
    private final Lifecycle.Store components = new Lifecycle.Store();
    private final List<Lifecycle.Component> started;
    private volatile boolean ready;

    public ForgeApplication(Config config) {
        this.config = config;

        // ---- Core -------------------------------------------------------------------------
        EventBus events = new EventBus();
        CommandRegistry commandRegistry = new CommandRegistry();
        ContributionRegistry contributions = new ContributionRegistry();

        // ---- Infrastructure bound to capabilities -----------------------------------------
        LocalWorkspaceProvider workspaceProvider = new LocalWorkspaceProvider(config.workspaceRoot(),
                config.maxWorkspaceBytes(), config.maxDirectoryEntries(), config.maxTraversalEntries());
        StateStore stateStore = new FileStateStore(config.dataDir(), config.maxStateBytes(),
                config.maxStateDocumentBytes());
        AuthenticationProvider authentication =
                new PasswordAuthenticationProvider(config.authUsername(), config.authPassword());

        // ---- Features ---------------------------------------------------------------------
        WorkspaceService workspaces = new WorkspaceService(events, List.of(workspaceProvider),
                config.maxOpenWorkspaces());
        SessionService sessions = new SessionService(events, config.sessionIdleTimeout(),
                config.sessionMaxLifetime(), config.maxSessions());
        Authorizer authorizer = new Authorizer(workspaces, sessions);
        QueryRegistry queries = new QueryRegistry(authorizer);
        CommandExecutor executor = new CommandExecutor(commandRegistry, events, authorizer,
                config.maxCommandsGlobal(), config.maxCommandsPerSession());

        FileService files = new FileService(workspaces, events, config.maxFileBytes());
        EditorService editors = new EditorService(files, events);
        SettingsService settings = new SettingsService(stateStore, events);

        // The language feature reads live buffers through a lambda rather than importing the
        // editor, which keeps the package dependency one-way.
        LanguageService languages = new LanguageService(
                (workspace, document) -> snapshotOf(editors, workspace, document), events);
        SearchService search = new SearchService(workspaces, languages::workspaceSymbols,
                config.maxTraversalEntries());
        BackupService backups = new BackupService(workspaceProvider, stateStore, config.backupWorkerUrl(),
                config.backupWorkerToken());

        TerminalService terminals = new TerminalService(
                new ProcessTerminalProvider(workspaceProvider, config.terminalsEnabled()),
                events, config.shell());
        TaskService tasks = new TaskService(List.of(new WorkspaceTaskProvider(workspaces)), terminals, events);
        SourceControlService scm =
                new SourceControlService(List.of(new GitSourceControlProvider(workspaceProvider)), events);
        DebugService debug = new DebugService(events);

        ExtensionRegistry extensions =
                new ExtensionRegistry(commandRegistry, queries, executor, events, contributions);

        // ---- Feature registration ---------------------------------------------------------
        new WorkspaceCommands(workspaces).register(commandRegistry, queries, contributions);
        new FileCommands(files).register(commandRegistry, queries, contributions);
        new EditorCommands(editors, languages).register(commandRegistry, queries, contributions);
        new AuthCommands(authentication, sessions).register(commandRegistry, queries);
        new SettingsCommands(settings).register(commandRegistry, queries, contributions);
        new StateCommands(stateStore).register(commandRegistry, queries);
        new SearchCommands(search).register(commandRegistry, contributions);
        new BackupCommands(backups).register(commandRegistry, queries);
        new TerminalCommands(terminals).register(commandRegistry, queries, contributions);
        new TaskCommands(tasks).register(commandRegistry, queries, contributions);
        new LanguageCommands(languages, editors).register(commandRegistry, queries, contributions);
        new ScmCommands(scm).register(commandRegistry, queries, contributions);
        new DebugCommands(debug).register(commandRegistry, queries, contributions);
        new WorkbenchQueries(commandRegistry, executor, contributions, extensions, languages,
                config.terminalsEnabled()).register(queries, commandRegistry);

        defineSettings(settings);
        defineViews(contributions);

        // ---- Cross-feature wiring, done here so no feature reaches into another -------------
        languages.register(new BufferWordLanguageProvider());

        // Document synchronisation. A real language server keeps its own copy of every open
        // buffer, so it needs open, change and close — routed through events rather than by
        // giving the language feature a handle on the editor.
        editors.onDocumentChanged(document -> snapshotOf(editors, document.workspaceId(), document.id())
                .ifPresent(languages::documentChanged));
        events.subscribe(EditorEvents.EditorOpened.class, opened ->
                snapshotOf(editors, opened.workspaceId(), opened.documentId())
                        .ifPresent(languages::documentOpened));
        events.subscribe(EditorEvents.EditorClosed.class, closed ->
                languages.documentClosed(new LanguageTypes.DocumentSnapshot(closed.workspaceId(),
                        closed.documentId(), closed.path(), closed.languageId(), "", 0)));

        events.subscribe(WorkspaceEvents.WorkspaceOpened.class, opened -> {
            workspaces.onClose(opened.workspaceId(), files.watch(opened.workspaceId()));
            extensions.activateFor(dev.forge.core.extension.ExtensionDescriptor.ON_WORKSPACE);
        });
        events.subscribe(AuthEvents.SessionEnded.class, ended -> {
            workspaces.releaseSession(ended.sessionId());
            stateStore.clear(StateStore.Scope.SESSION, ended.sessionId().value());
        });

        new JarExtensionLoader(config.extensionsDir())
                .discoverInto(extensions, (extension, contributes) ->
                        defineContributedSettings(settings, extension, contributes));

        // ---- Transport ---------------------------------------------------------------------
        Json json = new Json();
        EventStream stream = new EventStream(events, json,
                (session, event) -> isVisible(workspaces, session, event),
                config.maxEventClientsGlobal(), config.maxEventClientsPerSession());
        Gateway gateway = new Gateway(executor, queries, sessions, Duration.ofSeconds(30),
                config.queryTimeout());
        HttpTransport http = new HttpTransport(gateway, json, stream,
                new StaticAssets(config.webRoot()), () -> ready, config.host(), config.port(),
                config.requestBodyTimeout());

        this.started = List.of(executor, workspaces, sessions, editors, terminals, tasks, scm, debug,
                languages, extensions, gateway, stream, http);
    }

    @Override
    public void start() {
        config.validateSecurityDefaults();
        started.forEach(component -> {
            components.add(component);
            component.start();
        });
        ready = true;
        log.with("port", config.port()).with("workspaceRoot", config.workspaceRoot())
                .info(Forge.PRODUCT + " " + Forge.VERSION + " started");
    }

    @Override
    public void dispose() {
        ready = false;
        components.dispose();
        log.info("Shutdown complete");
    }

    /**
     * Who may see an event.
     *
     * <p>Session-addressed events go to that session only; workspace events go to the sessions
     * attached to that workspace; command outcomes go to whoever issued them. Anything else is
     * application-wide. Getting this wrong would leak one user's file changes, terminal output
     * and command results to every other connected client.
     */
    private static boolean isVisible(WorkspaceService workspaces, SessionId session, Event event) {
        if (event.type().startsWith("command.")) {
            return session.equals(event.sessionId());
        }
        if (event.sessionId() != null) {
            return session.equals(event.sessionId());
        }
        if (event.workspaceId() != null) {
            return workspaces.sessions(event.workspaceId()).contains(session);
        }
        return true;
    }

    private static Optional<LanguageTypes.DocumentSnapshot> snapshotOf(
            EditorService editors, dev.forge.core.Ids.WorkspaceId workspace,
            dev.forge.core.Ids.DocumentId documentId) {
        try {
            Document document = editors.document(documentId);
            if (!document.workspaceId().equals(workspace)) {
                return Optional.empty();
            }
            return Optional.of(new LanguageTypes.DocumentSnapshot(document.workspaceId(), document.id(),
                    document.path(), document.languageId(), editors.text(document.id()), document.version()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Turns an extension manifest's declared settings into real definitions.
     *
     * <p>They land in the {@code EXTENSION} layer, below anything a user or workspace chooses.
     * A malformed declaration is skipped rather than failing startup: a bad manifest is the
     * extension author's problem, not the IDE's.
     */
    @SuppressWarnings("unchecked")
    private static void defineContributedSettings(SettingsService settings,
                                                  dev.forge.core.Ids.ExtensionId extension,
                                                  java.util.Map<String, Object> contributes) {
        if (!(contributes.get("settings") instanceof List<?> declared)) {
            return;
        }
        for (Object entry : declared) {
            if (!(entry instanceof java.util.Map<?, ?> fields)) {
                continue;
            }
            java.util.Map<String, Object> setting = (java.util.Map<String, Object>) fields;
            try {
                settings.define(extension, new Settings.Definition(
                        String.valueOf(setting.get("key")),
                        Settings.Type.valueOf(String.valueOf(setting.getOrDefault("type", "string"))
                                .toUpperCase(java.util.Locale.ROOT)),
                        setting.get("default"),
                        String.valueOf(setting.getOrDefault("description", "")),
                        Settings.Layer.EXTENSION,
                        List.of(),
                        extension.value()));
            } catch (RuntimeException e) {
                log.with("extensionId", extension).warn("Ignoring invalid setting declaration: "
                        + e.getMessage());
            }
        }
    }

    /** The settings this product declares. Extensions add their own via their manifest. */
    private static void defineSettings(SettingsService settings) {
        settings.define(Settings.Definition.of("workbench.colorTheme", Settings.Type.STRING, "dark",
                "Colour theme used by the workbench").choices("dark", "light"));
        settings.define(Settings.Definition.of("workbench.sidebarWidth", Settings.Type.NUMBER, 260,
                "Width of the sidebar in pixels"));
        settings.define(Settings.Definition.of("editor.fontSize", Settings.Type.NUMBER, 13,
                "Editor font size in pixels"));
        settings.define(Settings.Definition.of("editor.tabSize", Settings.Type.NUMBER, 4,
                "Spaces per indentation level"));
        settings.define(Settings.Definition.of("editor.wordWrap", Settings.Type.BOOLEAN, false,
                "Wrap long lines in the editor"));
        settings.define(Settings.Definition.of("editor.minimap", Settings.Type.BOOLEAN, false,
                "Show the editor minimap"));
        settings.define(Settings.Definition.of("files.autoSave", Settings.Type.BOOLEAN, false,
                "Save a document shortly after editing stops"));
        settings.define(Settings.Definition.of("terminal.fontSize", Settings.Type.NUMBER, 12,
                "Terminal font size in pixels"));
    }

    private static void defineViews(ContributionRegistry contributions) {
        contributions.addView(ContributionRegistry.View.of("explorer", "Explorer", "sidebar", "files", 10));
        contributions.addView(ContributionRegistry.View.of("search", "Search", "sidebar", "search", 20));
        contributions.addView(ContributionRegistry.View.of("extensions", "Extensions", "sidebar",
                "extensions", 50));
        contributions.addView(ContributionRegistry.View.of("backups", "Backups", "sidebar", "archive", 60));
        contributions.addView(ContributionRegistry.View.of("terminal", "Terminal", "panel", "terminal", 10));
        contributions.addView(ContributionRegistry.View.of("problems", "Problems", "panel", "warning", 20));
        contributions.addView(ContributionRegistry.View.of("tasks", "Tasks", "panel", "checklist", 30));
    }
}
