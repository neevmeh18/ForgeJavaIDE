import type { WorkbenchContext } from '../forge/context';
import type { TerminalInfo } from '../forge/protocol';
import { clear, el } from './dom';
import { describeError as describe, ForgeRequestError } from '../forge/client';

interface TerminalErrorEntry {
  time: string;
  operation: string;
  code: string;
  message: string;
  details: Record<string, string>;
  userId?: string;
  sessionId?: string;
  workspaceId?: string;
}

interface SharedTerminalErrorEntry {
  time: string;
  userId: string;
  sessionId: string;
  workspaceId: string;
  operation: string;
  error: { code: string; message: string; details: Record<string, string> };
}

export class TerminalView {
  readonly element = el('div', { class: 'view terminal-view' });
  private readonly tabs = el('div', { class: 'terminal-tabs' });
  private readonly identity = el('div', { class: 'terminal-identity' });
  private readonly output = el('pre', { class: 'terminal-output', tabindex: '0' });
  private readonly errors = el('div', { class: 'terminal-errors', hidden: 'true' });
  private readonly input = el('input', { class: 'terminal-input', type: 'text', spellcheck: 'false', placeholder: 'Type a command and press Enter' });
  private readonly errorEntries: TerminalErrorEntry[] = [];
  private terminals: TerminalInfo[] = [];
  private active: string | null = null;
  private readonly buffers = new Map<string, string>();
  private readonly history: string[] = [];
  private historyIndex = 0;
  private showingErrors = false;
  private awaitingSensitiveInput = false;

  constructor(private readonly ctx: WorkbenchContext) {
    const actions = el('div', { class: 'view-actions' });
    const errorButton = el('button', { class: 'view-action', title: 'Terminal errors', text: 'Errors' });
    errorButton.addEventListener('click', () => void this.toggleErrors());
    const create = el('button', { class: 'view-action', title: 'New terminal', text: '＋' });
    create.addEventListener('click', () => void this.create());
    const kill = el('button', { class: 'view-action', title: 'Kill terminal', text: '🗑' });
    kill.addEventListener('click', () => void this.kill());
    actions.append(errorButton, create, kill);

    this.element.append(el('div', { class: 'view-header' }, this.tabs, actions), this.identity, this.output, this.errors, this.input);
    this.input.addEventListener('keydown', (event) => this.onKey(event));
    ctx.on('terminal.output', (event) => {
      const payload = event.payload as { terminalId: string; data: string };
      this.appendOutput(payload.terminalId, payload.data);
    });
    ctx.on('terminal.created', () => void this.refresh());
    ctx.on('terminal.exited', (event) => {
      const payload = event.payload as { terminalId: string; exitCode: number };
      this.appendOutput(payload.terminalId, `\n[process exited with code ${payload.exitCode}]\n`);
      void this.refresh();
    });
    ctx.on('workspace.opened', () => void this.refresh());
    this.renderIdentity();
  }

  async refresh(): Promise<void> {
    const generation = this.ctx.client.workspaceGeneration;
    if (!this.ctx.client.currentWorkspace) { this.resetWorkspace(); return; }
    try { this.terminals = await this.ctx.client.query<TerminalInfo[]>('terminal.list'); }
    catch (error) { if (generation !== this.ctx.client.workspaceGeneration) return; this.recordError('terminal.list', error); this.terminals = []; }
    if (this.active && !this.terminals.some((terminal) => terminal.id === this.active)) this.active = null;
    if (!this.active && this.terminals.length > 0) await this.select(this.terminals[0].id);
    this.renderTabs(); this.renderIdentity();
  }

  resetWorkspace(): void {
    this.terminals = []; this.active = null; this.buffers.clear(); this.history.length = 0; this.historyIndex = 0;
    this.output.textContent = ''; this.input.value = ''; this.errorEntries.length = 0; this.showingErrors = false;
    this.renderTabs(); this.renderIdentity(); this.renderErrors();
  }

  applySettings(settings: Map<string, unknown>): void {
    const size = Number(settings.get('terminal.fontSize') ?? 12);
    const fontSize = Number.isFinite(size) ? Math.max(8, Math.min(32, size)) : 12;
    this.output.style.fontSize = `${fontSize}px`; this.input.style.fontSize = `${fontSize}px`;
  }

  async create(): Promise<void> {
    try {
      const terminal = await this.ctx.commands.execute<TerminalInfo>('terminal.create', { columns: 120, rows: 30 }) as TerminalInfo;
      await this.refresh(); await this.select(terminal.id); this.input.focus();
    } catch (error) { this.recordError('terminal.create', error); this.ctx.notify('error', describe(error)); }
  }

  private async kill(): Promise<void> {
    if (!this.active) return;
    try { await this.ctx.commands.execute('terminal.kill', { terminalId: this.active }); }
    catch (error) { this.recordError('terminal.kill', error); this.ctx.notify('error', describe(error)); }
  }

  private async select(terminalId: string): Promise<void> {
    this.active = terminalId;
    if (!this.buffers.has(terminalId)) {
      try { this.buffers.set(terminalId, await this.ctx.client.query<string>('terminal.scrollback', { terminalId })); }
      catch (error) { this.recordError('terminal.scrollback', error); this.buffers.set(terminalId, ''); }
    }
    this.renderOutput(); this.renderTabs();
  }

  private onKey(event: KeyboardEvent): void {
    if (event.key === 'Enter') {
      event.preventDefault(); const line = this.input.value; this.input.value = '';
      if (line.trim() && !this.awaitingSensitiveInput) { this.history.push(line); if (this.history.length > 500) this.history.shift(); }
      this.historyIndex = this.history.length;
      if (line.length > 0 && !this.awaitingSensitiveInput) this.appendLocalPrompt(line);
      void this.executeLine(line);
    } else if (event.key === 'ArrowUp') {
      event.preventDefault(); this.historyIndex = Math.max(0, this.historyIndex - 1); this.input.value = this.history[this.historyIndex] ?? '';
    } else if (event.key === 'ArrowDown') {
      event.preventDefault(); this.historyIndex = Math.min(this.history.length, this.historyIndex + 1); this.input.value = this.history[this.historyIndex] ?? '';
    }
  }

  private async executeLine(command: string): Promise<void> {
    if (!this.active) await this.create();
    if (!this.active) return;
    try {
      const sensitive = this.awaitingSensitiveInput;
      this.awaitingSensitiveInput = false;
      if (!sensitive) {
        await this.ctx.commands.execute('terminal.noteCommand', { terminalId: this.active, command });
      }
      await this.ctx.commands.execute('terminal.write', { terminalId: this.active, data: `${command}\n` });
    } catch (error) { this.recordError('terminal.write', error); this.ctx.notify('error', describe(error)); }
  }

  private appendLocalPrompt(command: string): void {
    if (!this.active) return;
    const prefix = `${this.userLabel()}@${this.workspaceLabel()} $ `;
    this.appendOutput(this.active, `${prefix}${command}\n`);
  }

  private appendOutput(terminalId: string, data: string): void {
    if (terminalId === this.active && /password\s*:/i.test(data)) this.awaitingSensitiveInput = true;
    if (!this.buffers.has(terminalId) && this.buffers.size >= 64) this.buffers.delete(this.buffers.keys().next().value!);
    const combined = (this.buffers.get(terminalId) ?? '') + data;
    this.buffers.set(terminalId, combined.length > 120_000 ? combined.slice(-120_000) : combined);
    if (terminalId === this.active) this.renderOutput();
  }

  private recordError(operation: string, error: unknown): void {
    const entry: TerminalErrorEntry = error instanceof ForgeRequestError
      ? { time: new Date().toLocaleTimeString(), operation, code: error.code, message: error.message, details: error.details }
      : { time: new Date().toLocaleTimeString(), operation, code: 'CLIENT_ERROR', message: describe(error), details: {} };
    this.errorEntries.unshift(entry);
    if (this.errorEntries.length > 50) this.errorEntries.length = 50;
    this.renderErrors();
  }

  private async toggleErrors(): Promise<void> {
    this.showingErrors = !this.showingErrors;
    if (this.showingErrors) {
      try {
        const response = await this.ctx.client.terminalErrors<{ errors: SharedTerminalErrorEntry[] }>();
        this.errorEntries.length = 0;
        for (const item of response.errors) {
          this.errorEntries.push({
            time: new Date(item.time).toLocaleTimeString(), operation: item.operation,
            code: item.error.code, message: item.error.message, details: item.error.details,
            userId: item.userId, sessionId: item.sessionId, workspaceId: item.workspaceId,
          });
        }
      } catch (error) {
        this.recordError('terminal.errors', error);
      }
    }
    this.renderErrors();
  }

  private renderErrors(): void {
    this.errors.hidden = !this.showingErrors; this.output.hidden = this.showingErrors; this.input.hidden = this.showingErrors;
    clear(this.errors);
    if (!this.showingErrors) return;
    if (this.errorEntries.length === 0) { this.errors.append(el('p', { class: 'view-empty', text: 'No recorded terminal errors.' })); return; }
    for (const error of this.errorEntries) {
      const detailText = Object.entries(error.details).map(([key, value]) => `${key}: ${value}`).join('\n');
      this.errors.append(el('article', { class: 'terminal-error-card' },
        el('div', { class: 'terminal-error-title', text: `${error.code} · ${error.operation}` }),
        el('div', { class: 'terminal-error-meta', text: `${error.time} · ${error.userId ?? this.userLabel()}@${error.workspaceId || this.workspaceLabel()} · session ${error.sessionId ?? 'current'}` }),
        el('div', { class: 'terminal-error-message', text: error.message }),
        ...(detailText ? [el('pre', { class: 'terminal-error-details', text: detailText })] : []),
      ));
    }
  }

  private userLabel(): string { return this.ctx.state.user ?? 'user'; }
  private workspaceLabel(): string { return this.ctx.state.workspace?.name ?? 'workspace'; }
  private renderIdentity(): void {
    clear(this.identity);
    this.identity.append(el('span', { text: `Signed in as: ${this.userLabel()}` }), el('span', { text: `Workspace: ${this.workspaceLabel()}` }));
  }
  private renderOutput(): void { this.output.textContent = this.active ? (this.buffers.get(this.active) ?? '') : ''; this.output.scrollTop = this.output.scrollHeight; }
  private renderTabs(): void {
    clear(this.tabs);
    if (this.terminals.length === 0) { this.tabs.append(el('span', { class: 'view-empty', text: 'No terminals' })); return; }
    for (const terminal of this.terminals) {
      const tab = el('button', { class: `terminal-tab${terminal.id === this.active ? ' active' : ''}`, text: terminal.alive ? terminal.title : `${terminal.title} (exited)` });
      tab.addEventListener('click', () => void this.select(terminal.id)); this.tabs.append(tab);
    }
  }
}
