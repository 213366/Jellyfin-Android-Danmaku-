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

> 双击涟漪已交还上游实现（上游 #2194 把涟漪移到独立的 `seek_ripple_overlay`，本项目的移除提交已撤掉），因此 `PlayerGestureHelper.kt` 与上游保持一致。

## 跟版记录

| 日期 | 上游基线 | 产品版本 | 产物 | 结果 |
| --- | --- | --- | --- | --- |
| 2026-10-20 | `upstream/master` `90400b5b`（2026-09-13） | 1.2.1 | `danmaku-jellyfin-v1.2.1-personal.apk`（29.3 MB，`6C184C15A6BF1E3B`）/ `danmaku-jellyfin-v1.2.1-public.apk`（29.3 MB，`29EAB3D8D896BB58`） | 新增 personal/public 构建变体；public 不含 Token 并带首次配置，personal 由 `local.properties` 预注入；两套 release 均编译、签名通过 |
| 2026-10-20 | `upstream/master` `90400b5b`（2026-09-13） | 1.2.0 | `danmaku-jellyfin-v1.2.0.apk`（29.3 MB，SHA-256 前 16 位 `05CFB068894995D9`） | 弹幕源切换为自建 misaka（`match` + `comment`），移除 dandanplay/服务端 XML 分支；`assembleProprietaryRelease` 通过 |
| 2026-09-15 | `upstream/master` `90400b5b`（2026-09-13） | 1.1.1 | `danmaku-jellyfin-v1.1.1.apk`（28.0 MB，SHA-256 前 16 位 `109149D5D0C325C5`） | rebase 成功，`assembleProprietaryRelease` 通过；签名密钥已轮换 |
| 2026-07-21 | `master` `c894a273`（2026-07-17） | 1.0.0 | `danmaku-jellyfin-v1.0.0.apk`（9.5 MB） | 初始版本 |

> 1.1.0 体积从 9.5 MB 增到 28 MB，是因为上游自 1.8 起引入了直通 ASS 字幕渲染（`libass` + `libffmpegJNI` 等原生库）。

## 跟版步骤

```bash
git fetch upstream --tags
git rebase upstream/master          # 冲突热点见下方
./gradlew :app:assembleProprietaryPersonalRelease
./gradlew :app:assembleProprietaryPublicRelease
git push --force-with-lease origin danmaku
```

## 编译与签名

- 编译环境：JDK 21（`~/.gradle/jdks` 已缓存）、Android SDK platform 37。
- 产物：
  - personal：`app/build/outputs/apk/proprietaryPersonal/release/danmaku-jellyfin-v<版本>-proprietary-personal-release.apk`
  - public：`app/build/outputs/apk/proprietaryPublic/release/danmaku-jellyfin-v<版本>-proprietary-public-release.apk`
- 口味：使用 **proprietary**（含 Chromecast 支持），与线上 1.0.0 一致。
- 版本号：`gradle.properties` 的 `jellyfin.version`（当前 `v1.2.1`）。
- 构建变体：
  - `personal`：`applicationId=org.jellyfin.mobile.danmaku`，从 `local.properties` 的 `misaka.api.base`（或环境变量 `MISAKA_API_BASE`）注入 Token。**只在本机使用，不提交、不上传公开 Release。**
  - `public`：`applicationId=org.jellyfin.mobile.danmaku.public`，`MISAKA_API_BASE_URL` 固定为空；首次播放时弹配置框，用户填写自己的 misaka 地址和 Token。可以公开分发。
- Token 安全：`local.properties` 已被 gitignore，Token 不进入源码；personal APK 本身仍包含 Token，不能公开分发。public APK 已用 dex 扫描确认不含 Token。
- 签名：**密钥与密码不在本仓库中**。签名属性（`keystore.file` / `keystore.password` / `signing.key.alias` / `signing.key.password`）放在 `GRADLE_USER_HOME/gradle.properties`（本机为 `%USERPROFILE%\.gradle\gradle.properties`），密钥文件放在仓库外的 `keys/` 目录。
- ⚠️ 2026-09-15 已轮换密钥：**旧的 1.0.0 / 1.1.0 安装包与新版本签名不同，必须先卸载旧版再安装 1.1.1**（会丢失 Jellyfin 登录状态与弹幕匹配记忆）。

## 发布 (Release)

每次跟版出包后，在 GitHub 上发一个 release：

| 项 | 值 |
| --- | --- |
| tag | `v<版本号>`，与 `gradle.properties` 的 `jellyfin.version` 一致 |
| 目标分支 | `danmaku` |
| 标题 | `Danmaku Jellyfin Android v<版本> (弹幕版)` |
| 附件 | **只上传 public 包**：`danmaku-jellyfin-v<版本>-public.apk`；personal 包含 Token，禁止上传 |
| 说明 | 双语，含本版跟版记录；**若签名有变化，必须在说明里写明"先卸载旧版再安装"** |

已发布：`v1.0.0`（2026-07-21，指向 `main`）、`v1.1.1`（2026-09-15，指向 `danmaku`，签名已轮换）。

发布用的是仓库 owner 的 GitHub OAuth 凭据（本机由 Git 凭据管理器保存），调用 REST API 完成；没有安装 `gh` CLI，用 `Invoke-RestMethod` 即可，无需额外配置。

## 已知冲突热点

| 文件 | 原因 |
| --- | --- |
| `app/src/main/java/org/jellyfin/mobile/player/PlayerViewModel.kt` | 上游新增 `appPreferences.exoPlayerDirectPlayAss` 等用法，本项目替换了缓冲策略，容易漏掉 import |
| `app/src/main/res/layout/fragment_player.xml` | 上游新增 `seek_ripple_overlay`，与本项目的 `danmaku_view` 在同一位置插入 |
| `app/src/main/res/values*/strings.xml` | 上游每周有 Weblate 翻译提交，文案冲突量大但易解 |
