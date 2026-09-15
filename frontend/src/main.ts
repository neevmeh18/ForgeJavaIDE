import './style.css';
import { ForgeClient } from './forge/client';
import { showLogin } from './workbench/login';
import { Workbench } from './workbench/workbench';

/**
 * Entry point: authenticate, then build the workbench.
 *
 * <p>Nothing about the IDE is decided here. The workbench asks the backend what commands,
 * menus, shortcuts and views exist and renders those — which is what lets one frontend bundle
 * serve different IDE products built on the same framework.
 */
const root = document.getElementById('workbench');
if (!root) {
  throw new Error('Missing #workbench container');
}

const client = new ForgeClient();

showLogin(client, root)
  .then((login) => {
    client.setToken(login.token);
    const workbench = new Workbench(root, client);
    return workbench.start(login.user.displayName);
  })
  .catch((error: unknown) => {
    console.error('Workbench failed to start', error);
  });
