# 安全策略 / Security Policy

## 支持的版本 / Supported Versions

仅维护最新 [Release](https://github.com/fjjzy/gkd-plus/releases/latest) 版本。
Only the latest [Release](https://github.com/fjjzy/gkd-plus/releases/latest) is maintained.

## 上报漏洞 / Reporting a Vulnerability

请通过 GitHub 私有漏洞上报（Private vulnerability reporting）提交，或发送邮件到 `3329221986@qq.com`。
Please report via GitHub Private vulnerability reporting, or email `3329221986@qq.com`.

- 请附上：GKD-Plus 版本号、设备/系统版本、复现步骤，必要时附带脱敏后的日志导出文件。
- 请勿在公开 Issue / Discussion 中披露漏洞细节。

Please include: the GKD-Plus version, device/OS version, reproduction steps, and a redacted log export if possible. Do not disclose vulnerability details in public Issues or Discussions.

## 供应链提示 / Supply chain notes

Release APK 使用固定签名密钥；应用内更新兜底数据来自 Gitee `fjjzy/gkd-plus@master:version.json` 与 npm 包 `gkd-plus-version`，这两处被篡改可影响所有用户的更新来源，相关报告优先级最高。

Release APKs are signed with a fixed key; the in-app update fallback data comes from Gitee `fjjzy/gkd-plus@master:version.json` and the npm package `gkd-plus-version`. Tampering with either affects every user's update source, so related reports get the highest priority.
