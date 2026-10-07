# Multiuser login, 2026-10-07

- Replaced single-user PasswordAuthenticationProvider with MultiUserPasswordAuthenticationProvider
  supporting multiple concurrent users via the new `IDE_AUTH_USERS` environment variable
  (format: `user1:pass1,user2:pass2`). Backward compatible — `IDE_AUTH_USER`/`IDE_AUTH_PASSWORD`
  continue to work for single-user deployments.
- Each user gets PBKDF2-HMAC-SHA-256 hashed credentials with a unique random salt. Password
  verification is always constant-time, never distinguishing an unknown username from a wrong
  password. Rate limiting is shared across all users (2 concurrent derivations, 30 attempts per
  60-second window).
- Workspace isolation, session scoping and event filtering were already multiuser-safe — no
  changes needed to Authorizer, SessionService or EventStream.
- Frontend login form already accepts any username/password generically — no frontend changes
  were needed.

# Repair pass, 2026-09-16

This is a repair candidate, not a release verified end to end. See VERIFICATION.md before deployment.

- Added Java command argument metadata, backend type checks, generic prompts, interactive save routes and parameterized extension support. Removed palette filtering used to conceal missing arguments.
- Required an explicit password on every bind address. Added global login admission and hashing concurrency limits, bounded login-source tracking, query/helper-process limits, request deadlines, and bounded SSE queues with revocation cleanup.
- Added command outcome polling with bounded retention, workspace response generation checks, search cancellation/recovery, UI resets, serialized editor updates/saves, settings application events, and extension deactivation controls.
- Added file content revision checks and missing-file rejection; strengthened root/state symlink checks, bounded reads, aggregate API workspace accounting and filesystem operation checks.
- Serialized terminal callbacks and process reservations, preserved fast-task output before exit, bounded histories, cleaned up workspace state, and retained explicit child environment allowlists.
- Added regex character-access/time budgets, interruptible SCM locking, literal Git paths and NUL-delimited status parsing, output-limit errors, and debug ownership/startup rollback checks.
- Added 20 JUnit regression tests and eight dependency-free frontend checks. Eight frontend checks ran successfully. Java tests, production frontend build and Docker execution remain blocked by this environment.

No new production framework, role model, debugger adapter, or PTY was added. Java 21, commands/queries/events and provider boundaries remain the architecture.
