# CLAUDE.md

**Required reading before any change:** [AGENTS.md](AGENTS.md) (layout, build/test commands,
conventions, the "never do" list, gotchas) and [ARCHITECTURE.md](ARCHITECTURE.md) (components, data
flow, design decisions, platform constraints, threat model). Keep both updated **in the same commit**
as any change to structure or behavior.

Quick reminders (full detail in AGENTS.md):
- Agent is generated (`agent/ → npm run build`), binaries are downloaded (`scripts/fetch-frida.sh`);
  neither is committed. Never commit `jniLibs/`, `assets/agent.js`, `third_party/`, keystores,
  `local.properties`.
- Never set `jniLibs.useLegacyPackaging = false` (the binary must be extracted to be exec'd).
- Never add INTERNET permission — CamSpoof is fully local.
- The feed header (`feed/FeedFormat.kt`) and `agent/index.js` are one wire format; change both
  together. The agent's `csf:` lines and `InjectionParser` are one contract; keep in step.
- Don't run live injection into a real app in automated tests; verify build + install + launch +
  `libfridainject.so --help` on the emulator instead.
- Build with `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`, wrap heavy jobs in `/usr/local/bin/heavy`.
