import type { ServerEvent } from '../protocol';

export type EventListener = (event: ServerEvent) => void;
