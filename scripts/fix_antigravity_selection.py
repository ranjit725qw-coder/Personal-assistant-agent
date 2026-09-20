from pathlib import Path
p = Path("app/src/main/java/com/jarves/mh/runtime/AntigravityRuntimeBridge.kt")
text = p.read_text()
old = """                    addAntigravitySelection(model(), effort())
                    add(\"--new-project\")"""
new = """                    val selectedModel = model()
                    val selectedEffort = effort()
                    if (selectedModel.isNotBlank()) {
                        addAll(listOf(\"--model\", selectedModel))
                    } else if (selectedEffort in setOf(\"low\", \"medium\", \"high\")) {
                        addAll(listOf(\"--effort\", selectedEffort))
                    }
                    add(\"--new-project\")"""
count = text.count(old)
if count != 1:
    raise SystemExit(f"expected one selection call, found {count}")
p.write_text(text.replace(old, new, 1))
