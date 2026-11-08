# CamSpoof Frida agent

`index.js` is compiled with [frida-compile] into `../app/src/main/assets/agent.js` (bundled in the
APK, **not committed**). It is injected into the chosen target app by `frida-inject` and replaces
the app's camera frames with CamSpoof's composited NV21 feed.

```bash
npm install
npm run build      # -> ../app/src/main/assets/agent.js
```

The agent and the Kotlin side share a wire format; the feed header offsets here must match
`app/.../feed/FeedFormat.kt`, and the `csf:` status lines must match `InjectionParser.kt`. See
[../ARCHITECTURE.md](../ARCHITECTURE.md).

[frida-compile]: https://github.com/frida/frida-compile
