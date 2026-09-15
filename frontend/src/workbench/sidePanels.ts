import type { WorkbenchContext } from '../forge/context';
import type { ExtensionStatus, ResolvedSetting } from '../forge/protocol';
import { clear, el } from './dom';
import { describeError as describe } from '../forge/client';

/**
 * Two small read-and-edit views that share a shape: list what the backend declares, let the
 * user act on one entry. Keeping them together is cheaper to read than two near-identical files.
 */

/** Shows installed extensions, their lifecycle state, and why a failed one failed. */
export class ExtensionsView {
  readonly element = el('div', { class: 'view extensions-view' });

  private readonly list = el('div', { class: 'extension-list' });

  constructor(private readonly ctx: WorkbenchContext) {
    this.element.append(
      el('div', { class: 'view-header' }, el('h2', { text: 'Extensions' })),
      this.list,
    );
    ctx.on('extension.activated', () => void this.refresh());
    ctx.on('extension.failed', (event) => {
      const payload = event.payload as { extensionId: string; reason: string };
      ctx.notify('warning', `Extension ${payload.extensionId} failed: ${payload.reason}`);
      void this.refresh();
    });
  }

  async refresh(): Promise<void> {
    clear(this.list);
    let extensions: ExtensionStatus[];
    try {
      extensions = await this.ctx.client.query<ExtensionStatus[]>('workbench.extensions');
    } catch (error) {
      this.list.append(el('p', { class: 'view-empty', text: describe(error) }));
      return;
    }
    if (extensions.length === 0) {
      this.list.append(
        el('p', {
          class: 'view-empty',
          text: 'No extensions installed. Drop a jar into the extensions directory and restart.',
        }),
      );
      return;
    }
    for (const extension of extensions) {
      const row = el(
        'div',
        { class: 'extension-row' },
        el('div', { class: 'extension-name', text: extension.name }),
        el('div', { class: 'extension-meta', text: `${extension.id} · ${extension.version}` }),
        el('div', { class: `extension-state state-${extension.state.toLowerCase()}`, text: extension.state }),
        extension.failure ? el('div', { class: 'extension-failure', text: extension.failure }) : null,
      );
      if (extension.state === 'DISCOVERED' || extension.state === 'DEACTIVATED') {
        const activate = el('button', { class: 'view-action', text: 'Activate' });
        activate.addEventListener('click', () => {
          void this.ctx.commands
            .execute('extension.activate', { extensionId: extension.id })
            .then(() => this.refresh())
            .catch((error: unknown) => this.ctx.notify('error', describe(error)));
        });
        row.append(activate);
      }
      this.list.append(row);
    }
  }
}

/**
 * The settings editor.
 *
 * <p>Renders whatever `settings.definitions` declares — including settings contributed by an
 * extension — and writes through `settings.set`. Types, allowed values and which layer a value
 * came from are the backend's answers, so the UI cannot invent a setting or accept an invalid one.
 */
export class SettingsView {
  readonly element = el('div', { class: 'view settings-view' });

  private readonly list = el('div', { class: 'settings-list' });
  private readonly scope = el('select', { class: 'field settings-scope' });

  constructor(private readonly ctx: WorkbenchContext) {
    this.scope.append(
      el('option', { value: 'user', text: 'User' }),
      el('option', { value: 'workspace', text: 'Workspace' }),
    );
    this.element.append(
      el('div', { class: 'view-header' }, el('h2', { text: 'Settings' }), this.scope),
      this.list,
    );
    ctx.on('settings.changed', () => void this.refresh());
  }

  async refresh(): Promise<void> {
    clear(this.list);
    let settings: ResolvedSetting[];
    try {
      settings = await this.ctx.client.query<ResolvedSetting[]>('settings.resolved');
    } catch (error) {
      this.list.append(el('p', { class: 'view-empty', text: describe(error) }));
      return;
    }
    for (const setting of settings) {
      this.list.append(this.row(setting));
    }
  }

  private row(setting: ResolvedSetting): HTMLElement {
    const control = this.control(setting);
    return el(
      'div',
      { class: 'setting-row' },
      el('div', { class: 'setting-key', text: setting.key }),
      el('div', { class: 'setting-description', text: setting.definition.description }),
      el('div', { class: 'setting-control' }, control),
      el('div', { class: 'setting-layer', text: `from ${setting.layer.toLowerCase()}` }),
    );
  }

  private control(setting: ResolvedSetting): HTMLElement {
    const write = (value: unknown) => {
      void this.ctx.commands
        .execute('settings.set', { key: setting.key, value, layer: this.scope.value })
        .catch((error: unknown) => this.ctx.notify('error', describe(error)));
    };

    if (setting.definition.type === 'BOOLEAN') {
      const input = el('input', { type: 'checkbox' });
      input.checked = setting.value === true;
      input.addEventListener('change', () => write(input.checked));
      return input;
    }
    if (setting.definition.allowedValues.length > 0) {
      const select = el('select', { class: 'field' });
      for (const option of setting.definition.allowedValues) {
        select.append(el('option', { value: option, text: option }));
      }
      select.value = String(setting.value ?? '');
      select.addEventListener('change', () => write(select.value));
      return select;
    }
    const input = el('input', {
      class: 'field',
      type: setting.definition.type === 'NUMBER' ? 'number' : 'text',
    });
    input.value = String(setting.value ?? '');
    input.addEventListener('change', () =>
      write(setting.definition.type === 'NUMBER' ? Number(input.value) : input.value),
    );
    return input;
  }
}
