#!/bin/sh
set -eu
password="$(cat /run/forge-terminal-server-secret/password)"
printf 'forge:%s\n' "$password" | chpasswd
exec /usr/sbin/sshd -D -e -p 2222 -h /run/forge-terminal-host-key/ssh_host_ed25519_key \
  -o PermitRootLogin=no \
  -o PasswordAuthentication=yes \
  -o KbdInteractiveAuthentication=no \
  -o UsePAM=no \
  -o AllowUsers=forge \
  -o X11Forwarding=no \
  -o AllowTcpForwarding=no \
  -o AllowAgentForwarding=no \
  -o PermitTunnel=no \
  -o GatewayPorts=no \
  -o PermitUserEnvironment=no
