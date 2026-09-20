from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1))

runtime = "app/src/main/java/com/jarves/mh/runtime/RuntimeInstaller.kt"
replace_once(runtime,
'''        guestWorkspacePath: String = "/workspace",
        emulateHardLinks: Boolean = true,
    ): Process {''',
'''        guestWorkspacePath: String = "/workspace",
        emulateHardLinks: Boolean = true,
        outputFile: File = File(context.cacheDir, "runtime-output-${System.nanoTime()}.log"),
        pseudoTerminal: Boolean = false,
        ptyRows: Int = 40,
        ptyColumns: Int = 120,
    ): Process {''')
replace_once(runtime,
'''            cwd = context.filesDir.absolutePath,
            outputFile = File(context.cacheDir, "runtime-output-${System.nanoTime()}.log"),
        )''',
'''            cwd = context.filesDir.absolutePath,
            outputFile = outputFile,
            pseudoTerminal = pseudoTerminal,
            ptyRows = ptyRows,
            ptyColumns = ptyColumns,
        )''')

bridge = Path("app/src/main/java/com/jarves/mh/runtime/AntigravityRuntimeBridge.kt")
text = bridge.read_text()
marker = "\ninternal fun antigravityCommand("
if marker in text:
    text = text[:text.index(marker)].rstrip() + "\n"
bridge.write_text(text)
