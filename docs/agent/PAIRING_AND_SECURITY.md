# Agent pairing and security

An authenticated ShackCQ user creates a 12-character, short-lived, one-time pairing code for one station profile. The Agent exchanges it over HTTPS at /api/v1/agent/pair. The account password is never entered into the Agent.

The response credential is stored only through SystemCredentialVault: macOS Keychain, Windows Credential Manager, or Linux Secret Service. desktop-config.json stores only cloudAgent.enabled; endpoint and token fields are rejected during restore. Logs and health output contain neither token nor endpoint.

The server stores only a SHA-256 credential verifier. Revocation closes the live socket. Rate limits apply to pairing, socket authentication, and browser commands. Database tenancy and radio-device lookup prevent cross-account addressing. TLS verification cannot be bypassed and the Agent requires TLS 1.3 or later.

    ShackCQAgent --pair-with-shackcq ABCD-EFGH-IJKL --agent-name "Home shack"
    ShackCQAgent --foreground
    ShackCQAgent --status
