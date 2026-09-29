import type { Attributes } from "./dom/Attributes";
import type { Child } from "./dom/Child";












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
    close: '✕',
    chevron: '›',
    dirty: '●',
  };
  return el('span', { class: 'icon', text: glyphs[name] ?? '•', 'aria-hidden': 'true' });
}

export function fileIcon(directory: boolean): HTMLElement {
  return el('span', { class: 'icon', text: directory ? '🗀' : '🗎', 'aria-hidden': 'true' });
}

export type { Attributes } from "./dom/Attributes";
export type { Child } from "./dom/Child";
