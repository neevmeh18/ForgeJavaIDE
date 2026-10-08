# Deployment trust boundary

Use an explicit unique password of at least 12 characters. Compose publishes only host loopback. Never publish this plaintext listener directly to a remote network. For remote use, terminate HTTPS at an authenticated, maintained reverse proxy, keep the backend on a private interface, and configure header/body/idle timeouts and connection limits there. Do not trust forwarded headers from arbitrary peers. TLS deployment has not been exercised in this repair environment.

The product has one trusted authenticated identity. Terminals, tasks, repository Git hooks/configuration and installed JVM extensions can execute with the server operating-system identity. Environment allowlisting prevents accidental inheritance; it does not isolate arbitrary code from same-UID files or process metadata. A malicious installed JVM extension is equivalent to administrator-installed server code. A malicious workspace must not be executed or treated as trusted simply because it was opened.

Use container CPU/memory/PID caps and a dedicated quota-enforced workspace/data filesystem. Application write quotas do not cover child processes or external writers. Do not mount administrator/cloud credentials into the runtime. Local administrator or build-system compromise is outside the remote-unauthenticated threat boundary.

Custom extension view renderers are not supported. Supported IDs are explorer, search, scm, extensions, settings, debug, terminal, problems and tasks, with their built-in rendering contracts. Commands can declare required argument metadata with CommandDescriptor.withArguments; those arguments are validated in Java and prompted by the workbench. Trusted extension handlers remain responsible for semantic validation and cooperative termination.

Read VERIFICATION.md for the remaining limits. This source candidate has not passed the full build and runtime release gate.
