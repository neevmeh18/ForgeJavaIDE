import type { NotificationKind } from '../forge/context';
import { clear, el } from './dom';

/**
 * Transient messages.
 *
 * <p>Errors reaching here are already structured: the backend sends a code and a safe message,
 * never a stack trace, so the workbench can show exactly what it was given.
 */
export class Notifications {
  readonly element = el('div', { class: 'notifications', role: 'status', 'aria-live': 'polite' });

  show(kind: NotificationKind, message: string): void {
    const toast = el('div', { class: `toast toast-${kind}` }, el('span', { text: message }));
    const dismiss = el('button', { class: 'toast-close', 'aria-label': 'Dismiss', text: '✕' });
    dismiss.addEventListener('click', () => toast.remove());
    toast.append(dismiss);
    this.element.append(toast);
    setTimeout(() => toast.remove(), kind === 'error' ? 12000 : 5000);
  }

  clearAll(): void {
    clear(this.element);
  }
}
