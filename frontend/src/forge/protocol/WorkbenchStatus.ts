export interface WorkbenchStatus {
  product: string;
  version: string;
  commandCount: number;
  extensionCount: number;
  languages: string[];
  terminalsAvailable: boolean;
}
