import type { TextMatch } from "./TextMatch";
export interface TextSearchResult {
  matches: TextMatch[];
  truncated: boolean;
  filesScanned: number;
}
