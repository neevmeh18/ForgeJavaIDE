import type { WorkbenchContext } from '../forge/context';
import type { SnapshotInfo, Workspace } from '../forge/protocol';
import { describeError as describe } from '../forge/client';
import { clear, el } from './dom';

export class SnapshotView {
  readonly element = el('div', { class: 'view snapshot-view' });

  private readonly list = el('div', { class: 'snapshot-list' });

  constructor(private readonly ctx: WorkbenchContext) {
    const create = el('button', { class: 'view-action', title: 'Create snapshot', text: '＋' });
    const refresh = el('button', { class: 'view-action', title: 'Refresh', text: '⟳' });
    create.addEventListener('click', () => void this.create());
    refresh.addEventListener('click', () => void this.refresh());

    this.element.append(
      el(
        'div',
        { class: 'view-header' },
        el('h2', { text: 'Snapshots' }),
        el('div', { class: 'view-actions' }, create, refresh),
      ),
      this.list,
    );

    for (const event of ['snapshot.created', 'snapshot.restored', 'snapshot.deleted']) {
      ctx.on(event, () => void this.refresh());
    }
  }

  async refresh(): Promise<void> {
    clear(this.list);
    if (!this.ctx.client.currentWorkspace) {
      this.list.append(el('p', { class: 'view-empty', text: 'No workspace open' }));
      return;
    }
    try {
      const snapshots = await this.ctx.client.query<SnapshotInfo[]>('snapshot.list');
      if (snapshots.length === 0) {
        this.list.append(el('p', { class: 'view-empty', text: 'No snapshots for this workspace' }));
        return;
      }
      snapshots.forEach((snapshot) => this.list.append(this.row(snapshot)));
    } catch (error) {
      this.list.append(el('p', { class: 'view-empty', text: describe(error) }));
    }
  }

  private row(snapshot: SnapshotInfo): HTMLElement {
    const restore = el('button', { class: 'snapshot-action', text: 'Restore' });
    const remove = el('button', { class: 'snapshot-action snapshot-delete', text: 'Delete' });
    restore.addEventListener('click', () => void this.restore(snapshot));
    remove.addEventListener('click', () => void this.remove(snapshot));

    return el(
      'div',
      { class: 'snapshot-row' },
      el('div', { class: 'snapshot-name', text: snapshot.name }),
      el('div', {
        class: 'snapshot-meta',
        text: `${new Date(snapshot.createdAt).toLocaleString()} · ${snapshot.fileCount} files · ${formatSize(snapshot.totalBytes)}`,
      }),
      el('div', { class: 'snapshot-actions' }, restore, remove),
    );
  }

  private async create(): Promise<void> {
    const name = window.prompt('Snapshot name', `Snapshot ${new Date().toLocaleDateString()}`);
    if (name === null) {
      return;
    }
    try {
      await this.ctx.commands.execute('snapshot.create', { name });
      this.ctx.notify('info', 'Snapshot created');
      await this.refresh();
    } catch (error) {
      this.ctx.notify('error', describe(error));
    }
  }

  private async restore(snapshot: SnapshotInfo): Promise<void> {
    try {
      const workspaces = await this.ctx.client.query<Workspace[]>('workspace.opened');
      const current = this.ctx.client.currentWorkspace;
      const options = workspaces.map((workspace) => `${workspace.id} — ${workspace.name}`).join('\n');
      const target = window.prompt(`Restore into workspace:\n${options}`, current ?? '');
      if (!target) {
        return;
      }
      const targetWorkspaceId = target.includes(' — ') ? target.slice(0, target.indexOf(' — ')) : target.trim();
      await this.ctx.commands.execute('snapshot.restore', {
        snapshotId: snapshot.id,
        targetWorkspaceId,
      });
      this.ctx.notify('info', 'Snapshot restored');
      await this.refresh();
    } catch (error) {
      this.ctx.notify('error', describe(error));
    }
  }

  private async remove(snapshot: SnapshotInfo): Promise<void> {
    if (!window.confirm(`Delete snapshot “${snapshot.name}”?`)) {
      return;
    }
    try {
      await this.ctx.commands.execute('snapshot.delete', { snapshotId: snapshot.id });
      await this.refresh();
    } catch (error) {
      this.ctx.notify('error', describe(error));
    }
  }
}

function formatSize(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
