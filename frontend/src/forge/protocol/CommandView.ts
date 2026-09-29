export interface CommandView {
  id: string;
  title: string;
  category: string;
  description: string;
  requiresWorkspace: boolean;
  undoable: boolean;
  sensitive: boolean;
  source: string;
  paletteVisible?: boolean;
  arguments?: Array<{ name: string; type: string; description: string }>;
}
