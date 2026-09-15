# Wire protocol

Four endpoints. Anything a browser can do, a script can do — that is the point.

```
POST /api/command     run a command
POST /api/query       run a query
GET  /api/events      subscribe to events (server-sent events)
GET  /api/health      readiness
```

Authentication is a bearer token in the `Authorization` header, on every call including the
event stream. There is no cookie, so there is nothing for a cross-site request to ride on.

The active workspace is chosen per request with `X-Forge-Workspace`. The backend checks that the
session is actually attached to it; sending the header is not permission to use it.

## Commands

```bash
TOKEN=$(curl -s localhost:3000/api/command \
  -H 'Content-Type: application/json' \
  -d '{"id":"auth.login","args":{"username":"developer","password":"forge"}}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["value"]["token"])')

WS=$(curl -s localhost:3000/api/query -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"id":"workspace.available"}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["value"][0]["id"])')

curl -s localhost:3000/api/command -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"id\":\"workspace.open\",\"args\":{\"workspaceId\":\"$WS\"}}"

curl -s localhost:3000/api/command -H "Authorization: Bearer $TOKEN" \
  -H "X-Forge-Workspace: $WS" -H 'Content-Type: application/json' \
  -d '{"id":"file.save","args":{"path":"hello.txt","content":"written by curl\n"}}'
```

Request:

```json
{ "id": "file.save", "args": { "path": "a.txt", "content": "…" }, "async": false }
```

Response — the same envelope for every command and query:

```json
{ "ok": true, "value": { "path": "a.txt", "size": 12, "modifiedAt": 1730000000000 },
  "error": null, "executionId": "exec-…", "pending": false }
```

```json
{ "ok": false, "value": null, "pending": false, "executionId": "exec-…",
  "error": { "code": "FORBIDDEN", "message": "Path escapes the workspace", "details": {} } }
```

`"async": true` returns immediately with `pending: true` and an `executionId`; so does a
synchronous call that outlives the gateway's 30-second window. Either way the outcome arrives on
the event stream as `command.completed` or `command.failed` with the same id, and
`command.cancel` stops it.

### Error codes and HTTP status

| Code | Status |
|---|---|
| `INVALID_ARGUMENT` | 400 |
| `UNAUTHORIZED` | 401 |
| `FORBIDDEN` | 403 |
| `NOT_FOUND` | 404 |
| `CONFLICT`, `UNAVAILABLE` | 409 |
| `CANCELLED` | 499 |
| `INTERNAL_FAILURE` | 500 |
| `UNSUPPORTED` | 501 |

## Events

```
GET /api/events
Authorization: Bearer <token>

event: file.saved
data: {"type":"file.saved","workspaceId":"local-…","payload":{"path":"a.txt","size":12}}

: ping
```

Delivery is filtered, never broadcast: session-addressed events reach one session, workspace
events reach the sessions attached to that workspace, and command outcomes reach whoever issued
them. Each client has a bounded queue and drops its oldest events rather than growing the
server's heap.

## Reference

Commands and queries are discoverable at runtime rather than listed here, which is what keeps
this document from going stale:

```bash
curl -s localhost:3000/api/query -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"id":"workbench.commands"}'
```

`workbench.contributions` returns the menus, keybindings and views the frontend renders itself
from. Extensions appear in both.

Selected ids:

| Commands | Queries | Events |
|---|---|---|
| `workspace.open` `workspace.close` `workspace.reload` `workspace.create` | `workspace.available` `workspace.opened` `workspace.current` `workspace.sessions` | `workspace.opened` `workspace.closed` `workspace.sessionAttached` |
| `file.save` `file.create` `file.createDirectory` `file.delete` `file.rename` `file.move` `file.copy` | `file.list` `file.read` `file.stat` | `file.created` `file.changed` `file.deleted` `file.moved` `file.saved` |
| `editor.open` `editor.close` `editor.update` `editor.format` | `editor.documents` `editor.document` | `editor.opened` `editor.closed` `editor.documentChanged` `editor.dirtyStateChanged` |
| `auth.login` `auth.logout` | `auth.currentUser` | `auth.sessionStarted` `auth.sessionEnded` |
| `search.files` `search.text` `search.symbols` | — | — |
| `terminal.create` `terminal.write` `terminal.resize` `terminal.kill` | `terminal.list` `terminal.scrollback` | `terminal.created` `terminal.output` `terminal.exited` |
| `task.run` `task.cancel` | `task.available` `task.executions` | `task.started` `task.finished` |
| `scm.stage` `scm.unstage` `scm.discard` `scm.commit` `scm.checkout` `scm.fetch` `scm.pull` `scm.push` | `scm.status` `scm.branches` `scm.diff` `scm.history` | `scm.repositoryChanged` `scm.committed` `scm.branchChanged` |
| `debug.start` `debug.stop` `debug.continue` `debug.pause` `debug.step` `debug.toggleBreakpoint` | `debug.sessions` `debug.breakpoints` `debug.stackTrace` `debug.variables` | `debug.stopped` `debug.output` `debug.breakpointsChanged` |
| `language.rename` | `language.completion` `language.hover` `language.definition` `language.references` `language.documentSymbols` `language.codeActions` | `language.diagnostics` |
| `settings.set` `state.set` `command.cancel` `extension.activate` | `settings.resolved` `settings.definitions` `state.get` `workbench.commands` `workbench.contributions` `workbench.extensions` `workbench.status` | `settings.changed` `extension.activated` `extension.failed` |
