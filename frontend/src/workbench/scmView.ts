import type { WorkbenchContext } from '../forge/context';
import type { ScmChange, ScmStatus } from '../forge/protocol';
import { clear, el } from './dom';
import { describeError as describe } from '../forge/client';

/**
 * The source-control view.
 *
 * <p>Written entirely against the generic `scm.*` commands and queries — there is no mention of
 * Git here. A different provider backing the same commands would render identically, which is
 * the point of keeping Git-specific behaviour inside its provider.
 */
export class ScmView {
  readonly element = el('div', { class: 'view scm-view' });

  private readonly message = el('textarea', {
    class: 'field scm-message',
    rows: 2,
    placeholder: 'Commit message',
  });
  private readonly branchLabel = el('span', { class: 'scm-branch' });
  private readonly changes = el('div', { class: 'scm-changes' });

  constructor(private readonly ctx: WorkbenchContext) {
    const commit = el('button', { class: 'primary', text: 'Commit' });
    commit.addEventListener('click', () => void this.commit());

    const actions = el(
      'div',
      { class: 'view-actions' },
      this.action('Refresh', '⟳', () => void this.refresh()),
      this.action('Fetch', '↓', () => void this.run('scm.fetch')),
      this.action('Pull', '⇣', () => void this.run('scm.pull')),
      this.action('Push', '⇡', () => void this.run('scm.push')),
      this.action('Switch branch', '⑂', () => void this.switchBranch()),
    );

    this.element.append(
      el('div', { class: 'view-header' }, el('h2', { text: 'Source Control' }), actions),
      el('div', { class: 'scm-commit' }, this.branchLabel, this.message, commit),
      this.changes,
    );

    ctx.on('scm.repositoryChanged', () => void this.refresh());
    ctx.on('file.saved', () => void this.refresh());
    ctx.on('workspace.opened', () => void this.refresh());
  }

  async refresh(): Promise<void> {
    clear(this.changes);
    if (!this.ctx.client.currentWorkspace) {
      this.changes.append(el('p', { class: 'view-empty', text: 'No workspace open' }));
      return;
    }
    let status: ScmStatus;
    try {
      status = await this.ctx.client.query<ScmStatus>('scm.status');
    } catch (error) {
      this.changes.append(el('p', { class: 'view-empty', text: describe(error) }));
      return;
    }
    if (!status.repository) {
      this.branchLabel.textContent = '';
      this.changes.append(el('p', { class: 'view-empty', text: 'This workspace is not a repository' }));
      return;
    }
    this.branchLabel.textContent = `⑂ ${status.branch}${status.conflicted ? ' — conflicts' : ''}`;
    if (status.changes.length === 0) {
      this.changes.append(el('p', { class: 'view-empty', text: 'No changes' }));
      return;
    }
    this.section('Staged', status.changes.filter((change) => change.staged), 'scm.unstage');
    this.section('Changes', status.changes.filter((change) => !change.staged), 'scm.stage');
  }

  private section(title: string, changes: ScmChange[], action: string): void {
    if (changes.length === 0) {
      return;
    }
    this.changes.append(el('div', { class: 'scm-section', text: `${title} (${changes.length})` }));
    for (const change of changes) {
      const row = el('div', { class: 'scm-row', title: change.path });
      const open = el('button', { class: 'scm-path', text: change.path });
      open.addEventListener('click', () => void this.ctx.openFile(change.path));
      const toggle = el('button', {
        class: 'scm-action',
        title: action === 'scm.stage' ? 'Stage' : 'Unstage',
        text: action === 'scm.stage' ? '＋' : '−',
      });
      toggle.addEventListener('click', () => void this.run(action, { paths: [change.path] }));
      row.append(
        el('span', { class: `scm-status status-${change.status.toLowerCase()}`, text: change.status[0] }),
        open,
        toggle,
      );
      this.changes.append(row);
    }
  }

  private async commit(): Promise<void> {
    const text = this.message.value.trim();
    if (!text) {
      this.ctx.notify('warning', 'A commit needs a message');
      return;
    }
    try {
      await this.ctx.commands.execute('scm.commit', { message: text });
      this.message.value = '';
      this.ctx.notify('info', 'Committed');
      await this.refresh();
    } catch (error) {
      this.ctx.notify('error', describe(error));
    }
  }

  private async switchBranch(): Promise<void> {
    try {
      const branches = await this.ctx.client.query<Array<{ name: string; current: boolean; remote: boolean }>>(
        'scm.branches',
      );
      const chosen = window.prompt(
        `Branch to check out:\n${branches.map((branch) => branch.name).join('\n')}`,
        branches.find((branch) => branch.current)?.name ?? '',
      );
      if (chosen) {
        await this.ctx.commands.execute('scm.checkout', { branch: chosen });
        await this.refresh();
      }
    } catch (error) {
      this.ctx.notify('error', describe(error));
    }
  }

  private async run(commandId: string, args: Record<string, unknown> = {}): Promise<void> {
    try {
      await this.ctx.commands.execute(commandId, args);
      await this.refresh();
    } catch (error) {
      this.ctx.notify('error', describe(error));
    }
  }

  private action(title: string, glyph: string, handler: () => void): HTMLButtonElement {
    const button = el('button', { class: 'view-action', title, text: glyph });
    button.addEventListener('click', handler);
    return button;
  }
}
