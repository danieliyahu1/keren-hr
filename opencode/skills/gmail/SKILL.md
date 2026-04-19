---
name: gmail
description: Primary email account skill for ${KEREN_EMAIL}. Use this skill whenever you need to access this email address — reading inbox, retrieving verification codes or OTPs, OAuth login, or any Gmail interaction.
---

## Your Credentials - use it if you are not authenticated
# email: ${KEREN_EMAIL}
# password: ${GMAIL_PASSWORD}

## Technical Requirement
# Use your available browser tools to access Gmail and perform authentication tasks.

## Core Mission
Act as the primary authentication source for the user's digital identity. These credentials are used to authenticate and access other applications and services that support Google OAuth or direct Gmail login.

## Authentication Capabilities
- Google OAuth Login Authenticate into any application that supports "Sign in with Google" using the provided Gmail credentials.
- OAuth Consent When authenticating with OAuth flows, approve access requests on behalf of the user for services they want to link.
- Account Recovery Assist with account recovery processes if needed for linked services.
- Permission Management Review and approve OAuth permission requests from third-party applications.

## Interaction Protocol
1. Navigate to the target application's "Sign in with Google" or Google OAuth endpoint.
2. Enter the Gmail address when prompted for the Google account.
3. If a new browser session, enter the Gmail password to complete authentication.
4. For OAuth consent screens, review the requested permissions and approve if the user has authorized the service.
5. Handle 2FA prompts if Two-Factor Authentication is enabled on the account.

## Account Security Guidelines
- Session Management Maintain secure sessions when authenticating multiple apps in sequence.
- Permission Scrutiny Review OAuth permission requests carefully before approving (e.g., avoid granting excessive access to unknown apps).
- Logout Awareness When done authenticating apps, close the authenticated sessions if the user requests.
- Never share these credentials beyond the scope of authenticating user-authorized applications.

## Two-Factor Authentication
If 2FA is required, the authentication flow may include:
- SMS verification codes sent to the registered phone number
- Google Authenticator app codes
- Backup codes if available

## Retrieving Verification Codes

Whenever a verification code or OTP is needed from ${KEREN_EMAIL}, **use this shell command — do not attempt browser-based Gmail login**. It connects via IMAP, scans the inbox for the most recent 6-digit code, and is not blocked by Google.

```bash
node /scripts/fetch-verification-code.js
```

The command prints a single 6-digit code to stdout and does not mark any email as read.
If it exits with an error, wait 5 seconds and retry once — the email may not have arrived yet.

## Session Cleanup
Once you have completed the requested task, close the browser tab and terminate the browser session to free up memory.
