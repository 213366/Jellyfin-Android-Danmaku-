<h1 align="center">Jellyfin for Android (Danmaku 弹幕版)</h1>

> [!IMPORTANT]
> **弹幕功能需要自行部署 misaka / Danmaku requires a self-hosted Misaka server**
>
> 本分支已停用原版项目的弹弹play API 和 `jellyfin-plugin-danmu` XML 接口，全部改为自建 [misaka](https://github.com/l429609201/misaka_danmu_server)。
>
> - `public` 包不内置任何服务地址或 Token；首次播放时填写自己的 misaka 地址和 Token。
> - `personal` 包由维护者预注入地址和 Token，仅自用，不对外发布。
> - 本项目不提供公共弹幕服务，请自行部署 misaka；地址格式为 `http(s)://<host>[:port][/path]`，客户端会拼上 `/api/v1/{token}`。
> - Releases 只发布 public 包。
>
> **English:** this fork provides no shared danmaku service. Deploy your own Misaka instance. The public build asks for your server URL and token on first playback; the personal build is not distributed.

---

> **增强版 Fork** - 本项目 Fork 自 [jellyfin/jellyfin-android](https://github.com/jellyfin/jellyfin-android)，在原版基础上增加了以下功能：

## 新增功能 / New Features

### 弹幕功能 (Danmaku Support)
视频播放时支持实时弹幕/评论叠加显示，允许用户在观看视频时看到其他观众的实时评论。

### 播放缓冲增强 (Playback Buffer 10-20 min)
增加了 10-20 分钟的播放缓冲，主要解决跨运营商播放时的卡顿问题，让跨网播放更流畅。

---

## 致谢 / Acknowledgments

- 原项目：[jellyfin/jellyfin-android](https://github.com/jellyfin/jellyfin-android)
- 弹幕参考：[Izumiko/jellyfin-danmaku](https://github.com/Izumiko/jellyfin-danmaku)

---

*代码由 [TRAE](https://trae.ai) + DeepSeek V4 Pro 生成*
