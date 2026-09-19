from pathlib import Path
import re

p = Path('app/src/main/java/com/jarves/mh/runtime/DshRuntimeBridge.kt')
text = p.read_text()
text = text.replace('installer.isAgentInstalled(com.jarves.mh.model.AgentKind.DEEPSEEK_HARNESS)', 'installer.isDeepSeekHarnessInstalled()')
text = text.replace('profile.resolvedBaseUrl', "profile.baseUrl.trimEnd('/')")
text = text.replace('profile.dshApi.ifBlank { "anthropic-messages" }', '"anthropic-messages"')
text = re.sub(r'''\n            ProviderKind\.OPENCODE_ZEN -> DshRoute\(\n(?:.*\n){0,8}?            \)''', '', text)
p.write_text(text)

vm = Path('app/src/main/java/com/jarves/mh/ui/MainViewModel.kt')
text = vm.read_text().replace('activeRuntime().proot', 'runtime.proot').replace('activeRuntime().rootfs', 'runtime.rootfs')
vm.write_text(text)
