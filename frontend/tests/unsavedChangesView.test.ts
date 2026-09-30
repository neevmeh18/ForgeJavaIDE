// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { WorkbenchContext } from '../src/forge/context';
import type { DocumentInfo, ServerEvent } from '../src/forge/protocol';
import { UnsavedChangesView } from '../src/workbench/unsavedChangesView';

const draft = (id: string, path = `${id}.java`, workspaceId = 'workspace'): DocumentInfo => ({
  id, path, workspaceId, languageId: 'java', version: 2, dirty: true,
});

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

function fixture() {
  const handlers = new Map<string, (event: ServerEvent) => void>();
  const client = { currentWorkspace: 'workspace' as string | null, query: vi.fn() };
  const commands = { execute: vi.fn().mockResolvedValue(undefined) };
  const notify = vi.fn();
  const ctx = {
    client, commands, notify,
    on: (type: string, handler: (event: ServerEvent) => void) => {
      handlers.set(type, handler);
      return () => handlers.delete(type);
    },
  } as unknown as WorkbenchContext;
  const view = new UnsavedChangesView(ctx);
  document.body.append(view.element);
  const emit = (type: string, workspaceId = 'workspace') =>
    handlers.get(type)?.({ type, workspaceId, payload: {} });
  return { view, client, commands, notify, emit };
}

afterEach(() => { document.body.replaceChildren(); });

describe('Unsaved Changes view', () => {
  it('does not query without an open workspace', async () => {
    const { view, client } = fixture();
    client.currentWorkspace = null;
    await view.refresh();
    expect(view.element.textContent).toContain('No workspace open');
    expect(client.query).not.toHaveBeenCalled();
  });

  it('shows an empty state when there are no dirty buffers', async () => {
    const { view, client } = fixture();
    client.query.mockResolvedValue([]);
    await view.refresh();
    expect(view.element.textContent).toContain('No unsaved changes');
    expect(view.element.querySelector('.unsaved-preview')?.textContent).toBe('');
  });

  it('previews buffer text literally and restores through the existing open command', async () => {
    const { view, client, commands } = fixture();
    const document = draft('closed', 'src/Main.java');
    const text = '<script>unsaved()</script>\n  retained text\n';
    client.query.mockImplementation(async (id: string) =>
      id === 'editor.unsavedDocuments' ? [document] : { document, text });
    await view.refresh();

    expect(client.query.mock.calls).toEqual([
      ['editor.unsavedDocuments'], ['editor.document', { documentId: 'closed' }],
    ]);
    expect(view.element.querySelector('pre')?.textContent).toBe(text);
    expect(view.element.querySelector('script')).toBeNull();
    expect(commands.execute).not.toHaveBeenCalled();
    view.element.querySelector<HTMLButtonElement>('.primary')!.click();
    expect(commands.execute).toHaveBeenCalledWith('workbench.openFile', { path: 'src/Main.java' });
  });

  it('keeps the newest selection when earlier preview requests complete later', async () => {
    const { view, client } = fixture();
    const first = deferred<{ document: DocumentInfo; text: string }>();
    const a = draft('a');
    const b = draft('b');
    client.query.mockImplementation((id: string, args?: { documentId: string }) => {
      if (id === 'editor.unsavedDocuments') return Promise.resolve([a, b]);
      return args?.documentId === 'a' ? first.promise : Promise.resolve({ document: b, text: 'B draft' });
    });
    const loading = view.refresh();
    await vi.waitFor(() => expect(view.element.querySelectorAll('.unsaved-document')).toHaveLength(2));
    view.element.querySelectorAll<HTMLButtonElement>('.unsaved-document')[1].click();
    await vi.waitFor(() => expect(view.element.querySelector('pre')?.textContent).toBe('B draft'));
    first.resolve({ document: a, text: 'A draft' });
    await loading;
    expect(view.element.querySelector('pre')?.textContent).toBe('B draft');
    expect(view.element.querySelector('[aria-pressed="true"]')?.textContent).toBe('b.java');
  });

  it('ignores an older list response after a newer refresh has completed', async () => {
    const { view, client } = fixture();
    const old = deferred<DocumentInfo[]>();
    client.query.mockReturnValueOnce(old.promise).mockResolvedValueOnce([]);
    const loading = view.refresh();
    await view.refresh();
    old.resolve([draft('stale')]);
    await loading;
    expect(view.element.textContent).toContain('No unsaved changes');
    expect(view.element.textContent).not.toContain('stale.java');
  });

  it('refreshes current text and removes drafts that become clean or deleted', async () => {
    const { view, client, emit } = fixture();
    const document = draft('a');
    let documents = [document];
    let text = 'first edit';
    client.query.mockImplementation(async (id: string) =>
      id === 'editor.unsavedDocuments' ? documents : { document, text });
    view.setVisible(true);
    await vi.waitFor(() => expect(view.element.querySelector('pre')?.textContent).toBe(text));
    text = 'latest edit';
    emit('editor.documentChanged');
    await vi.waitFor(() => expect(view.element.querySelector('pre')?.textContent).toBe(text));

    documents = [];
    emit('editor.dirtyStateChanged');
    await vi.waitFor(() => expect(view.element.textContent).toContain('No unsaved changes'));
    expect(view.element.querySelector('pre')).toBeNull();
    documents = [document];
    emit('editor.closed');
    await vi.waitFor(() => expect(view.element.querySelector('pre')?.textContent).toBe(text));
    documents = [];
    emit('file.deleted');
    await vi.waitFor(() => expect(view.element.textContent).toContain('No unsaved changes'));
  });

  it('ignores other workspaces and avoids querying while hidden', async () => {
    const { view, client, emit } = fixture();
    client.query.mockResolvedValue([]);
    emit('editor.documentChanged');
    expect(client.query).not.toHaveBeenCalled();
    view.setVisible(true);
    await vi.waitFor(() => expect(view.element.textContent).toContain('No unsaved changes'));
    client.query.mockClear();
    emit('editor.documentChanged', 'other-workspace');
    expect(client.query).not.toHaveBeenCalled();
    view.setVisible(false);
    emit('editor.dirtyStateChanged');
    expect(client.query).not.toHaveBeenCalled();
  });

  it('clears the old workspace and ignores its pending preview after switching', async () => {
    const { view, client } = fixture();
    const old = deferred<{ document: DocumentInfo; text: string }>();
    const document = draft('a');
    client.query.mockResolvedValueOnce([document]).mockReturnValueOnce(old.promise);
    const loading = view.refresh();
    await vi.waitFor(() => expect(view.element.textContent).toContain('Loading draft'));
    client.currentWorkspace = 'other-workspace';
    client.query.mockResolvedValueOnce([]);
    await view.refresh();
    old.resolve({ document, text: 'old workspace text' });
    await loading;
    expect(view.element.textContent).toContain('No unsaved changes');
    expect(view.element.textContent).not.toContain('old workspace text');
    expect(view.element.querySelector('.primary')).toBeNull();
  });

  it('clears the view when the current workspace closes and invalidates pending reads', async () => {
    const { view, client, emit } = fixture();
    const old = deferred<DocumentInfo[]>();
    client.query.mockReturnValueOnce(old.promise);
    const loading = view.refresh();
    emit('workspace.closed');
    client.currentWorkspace = null;
    old.resolve([draft('a')]);
    await loading;
    expect(view.element.textContent).toContain('No workspace open');
    expect(view.element.querySelector('.unsaved-document')).toBeNull();
  });

  it('shows list and preview failures without leaving a stale restore control', async () => {
    const { view, client } = fixture();
    client.query.mockResolvedValueOnce([draft('a')]).mockRejectedValueOnce(new Error('Draft removed'));
    await view.refresh();
    expect(view.element.textContent).toContain('Could not load draft: Draft removed');
    expect(view.element.querySelector('.primary')).toBeNull();
    client.query.mockRejectedValueOnce(new Error('Offline'));
    await view.refresh();
    expect(view.element.textContent).toContain('Could not load unsaved changes: Offline');
    expect(view.element.querySelector('.unsaved-preview')?.textContent).toBe('');
  });
});
