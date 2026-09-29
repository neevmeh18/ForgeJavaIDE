import type { WorkbenchStatus, Workspace } from '../protocol';

export interface WorkbenchState {
  workspace: Workspace | null;
  status: WorkbenchStatus | null;
  user: string | null;
  theme: 'dark' | 'light';
  settings: Map<string, unknown>;
}
