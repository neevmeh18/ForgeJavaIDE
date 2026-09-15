import type { CommandRouter } from './commands';
import type { Keybinding } from './protocol';

/**
 * Keystrokes to command ids.
 *
 * <p>The table comes from the backend's contribution registry, so an extension's shortcut works
 * without the frontend being rebuilt, and a shortcut and its menu item can never drift apart:
 * both name the same command id.
 *
 * <p>`ctrl` in a contributed binding means "the platform's primary modifier" — Command on macOS,
 * Control elsewhere. That mapping is presentation, which is why it lives here and not in Java.
 */
const MAC = /Mac|iPhone|iPad/i.test(navigator.platform || navigator.userAgent);

export class Keybindings {
  private bindings = new Map<string, string>();

  constructor(private readonly commands: CommandRouter) {}

  setBindings(contributed: Keybinding[]): void {
    this.bindings.clear();
    for (const binding of contributed) {
      this.bindings.set(normalize(binding.key), binding.command);
    }
  }

  /** Adds a workbench-local shortcut, such as opening the command palette. */
  bind(key: string, commandId: string): void {
    this.bindings.set(normalize(key), commandId);
  }

  attach(target: HTMLElement | Document): () => void {
    const handler = (event: KeyboardEvent) => this.dispatch(event);
    target.addEventListener('keydown', handler as EventListener, true);
    return () => target.removeEventListener('keydown', handler as EventListener, true);
  }

  private dispatch(event: KeyboardEvent): void {
    const stroke = describe(event);
    const commandId = this.bindings.get(stroke);
    if (!commandId) {
      return;
    }
    event.preventDefault();
    event.stopPropagation();
    void this.commands.execute(commandId).catch((error: unknown) => {
      console.warn(`Keybinding ${stroke} -> ${commandId} failed`, error);
    });
  }

  /** The printable form of a binding, for menus and the palette. */
  static label(key: string): string {
    return key
      .split('+')
      .map((part) => {
        if (part === 'ctrl') {
          return MAC ? '⌘' : 'Ctrl';
        }
        if (part === 'shift') {
          return MAC ? '⇧' : 'Shift';
        }
        if (part === 'alt') {
          return MAC ? '⌥' : 'Alt';
        }
        return part.length === 1 ? part.toUpperCase() : part;
      })
      .join(MAC ? '' : '+');
  }

  labelFor(commandId: string): string | null {
    for (const [stroke, id] of this.bindings) {
      if (id === commandId) {
        return Keybindings.label(stroke);
      }
    }
    return null;
  }
}

function normalize(key: string): string {
  return key.toLowerCase().split('+').map((part) => part.trim()).sort(order).join('+');
}

function describe(event: KeyboardEvent): string {
  const parts: string[] = [];
  if (MAC ? event.metaKey : event.ctrlKey) {
    parts.push('ctrl');
  }
  if (event.shiftKey) {
    parts.push('shift');
  }
  if (event.altKey) {
    parts.push('alt');
  }
  parts.push(event.key.length === 1 ? event.key.toLowerCase() : event.key.toLowerCase());
  return parts.sort(order).join('+');
}

/** Modifiers first, in a fixed order, so lookup does not depend on how a binding was written. */
function order(a: string, b: string): number {
  const rank = (part: string) => ['ctrl', 'shift', 'alt'].indexOf(part);
  return rank(b) - rank(a) || a.localeCompare(b);
}
