import type { WorkbenchContext } from '../forge/context';
import type { ScmStatus } from '../forge/protocol';
import { clear, el } from './dom';

/**
 * The bottom strip: workspace, branch, problems, cursor position, identity.
 *
 * <p>Every segment is driven by events rather than polling — a commit, a save or a diagnostic
 * updates it because the backend said something happened.
 */
export class StatusBar {
  readonly element = el('footer', { class: 'status-bar' });

  private readonly workspaceSlot = el('button', { class: 'status-item', title: 'Open workspace' });
  private readonly branchSlot = el('button', { class: 'status-item', title: 'Source control' });
  private readonly problemsSlot = el('button', { class: 'status-item', title: 'Problems' });
  private readonly positionSlot = el('span', { class: 'status-item status-right' });
  private readonly userSlot = el('span', { class: 'status-item status-right' });

  constructor(private readonly ctx: WorkbenchContext) {
    this.workspaceSlot.addEventListener('click', () => void ctx.commands.execute('workbench.openWorkspace'));
    this.branchSlot.addEventListener('click', () => ctx.showView('scm'));
    this.problemsSlot.addEventListener('click', () => ctx.showView('problems'));
    this.element.append(
      this.workspaceSlot,
      this.branchSlot,
      this.problemsSlot,
      el('span', { class: 'status-spacer' }),
      this.positionSlot,
      this.userSlot,
    );

    ctx.on('scm.repositoryChanged', () => void this.refreshBranch());
    ctx.on('scm.committed', () => void this.refreshBranch());
    ctx.on('workspace.opened', () => void this.refreshBranch());
  }

  setWorkspace(name: string | null): void {
    this.workspaceSlot.textContent = name ? `⌂ ${name}` : 'No workspace';
  }

  setUser(user: string | null): void {
    this.userSlot.textContent = user ?? '';
  }

  setPosition(line: number, column: number): void {
    this.positionSlot.textContent = `Ln ${line}, Col ${column}`;
  }

  setProblems(count: number): void {
    clear(this.problemsSlot);
    this.problemsSlot.append(document.createTextNode(count === 0 ? '⚠ 0' : `⚠ ${count}`));
  }

  async refreshBranch(): Promise<void> {
    if (!this.ctx.client.currentWorkspace) {
      this.branchSlot.textContent = '';
      return;
    }
    try {
      const status = await this.ctx.client.query<ScmStatus>('scm.status');
      if (!status.repository) {
        this.branchSlot.textContent = '';
        return;
      }
      const dirty = status.clean ? '' : '*';
      const ahead = status.ahead > 0 ? ` ↑${status.ahead}` : '';
      const behind = status.behind > 0 ? ` ↓${status.behind}` : '';
      this.branchSlot.textContent = `⑂ ${status.branch}${dirty}${ahead}${behind}`;
    } catch {
      this.branchSlot.textContent = '';
    }
  }
}
