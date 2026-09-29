export interface DocumentInfo {
  id: string;
  workspaceId: string;
  path: string;
  languageId: string;
  version: number;
  dirty: boolean;
}
