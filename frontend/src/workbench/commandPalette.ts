import type { CommandRouter } from '../forge/commands';
import type { Keybindings } from '../forge/keybindings';
import type { CommandView } from '../forge/protocol';
import { clear, el } from './dom';

/**
 * The command palette.
 *
 * <p>It shows whatever the backend's command registry reports, including everything extensions
 * contributed, because the list is a query result rather than a hard-coded menu. Choosing an
 * entry executes the command id — the same path a keybinding or a menu item takes.
 *
 * <p>It also serves as a quick-open for files by switching to the `search.files` command when
 * the query is not prefixed with `>`.
 */
export type QuickPickItem = { id: string; label: string; detail?: string; hint?: string };

export class CommandPalette {
  readonly element = el('div', { class: 'palette-overlay', hidden: 'true' });

  private readonly input = el('input', { class: 'palette-input', type: 'text', spellcheck: 'false' });
  private readonly list = el('ul', { class: 'palette-list', role: 'listbox' });
  private items: QuickPickItem[] = [];
  private selection = 0;
  private resolveChoice: ((item: QuickPickItem | null) => void) | null = null;
  private source: (query: string) => Promise<QuickPickItem[]> = async () => [];

  constructor(
    private readonly commands: CommandRouter,
    private readonly keybindings: Keybindings,
  ) {
    this.element.append(el('div', { class: 'palette' }, this.input, this.list));
    this.element.addEventListener('mousedown', (event) => {
      if (event.target === this.element) {
        this.close(null);
      }
    });
    this.input.addEventListener('input', () => void this.refresh());
    this.input.addEventListener('keydown', (event) => this.onKey(event));
  }

  /** Opens the palette over the full command list. */
  openCommands(): void {
    this.open('> ', async (query) => this.commandItems(query));
  }

  /** Opens the palette as a file picker driven by the backend's `search.files` command. */
  openFiles(): void {
    this.open('', async (query) => this.fileItems(query));
  }

  /** Generic quick pick, used by branch selection, task selection and the like. */
  pick(placeholder: string, items: QuickPickItem[]): Promise<QuickPickItem | null> {
    return new Promise((resolve) => {
      this.resolveChoice = resolve;
      this.input.placeholder = placeholder;
      this.open('', async (query) => {
        const needle = query.toLowerCase();
        return items.filter((item) => item.label.toLowerCase().includes(needle));
      });
    });
  }

  private open(prefix: string, source: (query: string) => Promise<QuickPickItem[]>): void {
    this.source = source;
    this.element.hidden = false;
    this.input.value = prefix;
    this.input.focus();
    this.input.setSelectionRange(prefix.length, prefix.length);
    void this.refresh();
  }

  private close(chosen: QuickPickItem | null): void {
    this.element.hidden = true;
    this.input.placeholder = '';
    const resolve = this.resolveChoice;
    this.resolveChoice = null;
    resolve?.(chosen);
  }

  private async refresh(): Promise<void> {
    const raw = this.input.value;
    const query = raw.startsWith('> ') ? raw.slice(2) : raw;
    this.source = raw.startsWith('> ')
      ? async (text) => this.commandItems(text)
      : this.resolveChoice
        ? this.source
        : async (text) => this.fileItems(text);
    try {
      this.items = await this.source(query);
    } catch {
      this.items = [];
    }
    this.selection = 0;
    this.render();
  }

  private commandItems(query: string): QuickPickItem[] {
    const needle = query.toLowerCase();
    return this.commands
      .all()
      .filter((command) => matches(command, needle))
      .slice(0, 200)
      .map((command) => ({
        id: command.id,
        label: command.category ? `${command.category}: ${command.title}` : command.title,
        detail: command.description || command.id,
        hint: this.keybindings.labelFor(command.id) ?? undefined,
      }));
  }

  private async fileItems(query: string): Promise<QuickPickItem[]> {
    const matchesFound = (await this.commands.execute('search.files', { query, limit: 50 })) as
      | Array<{ path: string; name: string }>
      | undefined;
    return (matchesFound ?? []).map((match) => ({
      id: match.path,
      label: match.name,
      detail: match.path,
    }));
  }

  private render(): void {
    clear(this.list);
    this.items.forEach((item, index) => {
      const row = el(
        'li',
        { class: index === this.selection ? 'palette-item selected' : 'palette-item', role: 'option' },
        el('span', { class: 'palette-label', text: item.label }),
        item.detail ? el('span', { class: 'palette-detail', text: item.detail }) : null,
        item.hint ? el('kbd', { class: 'palette-hint', text: item.hint }) : null,
      );
      row.addEventListener('mousedown', (event) => {
        event.preventDefault();
        this.choose(index);
      });
      this.list.append(row);
    });
    if (this.items.length === 0) {
      this.list.append(el('li', { class: 'palette-empty', text: 'No matching entries' }));
    }
  }

  private onKey(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      this.close(null);
    } else if (event.key === 'ArrowDown') {
      event.preventDefault();
      this.selection = Math.min(this.selection + 1, this.items.length - 1);
      this.render();
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      this.selection = Math.max(this.selection - 1, 0);
      this.render();
    } else if (event.key === 'Enter') {
      event.preventDefault();
      this.choose(this.selection);
    }
  }

  private choose(index: number): void {
    const item = this.items[index];
    if (!item) {
      return;
    }
    if (this.resolveChoice) {
      this.close(item);
      return;
    }
    const wasCommand = this.input.value.startsWith('> ');
    this.close(null);
    if (wasCommand) {
      void this.commands.execute(item.id);
    } else {
      void this.commands.execute('workbench.openFile', { path: item.id });
    }
  }
}

function matches(command: CommandView, needle: string): boolean {
  if (!needle) {
    return true;
  }
  return `${command.category} ${command.title} ${command.id}`.toLowerCase().includes(needle);
}
