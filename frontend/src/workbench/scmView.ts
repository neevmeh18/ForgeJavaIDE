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
      this.action('Clone', '⊕', () => void this.cloneRepository()),
      this.action('Git credentials', '⚿', () => void this.manageCredentials()),
      this.action('Fetch', '↓', () => void this.run('scm.fetch')),
      this.action('Pull', '⇣', () => void this.run('scm.pull')),
      this.action('Push', '⇡', () => void this.run('scm.push')),
      this.action('Switch branch', '⑂', () => void this.switchBranch()),
      this.action('History', '◷', () => void this.showHistory()),
    );

    this.element.append(
      el('div', { class: 'view-header' }, el('h2', { text: 'Source Control' }), actions),
      el('div', { class: 'scm-commit' }, this.branchLabel, this.message, commit),
      this.changes,
    );

    ctx.commands.registerInteractive('scm.clone', () => this.cloneRepository());
    ctx.commands.registerInteractive('scm.addCredential', () => { void this.editCredential(); });
    ctx.commands.registerInteractive('scm.removeCredential', () => this.manageCredentials());

    ctx.on('scm.repositoryChanged', () => void this.refresh());
    ctx.on('file.saved', () => void this.refresh());
    ctx.on('workspace.opened', () => void this.refresh());
  }

  resetWorkspace(): void {
    this.message.value = '';
    this.branchLabel.textContent = '';
    clear(this.changes);
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
      const diff = el('button', { class: 'scm-action', title: 'Show diff', text: 'Diff' });
      diff.addEventListener('click', () => {
        void this.ctx.client.query<{ text: string }>('scm.diff', { path: change.path, staged: change.staged })
          .then((result) => this.showText(change.path, result.text))
          .catch((error: unknown) => this.ctx.notify('error', describe(error)));
      });
      row.append(diff);
      if (!change.staged && change.status !== 'UNTRACKED') {
        const discard = el('button', { class: 'scm-action', title: 'Discard changes', text: 'Discard' });
        discard.addEventListener('click', () => {
          if (window.confirm(`Discard changes to ${change.path}?`)) void this.run('scm.discard', { paths: [change.path] });
        });
        row.append(discard);
      }
      this.changes.append(row);
    }
  }

  private showText(title: string, text: string): void {
    const dialog = el('dialog', {});
    const close = el('button', { text: 'Close' });
    close.addEventListener('click', () => dialog.close());
    dialog.addEventListener('close', () => dialog.remove());
    dialog.append(el('h3', { text: title }), el('pre', { text, style: 'max-height:70vh;max-width:85vw;overflow:auto' }), close);
    document.body.append(dialog);
    dialog.showModal();
  }

  private async showHistory(): Promise<void> {
    try {
      const commits = await this.ctx.client.query<Array<{ shortId: string; message: string }>>('scm.history', { limit: 50 });
      this.showText('Recent commits', commits.map((commit) => `${commit.shortId} ${commit.message}`).join('\n'));
    } catch (error) { this.ctx.notify('error', describe(error)); }
  }

  private async cloneRepository(): Promise<void> {
    const dialog = el('dialog', { class: 'scm-dialog' });
    const close = el('button', { text: 'Cancel' });
    close.addEventListener('click', () => dialog.close());
    dialog.addEventListener('close', () => dialog.remove());

    const urlInput = el('input', { class: 'field', type: 'text', placeholder: 'http://gitea:3000/user/private-repo.git' }) as HTMLInputElement;
    const nameInput = el('input', { class: 'field', type: 'text', placeholder: 'Workspace name' }) as HTMLInputElement;
    const branchInput = el('input', { class: 'field', type: 'text', placeholder: 'Optional branch; blank uses remote default' }) as HTMLInputElement;
    const usernameInput = el('input', { class: 'field', type: 'text', autocomplete: 'username', placeholder: 'Leave blank to use a saved credential' }) as HTMLInputElement;
    const passwordInput = el('input', { class: 'field', type: 'password', autocomplete: 'current-password', placeholder: 'Password or personal access token' }) as HTMLInputElement;
    const saveInput = el('input', { type: 'checkbox', checked: true }) as HTMLInputElement;

    urlInput.addEventListener('change', () => {
      if (!nameInput.value.trim()) {
        const match = urlInput.value.trim().match(/\/([^/]+?)(?:\.git)?\/?$/);
        if (match) nameInput.value = match[1];
      }
    });

    const clone = el('button', { class: 'primary', text: 'Clone' });
    clone.addEventListener('click', async () => {
      const url = urlInput.value.trim();
      const name = nameInput.value.trim();
      const branch = branchInput.value.trim();
      const username = usernameInput.value.trim();
      const password = passwordInput.value;
      if (!url || !name) {
        this.ctx.notify('warning', 'URL and workspace name are required');
        return;
      }
      if ((username && !password) || (!username && password)) {
        this.ctx.notify('warning', 'Username and password/token must be provided together');
        return;
      }

      clone.setAttribute('disabled', 'true');
      try {
        const args: Record<string, unknown> = { url, name };
        if (branch) args.branch = branch;
        if (username && password) {
          args.username = username;
          args.password = password;
          args.saveCredential = saveInput.checked;
        }
        await this.ctx.commands.execute('scm.clone', args);
        dialog.close();
        this.ctx.notify('info', `Repository cloned into "${name}"`);

        const workspaces = await this.ctx.client.query<Array<{ id: string; name: string }>>('workspace.available');
        const ws = workspaces.find((w) => w.name === name);
        if (ws) await this.ctx.commands.execute('workspace.open', { workspaceId: ws.id });
      } catch (error) {
        this.ctx.notify('error', describe(error));
      } finally {
        clone.removeAttribute('disabled');
      }
    });

    const credentialHint = el('p', {
      class: 'view-empty',
      text: 'Authentication is optional here. Leave it blank to reuse a credential already saved for this exact repository URL.',
    });
    const saveRow = el('label', { class: 'scm-checkbox-row' }, saveInput, ' Save credential after clone succeeds');

    dialog.append(
      el('h3', { text: 'Clone Repository' }),
      el('label', { text: 'Remote URL' }), urlInput,
      el('label', { text: 'Workspace name' }), nameInput,
      el('label', { text: 'Branch' }), branchInput,
      el('h4', { text: 'Authentication' }),
      credentialHint,
      el('label', { text: 'Username' }), usernameInput,
      el('label', { text: 'Password / Token' }), passwordInput,
      saveRow,
      el('div', { class: 'scm-dialog-actions' }, clone, close),
    );
    document.body.append(dialog);
    dialog.showModal();
    urlInput.focus();
  }

  private async manageCredentials(): Promise<void> {
    const dialog = el('dialog', { class: 'scm-dialog scm-credential-dialog' });
    const close = el('button', { text: 'Close' });
    close.addEventListener('click', () => dialog.close());
    dialog.addEventListener('close', () => dialog.remove());
    const list = el('div', { class: 'scm-credential-list' });

    const refresh = async (): Promise<void> => {
      clear(list);
      try {
        const credentials = await this.ctx.client.query<Record<string, string>>('scm.listCredentials');
        const entries = Object.entries(credentials).sort(([a], [b]) => a.localeCompare(b));
        if (!entries.length) {
          list.append(el('p', { class: 'view-empty', text: 'No saved Git credentials' }));
          return;
        }
        for (const [repositoryUrl, username] of entries) {
          const replace = el('button', { text: 'Replace' });
          replace.addEventListener('click', () => void this.editCredential(repositoryUrl, username).then(refresh));
          const remove = el('button', { text: 'Delete' });
          remove.addEventListener('click', async () => {
            if (!window.confirm(`Delete the saved credential for ${repositoryUrl}?`)) return;
            try {
              await this.ctx.commands.execute('scm.removeCredential', { repositoryUrl });
              this.ctx.notify('info', 'Git credential deleted');
              await refresh();
            } catch (error) {
              this.ctx.notify('error', describe(error));
            }
          });
          list.append(el(
            'div',
            { class: 'scm-credential-row' },
            el('div', {},
              el('strong', { text: repositoryUrl }),
              el('div', { class: 'view-empty', text: `Username: ${username}` }),
            ),
            el('div', { class: 'scm-credential-actions' }, replace, remove),
          ));
        }
      } catch (error) {
        list.append(el('p', { class: 'view-empty', text: describe(error) }));
      }
    };

    const add = el('button', { class: 'primary', text: 'Add credential' });
    add.addEventListener('click', () => void this.editCredential().then(refresh));
    dialog.append(
      el('h3', { text: 'Git Credentials' }),
      el('p', { class: 'view-empty', text: 'Credentials are saved per HTTP(S) repository. Stored passwords/tokens are never shown again.' }),
      list,
      el('div', { class: 'scm-dialog-actions' }, add, close),
    );
    document.body.append(dialog);
    dialog.showModal();
    await refresh();
  }

  private editCredential(repositoryUrl = '', username = ''): Promise<void> {
    return new Promise((resolve) => {
      const dialog = el('dialog', { class: 'scm-dialog' });
      const close = el('button', { text: 'Cancel' });
      close.addEventListener('click', () => dialog.close());
      dialog.addEventListener('close', () => { dialog.remove(); resolve(); });
      const urlInput = el('input', {
        class: 'field',
        type: 'text',
        value: repositoryUrl,
        readonly: repositoryUrl ? true : undefined,
        placeholder: 'http://gitea:3000/user/private-repo.git',
      }) as HTMLInputElement;
      const usernameInput = el('input', {
        class: 'field',
        type: 'text',
        value: username,
        autocomplete: 'username',
      }) as HTMLInputElement;
      const passwordInput = el('input', {
        class: 'field',
        type: 'password',
        autocomplete: 'new-password',
        placeholder: 'Password or personal access token',
      }) as HTMLInputElement;
      const save = el('button', { class: 'primary', text: repositoryUrl ? 'Replace' : 'Save' });

      save.addEventListener('click', async () => {
        const url = urlInput.value.trim();
        const user = usernameInput.value.trim();
        const password = passwordInput.value;
        if (!url || !user || !password) {
          this.ctx.notify('warning', 'Repository URL, username and password/token are required');
          return;
        }
        save.setAttribute('disabled', 'true');
        try {
          await this.ctx.commands.execute('scm.addCredential', { repositoryUrl: url, username: user, password });
          dialog.close();
          this.ctx.notify('info', 'Git credential saved');
        } catch (error) {
          this.ctx.notify('error', describe(error));
        } finally {
          save.removeAttribute('disabled');
        }
      });

      dialog.append(
        el('h3', { text: repositoryUrl ? 'Replace Git Credential' : 'Add Git Credential' }),
        el('label', { text: 'Repository URL' }), urlInput,
        el('label', { text: 'Username' }), usernameInput,
        el('label', { text: 'Password / Token' }), passwordInput,
        el('div', { class: 'scm-dialog-actions' }, save, close),
      );
      document.body.append(dialog);
      dialog.showModal();
      (repositoryUrl ? passwordInput : urlInput).focus();
    });
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
    const generation = this.ctx.client.workspaceGeneration;
    try {
      const branches = await this.ctx.client.query<Array<{ name: string; current: boolean; remote: boolean }>>(
        'scm.branches',
      );
      const chosen = window.prompt(
        `Branch to check out:\n${branches.map((branch) => branch.name).join('\n')}`,
        branches.find((branch) => branch.current)?.name ?? '',
      );
      if (chosen && generation === this.ctx.client.workspaceGeneration) {
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
