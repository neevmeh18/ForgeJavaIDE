import type * as monaco from 'monaco-editor/esm/vs/editor/editor.api';

export interface OpenTab {
  workspaceGeneration: number;
  documentId: string;
  path: string;
  model: monaco.editor.ITextModel;
  version: number;
  dirty: boolean;
  viewState: monaco.editor.ICodeEditorViewState | null;
}
