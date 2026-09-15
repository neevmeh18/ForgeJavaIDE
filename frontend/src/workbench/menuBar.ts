import type { WorkbenchContext } from '../forge/context';
import type { Keybindings } from '../forge/keybindings';
import type { MenuItem } from '../forge/protocol';
import { clear, el } from './dom';

/**
 * The menu bar.
 *
 * <p>Entirely generated from contributed menu items: the workbench ships no menu structure of
 * its own, so what appears here is exactly what the backend's contribution registry holds,
 * extensions included. Each entry executes a command id, which is why a menu item, its
 * keybinding and its palette entry can never diverge.
 */
const MENUS: Array<{ id: string; title: string }> = [
  { id: 'menu.file', title: 'File' },
  { id: 'menu.edit', title: 'Edit' },
  { id: 'menu.view', title: 'View' },
];

export class MenuBar {
  readonly element = el('div', { class: 'menu-bar' });

  private items: MenuItem[] = [];
  private open: HTMLElement | null = null;

  constructor(
    private readonly ctx: WorkbenchContext,
    private readonly keybindings: Keybindings,
  ) {
    document.addEventListener('mousedown', (event) => {
      if (this.open && !this.element.contains(event.target as Node)) {
        this.close();
      }
    });
  }

  setItems(items: MenuItem[]): void {
    this.items = items;
    this.render();
  }

  private render(): void {
    clear(this.element);
    this.element.append(el('span', { class: 'brand', text: 'Forge' }));
    for (const menu of MENUS) {
      const entries = this.items.filter((item) => item.menu === menu.id);
      if (entries.length === 0) {
        continue;
      }
      const button = el('button', { class: 'menu-title', text: menu.title });
      const dropdown = el('div', { class: 'menu-dropdown', hidden: 'true' });
      for (const entry of entries) {
        const hint = this.keybindings.labelFor(entry.command);
        const item = el(
          'button',
          { class: 'menu-item', title: entry.command },
          el('span', { text: entry.title }),
          hint ? el('kbd', { class: 'menu-hint', text: hint }) : null,
        );
        item.addEventListener('click', () => {
          this.close();
          void this.ctx.commands.execute(entry.command);
        });
        dropdown.append(item);
      }
      button.addEventListener('click', () => {
        const wasOpen = this.open === dropdown;
        this.close();
        if (!wasOpen) {
          dropdown.hidden = false;
          this.open = dropdown;
        }
      });
      this.element.append(el('div', { class: 'menu' }, button, dropdown));
    }
  }

  private close(): void {
    if (this.open) {
      this.open.hidden = true;
      this.open = null;
    }
  }
}
