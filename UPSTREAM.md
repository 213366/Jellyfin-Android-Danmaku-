# 上游跟版记录（Jellyfin for Android 弹幕版）

本仓库是 [jellyfin/jellyfin-android](https://github.com/jellyfin/jellyfin-android) 的 fork。
自研的弹幕改动全部集中在 `danmaku` 分支，官方主线保持只读。

## 远程与分支

| 远程 | 地址 | 用途 |
| --- | --- | --- |
| `origin` | https://github.com/213366/Jellyfin-Android-Danmaku- | 自研分支的推送目标 |
| `upstream` | https://github.com/jellyfin/jellyfin-android | 官方仓库（只读） |

- 自研分支：`danmaku`，跟踪 `origin/danmaku`
- 官方基线：`upstream/master`
- 自研提交（在 `upstream/master` 之上）：
  1. `feat: add danmaku support and cache time enhancement`
  2. `docs: add enhanced version description - danmaku & cache features`
  3. `docs: rewrite README with danmaku and buffer features description (CN + EN)`
  4. `feat(danmaku): improve episode matching`
  5. `feat(player): enter immersive mode and hide web UI during playback`
  6. `style(player): remove double-tap ripple effect`

## 跟版记录

| 日期 | 上游基线 | 产品版本 | 产物 | 结果 |
| --- | --- | --- | --- | --- |
| 2026-09-14 | `upstream/master` `90400b5b`（2026-09-13） | 1.1.0 | `danmaku-jellyfin-v1.1.0.apk`（28.0 MB，SHA-256 前 16 位 `552615831144761D`） | rebase 成功，`assembleProprietaryRelease` 通过 |
| 2026-07-21 | `master` `c894a273`（2026-07-17） | 1.0.0 | `danmaku-jellyfin-v1.0.0.apk`（9.5 MB） | 初始版本 |

> 1.1.0 体积从 9.5 MB 增到 28 MB，是因为上游自 1.8 起引入了直通 ASS 字幕渲染（`libass` + `libffmpegJNI` 等原生库）。

## 跟版步骤

```bash
git fetch upstream --tags
git rebase upstream/master          # 冲突热点见下方
./gradlew :app:assembleProprietaryRelease '-Pjellyfin.version=<新版本号>'
git push --force-with-lease origin danmaku
```

## 编译与签名

- 编译环境：JDK 21（`~/.gradle/jdks` 已缓存）、Android SDK platform 37。
- 产物：`app/build/outputs/apk/proprietary/release/danmaku-jellyfin-v<版本>-proprietary-release.apk`
- 口味：使用 **proprietary**（含 Chromecast 支持），与线上 1.0.0 一致。
- 签名：`keystore/danmaku-release.jks`，证书 SHA-256 `30332af511ffcd2ebd3474eced705b8e5f28729966cd04dc0caca41fd9e33ba7`，可直接覆盖安装。
  ⚠️ 该密钥与密码目前随仓库公开，建议尽快迁移到私密位置并轮换（见项目书 R6）。

## 已知冲突热点

| 文件 | 原因 |
| --- | --- |
| `app/src/main/java/org/jellyfin/mobile/player/PlayerViewModel.kt` | 上游新增 `appPreferences.exoPlayerDirectPlayAss` 等用法，本项目替换了缓冲策略，容易漏掉 import |
| `app/src/main/java/org/jellyfin/mobile/player/ui/PlayerGestureHelper.kt` | 上游在 2026-09 重构了双击涟漪（#2194），本项目移除了该效果，每次跟版都会冲突 |
| `app/src/main/res/layout/fragment_player.xml` | 上游新增 `seek_ripple_overlay`，与本项目的 `danmaku_view` 在同一位置插入 |
| `app/src/main/res/values*/strings.xml` | 上游每周有 Weblate 翻译提交，文案冲突量大但易解 |
