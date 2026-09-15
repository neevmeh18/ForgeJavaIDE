import * as monaco from 'monaco-editor/esm/vs/editor/editor.api';
import 'monaco-editor/esm/vs/editor/editor.all.js';
import 'monaco-editor/esm/vs/basic-languages/monaco.contribution';
import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker';
import type { WorkbenchContext } from '../forge/context';
import type { OpenDocument } from '../forge/protocol';
import { clear, el, icon } from './dom';
import { describeError as describe } from '../forge/client';

/**
 * Editor groups, tabs and the Monaco integration.
 *
 * <p>This is the only file in the repository that knows Monaco exists. Everything it learns from
 * the editor — text, cursor position, a request for completions — is turned into a framework
 * command or query before it leaves; everything it shows comes back the same way. Swapping the
 * editor component is a change to this file alone, and the Java side would not notice.
 *
 * <p>Editor groups and tab layout are frontend state on purpose. The backend owns document
 * identity, contents and dirty state — the things two sessions must agree on — while how those
 * documents are arranged on one screen is presentation and differs per client.
 */

// Monaco needs a worker for tokenisation and its editing services. Vite compiles it as a
// same-origin module worker, which is what the backend's Content-Security-Policy permits.
(self as unknown as { MonacoEnvironment: unknown }).MonacoEnvironment = {
  getWorker: () => new EditorWorker(),
};

interface OpenTab {
  documentId: string;
  path: string;
  model: monaco.editor.ITextModel;
  version: number;
  dirty: boolean;
  viewState: monaco.editor.ICodeEditorViewState | null;
}

interface Group {
  readonly container: HTMLElement;
  readonly tabBar: HTMLElement;
  readonly host: HTMLElement;
  readonly editor: monaco.editor.IStandaloneCodeEditor;
  tabs: OpenTab[];
  active: string | null;
}

export class EditorArea {
  readonly element = el('div', { class: 'editor-area' });

  private groups: Group[] = [];
  private activeGroup = 0;
  private readonly placeholder = el(
    'div',
    { class: 'editor-placeholder' },
    el('p', { text: 'Open a file from the explorer, or press the file-search shortcut.' }),
  );

  constructor(private readonly ctx: WorkbenchContext) {
    this.element.append(this.placeholder);
    this.registerLanguageBridge();

    ctx.on('editor.dirtyStateChanged', (event) => {
      const payload = event.payload as { documentId: string; dirty: boolean };
      this.forEachTab((tab, group) => {
        if (tab.documentId === payload.documentId && tab.dirty !== payload.dirty) {
          tab.dirty = payload.dirty;
          this.renderTabs(group);
        }
      });
    });
    ctx.on('language.diagnostics', (event) => {
      const payload = event.payload as { path: string; diagnostics: Diagnostic[] };
      this.applyDiagnostics(payload.path, payload.diagnostics);
    });
    ctx.on('debug.breakpointsChanged', (event) => {
      const payload = event.payload as { path: string; breakpoints: Array<{ line: number }> };
      this.applyBreakpoints(payload.path, payload.breakpoints.map((breakpoint) => breakpoint.line));
    });
    ctx.on('workspace.closed', () => this.closeEverything());
  }

  /** Splits the editor area. A second group is as far as the first milestone goes. */
  split(): void {
    if (this.groups.length >= 2) {
      return;
    }
    this.createGroup();
    this.layout();
  }

  async open(path: string, line?: number): Promise<void> {
    const group = this.ensureGroup();
    const existing = group.tabs.find((tab) => tab.path === path);
    if (existing) {
      this.activate(group, existing.documentId, line);
      return;
    }
    let opened: OpenDocument;
    try {
      opened = (await this.ctx.commands.execute<OpenDocument>('editor.open', { path })) as OpenDocument;
    } catch (error) {
      this.ctx.notify('error', describe(error));
      return;
    }
    const model = monaco.editor.createModel(
      opened.text,
      monacoLanguage(opened.document.languageId),
      monaco.Uri.parse(`forge:/${path}`),
    );
    const tab: OpenTab = {
      documentId: opened.document.id,
      path,
      model,
      version: opened.document.version,
      dirty: opened.document.dirty,
      viewState: null,
    };
    model.onDidChangeContent(() => this.onLocalEdit(tab));
    group.tabs.push(tab);
    this.activate(group, tab.documentId, line);
  }

  /** `file.save` for the active document. The content travels with the command. */
  async saveActive(): Promise<void> {
    const group = this.groups[this.activeGroup];
    const tab = group ? this.tabOf(group, group.active) : null;
    if (!tab) {
      return;
    }
    try {
      await this.ctx.commands.execute('file.save', { path: tab.path, content: tab.model.getValue() });
      tab.dirty = false;
      this.renderTabs(group);
    } catch (error) {
      this.ctx.notify('error', describe(error));
    }
  }

  async closeActive(): Promise<void> {
    const group = this.groups[this.activeGroup];
    if (group?.active) {
      await this.closeTab(group, group.active);
    }
  }

  /** Applies the edits `editor.format` returned. Computing them was the backend's job. */
  async formatActive(): Promise<void> {
    const group = this.groups[this.activeGroup];
    const tab = group ? this.tabOf(group, group.active) : null;
    if (!tab) {
      return;
    }
    try {
      const edits = (await this.ctx.commands.execute<TextEdit[]>('editor.format', {
        documentId: tab.documentId,
      })) as TextEdit[] | undefined;
      if (!edits || edits.length === 0) {
        this.ctx.notify('info', 'No formatter is registered for this language');
        return;
      }
      tab.model.pushEditOperations(
        [],
        edits.map((edit) => ({ range: toRange(edit.range), text: edit.newText })),
        () => null,
      );
    } catch (error) {
      this.ctx.notify('error', describe(error));
    }
  }

  activeDocumentId(): string | null {
    const group = this.groups[this.activeGroup];
    return group?.active ?? null;
  }

  activePath(): string | null {
    const group = this.groups[this.activeGroup];
    return group ? (this.tabOf(group, group.active)?.path ?? null) : null;
  }

  layout(): void {
    this.groups.forEach((group) => group.editor.layout());
  }

  setTheme(theme: 'dark' | 'light'): void {
    monaco.editor.setTheme(theme === 'light' ? 'vs' : 'vs-dark');
  }

  // ---- internals ----------------------------------------------------------------------

  private ensureGroup(): Group {
    if (this.groups.length === 0) {
      this.createGroup();
    }
    return this.groups[this.activeGroup] ?? this.groups[0];
  }

  private createGroup(): Group {
    this.placeholder.remove();
    const tabBar = el('div', { class: 'tab-bar', role: 'tablist' });
    const host = el('div', { class: 'editor-host' });
    const container = el('div', { class: 'editor-group' }, tabBar, host);
    this.element.append(container);

    const editor = monaco.editor.create(host, {
      automaticLayout: true,
      theme: document.documentElement.dataset.theme === 'light' ? 'vs' : 'vs-dark',
      fontSize: 13,
      minimap: { enabled: false },
      glyphMargin: true,
      scrollBeyondLastLine: false,
      renderWhitespace: 'selection',
      fixedOverflowWidgets: true,
    });

    const group: Group = { container, tabBar, host, editor, tabs: [], active: null };
    const index = this.groups.push(group) - 1;

    editor.onDidChangeCursorPosition((event) => {
      this.activeGroup = index;
      this.ctx.commands.execute('workbench.reportPosition', {
        line: event.position.lineNumber,
        column: event.position.column,
      });
    });
    editor.onMouseDown((event) => {
      if (event.target.type === monaco.editor.MouseTargetType.GUTTER_GLYPH_MARGIN) {
        const tab = this.tabOf(group, group.active);
        const line = event.target.position?.lineNumber;
        if (tab && line) {
          void this.ctx.commands.execute('debug.toggleBreakpoint', { path: tab.path, line });
        }
      }
    });
    host.addEventListener('focusin', () => {
      this.activeGroup = index;
    });
    return group;
  }

  private activate(group: Group, documentId: string, line?: number): void {
    const previous = this.tabOf(group, group.active);
    if (previous) {
      previous.viewState = group.editor.saveViewState();
    }
    const tab = this.tabOf(group, documentId);
    if (!tab) {
      return;
    }
    group.active = documentId;
    group.editor.setModel(tab.model);
    if (tab.viewState) {
      group.editor.restoreViewState(tab.viewState);
    }
    if (line) {
      group.editor.revealLineInCenter(line);
      group.editor.setPosition({ lineNumber: line, column: 1 });
    }
    group.editor.focus();
    this.renderTabs(group);
  }

  private renderTabs(group: Group): void {
    clear(group.tabBar);
    for (const tab of group.tabs) {
      const isActive = tab.documentId === group.active;
      const button = el('div', {
        class: `tab${isActive ? ' active' : ''}`,
        role: 'tab',
        title: tab.path,
      });
      button.append(
        el('span', { class: 'tab-label', text: tab.path.split('/').pop() ?? tab.path }),
        tab.dirty ? icon('dirty') : el('span', { class: 'tab-close', text: '✕' }),
      );
      button.addEventListener('click', () => this.activate(group, tab.documentId));
      button.addEventListener('auxclick', () => void this.closeTab(group, tab.documentId));
      const close = button.querySelector('.tab-close');
      close?.addEventListener('click', (event) => {
        event.stopPropagation();
        void this.closeTab(group, tab.documentId);
      });
      group.tabBar.append(button);
    }
  }

  private async closeTab(group: Group, documentId: string): Promise<void> {
    const tab = this.tabOf(group, documentId);
    if (!tab) {
      return;
    }
    if (tab.dirty && !window.confirm(`${tab.path} has unsaved changes. Close anyway?`)) {
      return;
    }
    try {
      await this.ctx.commands.execute('editor.close', { documentId });
    } catch {
      // Closing is best-effort: the buffer may already be gone on the backend.
    }
    tab.model.dispose();
    group.tabs = group.tabs.filter((candidate) => candidate.documentId !== documentId);
    group.active = group.tabs.at(-1)?.documentId ?? null;
    if (group.active) {
      this.activate(group, group.active);
    } else {
      group.editor.setModel(null);
      this.renderTabs(group);
    }
  }

  private closeEverything(): void {
    this.groups.forEach((group) => {
      group.tabs.forEach((tab) => tab.model.dispose());
      group.tabs = [];
      group.active = null;
      group.editor.setModel(null);
      clear(group.tabBar);
    });
  }

  /**
   * Pushes the working copy into the shared backend buffer, debounced. This is what makes
   * language tooling see what the user actually typed rather than the file on disk.
   */
  private onLocalEdit(tab: OpenTab): void {
    tab.dirty = true;
    const group = this.groups.find((candidate) => candidate.tabs.includes(tab));
    if (group) {
      this.renderTabs(group);
    }
    window.clearTimeout(this.syncTimers.get(tab.documentId));
    this.syncTimers.set(
      tab.documentId,
      window.setTimeout(() => {
        void this.ctx.client
          .command<{ version: number }>('editor.update', {
            documentId: tab.documentId,
            text: tab.model.getValue(),
          })
          .then((document) => {
            tab.version = document.version;
          })
          .catch(() => {
            // A failed sync only costs language intelligence; the buffer is still the user's.
          });
      }, 400),
    );
  }

  private syncTimers = new Map<string, number>();

  private forEachTab(visit: (tab: OpenTab, group: Group) => void): void {
    this.groups.forEach((group) => group.tabs.forEach((tab) => visit(tab, group)));
  }

  private tabOf(group: Group, documentId: string | null): OpenTab | null {
    return group.tabs.find((tab) => tab.documentId === documentId) ?? null;
  }

  private applyDiagnostics(path: string, diagnostics: Diagnostic[]): void {
    this.forEachTab((tab) => {
      if (tab.path !== path) {
        return;
      }
      monaco.editor.setModelMarkers(
        tab.model,
        'forge',
        diagnostics.map((diagnostic) => ({
          ...toMarkerRange(diagnostic.range),
          message: diagnostic.message,
          severity: severityOf(diagnostic.severity),
          source: diagnostic.source ?? undefined,
        })),
      );
    });
  }

  private breakpointDecorations = new Map<string, string[]>();

  private applyBreakpoints(path: string, lines: number[]): void {
    this.forEachTab((tab, group) => {
      if (tab.path !== path) {
        return;
      }
      const previous = this.breakpointDecorations.get(tab.documentId) ?? [];
      const next = group.editor.deltaDecorations(
        previous,
        lines.map((line) => ({
          range: new monaco.Range(line, 1, line, 1),
          options: { isWholeLine: false, glyphMarginClassName: 'breakpoint-glyph' },
        })),
      );
      this.breakpointDecorations.set(tab.documentId, next);
    });
  }

  /**
   * Bridges Monaco's providers to the framework's language queries. Monaco asks; the backend
   * answers; no language knowledge is implemented here.
   */
  private registerLanguageBridge(): void {
    monaco.languages.registerCompletionItemProvider('*', {
      provideCompletionItems: async (model, position) => {
        const tab = this.findTabByModel(model);
        if (!tab) {
          return { suggestions: [] };
        }
        const word = model.getWordUntilPosition(position);
        const range = new monaco.Range(
          position.lineNumber,
          word.startColumn,
          position.lineNumber,
          word.endColumn,
        );
        const items = await this.ctx.client.query<CompletionItem[]>('language.completion', {
          documentId: tab.documentId,
          position: { line: position.lineNumber - 1, character: position.column - 1 },
        });
        return {
          suggestions: items.map((item) => ({
            label: item.label,
            kind: monaco.languages.CompletionItemKind.Text,
            detail: item.detail ?? undefined,
            documentation: item.documentation ?? undefined,
            insertText: item.insertText ?? item.label,
            range,
          })),
        };
      },
    });

    monaco.languages.registerHoverProvider('*', {
      provideHover: async (model, position) => {
        const tab = this.findTabByModel(model);
        if (!tab) {
          return null;
        }
        const hover = await this.ctx.client.query<{ contents: string } | null>('language.hover', {
          documentId: tab.documentId,
          position: { line: position.lineNumber - 1, character: position.column - 1 },
        });
        return hover ? { contents: [{ value: hover.contents }] } : null;
      },
    });
  }

  private findTabByModel(model: monaco.editor.ITextModel): OpenTab | null {
    let found: OpenTab | null = null;
    this.forEachTab((tab) => {
      if (tab.model === model) {
        found = tab;
      }
    });
    return found;
  }
}

interface Range {
  start: { line: number; character: number };
  end: { line: number; character: number };
}

interface TextEdit {
  range: Range;
  newText: string;
}

interface Diagnostic {
  range: Range;
  severity: 'ERROR' | 'WARNING' | 'INFORMATION' | 'HINT';
  message: string;
  source: string | null;
  code: string | null;
}

interface CompletionItem {
  label: string;
  detail: string | null;
  documentation: string | null;
  insertText: string | null;
}

function toRange(range: Range): monaco.Range {
  return new monaco.Range(
    range.start.line + 1,
    range.start.character + 1,
    range.end.line + 1,
    range.end.character + 1,
  );
}

function toMarkerRange(range: Range) {
  return {
    startLineNumber: range.start.line + 1,
    startColumn: range.start.character + 1,
    endLineNumber: range.end.line + 1,
    endColumn: range.end.character + 1,
  };
}

function severityOf(severity: Diagnostic['severity']): monaco.MarkerSeverity {
  switch (severity) {
    case 'ERROR':
      return monaco.MarkerSeverity.Error;
    case 'WARNING':
      return monaco.MarkerSeverity.Warning;
    case 'INFORMATION':
      return monaco.MarkerSeverity.Info;
    default:
      return monaco.MarkerSeverity.Hint;
  }
}

/** The backend's language ids mostly match Monaco's; the few that differ are mapped here. */
function monacoLanguage(languageId: string): string {
  const aliases: Record<string, string> = {
    typescriptreact: 'typescript',
    javascriptreact: 'javascript',
    shellscript: 'shell',
    plaintext: 'plaintext',
  };
  return aliases[languageId] ?? languageId;
}
