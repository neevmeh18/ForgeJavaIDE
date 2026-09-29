export interface ExtensionStatus {
  id: string;
  name: string;
  version: string;
  state: 'DISCOVERED' | 'LOADED' | 'ACTIVATED' | 'DEACTIVATED' | 'FAILED';
  failure: string | null;
}
