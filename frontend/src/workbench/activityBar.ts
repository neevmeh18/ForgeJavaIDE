import type { WorkbenchContext } from '../forge/context';
import type { ViewContribution } from '../forge/protocol';
import { clear, el, icon } from './dom';

/**
 * The narrow strip of view switchers on the left.
 *
 * <p>Its contents come from the backend's contribution registry, so a view an extension adds
 * appears here with no frontend change. Each button executes `workbench.showView`, keeping even
 * this piece of chrome on the command path.
 */
export class ActivityBar {
  readonly element = el('nav', { class: 'activity-bar', 'aria-label': 'Views' });
  private buttons = new Map<string, HTMLButtonElement>();

  constructor(private readonly ctx: WorkbenchContext) {}

  setViews(views: ViewContribution[]): void {
    clear(this.element);
    this.buttons.clear();
    for (const view of views.filter((candidate) => candidate.container === 'sidebar')) {
      const button = el('button', {
        class: 'activity-item',
        title: view.title,
        'aria-label': view.title,
      });
      button.append(icon(view.icon));
      button.addEventListener('click', () =>
        void this.ctx.commands.execute('workbench.showView', { viewId: view.id }),
      );
      this.buttons.set(view.id, button);
      this.element.append(button);
    }
    const settings = el('button', { class: 'activity-item activity-bottom', title: 'Settings' });
    settings.append(icon('settings'));
    settings.addEventListener('click', () =>
      void this.ctx.commands.execute('workbench.showView', { viewId: 'settings' }),
    );
    this.buttons.set('settings', settings);
    this.element.append(settings);
  }

  setActive(viewId: string): void {
    this.buttons.forEach((button, id) => button.classList.toggle('active', id === viewId));
  }
}
