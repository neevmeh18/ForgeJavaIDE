import type { Result, ServerEvent } from './protocol';

/**
 * The only way the workbench talks to the backend.
 *
 * <p>Three operations — run a command, run a query, listen to events — mirroring the gateway
 * exactly. Nothing else in the frontend performs a network request, so authentication, error
 * translation and the workspace header are handled in one place.
 *
 * <p>The event stream is read with `fetch`, not `EventSource`, so the bearer token travels in
 * the `Authorization` header like every other call. No cookie means no ambient authority and
 * nothing for a cross-site request to abuse.
 */

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

type EventListener = (event: ServerEvent) => void;

/** Turns anything thrown into something a user can read. */
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

  /** Runs a command. Returns the typed result, or throws a structured error. */
  async command<T>(id: string, args: Record<string, unknown> = {}): Promise<T> {
    return this.call<T>('/api/command', { id, args });
  }

  /**
   * Starts a long-running command without waiting. The outcome arrives as a `command.completed`
   * or `command.failed` event carrying the same execution id.
   */
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

  /** Opens the event stream and keeps it open, reconnecting with a backoff. */
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
        // SSE frames are separated by a blank line; a comment line is a heartbeat.
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
