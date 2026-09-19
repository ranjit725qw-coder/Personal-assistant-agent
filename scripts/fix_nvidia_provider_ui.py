from pathlib import Path

path = Path("app/src/main/java/com/jarves/mh/ui/PocketDevApp.kt")
text = path.read_text()
replacements = [
    (
        '''        ProviderKind.KIMI -> Color(0xFF8B7CF6)
        ProviderKind.CUSTOM -> PocketOrange''',
        '''        ProviderKind.KIMI -> Color(0xFF8B7CF6)
        ProviderKind.NVIDIA_NIM -> Color(0xFF76B900)
        ProviderKind.CUSTOM -> PocketOrange''',
    ),
    (
        '''        ProviderKind.KIMI -> "K"
        ProviderKind.CUSTOM -> "<>"''',
        '''        ProviderKind.KIMI -> "K"
        ProviderKind.NVIDIA_NIM -> "NIM"
        ProviderKind.CUSTOM -> "<>"''',
    ),
]
for old, new in replacements:
    if new in text:
        continue
    if text.count(old) != 1:
        raise SystemExit(f"Expected one match, found {text.count(old)}: {old!r}")
    text = text.replace(old, new, 1)
path.write_text(text)
