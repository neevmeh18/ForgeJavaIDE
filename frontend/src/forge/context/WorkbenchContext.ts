import type { ForgeClient } from '../client';
import type { CommandRouter } from '../commands';
import type { ServerEvent } from '../protocol';

import type { NotificationKind } from "./NotificationKind";
import type { WorkbenchState } from "./WorkbenchState";
export interface WorkbenchContext {
  readonly client: ForgeClient;
  readonly commands: CommandRouter;
  readonly state: WorkbenchState;


  on(type: string, handler: (event: ServerEvent) => void): () => void;

  notify(kind: NotificationKind, message: string): void;


  showView(id: string): void;


  openFile(path: string, line?: number): Promise<void>;
}
