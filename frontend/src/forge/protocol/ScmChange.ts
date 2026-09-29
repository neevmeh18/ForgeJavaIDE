export interface ScmChange {
  path: string;
  status: string;
  staged: boolean;
  originalPath: string | null;
}
