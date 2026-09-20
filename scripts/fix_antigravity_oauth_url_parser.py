from pathlib import Path
p=Path('app/src/main/java/com/jarves/mh/runtime/AntigravityProtocol.kt')
s=p.read_text()
start=s.index('internal fun extractAntigravityGoogleOAuthUrl(output: String): String? {')
end=s.index('\nprivate val URL_CHARACTERS',start)
new=r'''internal fun extractAntigravityGoogleOAuthUrl(output: String): String? {
    // agy's terminal can wrap one URL across multiple lines. Join only adjacent
    // URL fragments without whitespace; stop before prose such as "Copy and paste".
    val lines = output.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    val startIndex = lines.indexOfFirst { "https://accounts.google.com/" in it }
    if (startIndex >= 0) {
        val wrapped = buildString {
            val first = lines[startIndex]
            append(first.substring(first.indexOf("https://accounts.google.com/")))
            for (index in (startIndex + 1) until lines.size) {
                val continuation = lines[index]
                if (continuation.any(Char::isWhitespace) || continuation.startsWith("http")) break
                append(continuation)
            }
        }
        GOOGLE_OAUTH_URL.find(wrapped)?.value
            ?.takeIf { "client_id=" in it && "code_challenge=" in it }
            ?.let { return it }
    }

    val compact = output.replace(Regex("[\\r\\n\\t ]+"), "")
    if (!output.contains("Select login method", true) &&
        listOf("browser", "visit", "open", "code", "paste").any { output.contains(it, true) }
    ) {
        val candidates = Regex("https://[^\\s\"']{20,}")
            .findAll(compact)
            .map { it.value.trimEnd { char -> char !in URL_CHARACTERS } }
            .filter { it.length >= 30 && "." in it }
            .toList()
        return candidates.firstOrNull { "google" in it } ?: candidates.firstOrNull()
    }
    return null
}
'''
p.write_text(s[:start]+new+s[end:])
print('fixed wrapped OAuth URL extraction')
