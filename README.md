# NeuralPulse · 神经律动

把 Loyea 陪伴模式里那颗实时渲染的「Neural Living」神经网模型单拎出来，
接上**系统正在播放的声音**，让它在音乐里呼吸、振动、变色。

## 这是什么

- **模型本体**：`NeuralLivingScene`（135 核心节点 + 610 外壳节点 + 度上限 5 的近邻边、
  28 条三锚点桥接路径、2 组直立轨道弧线、30 尘埃、36 电火花），
  逐行移植自 Loyea 陪伴模式的 `NeuralLiving.kt` / `NeuralLivingCanvas.kt`
  （源头是 `docs/Loyea-Neural-Companion-v2.html` 的内嵌 canvas）。
  同一 LCG 随机种子(91237)，视觉基因完全保留：暖金加性混色、五层呼吸帧、
  漂移场、电火花沿边游走、可拖拽视角。
- **音频律动**（本次新增的全部内容）：
  | 音频特征 | 视觉映射 |
  | --- | --- |
  | 响度 RMS（AGC 归一） | 活跃度（静伴→倾听→思考之外再向上）、呼吸/漂移振幅、整体增辉 |
  | 节拍（谱通量自适应阈值） | 时间流瞬时加速、电火花提速、核心冲击波光环、节点半径脉冲 |
  | BPM（拍间隔中位数 + 倍速折叠） | HUD 实时显示；律动随节拍同步 |
  | 低/中/高频段能量 | 色相偏移（低频暖红、高频亮金）+ HUD 频段条 |
  | 谱重心 | 色相微调的第二输入 |

## 系统音频从哪来

Android 10(API 29)+ 的 `AudioPlaybackCapture`：用户通过 MediaProjection 授权后，
前台服务（`foregroundServiceType="mediaProjection"`）持有
`AudioPlaybackCaptureConfiguration`（USAGE_MEDIA/GAME/UNKNOWN）建的 `AudioRecord`，
浮点 PCM 48kHz → 2048 点汉宁窗 FFT（50% 重叠）→ 响度/频段/谱通量 → 特征快照
经 volatile 总线（`AudioBus`）进渲染帧，零锁、零重组。

任何正在出声的 App（视频、音乐、游戏）都会被律动捕捉；本 App 自己不出声。

## 跑起来

```bash
# Android Studio 直接打开，或：
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

1. 打开 App，点 **● 捕获系统音频**，在系统弹窗里允许投屏（音频捕获）。
2. 随便放点什么（B 站视频、Spotify、Chrome 里的歌）。
3. 看它律动；拖动可换视角。
4. 没有外部音源时点 **▷ 演示脉冲**：内置 120BPM 合成信号走同一条分析管线。

### 联调工具

```bash
# 生成指定 BPM 的测试节拍曲（底鼓+踩镲+贝斯，纯标准库）
python tools/make_test_beat.py --bpm 120 --seconds 90 --out out/beat120.wav

# 模拟器联调：宿主机起个静态服务，模拟器里用 Chrome 打开
#   http://10.0.2.2:8123/player.html  （10.0.2.2 = 宿主机）
python -m http.server 8123 -d out
```

## 工程结构

```
app/src/main/java/com/eddy/neuralpulse/
├── NeuralLiving.kt        # 神经网场景模型（Loyea 原版 + setAudioDrive 钩子）
├── NeuralLivingCanvas.kt  # Compose 加性混色渲染 + 音频响应绘制（色相/冲击波/增辉）
├── MainActivity.kt        # 授权流、HUD（BPM/响度/频段）、演示脉冲
└── audio/
    ├── AudioBus.kt        # 不可变特征快照 + volatile 总线
    ├── AudioAnalyzer.kt   # FFT / 频段 AGC / 节拍 / BPM / 色相
    └── AudioCaptureService.kt  # MediaProjection 前台服务（系统音频流入口）
```

构建链路对齐 Loyea 宿主：Gradle 8.13（腾讯镜像）/ AGP 8.13.2 / Kotlin 1.9.22 /
Compose BOM 2024.02.00 / JDK 17。

## Loyea 忠实度说明

`setAudioDrive(level, beat)` 两参数均为 0 时（无音频、非演示态），
场景逐帧行为与 Loyea 原版**完全一致**——所有音频调制都是乘法增益项，
不改变拓扑、种子、层帧定值与漂移场的任何基础常数。

## Roadmap

- [ ] 频谱驱动的边亮度分层（当前全局）
- [ ] BPM 锁定呼吸周期（当前以节拍冲击近似同步）
- [ ] 直通模式下的自动曝光（更慢的 AGC 峰值回落）
