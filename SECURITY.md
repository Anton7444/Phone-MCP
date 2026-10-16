# Security policy

Phone MCP gives an AI client remote code execution on a phone by design, so security reports are very welcome.

## Reporting a vulnerability

Please **do not open a public issue**. Use GitHub's private reporting instead: the **Security** tab of this repository → **Report a vulnerability**.

Include the app version, privilege mode (Root, Shizuku or Basic), connection type, and steps to reproduce.

## In scope

- Getting past the on-device approval, read-only mode, tool switches or path rules without the owner's consent.
- Reaching the MCP endpoint without the connector secret, or learning the secret.
- Leaks of the secret or tunnel token into logs, the audit log, backups or other apps.

## Out of scope

- Anything a client can do with Full Access on, or with commands the owner approved.
- Cloudflare seeing tunnel traffic (TLS ends at their edge; see the README).
- Attacks that need root or physical access to an unlocked phone.
