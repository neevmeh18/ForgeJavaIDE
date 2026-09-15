import type { ForgeClient } from '../forge/client';
import { ForgeRequestError } from '../forge/client';
import type { LoginResult, WorkbenchStatus } from '../forge/protocol';
import { el } from './dom';

/**
 * The sign-in screen.
 *
 * <p>It runs one command, `auth.login`, and keeps the returned token in memory only. Deliberately
 * not in `localStorage`: a token in storage survives the tab, is readable by any script that
 * manages to run on the page, and cannot be revoked by closing it.
 */
export function showLogin(client: ForgeClient, root: HTMLElement): Promise<LoginResult> {
  return new Promise((resolve) => {
    const username = el('input', {
      class: 'field',
      type: 'text',
      name: 'username',
      autocomplete: 'username',
      required: 'true',
      placeholder: 'Username',
    });
    const password = el('input', {
      class: 'field',
      type: 'password',
      name: 'password',
      autocomplete: 'current-password',
      required: 'true',
      placeholder: 'Password',
    });
    const message = el('p', { class: 'login-error', role: 'alert' });
    const submit = el('button', { class: 'primary', type: 'submit', text: 'Sign in' });
    const version = el('p', { class: 'login-version' });

    const form = el(
      'form',
      { class: 'login-form' },
      el('h1', { class: 'login-title', text: 'Forge' }),
      el('p', { class: 'login-subtitle', text: 'A Java-first IDE framework' }),
      username,
      password,
      message,
      submit,
      version,
    );
    const screen = el('div', { class: 'login-screen' }, form);
    root.append(screen);
    username.focus();

    void client
      .query<WorkbenchStatus>('workbench.status')
      .then((status) => {
        version.textContent = `${status.product} ${status.version}`;
      })
      .catch(() => {
        version.textContent = '';
      });

    form.addEventListener('submit', (event) => {
      event.preventDefault();
      message.textContent = '';
      submit.disabled = true;
      client
        .command<LoginResult>('auth.login', {
          username: username.value,
          password: password.value,
          client: navigator.userAgent,
        })
        .then((result) => {
          password.value = '';
          screen.remove();
          resolve(result);
        })
        .catch((error: unknown) => {
          submit.disabled = false;
          message.textContent =
            error instanceof ForgeRequestError ? error.message : 'Could not reach the server';
          password.select();
        });
    });
  });
}
