import type { WorkbenchContext } from '../forge/context';
import type { TextSearchResult } from '../forge/protocol';
import { clear, el } from './dom';
import { describeError as describe } from '../forge/client';








export class SearchView {
  readonly element = el('div', { class: 'view search-view' });

  private readonly query = el('input', {
    class: 'field',
    type: 'search',
    placeholder: 'Search in files',
    spellcheck: 'false',
  });
  private readonly regex = el('input', { type: 'checkbox', id: 'search-regex' });
  private readonly caseSensitive = el('input', { type: 'checkbox', id: 'search-case' });
  private readonly results = el('div', { class: 'search-results' });
  private readonly summary = el('p', { class: 'search-summary' });
  private readonly savedId = el('input', { class: 'field', type: 'text', placeholder: 'Saved search ID', spellcheck: 'false' });
  private readonly savedStatus = el('p', { class: 'search-summary' });
  private running: string | null = null;
  private generation = 0;

  constructor(private readonly ctx: WorkbenchContext) {
    const options = el(
      'div',
      { class: 'search-options' },
      el('label', { for: 'search-regex' }, this.regex, ' Regex'),
      el('label', { for: 'search-case' }, this.caseSensitive, ' Match case'),
    );
    const saveButton = el('button', { class: 'button', type: 'button', text: 'Save search' });
    const loadButton = el('button', { class: 'button', type: 'button', text: 'Load & search' });
    saveButton.addEventListener('click', () => void this.saveSearch());
    loadButton.addEventListener('click', () => void this.runSavedSearch());

    this.element.append(
      el('div', { class: 'view-header' }, el('h2', { text: 'Search' })),
      el('div', { class: 'search-form' },
        this.query, options,
        el('div', { class: 'search-actions' },
          saveButton,
        ),
        this.summary,
        el('div', { class: 'saved-search-form' },
          this.savedId,
          loadButton,
        ),
        this.savedStatus,
      ),
      this.results,
    );

    this.query.addEventListener('keydown', (event) => {
      if (event.key === 'Enter') {
        event.preventDefault();
        void this.run();
      }
      if (event.key === 'Escape' && this.running) {
        void this.ctx.commands.execute('command.cancel', { executionId: this.running });
      }
    });

  }

  resetWorkspace(): void {
    this.generation++;
    const previous = this.running;
    this.running = null;
    if (previous) void this.ctx.client.command('command.cancel', { executionId: previous }).catch(() => undefined);
    clear(this.results);
    this.query.value = '';
    this.summary.textContent = '';
    this.savedId.value = '';
    this.savedStatus.textContent = '';
  }

  focus(): void {
    this.query.focus();
    this.query.select();
  }

  private async run(): Promise<void> {
    const generation = ++this.generation;
    const workspace = this.ctx.client.workspaceGeneration;
    const text = this.query.value;
    const previous = this.running;
    this.running = null;
    if (previous) await this.ctx.client.command('command.cancel', { executionId: previous }).catch(() => undefined);
    if (generation !== this.generation || workspace !== this.ctx.client.workspaceGeneration) return;
    clear(this.results);
    this.summary.textContent = text ? 'Searching…' : '';
    if (!text) return;
    try {
      const executionId = await this.ctx.client.commandAsync('search.text', {
        query: text, regex: this.regex.checked, caseSensitive: this.caseSensitive.checked, limit: 500,
      });
      if (generation !== this.generation || workspace !== this.ctx.client.workspaceGeneration) {
        await this.ctx.client.command('command.cancel', { executionId }).catch(() => undefined);
        return;
      }
      this.running = executionId;
      const result = await this.ctx.client.waitForCommand<TextSearchResult>(executionId, workspace);
      if (generation === this.generation) this.render(result);
    } catch (error) {
      if (generation === this.generation) this.summary.textContent = describe(error);
    } finally {
      if (generation === this.generation) this.running = null;
    }
  }

  private async saveSearch(): Promise<void> {
    const text = this.query.value.trim();
    if (!text) { this.savedStatus.textContent = 'Enter a search first'; return; }
    try {
      const result = await this.ctx.commands.execute<{ id: string }>('search.saved.save', {
        query: text, regex: this.regex.checked, caseSensitive: this.caseSensitive.checked,
      }) as { id: string };
      this.savedId.value = result.id;
      this.savedStatus.textContent = `Saved as ${result.id}`;
    } catch (error) {
      this.savedStatus.textContent = describe(error);
    }
  }

  private async runSavedSearch(): Promise<void> {
    const id = this.savedId.value.trim();
    if (!id) { this.savedStatus.textContent = 'Enter a saved search ID'; return; }
    const generation = ++this.generation;
    const workspace = this.ctx.client.workspaceGeneration;
    clear(this.results);
    this.summary.textContent = 'Searching…';
    try {
      const loaded = await this.ctx.commands.execute<{ query: string; regex: boolean; caseSensitive: boolean; result: TextSearchResult }>('search.saved.run', { id }) as { query: string; regex: boolean; caseSensitive: boolean; result: TextSearchResult };
      if (generation === this.generation && workspace === this.ctx.client.workspaceGeneration) {
        this.query.value = loaded.query;
        this.regex.checked = loaded.regex;
        this.caseSensitive.checked = loaded.caseSensitive;
        this.savedStatus.textContent = `Loaded ${id}`;
        this.render(loaded.result);
      }
    } catch (error) {
      if (generation === this.generation) this.summary.textContent = describe(error);
    }
  }

  private render(result: TextSearchResult): void {
    clear(this.results);
    if (!result || result.matches.length === 0) {
      this.summary.textContent = 'No matches';
      return;
    }
    this.summary.textContent = `${result.matches.length} match${result.matches.length === 1 ? '' : 'es'}`
      + ` in ${result.filesScanned} files${result.truncated ? ' (truncated)' : ''}`;

    let currentPath = '';
    for (const match of result.matches) {
      if (match.path !== currentPath) {
        currentPath = match.path;
        this.results.append(el('div', { class: 'search-file', text: match.path }));
      }
      const row = el(
        'button',
        { class: 'search-match' },
        el('span', { class: 'search-line', text: String(match.line) }),
        el('span', { class: 'search-preview', text: match.preview }),
      );
      const path = match.path;
      const line = match.line;
      row.addEventListener('click', () => void this.ctx.openFile(path, line));
      this.results.append(row);
    }
  }
}
