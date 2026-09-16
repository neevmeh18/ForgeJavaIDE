import { ForgeClient } from '../forge/client';
import { CommandRouter } from '../forge/commands';
import type { NotificationKind, WorkbenchContext, WorkbenchState } from '../forge/context';
import { Keybindings } from '../forge/keybindings';
import type { Contributions, ResolvedSetting, ServerEvent, WorkbenchStatus, Workspace } from '../forge/protocol';
import { ActivityBar } from './activityBar';
import { CommandPalette } from './commandPalette';
import { clear, el } from './dom';
import { EditorArea } from './editorArea';
import { describeError as describe } from '../forge/client';
import { Explorer } from './explorer';
import { MenuBar } from './menuBar';
import { Notifications } from './notifications';
import { Panel } from './panel';
import { ScmView } from './scmView';
import { SearchView } from './searchView';
import { ExtensionsView, SettingsView } from './sidePanels';
import { StatusBar } from './statusBar';

/**
 * Assembles the workbench and owns the frontend's visual state.
 *
 * <p>The layout is conventional because it is the layout developers already know — activity bar,
 * sidebar, editor groups, panel, status bar. What is not conventional is how little it knows:
 * its menus, shortcuts and views are all fetched from the backend's contribution registry at
 * startup, and every interaction leaves through a command id.
 *
 * <p>This is also the composition root for the UI, the mirror of `ForgeApplication` on the Java
 * side: parts are constructed here, handed a {@link WorkbenchContext}, and never wired to each
 * other directly.
 */
export class Workbench implements WorkbenchContext {
  readonly client: ForgeClient;
  readonly commands: CommandRouter;
  readonly state: WorkbenchState = {
    workspace: null,
    status: null,
    user: null,
    theme: 'dark',
    settings: new Map(),
  };

  private readonly keybindings: Keybindings;
  private readonly listeners = new Map<string, Set<(event: ServerEvent) => void>>();

  private readonly notifications = new Notifications();
  private readonly menuBar: MenuBar;
  private readonly activityBar: ActivityBar;
  private readonly sidebar = el('aside', { class: 'sidebar' });
  private readonly editors: EditorArea;
  private readonly panel: Panel;
  private readonly statusBar: StatusBar;
  private readonly palette: CommandPalette;

  private readonly explorer: Explorer;
  private readonly search: SearchView;
  private readonly scm: ScmView;
  private readonly extensions: ExtensionsView;
  private readonly settings: SettingsView;
  private activeView = 'explorer';
  private switching = false;

  constructor(
    private readonly root: HTMLElement,
    client: ForgeClient,
  ) {
    this.client = client;
    this.commands = new CommandRouter(client);
    this.keybindings = new Keybindings(this.commands);

    this.menuBar = new MenuBar(this, this.keybindings);
    this.activityBar = new ActivityBar(this);
    this.editors = new EditorArea(this);
    this.panel = new Panel(this);
    this.statusBar = new StatusBar(this);
    this.palette = new CommandPalette(this.commands, this.keybindings);

    this.explorer = new Explorer(this);
    this.search = new SearchView(this);
    this.scm = new ScmView(this);
    this.extensions = new ExtensionsView(this);
    this.settings = new SettingsView(this);

    client.onEvent((event) => this.fanOut(event));
    this.registerLocalCommands();
    this.render();
  }

  // ---- WorkbenchContext ---------------------------------------------------------------

  on(type: string, handler: (event: ServerEvent) => void): () => void {
    const handlers = this.listeners.get(type) ?? new Set();
    handlers.add(handler);
    this.listeners.set(type, handlers);
    return () => handlers.delete(handler);
  }

  notify(kind: NotificationKind, message: string): void {
    this.notifications.show(kind, message);
  }

  showView(id: string): void {
    void this.commands.execute('workbench.showView', { viewId: id });
  }

  async openFile(path: string, line?: number): Promise<void> {
    await this.editors.open(path, line);
  }

  // ---- startup ------------------------------------------------------------------------

  /** Loads everything the workbench renders itself from, then opens a workspace. */
  async start(user: string): Promise<void> {
    this.state.user = user;
    this.statusBar.setUser(user);
    this.client.connectEvents();

    try {
      const [status, commands, contributions] = await Promise.all([
        this.client.query<WorkbenchStatus>('workbench.status'),
        this.client.query<Parameters<CommandRouter['setBackendCommands']>[0]>('workbench.commands'),
        this.client.query<Contributions>('workbench.contributions'),
      ]);
      this.state.status = status;
      this.commands.setBackendCommands(commands);
      this.keybindings.setBindings(contributions.keybindings);
      this.bindWorkbenchShortcuts();
      this.menuBar.setItems(contributions.menus);
      this.activityBar.setViews(contributions.views);
      this.panel.setViews(contributions.views);
      this.explorer.setContextMenu(contributions.menus);
    } catch (error) {
      this.notify('error', `Could not load the workbench: ${describe(error)}`);
      return;
    }

    this.keybindings.attach(document);
    // An extension activated lazily contributes commands, menus and keybindings after startup,
    // so the workbench re-reads the registries rather than showing a stale catalogue.
    this.on('extension.activated', () => void this.reloadContributions());
    this.on('extension.deactivated', () => void this.reloadContributions());
    this.on('settings.changed', () => void this.applySettings());
    await this.applySettings();
    await this.openInitialWorkspace();
    this.showSidebarView('explorer');
  }

  /** Re-reads the command catalogue and contribution registry and re-renders the chrome. */
  private async reloadContributions(): Promise<void> {
    try {
      const [commands, contributions] = await Promise.all([
        this.client.query<Parameters<CommandRouter['setBackendCommands']>[0]>('workbench.commands'),
        this.client.query<Contributions>('workbench.contributions'),
      ]);
      this.commands.setBackendCommands(commands);
      this.keybindings.setBindings(contributions.keybindings);
      this.bindWorkbenchShortcuts();
      this.menuBar.setItems(contributions.menus);
      this.activityBar.setViews(contributions.views);
      this.panel.setViews(contributions.views);
      this.explorer.setContextMenu(contributions.menus);
      this.activityBar.setActive(this.activeView);
    } catch {
      // A failed refresh leaves the previous catalogue in place, which is still usable.
    }
  }

  private async openInitialWorkspace(): Promise<void> {
    try {
      const available = await this.client.query<Workspace[]>('workspace.available');
      if (available.length === 0) {
        this.notify('warning', 'No workspaces were found under the configured workspace root');
        return;
      }
      // The mounted root is what the developer asked for; subdirectories are offered as
      // separate workspaces but should not win the first open by alphabetical accident.
      const root = available.find((workspace) => workspace.location.path === '');
      await this.openWorkspace((root ?? available[0]).id);
    } catch (error) {
      this.notify('error', describe(error));
    }
  }

  private async openWorkspace(workspaceId: string): Promise<void> {
    if (this.switching || this.state.workspace?.id === workspaceId) return;
    this.switching = true;
    try {
    const previous = this.state.workspace;
    if (previous) {
      const proceed = await this.editors.prepareWorkspaceChange();
      if (!proceed) return;
      try {
        await this.commands.execute('workspace.close', { workspaceId: previous.id });
      } catch {
        // The server may already have closed it; continue with a clean client state.
      }
      this.panel.resetWorkspace();
      this.search.resetWorkspace();
      this.explorer.resetWorkspace();
      this.scm.resetWorkspace();
      this.client.setWorkspace(null);
      this.state.workspace = null;
    }
    const workspace = (await this.commands.execute<Workspace>('workspace.open', { workspaceId })) as Workspace;
    this.state.workspace = workspace;
    this.client.setWorkspace(workspace.id);
    this.statusBar.setWorkspace(workspace.name);
    document.title = `${workspace.name} — Forge`;
    await Promise.all([this.explorer.refresh(), this.scm.refresh(), this.statusBar.refreshBranch()]);
    await this.applySettings();
    } finally { this.switching = false; }
  }

  /** Reads resolved settings and applies the handful the workbench renders with. */
  private async applySettings(): Promise<void> {
    try {
      const resolved = await this.client.query<ResolvedSetting[]>('settings.resolved');
      this.state.settings = new Map(resolved.map((setting) => [setting.key, setting.value]));
    } catch {
      return;
    }
    const theme = this.state.settings.get('workbench.colorTheme') === 'light' ? 'light' : 'dark';
    this.state.theme = theme;
    document.documentElement.dataset.theme = theme;
    this.editors.setTheme(theme);
    this.editors.applySettings(this.state.settings);
    this.panel.applySettings(this.state.settings);
    const width = Number(this.state.settings.get('workbench.sidebarWidth') ?? 260);
    this.sidebar.style.width = `${Math.max(180, Math.min(600, width))}px`;
  }

  // ---- layout -------------------------------------------------------------------------

  private render(): void {
    clear(this.root);
    const middle = el(
      'div',
      { class: 'workbench-middle' },
      this.activityBar.element,
      this.sidebar,
      el('div', { class: 'workbench-center' }, this.editors.element, this.panel.element),
    );
    this.root.append(
      el('div', { class: 'workbench' }, this.menuBar.element, middle, this.statusBar.element),
      this.palette.element,
      this.notifications.element,
    );
  }

  private showSidebarView(viewId: string): void {
    this.activeView = viewId;
    clear(this.sidebar);
    switch (viewId) {
      case 'search':
        this.sidebar.append(this.search.element);
        this.search.focus();
        break;
      case 'scm':
        this.sidebar.append(this.scm.element);
        void this.scm.refresh();
        break;
      case 'extensions':
        this.sidebar.append(this.extensions.element);
        void this.extensions.refresh();
        break;
      case 'settings':
        this.sidebar.append(this.settings.element);
        void this.settings.refresh();
        break;
      case 'debug':
        this.sidebar.append(this.debugView());
        break;
      case 'explorer':
        this.sidebar.append(this.explorer.element);
        void this.explorer.refresh();
        break;
    }
    if (!this.sidebar.childElementCount) this.sidebar.append(el('p', { class: 'view-empty', text: 'This view has no supported renderer.' }));
    this.activityBar.setActive(viewId);
    this.editors.layout();
  }

  /**
   * Debugging has no adapter in this milestone; the view says so plainly instead of pretending.
   * Breakpoints still work — they are workspace state, not adapter behaviour.
   */
  private debugView(): HTMLElement {
    const view = el('div', { class: 'view debug-view' });
    view.append(
      el('div', { class: 'view-header' }, el('h2', { text: 'Run and Debug' })),
      el('p', {
        class: 'view-empty',
        text: 'No debug adapter is registered. Breakpoints set in the editor gutter are kept '
          + 'with the workspace and are sent to an adapter as soon as one is available.',
      }),
    );
    void this.client
      .query<Array<{ path: string; line: number }>>('debug.breakpoints')
      .then((breakpoints) => {
        for (const breakpoint of breakpoints) {
          const row = el('button', {
            class: 'tree-row',
            text: `${breakpoint.path}:${breakpoint.line}`,
          });
          row.addEventListener('click', () => void this.openFile(breakpoint.path, breakpoint.line));
          view.append(row);
        }
      })
      .catch(() => undefined);
    return view;
  }

  // ---- commands -----------------------------------------------------------------------

  /**
   * Presentation-only commands. They exist so that showing a view or toggling the panel is
   * addressable by id like everything else — a menu, a keybinding or an extension can trigger
   * them — without pretending that UI layout is application behaviour that belongs in Java.
   */
  private registerLocalCommands(): void {
    const local = this.commands;
    local.registerInteractive('auth.logout', async () => {
      await this.client.command('auth.logout');
      this.client.setToken(null);
      window.location.reload();
    });
    for (const id of ['file.save', 'editor.save']) local.registerInteractive(id, () => this.editors.saveActive());
    local.registerInteractive('editor.close', () => this.editors.closeActive());
    local.registerInteractive('editor.format', () => this.editors.formatActive());
    local.registerInteractive('workspace.open', async () => { await local.execute('workbench.openWorkspace'); });
    local.registerInteractive('terminal.create', async () => { await local.execute('workbench.newTerminal'); });
    local.registerInteractive('language.rename', async () => { await local.execute('workbench.renameSymbol'); });
    local.registerInteractive('debug.start', async () => { await local.execute('workbench.startDebug'); });
    local.registerInteractive('search.text', () => this.showSidebarView('search'));
    local.registerInteractive('settings.set', () => this.showSidebarView('settings'));
    local.registerInteractive('workspace.close', async () => {
      if (!await this.editors.prepareWorkspaceChange()) return;
      await this.client.command('workspace.close');
    });

    local.registerLocal('workbench.showView', 'Show View', 'View', (args) =>
      this.showSidebarView(String(args.viewId ?? 'explorer')),
    );
    local.registerLocal('workbench.commandPalette', 'Show All Commands', 'View', () =>
      this.palette.openCommands(),
    );
    local.registerLocal('workbench.quickOpen', 'Go to File', 'View', () => this.palette.openFiles());
    local.registerLocal('workbench.togglePanel', 'Toggle Panel', 'View', () => {
      this.panel.toggle();
      this.editors.layout();
    });
    local.registerLocal('workbench.splitEditor', 'Split Editor', 'View', () => this.editors.split());
    local.registerLocal('workbench.openFile', 'Open File', 'File', (args) =>
      this.openFile(String(args.path), args.line ? Number(args.line) : undefined),
    );
    local.registerLocal('workbench.reportPosition', 'Report Cursor Position', 'Internal', (args) =>
      this.statusBar.setPosition(Number(args.line ?? 1), Number(args.column ?? 1)),
    );
    local.registerLocal('workbench.toggleTheme', 'Toggle Colour Theme', 'View', async () => {
      const next = this.state.theme === 'dark' ? 'light' : 'dark';
      await this.client.command('settings.set', {
        key: 'workbench.colorTheme',
        value: next,
        layer: 'user',
      });
      await this.applySettings();
      this.editors.setTheme(this.state.theme);
    });
    local.registerLocal('workbench.toggleSidebar', 'Toggle Sidebar', 'View', () => {
      this.sidebar.hidden = !this.sidebar.hidden;
      this.editors.layout();
    });
    local.registerLocal('workbench.openWorkspace', 'Open Workspace', 'Workspace', async () => {
      const available = await this.client.query<Workspace[]>('workspace.available');
      const chosen = await this.palette.pick(
        'Select a workspace',
        available.map((workspace) => ({
          id: workspace.id,
          label: workspace.name,
          detail: `${workspace.location.scheme}: ${workspace.location.path || '.'}`,
        })),
      );
      if (chosen) {
        await this.openWorkspace(chosen.id);
        this.showSidebarView('explorer');
      }
    });
    local.registerLocal('workbench.search', 'Find in Files', 'Search', () => this.showSidebarView('search'));
    local.registerLocal('workbench.newTerminal', 'New Terminal', 'Terminal', async () => {
      this.panel.show('terminal');
      await this.panel.terminal.create();
    });
    local.registerLocal('workbench.runTask', 'Run Task…', 'Tasks', () => this.panel.show('tasks'));
    local.registerLocal('workbench.renameSymbol', 'Rename Symbol', 'Language', async () => {
      const documentId = this.editors.activeDocumentId();
      const position = this.editors.activePosition();
      if (!documentId || !position) return;
      const newName = window.prompt('New symbol name', '');
      if (!newName?.trim()) return;
      const edit = await this.client.command<{ changes: unknown[] } | null>('language.rename', {
        documentId, position, newName: newName.trim(),
      });
      if (!edit || edit.changes.length === 0) {
        this.notify('info', 'No rename provider is registered for this language');
      } else {
        this.notify('warning', 'The language provider returned rename edits, but this milestone does not apply multi-file edits automatically.');
      }
    });
    local.registerLocal('workbench.startDebug', 'Start Debugging', 'Debug', async () => {
      const adapters = await this.client.query<string[]>('debug.adapters');
      if (adapters.length === 0) {
        this.showSidebarView('debug');
        this.notify('info', 'No debug adapter is registered');
        return;
      }
      const type = adapters.length === 1 ? adapters[0] : window.prompt(`Debug adapter (${adapters.join(', ')})`, adapters[0]);
      if (type) await this.client.command('debug.start', { type });
    });
    local.registerLocal('workbench.showLogs', 'Show Problems', 'View', () => this.panel.show('problems'));
    // Contributed menus may address a view directly; the settings menu item does.
    local.registerLocal('workbench.view.settings', 'Open Settings', 'View', () =>
      this.showSidebarView('settings'),
    );

    // Commands whose arguments only the UI knows. The ids stay canonical; the context is filled
    // in here rather than duplicated into a second "save the editor" command.
    for (const id of ['editor.close', 'editor.format']) {
      local.provideArguments(id, () => {
        const documentId = this.editors.activeDocumentId();
        return documentId ? { documentId } : null;
      });
    }

    // Saving and closing need the editor's content, so they route through it.
    local.registerLocal('workbench.save', 'Save', 'File', () => this.editors.saveActive());
    local.registerLocal('workbench.closeEditor', 'Close Editor', 'File', () => this.editors.closeActive());
    local.registerLocal('workbench.formatDocument', 'Format Document', 'Editor', () =>
      this.editors.formatActive(),
    );
  }

  /**
   * The workbench's own shortcuts, plus the three backend bindings whose arguments come from
   * the editor. Contributed bindings are already installed; these override the ones that need
   * the editor's content.
   */
  private bindWorkbenchShortcuts(): void {
    this.keybindings.bind('ctrl+shift+p', 'workbench.commandPalette');
    this.keybindings.bind('ctrl+p', 'workbench.quickOpen');
    this.keybindings.bind('ctrl+j', 'workbench.togglePanel');
    this.keybindings.bind('ctrl+b', 'workbench.toggleSidebar');
    this.keybindings.bind('ctrl+s', 'workbench.save', 'editorFocus');
    this.keybindings.bind('ctrl+w', 'workbench.closeEditor', 'editorFocus');
    this.keybindings.bind('ctrl+shift+i', 'workbench.formatDocument', 'editorFocus');
    this.keybindings.bind('ctrl+shift+f', 'workbench.search');
    this.keybindings.bind('ctrl+`', 'workbench.newTerminal');
    this.keybindings.bind('f2', 'workbench.renameSymbol', 'editorFocus');
    this.keybindings.bind('f5', 'workbench.startDebug');
    this.keybindings.bind('ctrl+shift+e', 'workbench.showView');
  }

  private fanOut(event: ServerEvent): void {
    if (event.workspaceId && event.workspaceId !== this.client.currentWorkspace) return;
    if (event.type === 'forge.resync') {
      void Promise.allSettled([this.explorer.refresh(), this.scm.refresh(), this.statusBar.refreshBranch()]);
      void this.panel.terminal.refresh();
      this.listeners.get(event.type)?.forEach((handler) => handler(event));
      return;
    }
    this.listeners.get(event.type)?.forEach((handler) => handler(event));
    if (event.type === 'file.saved') {
      this.notify('info', `Saved ${(event.payload as { path: string }).path}`);
    }
    if (event.type === 'language.diagnostics') {
      this.statusBar.setProblems(this.panel.problemCount());
    }
    if (event.type === 'auth.sessionEnded') {
      window.location.reload();
    }
    if (event.type === 'workspace.closed' && event.workspaceId === this.client.currentWorkspace) {
      // The workspace header must stop naming a workspace that no longer exists, or every
      // subsequent request fails with NOT_FOUND until the page is reloaded.
      this.client.setWorkspace(null);
      this.state.workspace = null;
      this.statusBar.setWorkspace(null);
      this.panel.resetWorkspace();
      this.search.resetWorkspace();
      this.explorer.resetWorkspace();
      this.scm.resetWorkspace();
      document.title = 'Forge';
      void this.explorer.refresh();
    }
  }

  /** Called by the current view id; used by the activity bar's toggle shortcut. */
  currentView(): string {
    return this.activeView;
  }
}
