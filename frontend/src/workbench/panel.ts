import type { WorkbenchContext } from '../forge/context';
import type { TaskInfo, ViewContribution } from '../forge/protocol';
import { clear, el } from './dom';
import { describeError as describe } from '../forge/client';
import { TerminalView } from './terminalView';

/**
 * The bottom panel: terminal, problems and tasks.
 *
 * <p>Its tabs come from the contributed views whose container is `panel`, the same mechanism the
 * sidebar uses, so an extension can add a panel without the workbench knowing about it.
 *
 * <p>Problems are collected from `language.diagnostics` events. Tasks are listed by a query and
 * started by the `task.run` command — the panel never runs anything itself.
 */
export class Panel {
  readonly element = el('section', { class: 'panel', hidden: 'true' });

  readonly terminal: TerminalView;
  private readonly tabs = el('div', { class: 'panel-tabs', role: 'tablist' });
  private readonly body = el('div', { class: 'panel-body' });
  private readonly problems = el('div', { class: 'view problems-view' });
  private readonly tasks = el('div', { class: 'view tasks-view' });
  private readonly diagnostics = new Map<string, DiagnosticEntry[]>();
  private active = 'terminal';
  private views: ViewContribution[] = [];

  constructor(private readonly ctx: WorkbenchContext) {
    this.terminal = new TerminalView(ctx);
    const close = el('button', { class: 'view-action', title: 'Close panel', text: '✕' });
    close.addEventListener('click', () => void ctx.commands.execute('workbench.togglePanel'));
    this.element.append(el('div', { class: 'panel-header' }, this.tabs, close), this.body);

    ctx.on('language.diagnostics', (event) => {
      const payload = event.payload as { path: string; diagnostics: DiagnosticEntry[] };
      if (payload.diagnostics.length === 0) {
        this.diagnostics.delete(payload.path);
      } else {
        this.diagnostics.set(payload.path, payload.diagnostics);
      }
      this.renderProblems();
    });
    ctx.on('task.started', () => void this.renderTasks());
    ctx.on('task.finished', (event) => {
      const payload = event.payload as { taskId: string; state: string };
      ctx.notify(payload.state === 'SUCCEEDED' ? 'info' : 'warning', `Task ${payload.taskId}: ${payload.state}`);
      void this.renderTasks();
    });
  }

  setViews(views: ViewContribution[]): void {
    this.views = views.filter((view) => view.container === 'panel');
    this.renderTabs();
  }

  toggle(): void {
    this.element.hidden = !this.element.hidden;
    if (!this.element.hidden) {
      this.show(this.active);
    }
  }

  show(viewId: string): void {
    this.active = viewId;
    this.element.hidden = false;
    clear(this.body);
    if (viewId === 'terminal') {
      this.body.append(this.terminal.element);
      void this.terminal.refresh();
    } else if (viewId === 'tasks') {
      this.body.append(this.tasks);
      void this.renderTasks();
    } else {
      this.body.append(this.problems);
      this.renderProblems();
    }
    this.renderTabs();
  }

  problemCount(): number {
    return [...this.diagnostics.values()].reduce((total, list) => total + list.length, 0);
  }

  private renderTabs(): void {
    clear(this.tabs);
    for (const view of this.views) {
      const tab = el('button', {
        class: `panel-tab${view.id === this.active ? ' active' : ''}`,
        text: view.id === 'problems' ? `${view.title} (${this.problemCount()})` : view.title,
      });
      tab.addEventListener('click', () => this.show(view.id));
      this.tabs.append(tab);
    }
  }

  private renderProblems(): void {
    clear(this.problems);
    if (this.diagnostics.size === 0) {
      this.problems.append(el('p', { class: 'view-empty', text: 'No problems reported' }));
    }
    for (const [path, entries] of this.diagnostics) {
      for (const entry of entries) {
        const row = el(
          'button',
          { class: `problem-row severity-${entry.severity.toLowerCase()}` },
          el('span', { class: 'problem-message', text: entry.message }),
          el('span', { class: 'problem-location', text: `${path}:${entry.range.start.line + 1}` }),
        );
        row.addEventListener('click', () => void this.ctx.openFile(path, entry.range.start.line + 1));
        this.problems.append(row);
      }
    }
    this.renderTabs();
  }

  private async renderTasks(): Promise<void> {
    clear(this.tasks);
    if (!this.ctx.client.currentWorkspace) {
      this.tasks.append(el('p', { class: 'view-empty', text: 'No workspace open' }));
      return;
    }
    let available: TaskInfo[] = [];
    try {
      available = await this.ctx.client.query<TaskInfo[]>('task.available');
    } catch (error) {
      this.tasks.append(el('p', { class: 'view-empty', text: describe(error) }));
      return;
    }
    if (available.length === 0) {
      this.tasks.append(
        el('p', {
          class: 'view-empty',
          text: 'No tasks. Define them in .forge/tasks.json in the workspace.',
        }),
      );
      return;
    }
    for (const task of available) {
      const row = el(
        'button',
        { class: 'task-row' },
        el('span', { class: 'task-name', text: task.name }),
        el('span', { class: 'task-command', text: `${task.executable} ${task.arguments.join(' ')}` }),
      );
      row.addEventListener('click', () => {
        void this.ctx.commands
          .execute('task.run', { taskId: task.id })
          .then(() => this.show('terminal'))
          .catch((error: unknown) => this.ctx.notify('error', describe(error)));
      });
      this.tasks.append(row);
    }
  }
}

interface DiagnosticEntry {
  range: { start: { line: number; character: number }; end: { line: number; character: number } };
  severity: string;
  message: string;
}
