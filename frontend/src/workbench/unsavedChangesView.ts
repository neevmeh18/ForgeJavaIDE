import type { WorkbenchContext } from '../forge/context';
import type { DocumentInfo, OpenDocument } from '../forge/protocol';
import { describeError } from '../forge/client';
import { clear, el } from './dom';

/** Presents the editor service's retained drafts; never caches a second copy of their text. */
export class UnsavedChangesView {
  readonly element = el('div', { class: 'view unsaved-changes-view' });

  private readonly list = el('div', { class: 'unsaved-list', 'aria-label': 'Unsaved documents' });
  private readonly preview = el('section', { class: 'unsaved-preview', 'aria-label': 'Draft preview' });
  private documents: DocumentInfo[] = [];
  private selectedId: string | null = null;
  private workspaceId: string | null = null;
  private visible = false;
  private listRequest = 0;
  private previewRequest = 0;

  constructor(private readonly ctx: WorkbenchContext) {
    const refresh = el('button', {
      class: 'view-action', title: 'Refresh Unsaved Changes', 'aria-label': 'Refresh Unsaved Changes', text: '⟳',
    });
    refresh.addEventListener('click', () => void this.refresh());
    this.element.append(
      el('div', { class: 'view-header' }, el('h2', { text: 'Unsaved Changes' }), refresh),
      this.list,
      this.preview,
    );
    for (const type of ['editor.documentChanged', 'editor.dirtyStateChanged', 'editor.closed', 'file.deleted']) {
      ctx.on(type, (event) => {
        if (this.visible && event.workspaceId === ctx.client.currentWorkspace) {
          void this.refresh();
        }
      });
    }
    ctx.on('workspace.closed', (event) => {
      if (event.workspaceId === this.workspaceId) {
        this.reset(null);
        this.message(this.list, 'No workspace open');
      }
    });
  }

  setVisible(visible: boolean): void {
    this.visible = visible;
    if (visible) {
      void this.refresh();
    } else {
      this.listRequest++;
      this.previewRequest++;
    }
  }

  async refresh(): Promise<void> {
    const workspace = this.ctx.client.currentWorkspace;
    if (workspace !== this.workspaceId) {
      this.reset(workspace);
    }
    const request = ++this.listRequest;
    this.previewRequest++;
    if (!workspace) {
      this.message(this.list, 'No workspace open');
      return;
    }
    try {
      const documents = await this.ctx.client.query<DocumentInfo[]>('editor.unsavedDocuments');
      if (request !== this.listRequest || workspace !== this.ctx.client.currentWorkspace) {
        return;
      }
      this.documents = documents;
      if (!documents.some((document) => document.id === this.selectedId)) {
        this.selectedId = documents[0]?.id ?? null;
      }
      this.renderList();
      await this.showPreview();
    } catch (error) {
      if (request === this.listRequest && workspace === this.ctx.client.currentWorkspace) {
        this.documents = [];
        this.selectedId = null;
        clear(this.preview);
        this.message(this.list, `Could not load unsaved changes: ${describeError(error)}`);
      }
    }
  }

  private reset(workspace: string | null): void {
    this.listRequest++;
    this.previewRequest++;
    this.workspaceId = workspace;
    this.documents = [];
    this.selectedId = null;
    clear(this.list);
    clear(this.preview);
  }

  private renderList(): void {
    clear(this.list);
    if (this.documents.length === 0) {
      this.message(this.list, 'No unsaved changes');
      return;
    }
    for (const document of this.documents) {
      const selected = document.id === this.selectedId;
      const row = el('button', {
        class: `tree-row unsaved-document${selected ? ' selected' : ''}`,
        title: document.path, text: document.path, 'aria-pressed': String(selected),
      });
      row.addEventListener('click', () => {
        this.selectedId = document.id;
        this.renderList();
        void this.showPreview();
      });
      this.list.append(row);
    }
  }

  private async showPreview(): Promise<void> {
    const selected = this.documents.find((document) => document.id === this.selectedId);
    const workspace = this.workspaceId;
    const request = ++this.previewRequest;
    clear(this.preview);
    if (!selected || !workspace) {
      return;
    }
    this.message(this.preview, 'Loading draft…');
    try {
      const opened = await this.ctx.client.query<OpenDocument>('editor.document', { documentId: selected.id });
      if (request !== this.previewRequest || workspace !== this.ctx.client.currentWorkspace) {
        return;
      }
      clear(this.preview);
      const restore = el('button', { class: 'primary', text: 'Restore in Editor' });
      restore.addEventListener('click', () => {
        if (workspace === this.ctx.client.currentWorkspace) {
          void this.ctx.commands.execute('workbench.openFile', { path: selected.path })
            .catch((error) => this.ctx.notify('error', describeError(error)));
        }
      });
      this.preview.append(
        el('div', { class: 'unsaved-preview-header' }, el('h3', { text: selected.path }), restore),
        el('pre', { class: 'unsaved-text', text: opened.text, tabindex: 0, 'aria-label': 'Current buffer text' }),
      );
    } catch (error) {
      if (request === this.previewRequest && workspace === this.ctx.client.currentWorkspace) {
        this.message(this.preview, `Could not load draft: ${describeError(error)}`);
      }
    }
  }

  private message(container: HTMLElement, text: string): void {
    clear(container);
    container.append(el('p', { class: 'view-empty', text }));
  }
}
