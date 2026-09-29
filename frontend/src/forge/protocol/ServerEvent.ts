export interface ServerEvent<T = Record<string, unknown>> {
  type: string;
  workspaceId: string | null;
  payload: T;
}
