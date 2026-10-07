import type { ForgeClient } from '../forge/client';
import { ForgeRequestError } from '../forge/client';
import type { LoginResult, RegisterResult, WorkbenchStatus } from '../forge/protocol';
import { el } from './dom';

/**
 * The sign-in / create-account screen.
 *
 * <p>Toggles between "Sign In" and "Create Account" modes. In sign-in mode it runs the
 * {@code auth.login} command; in register mode it runs {@code auth.register}. Both return a
 * {@link LoginResult}-shaped value with a session token.
 *
 * <p>The token is kept in memory only, never localStorage.
 */
export function showLogin(client: ForgeClient, root: HTMLElement): Promise<LoginResult> {
  return new Promise((resolve) => {
    let registerMode = false;

    const username = el('input', {
      class: 'field',
      type: 'text',
      name: 'username',
      autocomplete: 'username',
      required: 'true',
      placeholder: 'Username',
    });
    const displayName = el('input', {
      class: 'field',
      type: 'text',
      name: 'displayName',
      autocomplete: 'name',
      placeholder: 'Display name (optional)',
      hidden: 'true',
    });
    const password = el('input', {
      class: 'field',
      type: 'password',
      name: 'password',
      autocomplete: 'new-password',
      required: 'true',
      placeholder: 'Password',
    });
    const confirmPassword = el('input', {
      class: 'field',
      type: 'password',
      name: 'confirmPassword',
      autocomplete: 'new-password',
      placeholder: 'Confirm password',
      hidden: 'true',
    });
    const message = el('p', { class: 'login-error', role: 'alert' });
    const submit = el('button', { class: 'primary', type: 'submit', text: 'Sign in' });
    const toggleLink = el('a', { class: 'login-toggle', href: '#', text: 'Create an account' });
    const toggleRow = el('p', { class: 'login-toggle-row' }, toggleLink);
    const version = el('p', { class: 'login-version' });

    const form = el(
      'form',
      { class: 'login-form' },
      el('h1', { class: 'login-title', text: 'Forge' }),
      el('p', { class: 'login-subtitle', text: 'A Java-first IDE framework' }),
      username,
      displayName,
      password,
      confirmPassword,
      message,
      submit,
      toggleRow,
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

    function setMode(register: boolean): void {
      registerMode = register;
      submit.textContent = register ? 'Create Account' : 'Sign in';
      displayName.hidden = !register;
      confirmPassword.hidden = !register;
      password.autocomplete = register ? 'new-password' : 'current-password';
      toggleLink.textContent = register ? 'Already have an account? Sign in' : 'Create an account';
      message.textContent = '';
    }

    toggleLink.addEventListener('click', (event) => {
      event.preventDefault();
      setMode(!registerMode);
    });

    form.addEventListener('submit', (event) => {
      event.preventDefault();
      message.textContent = '';
      const user = username.value.trim();
      const pass = password.value;

      if (user.length < 2) {
        message.textContent = 'Username must be at least 2 characters';
        username.focus();
        return;
      }
      if (pass.length < 6) {
        message.textContent = 'Password must be at least 6 characters';
        password.focus();
        return;
      }

      if (registerMode) {
        const confirm = confirmPassword.value;
        if (pass !== confirm) {
          message.textContent = 'Passwords do not match';
          confirmPassword.focus();
          return;
        }
        const display = displayName.value.trim() || user;
        submit.disabled = true;
        client
          .command<RegisterResult>('auth.register', {
            username: user,
            password: pass,
            displayName: display,
            client: navigator.userAgent,
          })
          .then((result) => {
            password.value = '';
            confirmPassword.value = '';
            screen.remove();
            resolve(result as unknown as LoginResult);
          })
          .catch((error: unknown) => {
            submit.disabled = false;
            message.textContent =
              error instanceof ForgeRequestError ? error.message : 'Could not reach the server';
            password.select();
          });
      } else {
        submit.disabled = true;
        client
          .command<LoginResult>('auth.login', {
            username: user,
            password: pass,
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
      }
    });
  });
}
