# Security Policy

## Reporting a vulnerability

Please do not publish sensitive vulnerability details or real data in a public issue. Contact the repository maintainer privately through the hosting platform and include a minimal reproduction using fictional data.

## Supported scope

The latest main branch receives security fixes. This repository is a reference application and must be reviewed and hardened for each production environment.

## Important deployment notes

- Replace demo database credentials and protect all endpoints with your organization’s authentication and authorization.
- Store model API keys in a secret manager, not `.env` files committed to source control.
- Restrict database network access and review audit retention.
- Do not process real customer data, internal documents, or resumes until privacy, retention, legal, and access-control requirements are implemented.
- Review model-provider data handling and regional compliance before enabling AI calls.
