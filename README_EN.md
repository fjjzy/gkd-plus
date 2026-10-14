# GKD-Plus

**English** | [简体中文](README.md)

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="gkd-app/src/main/res/mipmap/gkd_white.png">
    <img src="gkd-app/src/main/res/mipmap/gkd_black.png" width="96" alt="GKD-Plus">
  </picture>
</p>

<p align="center">
  <a href="https://github.com/fjjzy/gkd-plus/releases/latest"><img alt="release" src="https://img.shields.io/github/v/release/fjjzy/gkd-plus?label=release"></a>
  <a href="https://github.com/fjjzy/gkd-plus/releases"><img alt="downloads" src="https://img.shields.io/github/downloads/fjjzy/gkd-plus/total?label=downloads"></a>
  <a href="https://github.com/fjjzy/gkd-plus/discussions"><img alt="discussions" src="https://img.shields.io/badge/discussions-join-8A2BE2"></a>
  <a href="/LICENSE"><img alt="license" src="https://img.shields.io/github/license/fjjzy/gkd-plus?label=license"></a>
</p>

GKD-Plus is an enhanced fork of [GKD](https://github.com/gkd-kit/gkd) that adds AI-powered rule generation and other capabilities on top of the original app.

GKD is a custom Android screen-clicking app built on [advanced selectors](https://gkd.li/guide/selector), [subscription rules](https://gkd.li/guide/subscription) and [snapshot inspection](https://github.com/gkd-kit/inspect): with custom rules, it clicks a specific node or position, or performs other actions, when a given condition (such as specific text on screen) is met on a given screen.

<p align="center">
  <img src="docs/images/dashboard.jpg" width="200" alt="Home">
  <img src="docs/images/ai-workbench.jpg" width="200" alt="AI rule workbench">
  <img src="docs/images/ai-settings.jpg" width="200" alt="AI rule settings">
</p>
<p align="center">
  <img src="docs/images/ai-conversation.jpg" width="200" alt="Conversational generation">
  <img src="docs/images/snapshot-picker.jpg" width="200" alt="Snapshot picker">
  <img src="docs/images/snapshot-record.jpg" width="200" alt="Snapshot records">
</p>

## Added Features

### AI Rule Generation

- Supports large model providers compatible with OpenAI / Anthropic protocols to generate click rules automatically, with a custom API URL, model, temperature, and related parameters
- Snapshot picker: zoom the selection range, multi-select components, inspect a component's properties, then generate directly or through a conversation
- AI rule workbench: conversations are kept per app, so you can review the history and keep asking
- Concurrent generation: each snapshot is processed independently without blocking others; re-triggering a running conversation reuses the in-flight task
- Thinking level: picks the right reasoning parameters per model (Claude / GPT-5 / Zhipu GLM / Doubao / Qwen / DeepSeek and more), with custom JSON as a fallback
- Adaptive max tokens: raise a too-small budget, double it on truncation, and fall back while remembering the ceiling when the server rejects the value
- "Test connection" in settings
- Recommended model: Deepseek-v4-flash

### AI Configuration Backup and Restore

- Large model settings, including API URL, API key, and model name, are included in backup/import flows

## Install

Download the latest `gkd-*.apk` from [Releases](https://github.com/fjjzy/gkd-plus/releases/latest) and install it.

You can also update in-app via `Settings - About - Check for updates`: when GitHub is unreachable it automatically falls back to the Gitee and npm mirrors for version info, and the APK can be downloaded from Quark Drive.

## Usage

1. **Configure a model**: open `AI Rule Workbench - AI Rule Settings`, fill in the protocol, API URL, API key and model, then tap "Test connection" to make sure it works.
2. **Pick a trigger** (any one of them):
   - Enable "Snapshot button" on the home page, then tap the floating button to capture
   - Add the "Capture snapshot" tile to Quick Settings and pull down the notification shade
   - Enable "Volume snapshot" and press a volume key
3. **Trigger a snapshot** on the screen you want to handle.
4. **Select components**: in the snapshot picker, zoom the selection range and tick the components you need (you can inspect their properties); selecting nothing is fine too.
5. **Generate and save**: tap "Generate directly" to produce a rule and add it to your local rules, or tap "Generate with conversation" to refine it in natural language and keep asking.
6. **Iterate later**: find the session again in the `AI Rule Workbench` and continue the conversation to adjust the rule.

Rules are not limited to closing ads. You can describe any condition you want, for example "when component 1 and component 2 are both present, click component 3", and the model translates it into a GKD rule.

## Original GKD Features

- **Quick actions**: simplify repetitive flows, such as confirming desktop login prompts automatically
- **Skip flows**: automatically skip annoying startup flows

## FAQ

If rule generation fails, open `Settings - About - Export logs` and send the exported logs to `3329221986@qq.com`, or file an issue on [GitHub](https://github.com/fjjzy/gkd-plus/issues) (please attach the logs, otherwise it is hard to diagnose).

Questions, feature ideas and tips are welcome in [Discussions](https://github.com/fjjzy/gkd-plus/discussions).

If the problem happens during an AI rule conversation, open "More" in the top-right corner of the conversation page and choose "Share current conversation", then send the generated file to `3329221986@qq.com`, or post it to [Issues](https://github.com/fjjzy/gkd-plus/issues) / [Discussions](https://github.com/fjjzy/gkd-plus/discussions).

## Disclaimer

This project is licensed under [GPL-3.0](/LICENSE) and is intended for learning and exchange only; commercial or illegal use is prohibited.

This project is an independent enhanced fork of GKD and does not represent the official position of the upstream project. For the original GKD project, visit [gkd-kit/gkd](https://github.com/gkd-kit/gkd).
