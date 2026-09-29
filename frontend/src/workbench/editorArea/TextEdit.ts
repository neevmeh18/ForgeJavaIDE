import type { Range } from "./Range";

export interface TextEdit {
  range: Range;
  newText: string;
}
