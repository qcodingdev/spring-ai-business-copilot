# Vue workbench development

The `frontend` directory is the Vue 3 + TypeScript + Vite source for the single
same-origin Spring Boot workbench. The Maven application build runs `npm ci`,
builds this project, and copies `dist/` to `classpath:/static`; the runnable JAR
and runtime container therefore do not require Node.js.

Requirements: Node 22 and npm 10. The pinned versions used by Maven are defined
in the root `pom.xml`; `.nvmrc` is provided for local shells.

```bash
nvm use
npm ci
npm run check
npm run test:e2e
npm run dev
```

Vite proxies `/api`, `/login`, and `/logout` to `127.0.0.1:8080`. API calls must
remain same-origin, use the unified response envelope, send the XSRF cookie as a
header for writes, and never place secrets or business input in URLs.

## Internationalization

- `zh-CN` is the deterministic default; browser language is not used.
- `en-US` is the only other supported locale.
- Domain dictionaries live in `src/locales/{locale}/{domain}.ts`.
- Add every key to both locales. `src/locales/i18n.spec.ts` rejects missing or
  extra keys.
- Use `Intl` through `src/locales/format.ts` for dates and numbers.
- `document.lang`, persisted preference, and `Accept-Language` are synchronized.
- AI prompts receive the explicit request locale; SQL, code, identifiers,
  quotations, citations, and uploaded source text are not translated.

The browser suite runs desktop and mobile workflows, language propagation,
keyboard focus, horizontal-overflow checks, and serious/critical axe checks.

