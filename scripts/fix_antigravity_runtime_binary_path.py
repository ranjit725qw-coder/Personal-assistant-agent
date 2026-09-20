from pathlib import Path

path = Path("app/src/main/java/com/jarves/mh/runtime/AntigravityProtocol.kt")
text = path.read_text()
old = '    add("/usr/local/bin/agy")\n'
new = '    add(RuntimeInstaller.AGY_GUEST_PATH)\n'
if text.count(old) != 1:
    raise SystemExit(f"Expected one legacy Antigravity path, found {text.count(old)}")
path.write_text(text.replace(old, new, 1))
print("Real tasks now use the same installed Antigravity binary as the successful model test")
