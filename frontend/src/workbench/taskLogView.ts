import type { WorkbenchContext } from '../forge/context';
import { describeError as describe } from '../forge/client';
import { clear, el } from './dom';

interface TaskLogSummary {
  executionId: string;
  taskId: string;
  name: string;
  state: string;
  recordCount: number;
  startedAt: string;
}

export class TaskLogView {
  readonly element = el('div', { class: 'view task-log-view' });
  private readonly runs = el('div', { class: 'task-log-runs' });
  private readonly records = el('pre', { class: 'task-log-records' });
  private selected: string | null = null;

  constructor(private readonly ctx: WorkbenchContext) {
    this.element.append(this.runs, this.records);
    ctx.on('task.started', () => void this.refresh());
    ctx.on('task.finished', () => void this.refresh());
  }

  async refresh(): Promise<void> {
    clear(this.runs);
    this.records.textContent = '';
    if (!this.ctx.client.currentWorkspace) {
      this.runs.append(el('p', { class: 'view-empty', text: 'No workspace open' }));
      return;
    }

    let items: TaskLogSummary[];
    try {
      items = await this.ctx.client.query<TaskLogSummary[]>('taskLog.executions');
    } catch (error) {
      this.runs.append(el('p', { class: 'view-empty', text: describe(error) }));
      return;
    }

    if (items.length === 0) {
      this.runs.append(el('p', { class: 'view-empty', text: 'Run a task to create a structured log.' }));
      return;
    }

    if (!this.selected || !items.some((item) => item.executionId === this.selected)) {
      this.selected = items[0].executionId;
    }

    for (const item of items) {
      const row = el(
        'button',
        { class: `task-log-run${item.executionId === this.selected ? ' active' : ''}` },
        el('span', { class: 'task-log-run-name', text: item.name }),
        el('span', { class: 'task-log-run-meta', text: `${item.state} · ${item.recordCount} records` }),
      );
      row.addEventListener('click', () => {
        this.selected = item.executionId;
        void this.refresh();
      });
      this.runs.append(row);
    }

    if (this.selected) await this.loadRecords(this.selected);
  }

  resetWorkspace(): void {
    this.selected = null;
    clear(this.runs);
    this.records.textContent = '';
  }

  private async loadRecords(executionId: string): Promise<void> {
    try {
      const records = await this.ctx.client.query<Array<Record<string, unknown>>>('taskLog.records', { executionId });
      this.records.textContent = records.map((record) => JSON.stringify(record, null, 2)).join('\n\n');
    } catch (error) {
      this.records.textContent = describe(error);
    }
  }
}
