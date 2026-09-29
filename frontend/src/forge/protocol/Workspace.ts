export interface Workspace {
  id: string;
  name: string;
  location: { scheme: string; authority: string; path: string };
  metadata: Record<string, string>;
  state: 'AVAILABLE' | 'OPENING' | 'OPEN' | 'CLOSED' | 'ERROR';
}
