import type * as monaco from 'monaco-editor/esm/vs/editor/editor.api';
import type { OpenTab } from "./OpenTab";

export interface Group {
  readonly container: HTMLElement;
  readonly tabBar: HTMLElement;
  readonly host: HTMLElement;
  readonly editor: monaco.editor.IStandaloneCodeEditor;
  tabs: OpenTab[];
  active: string | null;
}
