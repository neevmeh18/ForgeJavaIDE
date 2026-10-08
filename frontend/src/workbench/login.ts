import type { ForgeClient } from '../forge/client';
import { ForgeRequestError } from '../forge/client';
import type { Invitation, LoginResult, WorkbenchStatus } from '../forge/protocol';
import { el } from './dom';

/**
 * The sign-in screen.
 *
 * <p>It runs one command, `auth.login`, and keeps the returned token in memory only. Deliberately
 * not in `localStorage`: a token in storage survives the tab, is readable by any script that
 * manages to run on the page, and cannot be revoked by closing it.
 *
 * <p>An invitation is the other way in. The token is looked up first so the guest can see which
 * workspace and role they are accepting, and that same role is sent back with the token.
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
    const useInvitation = el('button', { class: 'login-switch', type: 'button', text: 'Have an invitation?' });

    const form = el(
      'form',
      { class: 'login-form' },
      el('h1', { class: 'login-title', text: 'Forge' }),
      el('p', { class: 'login-subtitle', text: 'A Java-first IDE framework' }),
      username,
      password,
      message,
      submit,
      useInvitation,
      version,
    );

    const token = el('input', {
      class: 'field',
      type: 'text',
      name: 'invitation',
      autocomplete: 'off',
      placeholder: 'Invitation token',
      spellcheck: 'false',
    });
    const inviteMessage = el('p', { class: 'login-error', role: 'alert' });
    const preview = el('p', { class: 'login-preview' });
    const lookup = el('button', { class: 'primary', type: 'submit', text: 'Continue' });
    const accept = el('button', { class: 'primary', type: 'button', text: 'Accept invitation' });
    accept.hidden = true;
    const usePassword = el('button', { class: 'login-switch', type: 'button', text: 'Sign in with a password' });
    const inviteForm = el(
      'form',
      { class: 'login-form', hidden: 'true' },
      el('h1', { class: 'login-title', text: 'Forge' }),
      el('p', { class: 'login-subtitle', text: 'Join a workspace' }),
      token,
      preview,
      inviteMessage,
      lookup,
      accept,
      usePassword,
    );

    const screen = el('div', { class: 'login-screen' }, form, inviteForm);
    root.append(screen);
    username.focus();

    let pending: Invitation | null = null;

    void client
      .query<WorkbenchStatus>('workbench.status')
      .then((status) => {
        version.textContent = `${status.product} ${status.version}`;
      })
      .catch(() => {
        version.textContent = '';
      });

    useInvitation.addEventListener('click', () => {
      form.hidden = true;
      inviteForm.hidden = false;
      token.focus();
    });
    usePassword.addEventListener('click', () => {
      inviteForm.hidden = true;
      form.hidden = false;
      username.focus();
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

    inviteForm.addEventListener('submit', (event) => {
      event.preventDefault();
      inviteMessage.textContent = '';
      pending = null;
      accept.hidden = true;
      lookup.disabled = true;
      client
        .query<Invitation>('workspaceAccess.preview', { token: token.value.trim() })
        .then((invitation) => {
          pending = invitation;
          preview.textContent = `${roleLabel(invitation.role)} access to ${invitation.workspaceId}`;
          lookup.hidden = true;
          accept.hidden = false;
        })
        .catch((error: unknown) => {
          preview.textContent = '';
          inviteMessage.textContent =
            error instanceof ForgeRequestError ? error.message : 'Could not reach the server';
        })
        .finally(() => {
          lookup.disabled = false;
        });
    });

    accept.addEventListener('click', () => {
      if (!pending) {
        return;
      }
      inviteMessage.textContent = '';
      accept.disabled = true;
      client
        .command<LoginResult>('workspaceAccess.accept', {
          token: token.value.trim(),
          role: pending.role,
          client: navigator.userAgent,
        })
        .then((result) => {
          token.value = '';
          screen.remove();
          resolve(result);
        })
        .catch((error: unknown) => {
          accept.disabled = false;
          inviteMessage.textContent =
            error instanceof ForgeRequestError ? error.message : 'Could not reach the server';
        });
    });
  });
}

function roleLabel(role: Invitation['role']): string {
  return role === 'OWNER' ? 'Owner' : 'Viewer';
}
