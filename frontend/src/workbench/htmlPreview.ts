import type { WorkbenchContext } from '../forge/context';
import { el } from './dom';

/**
 * The HTML preview view, displayed as a panel tab.
 *
 * <p>Shows sanitized HTML in a sandboxed iframe. The content is sanitised by the backend
 * (jsoup) to strip scripts, event handlers and dangerous URIs; the iframe sandbox provides
 * a second defence layer so even if sanitisation misses something the browser refuses to
 * execute it.
 *
 * <p>The preview binds to editor content changes via the context event bus. When the active
 * editor edits an {@code .html} file, the content is sent to the backend for sanitisation
 * and then displayed.
 */
export class HtmlPreviewView {
  readonly element: HTMLElement;

  private readonly iframe: HTMLIFrameElement;
  private readonly placeholder: HTMLElement;
  private activePath: string | null = null;
  private debounceTimer: ReturnType<typeof setTimeout> | null = null;
  private previewEnabled = false;
  private lastContent: string | null = null;

  constructor(private readonly ctx: WorkbenchContext) {
    this.element = el('div', { class: 'html-preview-view' });

    this.placeholder = el('div', { class: 'html-preview-placeholder' });
    this.placeholder.textContent = 'Open an HTML file and press the Preview button to see a rendered preview.';

    this.iframe = el('iframe', {
      class: 'html-preview-iframe',
      sandbox: '',
      title: 'HTML Preview',
    }) as HTMLIFrameElement;
    this.iframe.hidden = true;

    this.element.append(this.placeholder, this.iframe);

    // Listen for document changes to update preview
    ctx.on('editor.documentChanged', () => void this.checkAndUpdate());
    ctx.on('editor.opened', () => void this.checkAndUpdate());
  }

  /**
   * Activates the preview for a given file path.
   */
  showFor(path: string | null): void {
    this.activePath = path;
    this.previewEnabled = path !== null && path.toLowerCase().endsWith('.html');
    if (this.previewEnabled) {
      this.placeholder.hidden = true;
      this.iframe.hidden = false;
      void this.updatePreview();
    } else {
      this.placeholder.hidden = false;
      this.iframe.hidden = true;
    }
  }

  /**
   * Refreshes the preview display, called externally when the panel becomes visible.
   */
  refresh(): void {
    void this.checkAndUpdate();
  }

  /**
   * Checks whether the active file is HTML and, if so, fetches sanitised content.
   */
  private async checkAndUpdate(): Promise<void> {
    if (!this.previewEnabled || !this.activePath) return;

    // Get the editor content from Monaco through the workbench
    const editorPath = await this.getActiveEditorPath();
    if (editorPath !== this.activePath || !editorPath?.toLowerCase().endsWith('.html')) return;
    void this.updatePreview();
  }

  /**
   * Loads the current HTML content, sends to backend for sanitisation, renders in iframe.
   */
  private async updatePreview(): Promise<void> {
    if (this.debounceTimer) {
      clearTimeout(this.debounceTimer);
    }
    this.debounceTimer = setTimeout(() => {
      this.debounceTimer = null;
      void this.doUpdate();
    }, 300);
  }

  private async doUpdate(): Promise<void> {
    try {
      // Read the content from the open editor's Monaco model
      // We access it via the editor area indirectly through context
      const content = await this.readActiveEditorContent();
      if (content === null || content === this.lastContent) return;

      const result = await this.ctx.client.command<{ sanitized: string }>('htmlpreview.sanitize', {
        html: content,
      });
      const sanitized = result?.sanitized ?? '';
      this.lastContent = sanitized;
      this.iframe.srcdoc = sanitized;
    } catch {
      // Silently ignore — preview will remain stale if sanitisation fails
    }
  }

  /**
   * Reads the active editor content by querying the backend for the open document.
   */
  private async readActiveEditorContent(): Promise<string | null> {
    try {
      const path = this.activePath;
      if (!path) return null;

      // Use the editor.document query to get the current document content
      const documents = await this.ctx.client.query<Array<{ path: string; id: string }>>('editor.documents');
      const doc = documents.find((d) => d.path === path);
      if (!doc) return null;

      const opened = await this.ctx.client.query<{ document: { id: string }; text: string }>('editor.document', {
        documentId: doc.id,
      });
      return opened?.text ?? null;
    } catch {
      return null;
    }
  }

  /**
   * Returns the path of the currently active editor tab.
   */
  private async getActiveEditorPath(): Promise<string | null> {
    const documents = await this.ctx.client.query<Array<{ path: string; id: string }>>('editor.documents');
    // In this version we use the first HTML document found,
    // or the activePath if it's still open
    const htmlDoc = documents.find((d) => d.path.toLowerCase().endsWith('.html'));
    return htmlDoc?.path ?? this.activePath;
  }
}