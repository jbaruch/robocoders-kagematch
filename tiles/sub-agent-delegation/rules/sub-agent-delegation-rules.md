# Sub-Agent Delegation Rules

**Platform-agnostic.** The context-loss problem is the same on Claude Code and Copilot.

## The universal rule

- **Sub-agents / extensions / participants start with FRESH context.** They do NOT inherit parent's skills, history, or accumulated state.
- **Pass skills explicitly** — every platform, every time.
- **Validate the handoff.** First sub-agent action: echo received skills. Fail loudly on mismatch.

## Claude Code

- Inherit: `CLAUDE.md`, tool definitions, MCP servers.
- Pass skills: `AgentDefinition(skills=[...])` or inline in Task tool prompt.
- `context: fork` is INVERTED (GH #20492) — creates blank, not copy.
- `AskUserQuestion` unavailable in sub-agents (GH #34592).

## VS Code Copilot

- Agent mode persists within session BUT terminal commands and chat participants get fresh context.
- Scripts the agent writes that spawn sub-processes inherit NOTHING from Copilot.
- Pass skills: `#file:path/to/SKILL.md` in chat, or `.github/copilot-instructions.md` for always-on.

## Cross-platform ground truth

| | Claude Code | VS Code Copilot |
|---|---|---|
| Shared context | `CLAUDE.md` | `.github/copilot-instructions.md` |
| Per-request skills | `AgentDefinition(skills=[...])` | `#file:path/to/SKILL.md` |
| Subprocess context | Sub-agents start FRESH | Terminal commands are bare |
| Handshake | echo-skills protocol | same pattern works |
