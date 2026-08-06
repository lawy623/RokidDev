# RokidDev 参考仓库索引

这里记录 RokidDev 可用于技术调研的外部仓库，以及对应的本地参考资料。
参考仓库不是项目运行时依赖；引用其中的实现前，需要检查许可证、上游最新代码
和当前 Rokid 固件上的实际行为。

## awesome-rokid

- 上游：`Anezium/awesome-rokid`
- 本地快照：`.docs/references/awesome-rokid/README.md`
- 元数据与刷新说明：`.docs/references/awesome-rokid/SOURCE_METADATA.md`
- 用途：作为 Rokid 生态目录，按 AI/助手、AIUI、输入设备、桥接、终端、TTS、
  开发工具等方向发现相近项目；它本身主要是项目索引，不是所有项目源码的集合。
- 当前快照 commit：`934394a95a345f679f1a487205d733a9c4063640`

### 与 RokidTerm 当前方向优先相关的条目

以下条目来自快照 README，适合在后续实现和交互测试前优先检查：

- **Rokid Claude**：眼镜控制 Mac 上 Claude Code，包含语音输入、WebSocket relay、
  流式 agent 进度和眼镜端权限确认。
- **rode**：眼镜录音/HUD、后端、whisper.cpp、可插拔 AI 以及 SSE 回传。
- **Rokid-AIUI / rokid-aiui-lab**：AIUI `.aix` 项目结构、调试、能力探测和实验参考。
- **RokidKeyboard**：手机键盘/触摸板与眼镜端 BLE 接收器的输入桥接参考。
- **R08-Access-Bridge**：R08 智能戒指作为 Rokid 控制器的桥接参考。
- **rokid-ssh-terminal**：终端类应用和 SSH/HUD 交互的候选参考。
- **rokid-private-tts-kit**：Rokid 私有 TTS binder 的 Android 客户端与最小测试应用。
- **client-glasses / client-phone**：眼镜端和手机端服务、触摸板、文件同步、
  WebSocket、音频等底层/桥接方向的研究入口。

条目名称和链接的完整列表以本地快照为准；实际调研时直接在
`.docs/references/awesome-rokid/README.md` 中搜索项目名、`AIUI`、`ASR`、`voice`、
`keyboard`、`ring`、`terminal`、`SSE`、`WebSocket`、`TTS`、`CXR` 等关键词。

## 未来新增参考仓库

新增仓库时，优先采用以下结构：

```text
.docs/references/<repo-name>/README.md
.docs/references/<repo-name>/SOURCE_METADATA.md
```

同时在本索引中记录上游 URL、用途、当前 commit 和本地快照路径。除非项目已经
明确采用 Git submodule 规范，否则不要把外部仓库的 `.git` 目录嵌入 RokidDev。
