# 开发规划书构建与排版检查

先用工作区依赖加载器取得 bundled Python 与 documents 技能路径，再通过该 Python 执行 `build_development_plan.py`。默认读取 `docs/DEVELOPMENT-PLAN.md` 并更新现有 Word 文件。

macOS 上运行 documents 的 `render_docx.py` 前，将 `FONTCONFIG_FILE` 设为本目录 `fonts-macos.conf` 的绝对路径。它只为本次进程提供系统字体路径，不修改全局字体配置。规划书使用系统自带的 Arial Unicode MS；不要直接依赖 bundled LibreOffice 自动发现它。

```bash
# DOCUMENT_PYTHON 和 DOCUMENT_RENDERER 为工作区依赖加载器及技能给出的路径。
"$DOCUMENT_PYTHON" scripts/docs/build_development_plan.py
FONTCONFIG_FILE="$PWD/scripts/docs/fonts-macos.conf" \
  "$DOCUMENT_PYTHON" "$DOCUMENT_RENDERER" docs/UniPO-开发规划书-v1.0.docx \
  --output_dir .logs/plan-render --emit_pdf
```

渲染命令成功后，仍须逐页检查 PNG：中文应实际可见，表格完整，跨页标题和页脚不重叠。字体缺失时页数会异常减少，不能把这种结果当作排版通过。PNG 与 PDF 属于内部检查产物，不入库或作为默认交付。
