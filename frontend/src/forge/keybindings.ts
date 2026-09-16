import type { CommandRouter } from './commands';
import type { Keybinding } from './protocol';

/** Keystrokes to command ids, including the backend contribution's focus condition. */
const MAC = /Mac|iPhone|iPad/i.test(navigator.platform || navigator.userAgent);

interface BoundCommand {
  command: string;
  when?: string | null;
}

export class Keybindings {
  private bindings = new Map<string, BoundCommand>();

  constructor(private readonly commands: CommandRouter) {}

  setBindings(contributed: Keybinding[]): void {
    this.bindings.clear();
    for (const binding of contributed) {
      this.bindings.set(normalize(binding.key), { command: binding.command, when: binding.when });
    }
  }

  bind(key: string, commandId: string, when?: string): void {
    this.bindings.set(normalize(key), { command: commandId, when });
  }

  attach(target: HTMLElement | Document): () => void {
    const handler = (event: KeyboardEvent) => this.dispatch(event);
    target.addEventListener('keydown', handler as EventListener, true);
    return () => target.removeEventListener('keydown', handler as EventListener, true);
  }

  private dispatch(event: KeyboardEvent): void {
    const stroke = describe(event);
    const binding = this.bindings.get(stroke);
    if (!binding || !matchesWhen(binding.when, event)) {
      return;
    }
    event.preventDefault();
    event.stopPropagation();
    void this.commands.execute(binding.command).catch((error: unknown) => {
      console.warn(`Keybinding ${stroke} -> ${binding.command} failed`, error);
    });
  }

  static label(key: string): string {
    return key
      .split('+')
      .map((part) => {
        if (part === 'ctrl') return MAC ? '⌘' : 'Ctrl';
        if (part === 'shift') return MAC ? '⇧' : 'Shift';
        if (part === 'alt') return MAC ? '⌥' : 'Alt';
        return part.length === 1 ? part.toUpperCase() : part;
      })
      .join(MAC ? '' : '+');
  }

  labelFor(commandId: string): string | null {
    for (const [stroke, binding] of this.bindings) {
      if (binding.command === commandId) {
        return Keybindings.label(stroke);
      }
    }
    return null;
  }
}

function matchesWhen(when: string | null | undefined, event: KeyboardEvent): boolean {
  if (!when || when === 'true') {
    return true;
  }
  if (when === 'editorFocus') {
    const target = event.target instanceof Element ? event.target : document.activeElement;
    return Boolean(target?.closest('.editor-host, .monaco-editor'));
  }
  // Unknown context expressions fail closed instead of accidentally broadening a shortcut.
  return false;
}

function normalize(key: string): string {
  return key.toLowerCase().split('+').map((part) => part.trim()).sort(order).join('+');
}

function describe(event: KeyboardEvent): string {
  const parts: string[] = [];
  if (MAC ? event.metaKey : event.ctrlKey) parts.push('ctrl');
  if (event.shiftKey) parts.push('shift');
  if (event.altKey) parts.push('alt');
  parts.push(event.key.length === 1 ? event.key.toLowerCase() : event.key.toLowerCase());
  return parts.sort(order).join('+');
}

function order(a: string, b: string): number {
  const rank = (part: string) => ['ctrl', 'shift', 'alt'].indexOf(part);
  return rank(b) - rank(a) || a.localeCompare(b);
}
