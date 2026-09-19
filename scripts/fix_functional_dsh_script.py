from pathlib import Path
p = Path('scripts/apply_functional_dsh.py')
text = p.read_text()
start = text.index("replace_once(installer,\n'''        private val CLAUDE_VERSION_PATTERN")
end = text.index("\n\nvm =", start)
replacement = '''p = Path(installer)\ntext = p.read_text()\nif 'const val DSH_VERSION' not in text:\n    anchor = '        private val CORE_BUNDLE'\n    if text.count(anchor) != 1:\n        raise SystemExit('RuntimeInstaller CORE_BUNDLE anchor not found')\n    text = text.replace(anchor, '        const val DSH_VERSION = "0.1.2-rc.1"\\n        private const val DSH_ANDROID_COMPATIBILITY_VERSION = "copyfile-excl-v1"\\n' + anchor, 1)\n    p.write_text(text)'''
p.write_text(text[:start] + replacement + text[end:])
