import type { EventListener } from "./client/EventListener";
import type { Result, ServerEvent } from './protocol';













export class ForgeRequestError extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly details: Record<string, string> = {},
  ) {
    super(message);
    this.name = 'ForgeRequestError';
  }
}




export function describeError(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

export class ForgeClient {
  private token: string | null = null;
  private workspaceId: string | null = null;
  private generation = 0;
  get workspaceGeneration(): number { return this.generation; }
  private listeners = new Set<EventListener>();
  private stream: AbortController | null = null;
  private reconnectDelay = 1000;

  setToken(token: string | null): void {
    this.token = token;
  }

  setWorkspace(workspaceId: string | null): void {
    this.generation++;
    this.workspaceId = workspaceId;
  }

  get currentWorkspace(): string | null {
    return this.workspaceId;
  }

  get authenticated(): boolean {
    return this.token !== null;
  }


  async command<T>(id: string, args: Record<string, unknown> = {}): Promise<T> {
    return this.call<T>('/api/command', { id, args });
  }





  async commandAsync(id: string, args: Record<string, unknown> = {}): Promise<string> {
    const response = await this.post('/api/command', { id, args, async: true });
    const result = (await response.json()) as Result<unknown>;
    if (!result.ok) {
      throw this.toError(result);
    }
    return result.executionId ?? '';
  }

  async query<T>(id: string, args: Record<string, unknown> = {}): Promise<T> {
    return this.call<T>('/api/query', { id, args });
  }

  onEvent(listener: EventListener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }


  connectEvents(): void {
    if (this.stream || !this.token) {
      return;
    }
    const controller = new AbortController();
    this.stream = controller;
    void this.pump(controller);
  }

  disconnectEvents(): void {
    this.stream?.abort();
    this.stream = null;
  }

  private async pump(controller: AbortController): Promise<void> {
    try {
      const response = await fetch('/api/events', {
        headers: this.headers(false),
        signal: controller.signal,
      });
      if (!response.ok || !response.body) {
        throw new Error(`Event stream refused: ${response.status}`);
      }
      this.reconnectDelay = 1000;
      this.listeners.forEach((listener) => listener({ type: 'forge.resync', workspaceId: null, payload: {} }));
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      for (;;) {
        const { done, value } = await reader.read();
        if (done) {
          break;
        }
        buffer += decoder.decode(value, { stream: true });

        let split = buffer.indexOf('\n\n');
        while (split >= 0) {
          this.dispatch(buffer.slice(0, split));
          buffer = buffer.slice(split + 2);
          split = buffer.indexOf('\n\n');
        }
      }
    } catch (error) {
      if (controller.signal.aborted) {
        return;
      }
      console.warn('Event stream interrupted; reconnecting', error);
    }
    if (!controller.signal.aborted && this.token) {
      this.stream = null;
      const delay = this.reconnectDelay;
      this.reconnectDelay = Math.min(delay * 2, 15000);
      setTimeout(() => this.connectEvents(), delay);
    }
  }

  private dispatch(frame: string): void {
    const dataLine = frame.split('\n').find((line) => line.startsWith('data: '));
    if (!dataLine) {
      return;
    }
    try {
      const event = JSON.parse(dataLine.slice(6)) as ServerEvent;
      this.listeners.forEach((listener) => listener(event));
    } catch (error) {
      console.warn('Ignoring malformed event frame', error);
    }
  }

  private async call<T>(path: string, body: Record<string, unknown>): Promise<T> {
    const generation = this.generation;
    const response = await this.post(path, body);
    const result = (await response.json()) as Result<T>;
    if (!result.ok) {
      throw this.toError(result);
    }
    if (generation !== this.generation) throw new ForgeRequestError('CANCELLED', 'Workspace changed');
    if (result.pending && result.executionId) return this.waitForCommand<T>(result.executionId, generation);
    return result.value as T;
  }

  async waitForCommand<T>(executionId: string, generation = this.generation): Promise<T> {
    const deadline = Date.now() + 6 * 60 * 1000;
    while (Date.now() < deadline) {
      if (generation !== this.generation) throw new ForgeRequestError('CANCELLED', 'Workspace changed');
      const outcome = await this.query<Result<T>>('command.result', { executionId });
      if (!outcome.ok) throw this.toError(outcome);
      if (!outcome.pending) return outcome.value as T;
      await new Promise((resolve) => setTimeout(resolve, 250));
    }
    throw new ForgeRequestError('UNAVAILABLE', 'Command outcome not available; refresh state');
  }

  private async post(path: string, body: Record<string, unknown>): Promise<Response> {
    const response = await fetch(path, {
      method: 'POST',
      headers: this.headers(true),
      body: JSON.stringify(body),
    });
    if (response.status === 401) {
      this.token = null;
      this.disconnectEvents();
    }
    return response;
  }

  private headers(json: boolean): Record<string, string> {
    const headers: Record<string, string> = {};
    if (json) {
      headers['Content-Type'] = 'application/json';
    }
    if (this.token) {
      headers['Authorization'] = `Bearer ${this.token}`;
    }
    if (this.workspaceId) {
      headers['X-Forge-Workspace'] = this.workspaceId;
    }
    return headers;
  }

  private toError(result: Result<unknown>): ForgeRequestError {
    const error = result.error;
    return new ForgeRequestError(
      error?.code ?? 'INTERNAL_FAILURE',
      error?.message ?? 'Request failed',
      error?.details ?? {},
    );
  }
}

export type { EventListener } from "./client/EventListener";
