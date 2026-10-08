import type { ForgeClient } from './client';
import type { CommandView } from './protocol';

/**
 * The frontend side of the command pattern.
 *
 * <p>Every action in the workbench — a keystroke, a menu item, a toolbar button, a palette
 * entry — resolves to a command id and comes through here. Nothing calls a service directly,
 * so an action behaves identically however it was triggered.
 *
 * <p>Most ids are executed on the backend. A small number are genuinely presentational
 * ("show the explorer", "toggle the panel") and are handled locally: those are UI state, not
 * application behaviour, and sending them to Java would be duplicating presentation logic there.
 */
export type LocalHandler = (args: Record<string, unknown>) => void | Promise<void>;

export class CommandRouter {
  private local = new Map<string, { descriptor: CommandView; handler: LocalHandler }>();
  private backend: CommandView[] = [];
  private interactive = new Map<string, () => Promise<void> | void>();

  registerInteractive(id: string, handler: () => Promise<void> | void): void {
    this.interactive.set(id, handler);
  }
  private beforeExecute: Array<(id: string) => void> = [];
  private argumentSources = new Map<string, () => Record<string, unknown> | null>();

  constructor(private readonly client: ForgeClient) {}

  /** Registers a presentation-only command, e.g. focusing a view. */
  registerLocal(id: string, title: string, category: string, handler: LocalHandler): void {
    this.local.set(id, {
      descriptor: {
        id,
        title,
        category,
        description: '',
        requiresWorkspace: false,
        undoable: false,
        sensitive: false,
        source: 'workbench',
      },
      handler,
    });
  }

  setBackendCommands(commands: CommandView[]): void {
    this.backend = commands;
  }

  /**
   * Supplies the arguments a command needs from the current UI context.
   *
   * <p>`file.save` bound to a keystroke has to come from somewhere: the shortcut knows the
   * intent, the workbench knows which document is focused and what the user has typed. This
   * keeps the command id canonical — one `file.save` for the keybinding, the menu, the palette
   * and an extension — while letting presentation fill in what only presentation knows.
   */
  provideArguments(id: string, source: () => Record<string, unknown> | null): void {
    this.argumentSources.set(id, source);
  }

  /** Everything the palette can offer, backend and local together. */
  all(): CommandView[] {
    const descriptors = [...this.backend.filter((entry) => !this.local.has(entry.id)), ...[...this.local.values()].map((entry) => entry.descriptor)];
    return descriptors.sort((a, b) =>
      `${a.category} ${a.title}`.localeCompare(`${b.category} ${b.title}`),
    );
  }

  onExecute(listener: (id: string) => void): void {
    this.beforeExecute.push(listener);
  }

  async execute<T>(id: string, args: Record<string, unknown> = {}): Promise<T | void> {
    this.beforeExecute.forEach((listener) => listener(id));
    const interactive = this.interactive.get(id);
    if (interactive && !Object.keys(args).length) return await interactive() as T | void;
    const contextual = this.argumentSources.get(id)?.() ?? {};
    const merged = { ...contextual, ...args };
    const localCommand = this.local.get(id);
    if (localCommand) {
      return (await localCommand.handler(merged)) as T | void;
    }
    const descriptor = this.backend.find((entry) => entry.id === id);
    for (const argument of descriptor?.arguments ?? []) {
      if (Object.prototype.hasOwnProperty.call(merged, argument.name)) continue;
      const input = window.prompt(`${descriptor?.title}: ${argument.description} (${argument.type})`, '');
      if (input === null) return;
      merged[argument.name] = argument.type === 'string' ? input : JSON.parse(input);
    }
    // Legacy extension commands may accept free-form data; allow it before invocation.
    if (descriptor && descriptor.source !== 'builtin' && !descriptor.arguments?.length && !Object.keys(merged).length) {
      const input = window.prompt(`${descriptor.title}: arguments as a JSON object`, '{}');
      if (input === null) return;
      const supplied: unknown = JSON.parse(input);
      if (!supplied || Array.isArray(supplied) || typeof supplied !== 'object') throw new Error('Arguments must be an object');
      Object.assign(merged, supplied);
    }
    return this.client.command<T>(id, merged);
  }
}
