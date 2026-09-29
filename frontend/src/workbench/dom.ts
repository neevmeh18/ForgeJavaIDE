/**
 * Small DOM helpers.
 *
 * <p>The workbench renders with plain DOM rather than a UI framework: the tree is shallow, the
 * updates are event-driven, and a framework would be a large dependency for the framework's own
 * shell — which products are expected to replace anyway. These few functions are all the
 * ergonomics that needs.
 */

type Attributes = Record<string, string | number | boolean | undefined>;
type Child = Node | string | null | undefined | false;

export function el<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  attributes: Attributes = {},
  ...children: Child[]
): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  for (const [name, value] of Object.entries(attributes)) {
    if (value === undefined || value === false) {
      continue;
    }
    if (name === 'class') {
      node.className = String(value);
    } else if (name === 'text') {
      node.textContent = String(value);
    } else {
      node.setAttribute(name, String(value));
    }
  }
  append(node, children);
  return node;
}

export function append(parent: Element, children: Child[]): void {
  for (const child of children) {
    if (child === null || child === undefined || child === false) {
      continue;
    }
    parent.append(typeof child === 'string' ? document.createTextNode(child) : child);
  }
}

export function clear(node: Element): void {
  while (node.firstChild) {
    node.removeChild(node.firstChild);
  }
}

/** A named glyph. Kept as text so the workbench ships no icon font or sprite sheet. */
export function icon(name: string): HTMLElement {
  const glyphs: Record<string, string> = {
    files: '🗀',
    search: '⌕',
    'git-branch': '⑂',
    bug: '🐞',
    extensions: '⧉',
    terminal: '▸',
    warning: '⚠',
    checklist: '☑',
    settings: '⚙',
    archive: '▣',
    close: '✕',
    chevron: '›',
    dirty: '●',
  };
  return el('span', { class: 'icon', text: glyphs[name] ?? '•', 'aria-hidden': 'true' });
}

export function fileIcon(directory: boolean): HTMLElement {
  return el('span', { class: 'icon', text: directory ? '🗀' : '🗎', 'aria-hidden': 'true' });
}
