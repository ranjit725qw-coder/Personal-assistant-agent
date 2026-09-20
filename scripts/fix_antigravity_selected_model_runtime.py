from pathlib import Path

path = Path("app/src/main/java/com/jarves/mh/runtime/AntigravityProtocol.kt")
text = path.read_text()
old = '''    if (model.isNotBlank()) addAll(listOf("--model", model))
    if (conversationId.isNullOrBlank()) {
        add("--new-project")
        if (effort.isNotBlank()) addAll(listOf("--effort", effort))
    } else {
'''
new = '''    if (model.isNotBlank()) addAll(listOf("--model", model))
    if (conversationId.isNullOrBlank()) {
        add("--new-project")
        // Model IDs returned by `agy models` already encode their reasoning
        // level (for example, `-high`). Passing both --model and --effort makes
        // the real task exit with code 1 even though the model probe succeeds.
        if (model.isBlank() && effort.isNotBlank()) addAll(listOf("--effort", effort))
    } else {
'''
if text.count(old) != 1:
    raise SystemExit(f"Expected one command block, found {text.count(old)}")
path.write_text(text.replace(old, new, 1))
print("Aligned real Antigravity tasks with the successful model probe")
