# The agent's sandbox: Claude Code and nothing else. No repository, no ~/.claude
# (so no memory, settings, CLAUDE.md or MCP servers), no git. The code under
# question is mounted read-only at /work by the runner; nothing else is mounted.
FROM node:22-slim
RUN apt-get update && apt-get install -y --no-install-recommends ripgrep && rm -rf /var/lib/apt/lists/*
ARG CLAUDE_CODE_VERSION=2.1.284
RUN npm install -g @anthropic-ai/claude-code@${CLAUDE_CODE_VERSION} && npm cache clean --force
USER node
WORKDIR /work
ENTRYPOINT ["claude"]
