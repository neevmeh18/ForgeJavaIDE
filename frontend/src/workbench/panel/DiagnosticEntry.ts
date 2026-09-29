export interface DiagnosticEntry {
  range: { start: { line: number; character: number }; end: { line: number; character: number } };
  severity: string;
  message: string;
}
