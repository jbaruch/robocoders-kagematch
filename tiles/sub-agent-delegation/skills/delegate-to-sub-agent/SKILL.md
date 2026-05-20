---
name: delegate-to-sub-agent
description: Orchestrate sub-agents across Claude Code AND GitHub Copilot. Covers what inherits vs what doesn't on each platform, how to pass skills explicitly, and the echo-skills validation handshake. Use when spawning child agents, delegating tasks, building multi-agent pipelines, or troubleshooting why a sub-agent doesn't have access to expected skills or context.
---

# Sub-Agent Delegation

Sub-agents do **not** inherit the orchestrator's skills, history, or accumulated state
on ANY platform. They start with a blank slate. The core problem is the same whether
you're on Claude Code or GitHub Copilot: **delegation is a context-loss event unless
you explicitly propagate what the sub-agent needs.**

## Claude Code inheritance model

What Claude sub-agents DO inherit:
- `CLAUDE.md` content
- Tool definitions (all, unless you restrict them)
- MCP servers (all — there is no per-sub-agent scoping today)

What they do NOT inherit:
- Skills (unless explicitly listed in `AgentDefinition`)
- Parent conversation history
- Parent system prompt
- Accumulated state

### Claude Code channels

1. **Agent SDK (`AgentDefinition`)**
   - Pass skills by name: `AgentDefinition(skills=["govee-h6056", "face-recognition-calibration"])`
   - Complete example:
     ```python
     agent = AgentDefinition(
         model="claude-opus-4-5",
         skills=["govee-h6056", "face-recognition-calibration"],
         system_prompt="You are a lighting-control specialist.",
     )
     result = agent.run(task_prompt)
     ```

2. **Claude Code Task tool**
   - Cannot attach a skills list to the tool call.
   - Inline the skill content directly in the prompt string.
   - Filesystem discovery of `.claude/skills/` technically works but is undocumented
     (GH #32910) — do not rely on it.

## VS Code Copilot inheritance model

Copilot in VS Code (agent mode) maintains conversation context within a single
session — unlike Claude Code sub-agents which start fresh per spawn. But the
context-loss problem still exists at the tool/subprocess boundary.

What VS Code Copilot agent mode HAS in context:
- `.github/copilot-instructions.md` (workspace-level, like `CLAUDE.md`)
- VS Code settings custom instructions
- Open editor content, `#file`, `#selection` references
- Full conversation history within the session

What it LOSES when delegating:
- When agent mode runs a **terminal command**, that process does NOT inherit
  Copilot's conversation context — it's a bare subprocess.
- When agent mode invokes a **chat participant** (`@workspace`, `@terminal`,
  custom `@participant`), each participant has its own system prompt and gets
  only the routed message + explicit context references, NOT the accumulated
  conversation.
- Scripts the agent WRITES that spawn their own sub-processes inherit nothing
  from Copilot — same context-loss as Claude sub-agents.

### Copilot channels for passing skills

1. **`.github/copilot-instructions.md`** (workspace-level ground truth)
   - Equivalent to `CLAUDE.md`. Every Copilot interaction in the workspace sees it.
   - Put cross-cutting context here: device specs, API patterns, team conventions.

2. **`#file` references in chat**
   - `#file:.tessl/tiles/jbaruch/govee-h6056/skills/govee-h6056-control/SKILL.md`
   - Injects the file content into the conversation context explicitly.
   - The closest Copilot equivalent to Claude's `AgentDefinition(skills=[...])`.

3. **Custom instructions in VS Code settings**
   - `github.copilot.chat.codeGeneration.instructions` — persistent per-workspace.
   - Good for always-on rules; less flexible than per-request `#file` references.

### Cross-platform ground truth

| Concern | Claude Code | VS Code Copilot |
|---|---|---|
| Workspace-level shared context | `CLAUDE.md` | `.github/copilot-instructions.md` |
| Per-request skill injection | `AgentDefinition(skills=[...])` | `#file:path/to/SKILL.md` in chat |
| Always-on rules | `CLAUDE.md` rules | `copilot-instructions.md` + VS Code settings |
| Subprocess context | Sub-agents start FRESH | Terminal commands are bare processes |
| Validation handshake | Echo-skills protocol (below) | Same pattern — add to instructions |

## Validation protocol

Every sub-agent's **first action** must be to echo back the skill names it received.
The orchestrator verifies the echo matches the expected set. If it doesn't, fail
loudly rather than letting the sub-agent operate with missing context.

Minimal echo handler (inline in the sub-agent preamble or in [`scripts/echo_skills.py`](../../scripts/echo_skills.py)):

```python
import json, sys

def echo_skills(received: list[str], expected: list[str]) -> None:
    missing = set(expected) - set(received)
    extra   = set(received)  - set(expected)
    if missing or extra:
        raise RuntimeError(
            f"Skill handoff mismatch — missing: {missing}, unexpected: {extra}"
        )
    print(json.dumps({"skills_echo": received}), flush=True)

echo_skills(
    received=["govee-h6056", "face-recognition-calibration"],
    expected=["govee-h6056", "face-recognition-calibration"],
)
```

The orchestrator parses the `skills_echo` JSON line from the sub-agent's first
output and aborts the run if the lists don't match.

## Cross-sub-agent ground truth

If a piece of information must reach **every** sub-agent, put it in `CLAUDE.md`.
Skills are opt-in per sub-agent and will otherwise silently not apply.

## Critical gotcha

- **`context: fork` is semantically inverted** — it creates an ISOLATED blank context,
  not a fork of the parent. → [GH #20492](https://github.com/anthropics/claude-code/issues/20492)

For additional known issues (silent model switching, AskUserQuestion unavailability,
billing routing, filesystem skill discovery), see [../../docs/SUB_AGENT_ISSUES.md](../../docs/SUB_AGENT_ISSUES.md).

## How to act

1. Before launching a sub-agent, list the exact skills it needs.
2. Use `AgentDefinition(skills=[...])` if you're on the SDK. Otherwise, inline the
   skill bodies into the prompt string.
3. Include the echo-skills preamble so the first sub-agent response lets you verify
   the handoff; abort loudly if the echo doesn't match.
4. If cross-cutting context is required, put it in `CLAUDE.md` — do not rely on
   implicit inheritance.

See the rule `sub-agent-delegation-rules` for a quick reminder card.
