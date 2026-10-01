import type { WorkbenchContext } from '../forge/context';
import type { Invitation, IssuedInvitation, WorkspaceRole } from '../forge/protocol';
import { describeError as describe } from '../forge/client';
import { clear, el } from './dom';

/**
 * Owners invite someone into the current workspace and can take an invitation back.
 *
 * <p>The token is shown once, in the response that created it. The list afterwards is only
 * the invitations that are still open: id, role and expiry, never the token.
 */
export class WorkspaceAccessView {
  readonly element = el('div', { class: 'view access-view' });

  private readonly role = el('select', { class: 'field' });
  private readonly days = el('input', {
    class: 'field',
    type: 'number',
    min: '1',
    max: '14',
    value: '7',
  });
  private readonly issued = el('div', { class: 'access-issued' });
  private readonly list = el('div', { class: 'access-list' });

  constructor(private readonly ctx: WorkbenchContext) {
    this.role.append(
      el('option', { value: 'VIEWER', text: 'Viewer' }),
      el('option', { value: 'OWNER', text: 'Owner' }),
    );
    const create = el('button', { class: 'primary', text: 'Create invitation' });
    create.addEventListener('click', () => void this.create());
    this.element.append(
      el('div', { class: 'view-header' }, el('h2', { text: 'Access' })),
      el(
        'form',
        { class: 'access-form' },
        el('label', { class: 'access-label', text: 'Role' }),
        this.role,
        el('label', { class: 'access-label', text: 'Valid for (days)' }),
        this.days,
        create,
      ),
      this.issued,
      this.list,
    );
    this.element.querySelector('form')?.addEventListener('submit', (event) => event.preventDefault());
  }

  async refresh(): Promise<void> {
    clear(this.list);
    if (!this.ctx.state.workspace) {
      this.list.append(el('p', { class: 'view-empty', text: 'Open a workspace to invite someone.' }));
      return;
    }
    let invitations: Invitation[];
    try {
      invitations = await this.ctx.client.query<Invitation[]>('workspaceAccess.list');
    } catch (error) {
      this.list.append(el('p', { class: 'view-empty', text: describe(error) }));
      return;
    }
    if (invitations.length === 0) {
      this.list.append(el('p', { class: 'view-empty', text: 'No open invitations.' }));
      return;
    }
    for (const invitation of invitations) {
      const revoke = el('button', { class: 'view-action', text: 'Revoke' });
      revoke.addEventListener('click', () => void this.revoke(invitation.id));
      this.list.append(
        el(
          'div',
          { class: 'access-row' },
          el('div', { class: 'access-role', text: label(invitation.role) }),
          el('div', { class: 'access-meta', text: `until ${formatWhen(invitation.expiresAt)}` }),
          revoke,
        ),
      );
    }
  }

  private async create(): Promise<void> {
    const validDays = Number(this.days.value);
    try {
      const issued = (await this.ctx.commands.execute<IssuedInvitation>('workspaceAccess.invite', {
        role: this.role.value,
        validDays: Number.isFinite(validDays) ? validDays : 7,
      })) as IssuedInvitation;
      this.showToken(issued.token, issued.invitation.role);
      await this.refresh();
    } catch (error) {
      this.ctx.notify('error', describe(error));
    }
  }

  private showToken(token: string, role: WorkspaceRole): void {
    clear(this.issued);
    const value = el('input', { class: 'field', readonly: 'true', value: token });
    const copy = el('button', { class: 'view-action', text: 'Copy' });
    copy.addEventListener('click', () => {
      void navigator.clipboard.writeText(token).then(
        () => this.ctx.notify('info', 'Invitation copied'),
        () => this.ctx.notify('warning', 'Could not copy the invitation'),
      );
    });
    this.issued.append(
      el('p', { class: 'access-note', text: `Share this ${label(role).toLowerCase()} invitation once. It will not be shown again.` }),
      el('div', { class: 'access-token' }, value, copy),
    );
  }

  private async revoke(invitationId: string): Promise<void> {
    try {
      await this.ctx.commands.execute('workspaceAccess.revoke', { invitationId });
      await this.refresh();
    } catch (error) {
      this.ctx.notify('error', describe(error));
    }
  }
}

function label(role: WorkspaceRole): string {
  return role === 'OWNER' ? 'Owner' : 'Viewer';
}

function formatWhen(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}
