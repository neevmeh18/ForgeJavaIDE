import type { ForgeClient } from './client';
import type { CommandRouter } from './commands';
import type { ServerEvent, WorkbenchStatus, Workspace } from './protocol';

/**
 * Everything a workbench part is given.
 *
 * <p>Parts receive this and nothing else, which keeps them from reaching into each other: a view
 * that wants something to happen executes a command, and a view that needs to react subscribes
 * to an event. The same discipline the backend features follow, applied to the UI.
 */
export interface WorkbenchContext {
  readonly client: ForgeClient;
  readonly commands: CommandRouter;
  readonly state: WorkbenchState;

  /** Subscribes to one backend event type. Returns an unsubscribe function. */
  on(type: string, handler: (event: ServerEvent) => void): () => void;

  notify(kind: NotificationKind, message: string): void;

  /** Asks the workbench to reveal a view by its contributed id. */
  showView(id: string): void;

  /** Opens a file in the editor area, optionally revealing a line. */
  openFile(path: string, line?: number): Promise<void>;
}

export type NotificationKind = 'info' | 'warning' | 'error';

/**
 * Frontend visual state only.
 *
 * <p>What is open, which tab is active, how wide the sidebar is. Nothing here is authoritative:
 * document contents, dirty state and workspace membership all live in Java, and this object
 * caches only what the UI needs in order to draw.
 */
export interface WorkbenchState {
  workspace: Workspace | null;
  status: WorkbenchStatus | null;
  user: string | null;
  theme: 'dark' | 'light';
  settings: Map<string, unknown>;
}
