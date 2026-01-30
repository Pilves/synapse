# Security Policy

## Reporting a Vulnerability

If you discover a security vulnerability in Synapse, please report it responsibly:

1. **Email:** Open a private security advisory via [GitHub Security Advisories](https://github.com/Pilves/synapse/security/advisories/new)
2. **Do not** open a public issue for security vulnerabilities

Include as much detail as possible:
- Description of the vulnerability
- Steps to reproduce
- Affected components (see below)
- Potential impact

## Response Timeline

- **Acknowledgment:** Within 3 business days
- **Initial assessment:** Within 7 business days
- **Fix timeline:** Depends on severity; critical issues are prioritized for the next release

## Security-Sensitive Components

The following areas of the codebase handle sensitive data and are the most likely targets for security review:

| Component | File | What It Handles |
|-----------|------|-----------------|
| API key storage | `SecureKeyStorage.kt` | Encrypts API keys using Android `EncryptedSharedPreferences` (AES-256-GCM) |
| Output sanitization | `OutputSanitizer.kt` | Sanitizes LLM output before rendering or writing to files |
| Certificate pinning | `AppModule.kt` | TLS certificate pins for LLM API endpoints; uses real production SHA-256 certificate pins for all three cloud LLM providers (Anthropic, OpenAI, Google) with both leaf and intermediate CA backup pins |
| Accessibility service | `SynapseAccessibilityService.kt` | Reads on-screen text; has access to all visible UI content |
| File I/O | `SessionStorage.kt`, `ChunkStorage.kt` | Path validation to prevent directory traversal |
| SAF access | `ProjectStorage.kt` | Scoped storage access to user-selected vault folders |
| MediaProjection | `ScreenshotManager.kt` | Screenshot capture with explicit user consent per session |
| Network security config | `network_security_config.xml` | Enforces HTTPS-only globally, allows cleartext only for localhost/127.0.0.1 (Ollama) |
| Sensitive app exclusion | `SynapseAccessibilityService.kt` | Hardcoded exclusion of banking, password manager, payment, and authenticator apps from accessibility events |
| Key migration | `SecureKeyStorage.migrateFromDataStore()` | Idempotent migration of plaintext API keys to encrypted storage; failure could leave keys in plaintext |
| Vault file I/O | `VaultManager.kt` | Handles actual file writes to user-selected vault folders via SAF |
| Crash reporting | `CrashReporter.kt` | Transmits crash data (stack traces, device info) to Google Firebase servers |

## Supported Versions

Only the latest release is actively maintained with security fixes.

## Scope

The following are **in scope** for security reports:
- API key exposure or leakage
- Directory traversal or unauthorized file access
- Accessibility service data leakage
- Injection vulnerabilities in LLM output rendering
- Insecure network communication

The following are **out of scope**:
- Attacks requiring physical device access with USB debugging enabled
- Social engineering
- Vulnerabilities in third-party LLM provider APIs
- Issues in dependencies that already have upstream fixes
