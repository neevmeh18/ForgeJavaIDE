import type { Range } from "./Range";

export interface Diagnostic {
  range: Range;
  severity: 'ERROR' | 'WARNING' | 'INFORMATION' | 'HINT';
  message: string;
  source: string | null;
  code: string | null;
}
