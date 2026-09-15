import { describeError as describe } from '../forge/client';
import type { WorkbenchContext } from '../forge/context';
import type { DirEntry, MenuItem } from '../forge/protocol';
import { clear, el, fileIcon } from './dom';

/**
 * The file tree.
 *
 * <p>Children are fetched per directory with the `file.list` query, never by walking the tree in
 * the browser: a workspace may be large, remote, or both, and the backend is the only place that
 * can answer cheaply. The view keeps expansion state and nothing else.
 *
 * <p>The context menu is built from contributed `menu.explorer.context` items, so an extension's
 * file action appears here automatically.
 */
export class Explorer {
  readonly element = el('div', { class: 'view explorer' });

  private readonly tree = el('div', { class: 'tree', role: 'tree' });
  private readonly expanded = new Set<string>();
  private contextMenuItems: MenuItem[] = [];
  private selected: string | null = null;

  constructor(private readonly ctx: WorkbenchContext) {
    const actions = el(
      'div',
      { class: 'view-actions' },
      this.action('New File', () => this.promptCreate(false)),
      this.action('New Folder', () => this.promptCreate(true)),
      this.action('Refresh', () => void this.refresh()),
    );
    this.element.append(el('div', { class: 'view-header' }, el('h2', { text: 'Explorer' }), actions), this.tree);

    // Any change on disk — ours, another session's, or a build's — redraws the affected folder.
    for (const type of ['file.created', 'file.deleted', 'file.moved']) {
      ctx.on(type, () => void this.refresh());
    }
    ctx.on('workspace.opened', () => {
      this.expanded.clear();
      void this.refresh();
    });
  }

  setContextMenu(items: MenuItem[]): void {
    this.contextMenuItems = items.filter((item) => item.menu === 'menu.explorer.context');
  }

  async refresh(): Promise<void> {
    if (!this.ctx.client.currentWorkspace) {
      clear(this.tree);
      this.tree.append(el('p', { class: 'view-empty', text: 'No workspace open' }));
      return;
    }
    clear(this.tree);
    await this.renderDirectory('', this.tree, 0);
  }

  private async renderDirectory(path: string, parent: HTMLElement, depth: number): Promise<void> {
    let entries: DirEntry[];
    try {
      entries = await this.ctx.client.query<DirEntry[]>('file.list', { path });
    } catch (error) {
      parent.append(el('p', { class: 'view-empty', text: describe(error) }));
      return;
    }
    for (const entry of entries) {
      parent.append(this.renderEntry(entry, depth));
      if (entry.directory && this.expanded.has(entry.path)) {
        const children = el('div', { class: 'tree-children' });
        parent.append(children);
        await this.renderDirectory(entry.path, children, depth + 1);
      }
    }
  }

  private renderEntry(entry: DirEntry, depth: number): HTMLElement {
    const row = el('div', {
      class: `tree-row${this.selected === entry.path ? ' selected' : ''}`,
      role: 'treeitem',
      style: `padding-left:${8 + depth * 14}px`,
      title: entry.path,
    });
    if (entry.directory) {
      row.append(
        el('span', {
          class: this.expanded.has(entry.path) ? 'twisty open' : 'twisty',
          text: '›',
          'aria-hidden': 'true',
        }),
      );
    } else {
      row.append(el('span', { class: 'twisty', text: ' ', 'aria-hidden': 'true' }));
    }
    row.append(fileIcon(entry.directory), el('span', { class: 'tree-label', text: entry.name }));

    row.addEventListener('click', () => {
      this.selected = entry.path;
      if (entry.directory) {
        if (this.expanded.has(entry.path)) {
          this.expanded.delete(entry.path);
        } else {
          this.expanded.add(entry.path);
        }
        void this.refresh();
      } else {
        void this.ctx.openFile(entry.path);
      }
    });
    row.addEventListener('contextmenu', (event) => {
      event.preventDefault();
      this.selected = entry.path;
      this.showContextMenu(event, entry);
    });
    return row;
  }

  private showContextMenu(event: MouseEvent, entry: DirEntry): void {
    document.querySelector('.context-menu')?.remove();
    const menu = el('div', {
      class: 'context-menu',
      style: `left:${event.clientX}px; top:${event.clientY}px`,
    });
    for (const item of this.contextMenuItems) {
      const button = el('button', { class: 'context-item', text: item.title });
      button.addEventListener('click', () => {
        menu.remove();
        void this.runContextCommand(item.command, entry);
      });
      menu.append(button);
    }
    document.body.append(menu);
    const dismiss = () => {
      menu.remove();
      document.removeEventListener('mousedown', dismiss);
    };
    setTimeout(() => document.addEventListener('mousedown', dismiss), 0);
  }

  private async runContextCommand(commandId: string, entry: DirEntry): Promise<void> {
    try {
      if (commandId === 'file.rename') {
        const newName = window.prompt('New name', entry.name);
        if (!newName || newName === entry.name) {
          return;
        }
        await this.ctx.commands.execute(commandId, { path: entry.path, newName });
      } else if (commandId === 'file.delete') {
        if (!window.confirm(`Delete ${entry.path}?`)) {
          return;
        }
        await this.ctx.commands.execute(commandId, { path: entry.path, recursive: entry.directory });
      } else {
        await this.ctx.commands.execute(commandId, { path: entry.path });
      }
      await this.refresh();
    } catch (error) {
      this.ctx.notify('error', describe(error));
    }
  }

  private async promptCreate(directory: boolean): Promise<void> {
    const base = this.selectedDirectory();
    const name = window.prompt(directory ? 'New folder name' : 'New file name', '');
    if (!name) {
      return;
    }
    const path = base ? `${base}/${name}` : name;
    try {
      await this.ctx.commands.execute(directory ? 'file.createDirectory' : 'file.create', { path });
      if (base) {
        this.expanded.add(base);
      }
      await this.refresh();
      if (!directory) {
        await this.ctx.openFile(path);
      }
    } catch (error) {
      this.ctx.notify('error', describe(error));
    }
  }

  private selectedDirectory(): string {
    if (!this.selected) {
      return '';
    }
    return this.expanded.has(this.selected)
      ? this.selected
      : this.selected.slice(0, Math.max(0, this.selected.lastIndexOf('/')));
  }

  private action(label: string, handler: () => void): HTMLButtonElement {
    const button = el('button', { class: 'view-action', title: label, text: shortLabel(label) });
    button.addEventListener('click', handler);
    return button;
  }
}

function shortLabel(label: string): string {
  if (label === 'New File') {
    return '＋';
  }
  if (label === 'New Folder') {
    return '🗀';
  }
  return '⟳';
}
