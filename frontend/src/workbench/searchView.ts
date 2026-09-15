import type { WorkbenchContext } from '../forge/context';
import type { TextSearchResult } from '../forge/protocol';
import { clear, el } from './dom';
import { describeError as describe } from '../forge/client';

/**
 * Workspace text search.
 *
 * <p>Runs `search.text`, which is a command rather than a query precisely because it can take a
 * while and must be cancellable. Nothing is scanned in the browser: the workspace may be remote,
 * and the backend already has the filesystem capability that knows how to reach it.
 */
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
  private running: string | null = null;

  constructor(private readonly ctx: WorkbenchContext) {
    const options = el(
      'div',
      { class: 'search-options' },
      el('label', { for: 'search-regex' }, this.regex, ' Regex'),
      el('label', { for: 'search-case' }, this.caseSensitive, ' Match case'),
    );
    this.element.append(
      el('div', { class: 'view-header' }, el('h2', { text: 'Search' })),
      el('div', { class: 'search-form' }, this.query, options, this.summary),
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

    // A long search reports its result as an event; this is the async command path in use.
    ctx.on('command.completed', (event) => {
      const payload = event.payload as { executionId: string; commandId: string; result: unknown };
      if (payload.executionId === this.running && payload.commandId === 'search.text') {
        this.running = null;
        this.render(payload.result as TextSearchResult);
      }
    });
    ctx.on('command.failed', (event) => {
      const payload = event.payload as { executionId: string; message: string };
      if (payload.executionId === this.running) {
        this.running = null;
        this.summary.textContent = payload.message;
      }
    });
  }

  focus(): void {
    this.query.focus();
    this.query.select();
  }

  private async run(): Promise<void> {
    const text = this.query.value;
    if (!text) {
      clear(this.results);
      this.summary.textContent = '';
      return;
    }
    this.summary.textContent = 'Searching…';
    clear(this.results);
    try {
      // Started asynchronously so a slow search never blocks the request, and so pressing
      // Escape can cancel it through command.cancel.
      this.running = await this.ctx.client.commandAsync('search.text', {
        query: text,
        regex: this.regex.checked,
        caseSensitive: this.caseSensitive.checked,
        limit: 500,
      });
    } catch (error) {
      this.summary.textContent = describe(error);
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
