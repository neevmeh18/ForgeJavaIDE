import type { LocalHandler } from "./commands/LocalHandler";
import type { ForgeClient } from './client';
import type { CommandView } from './protocol';














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









  provideArguments(id: string, source: () => Record<string, unknown> | null): void {
    this.argumentSources.set(id, source);
  }


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

export type { LocalHandler } from "./commands/LocalHandler";
