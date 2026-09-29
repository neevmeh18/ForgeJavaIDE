export interface ResolvedSetting {
  key: string;
  value: unknown;
  layer: 'DEFAULT' | 'EXTENSION' | 'USER' | 'WORKSPACE';
  definition: {
    key: string;
    type: 'STRING' | 'NUMBER' | 'BOOLEAN' | 'STRING_LIST';
    defaultValue: unknown;
    description: string;
    allowedValues: string[];
    source: string;
  };
}
