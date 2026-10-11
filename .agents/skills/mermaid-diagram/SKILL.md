---
name: mermaid-diagram
description: Create source-grounded Mermaid FSM, sequence, dependency and architecture
  diagrams for repository documents, then render and inspect them.
---

Read [project rules](../../../agent.md) and [hardware skill context](../../../docs/hardware-skill-context.md) before work. User scope and the designated specification take precedence over templates. Installation or skill selection does not authorize RTL changes, builds, programming hardware, external publishing, or delegation. Preserve existing authorization; do not add approval rounds for already authorized work.

Use only the supplied/verified model and preserve qualifications; do not invent states or connections. Read the appropriate references/types file, references/syntax-pitfalls.md and references/mermaid-theme.md when relevant. Save .mmd or embed Mermaid in the requested Markdown, preserving native source.

Use an already installed mmdc for rendering. The upstream references/render_mermaid.sh uses npx and may download packages; this is optional, not an automatic dependency installation. After rendering, inspect overlap, labels and routing and correct actual defects. Standard flow/state/sequence layouts are useful for FSMs and transactions; cycle-accurate hardware timing goes through [hardware-wavedrom](../hardware-wavedrom/SKILL.md). Distinguish graphical validation from design validation.

The original entry is retained in [SKILL.upstream.md](SKILL.upstream.md) for reference. Load only relevant sections; its original host-specific invocation, automatic actions, and generic defaults are replaced by this project entry. Retained templates require technical review.
