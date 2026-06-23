# Claude Code Instructions

@AGENTS.md

## Secrets

- `.env` contains secrets including `BOT_TOKEN`. NEVER read, print, cat, or commit `.env` or its contents.
- To run the app with the token, load it in the shell without echoing it (e.g. `set -a; . ./.env; set +a`); never expand or display the value.
- `.env` is gitignored — keep it that way.
