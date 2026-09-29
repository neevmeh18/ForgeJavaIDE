import type { ScmChange } from "./ScmChange";
export interface ScmStatus {
  repository: boolean;
  branch: string;
  ahead: number;
  behind: number;
  clean: boolean;
  changes: ScmChange[];
  conflicted: boolean;
}
