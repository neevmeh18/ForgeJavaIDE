/**
 * The wire shapes the backend speaks.
 *
 * <p>These are transport types, not a second domain model. The frontend deliberately does not
 * re-model workspaces, documents or repositories: Java is authoritative, and anything the UI
 * "knows" beyond what it was told is a chance to disagree with the backend.
 */

export type ErrorCode =
  | 'NOT_FOUND'
  | 'INVALID_ARGUMENT'
  | 'CONFLICT'
  | 'UNAUTHORIZED'
  | 'FORBIDDEN'
  | 'UNAVAILABLE'
  | 'UNSUPPORTED'
  | 'CANCELLED'
  | 'INTERNAL_FAILURE';

export interface ForgeError {
  code: ErrorCode;
  message: string;
  details: Record<string, string>;
}

export interface Result<T> {
  ok: boolean;
  value: T | null;
  error: ForgeError | null;
  executionId: string | null;
  pending: boolean;
}

export interface ServerEvent<T = Record<string, unknown>> {
  type: string;
  workspaceId: string | null;
  payload: T;
}

export interface Workspace {
  id: string;
  name: string;
  location: { scheme: string; authority: string; path: string };
  metadata: Record<string, string>;
  state: 'AVAILABLE' | 'OPENING' | 'OPEN' | 'CLOSED' | 'ERROR';
}

export interface DirEntry {
  name: string;
  path: string;
  directory: boolean;
  size: number;
  modifiedAt: number;
}

export interface DocumentInfo {
  id: string;
  workspaceId: string;
  path: string;
  languageId: string;
  version: number;
  dirty: boolean;
}

export interface OpenDocument {
  document: DocumentInfo;
  text: string;
}

export interface CommandView {
  id: string;
  title: string;
  category: string;
  description: string;
  requiresWorkspace: boolean;
  undoable: boolean;
  sensitive: boolean;
  source: string;
}

export interface MenuItem {
  menu: string;
  command: string;
  title: string;
  group: string;
  order: number;
  source: string;
}

export interface Keybinding {
  key: string;
  command: string;
  when: string | null;
  source: string;
}

export interface ViewContribution {
  id: string;
  title: string;
  container: string;
  icon: string;
  order: number;
  source: string;
}

export interface Contributions {
  menus: MenuItem[];
  keybindings: Keybinding[];
  views: ViewContribution[];
}

export interface LoginResult {
  token: string;
  sessionId: string;
  user: { id: string; displayName: string; providerId: string };
  expiresAt: string;
}

export interface ResolvedSetting {
  key: string;
  value: unknown;
  layer: 'DEFAULT' | 'EXTENSION' | 'USER' | 'WORKSPACE';
  definition: {
    key: string;
    type: 'STRING' | 'NUMBER' | 'BOOLEAN' | 'STRING_LIST';
    defaultValue: unknown;
    description: string;
    allowedValues: string[];
    source: string;
  };
}

export interface FileMatch {
  path: string;
  name: string;
  score: number;
}

export interface TextMatch {
  path: string;
  line: number;
  column: number;
  preview: string;
}

export interface TextSearchResult {
  matches: TextMatch[];
  truncated: boolean;
  filesScanned: number;
}

export interface ScmChange {
  path: string;
  status: string;
  staged: boolean;
  originalPath: string | null;
}

export interface ScmStatus {
  repository: boolean;
  branch: string;
  ahead: number;
  behind: number;
  clean: boolean;
  changes: ScmChange[];
  conflicted: boolean;
}

export interface TerminalInfo {
  id: string;
  workspaceId: string;
  title: string;
  cwd: string;
  alive: boolean;
}

export interface TaskInfo {
  id: string;
  name: string;
  type: string;
  executable: string;
  arguments: string[];
  source: string;
}

export interface ExtensionStatus {
  id: string;
  name: string;
  version: string;
  state: 'DISCOVERED' | 'LOADED' | 'ACTIVATED' | 'DEACTIVATED' | 'FAILED';
  failure: string | null;
}

export interface WorkbenchStatus {
  product: string;
  version: string;
  commandCount: number;
  extensionCount: number;
  languages: string[];
  terminalsAvailable: boolean;
}
