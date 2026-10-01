import type { WorkbenchContext } from '../forge/context';
import type {
  ImportedSnapshot,
  IssuedSnapshotShare,
  SnapshotInfo,
  SnapshotShareInfo,
  Workspace,
} from '../forge/protocol';
import { describeError as describe } from '../forge/client';
import { clear, el } from './dom';

export class SnapshotView {
  readonly element = el('div', { class: 'view snapshot-view' });

  private readonly list = el('div', { class: 'snapshot-list' });
  private readonly shares = el('div', { class: 'snapshot-shares' });
  private readonly filter = el('input', {
    class: 'field snapshot-filter',
    type: 'search',
    placeholder: 'Filter by label',
  });
  private snapshots: SnapshotInfo[] = [];

  constructor(private readonly ctx: WorkbenchContext) {
    const create = el('button', { class: 'view-action', title: 'Create snapshot', text: '＋' });
    const importShared = el('button', { class: 'view-action', title: 'Import shared snapshot', text: '⇣' });
    const refresh = el('button', { class: 'view-action', title: 'Refresh', text: '⟳' });
    create.addEventListener('click', () => void this.create());
    importShared.addEventListener('click', () => void this.importShared());
    refresh.addEventListener('click', () => void this.refresh());
    this.filter.addEventListener('input', () => this.renderSnapshots());

    this.element.append(
      el(
        'div',
        { class: 'view-header' },
        el('h2', { text: 'Snapshots' }),
        el('div', { class: 'view-actions' }, create, importShared, refresh),
      ),
      el('div', { class: 'snapshot-toolbar' }, this.filter),
      this.list,
      this.shares,
    );

    for (const event of [
      'snapshot.created',
      'snapshot.updated',
      'snapshot.restored',
      'snapshot.deleted',
      'snapshot.shareCreated',
      'snapshot.shareRevoked',
    ]) {
      ctx.on(event, () => void this.refresh());
    }
  }

  async refresh(): Promise<void> {
    clear(this.list);
    clear(this.shares);
    if (!this.ctx.client.currentWorkspace) {
      this.list.append(el('p', { class: 'view-empty', text: 'No workspace open' }));
      return;
    }
    try {
      const [snapshots, shares] = await Promise.all([
        this.ctx.client.query<SnapshotInfo[]>('snapshot.list'),
        this.ctx.client.query<SnapshotShareInfo[]>('snapshot.shares'),
      ]);
      this.snapshots = snapshots;
      this.renderSnapshots();
      this.renderShares(shares);
    } catch (error) {
      this.list.append(el('p', { class: 'view-empty', text: describe(error) }));
    }
  }

  private renderSnapshots(): void {
    clear(this.list);
    const label = this.filter.value.trim().toLowerCase();
    const visible = label
      ? this.snapshots.filter((snapshot) => snapshot.labels.some((item) => item.toLowerCase().includes(label)))
      : this.snapshots;
    if (visible.length === 0) {
      this.list.append(el('p', {
        class: 'view-empty',
        text: label ? 'No snapshots match this label' : 'No snapshots for this workspace',
      }));
      return;
    }
    visible.forEach((snapshot) => this.list.append(this.row(snapshot)));
  }

  private row(snapshot: SnapshotInfo): HTMLElement {
    const restore = el('button', { class: 'snapshot-action', text: 'Restore' });
    const edit = el('button', { class: 'snapshot-action', text: 'Edit' });
    const share = el('button', { class: 'snapshot-action', text: 'Share' });
    const remove = el('button', { class: 'snapshot-action snapshot-delete', text: 'Delete' });
    restore.addEventListener('click', () => void this.restore(snapshot));
    edit.addEventListener('click', () => void this.edit(snapshot));
    share.addEventListener('click', () => void this.share(snapshot));
    remove.addEventListener('click', () => void this.remove(snapshot));

    const expiry = snapshot.expiresAt
      ? ` · expires ${new Date(snapshot.expiresAt).toLocaleDateString()}`
      : '';
    return el(
      'div',
      { class: 'snapshot-row' },
      el('div', { class: 'snapshot-name', text: snapshot.name }),
      el('div', {
        class: 'snapshot-meta',
        text: `${new Date(snapshot.createdAt).toLocaleString()} · ${snapshot.fileCount} files · ${formatSize(snapshot.totalBytes)}${expiry}`,
      }),
      snapshot.labels.length > 0
        ? el('div', { class: 'snapshot-labels' },
          ...snapshot.labels.map((label) => el('span', { class: 'snapshot-label', text: label })))
        : null,
      el('div', { class: 'snapshot-actions' }, restore, edit, share, remove),
    );
  }

  private async create(): Promise<void> {
    const name = window.prompt('Snapshot name', `Snapshot ${new Date().toLocaleDateString()}`);
    if (name === null) {
      return;
    }
    const labels = window.prompt('Labels (comma separated)', '');
    if (labels === null) {
      return;
    }
    const retention = window.prompt('Retention in days (0 keeps the snapshot)', '30');
    if (retention === null) {
      return;
    }
    try {
      await this.ctx.commands.execute('snapshot.create', {
        name,
        labels: parseLabels(labels),
        retentionDays: Number(retention),
      });
      this.ctx.notify('info', 'Snapshot created');
      await this.refresh();
    } catch (error) {
      this.ctx.notify('error', describe(error));
    }
  }

  private async edit(snapshot: SnapshotInfo): Promise<void> {
    const name = window.prompt('Snapshot name', snapshot.name);
    if (name === null) {
      return;
    }
    const labels = window.prompt('Labels (comma separated)', snapshot.labels.join(', '));
    if (labels === null) {
      return;
    }
    const currentRetention = snapshot.expiresAt
      ? Math.max(1, Math.ceil((Date.parse(snapshot.expiresAt) - Date.now()) / 86_400_000))
      : 0;
    const retention = window.prompt('Retention in days (0 keeps the snapshot)', String(currentRetention));
    if (retention === null) {
      return;
    }
    try {
      await this.ctx.commands.execute('snapshot.update', {
        snapshotId: snapshot.id,
        name,
        labels: parseLabels(labels),
        retentionDays: Number(retention),
      });
      await this.refresh();
    } catch (error) {
      this.ctx.notify('error', describe(error));
    }
  }

  private async share(snapshot: SnapshotInfo): Promise<void> {
    const duration = window.prompt('Share link duration in days', '7');
    if (duration === null) {
      return;
    }
    try {
      const issued = await this.ctx.commands.execute<IssuedSnapshotShare>('snapshot.share', {
        snapshotId: snapshot.id,
        validDays: Number(duration),
      });
      if (issued) {
        window.prompt('Share token', issued.token);
      }
      await this.refresh();
    } catch (error) {
      this.ctx.notify('error', describe(error));
    }
  }

  private async importShared(): Promise<void> {
    const token = window.prompt('Shared snapshot token');
    if (!token) {
      return;
    }
    try {
      const preview = await this.ctx.client.query<SnapshotShareInfo>('snapshot.sharedPreview', { token });
      if (!window.confirm(`Import “${preview.snapshotName}” into the current workspace?`)) {
        return;
      }
      const imported = await this.ctx.commands.execute<ImportedSnapshot>('snapshot.importShared', { token });
      if (imported) {
        this.ctx.notify('info', `Imported ${imported.name}`);
      }
      await this.refresh();
    } catch (error) {
      this.ctx.notify('error', describe(error));
    }
  }

  private renderShares(shares: SnapshotShareInfo[]): void {
    clear(this.shares);
    if (shares.length === 0) {
      return;
    }
    this.shares.append(el('div', { class: 'snapshot-section', text: 'Active shares' }));
    for (const share of shares) {
      const revoke = el('button', { class: 'snapshot-action snapshot-delete', text: 'Revoke' });
      revoke.addEventListener('click', () => void this.revokeShare(share));
      this.shares.append(
        el(
          'div',
          { class: 'snapshot-share-row' },
          el('div', { class: 'snapshot-name', text: share.snapshotName }),
          el('div', {
            class: 'snapshot-meta',
            text: `Expires ${new Date(share.expiresAt).toLocaleString()}`,
          }),
          revoke,
        ),
      );
    }
  }

  private async revokeShare(share: SnapshotShareInfo): Promise<void> {
    try {
      await this.ctx.commands.execute('snapshot.revokeShare', { shareId: share.id });
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

function parseLabels(value: string): string[] {
  return value.split(',').map((label) => label.trim()).filter(Boolean);
}
