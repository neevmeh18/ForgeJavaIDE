import type { WorkbenchContext } from '../forge/context';
import type { TerminalInfo } from '../forge/protocol';
import { clear, el } from './dom';
import { describeError as describe } from '../forge/client';

/**
 * The terminal panel.
 *
 * <p>Line-oriented, matching the process-based terminal provider the framework ships: input is
 * sent a line at a time with `terminal.write`, output arrives as `terminal.output` events. A
 * product that registers a pseudo-terminal provider would pair it with a richer view here; the
 * commands and events between them do not change.
 */
export class TerminalView {
  readonly element = el('div', { class: 'view terminal-view' });

  private readonly tabs = el('div', { class: 'terminal-tabs' });
  private readonly output = el('pre', { class: 'terminal-output', tabindex: '0' });
  private readonly input = el('input', {
    class: 'terminal-input',
    type: 'text',
    spellcheck: 'false',
    placeholder: 'Type a command and press Enter',
  });

  private terminals: TerminalInfo[] = [];
  private active: string | null = null;
  private readonly buffers = new Map<string, string>();
  private readonly history: string[] = [];
  private historyIndex = 0;

  constructor(private readonly ctx: WorkbenchContext) {
    const actions = el('div', { class: 'view-actions' });
    const create = el('button', { class: 'view-action', title: 'New terminal', text: '＋' });
    create.addEventListener('click', () => void this.create());
    const kill = el('button', { class: 'view-action', title: 'Kill terminal', text: '🗑' });
    kill.addEventListener('click', () => void this.kill());
    actions.append(create, kill);

    this.element.append(
      el('div', { class: 'view-header' }, this.tabs, actions),
      this.output,
      this.input,
    );

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
  }

  async refresh(): Promise<void> {
    if (!this.ctx.client.currentWorkspace) {
      this.terminals = [];
      this.renderTabs();
      return;
    }
    try {
      this.terminals = await this.ctx.client.query<TerminalInfo[]>('terminal.list');
    } catch {
      this.terminals = [];
    }
    if (this.active && !this.terminals.some((terminal) => terminal.id === this.active)) {
      this.active = null;
    }
    if (!this.active && this.terminals.length > 0) {
      await this.select(this.terminals[0].id);
    }
    this.renderTabs();
  }

  async create(): Promise<void> {
    try {
      const terminal = (await this.ctx.commands.execute<TerminalInfo>('terminal.create', {
        columns: 120,
        rows: 30,
      })) as TerminalInfo;
      await this.refresh();
      await this.select(terminal.id);
      this.input.focus();
    } catch (error) {
      this.ctx.notify('error', describe(error));
    }
  }

  private async kill(): Promise<void> {
    if (!this.active) {
      return;
    }
    try {
      await this.ctx.commands.execute('terminal.kill', { terminalId: this.active });
    } catch (error) {
      this.ctx.notify('error', describe(error));
    }
  }

  private async select(terminalId: string): Promise<void> {
    this.active = terminalId;
    if (!this.buffers.has(terminalId)) {
      try {
        this.buffers.set(terminalId, await this.ctx.client.query<string>('terminal.scrollback', { terminalId }));
      } catch {
        this.buffers.set(terminalId, '');
      }
    }
    this.renderOutput();
    this.renderTabs();
  }

  private onKey(event: KeyboardEvent): void {
    if (event.key === 'Enter') {
      event.preventDefault();
      const line = this.input.value;
      this.input.value = '';
      if (line.trim()) {
        this.history.push(line);
      }
      this.historyIndex = this.history.length;
      void this.send(`${line}\n`);
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      this.historyIndex = Math.max(0, this.historyIndex - 1);
      this.input.value = this.history[this.historyIndex] ?? '';
    } else if (event.key === 'ArrowDown') {
      event.preventDefault();
      this.historyIndex = Math.min(this.history.length, this.historyIndex + 1);
      this.input.value = this.history[this.historyIndex] ?? '';
    }
  }

  private async send(data: string): Promise<void> {
    if (!this.active) {
      await this.create();
    }
    if (!this.active) {
      return;
    }
    try {
      await this.ctx.commands.execute('terminal.write', { terminalId: this.active, data });
    } catch (error) {
      this.ctx.notify('error', describe(error));
    }
  }

  private appendOutput(terminalId: string, data: string): void {
    const existing = this.buffers.get(terminalId) ?? '';
    const combined = existing + data;
    this.buffers.set(terminalId, combined.length > 120_000 ? combined.slice(-120_000) : combined);
    if (terminalId === this.active) {
      this.renderOutput();
    }
  }

  private renderOutput(): void {
    this.output.textContent = this.active ? (this.buffers.get(this.active) ?? '') : '';
    this.output.scrollTop = this.output.scrollHeight;
  }

  private renderTabs(): void {
    clear(this.tabs);
    if (this.terminals.length === 0) {
      this.tabs.append(el('span', { class: 'view-empty', text: 'No terminals' }));
      return;
    }
    for (const terminal of this.terminals) {
      const tab = el('button', {
        class: `terminal-tab${terminal.id === this.active ? ' active' : ''}`,
        text: terminal.alive ? terminal.title : `${terminal.title} (exited)`,
      });
      tab.addEventListener('click', () => void this.select(terminal.id));
      this.tabs.append(tab);
    }
  }
}
