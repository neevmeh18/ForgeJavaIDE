import type { WorkbenchContext } from '../forge/context';
import { describeError as describe } from '../forge/client';
import { el } from './dom';

interface BackupConfiguration {
  destination: string;
}

interface BackupResult {
  destination: string;
  status: string;
}

export class BackupView {
  readonly element = el('div', { class: 'view backup-view' });
  private readonly body = el('div', { class: 'backup-body' });
  private readonly destination = el('input', {
    class: 'field backup-destination',
    type: 'text',
    placeholder: 'workspace-backup.zip',
  });
  private readonly status = el('div', { class: 'backup-status' });

  constructor(private readonly ctx: WorkbenchContext) {
    const save = el('button', { class: 'backup-primary', text: 'Save destination' });
    const run = el('button', { class: 'backup-secondary', text: 'Create backup' });
    save.addEventListener('click', () => void this.save());
    run.addEventListener('click', () => void this.run());
    this.body.append(
      el('div', { class: 'backup-card' },
        el('div', { class: 'backup-card-title', text: 'Workspace backup' }),
        el('div', { class: 'backup-card-copy', text: 'Create an archive of the current workspace.' }),
        el('label', { class: 'backup-label', text: 'Destination' }),
        this.destination,
        el('div', { class: 'backup-actions' }, save, run),
        this.status,
      ),
    );
    this.element.append(
      el('div', { class: 'view-header' }, el('h2', { text: 'Backups' })),
      this.body,
    );
  }

  async refresh(): Promise<void> {
    this.status.textContent = '';
    if (!this.ctx.state.workspace) {
      this.destination.value = '';
      this.status.textContent = 'Open a workspace to configure backups.';
      return;
    }
    try {
      const configuration = await this.ctx.client.query<BackupConfiguration>('backup.configuration');
      this.destination.value = configuration.destination;
    } catch (error) {
      this.status.textContent = describe(error);
    }
  }

  private async save(): Promise<void> {
    try {
      const configuration = await this.ctx.commands.execute<BackupConfiguration>('backup.configure', {
        destination: this.destination.value,
      });
      if (configuration) this.destination.value = configuration.destination;
      this.status.textContent = 'Destination saved.';
    } catch (error) {
      this.status.textContent = describe(error);
    }
  }

  private async run(): Promise<void> {
    this.status.textContent = 'Creating backup…';
    try {
      const result = await this.ctx.commands.execute<BackupResult>('backup.run');
      this.status.textContent = result ? `${result.status}: ${result.destination}` : 'Backup completed.';
    } catch (error) {
      this.status.textContent = describe(error);
    }
  }
}
