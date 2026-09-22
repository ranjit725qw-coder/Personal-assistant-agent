package com.jarves.mh.ui

internal object GitHubWorkCommands {
    fun snapshot(): String = """
        if [ ! -d .git ]; then
          printf '__REPOSITORY__\nno\n__BRANCH__\n\n__BASE__\nmain\n__STATUS__\n\n__DIFF__\n'
          exit 0
        fi
        base=${'$'}(git symbolic-ref --quiet --short refs/remotes/origin/HEAD 2>/dev/null | sed 's#^origin/##' || true)
        if [ -z "${'$'}base" ]; then
          if git show-ref --verify --quiet refs/remotes/origin/main; then base=main
          elif git show-ref --verify --quiet refs/remotes/origin/master; then base=master
          else base=main; fi
        fi
        printf '__REPOSITORY__\nyes\n__BRANCH__\n%s\n__BASE__\n%s\n__STATUS__\n' "${'$'}(git branch --show-current)" "${'$'}base"
        git status --short
        printf '__DIFF__\n'
        { git diff --stat; git diff --cached --stat; } | awk 'NF && !seen[${'$'}0]++'
    """.trimIndent()

    fun prepareBranch(branchSeed: String): String = """
        set -e
        test -d .git || { echo 'This project is not a Git repository'; exit 2; }
        base=${'$'}(git symbolic-ref --quiet --short refs/remotes/origin/HEAD 2>/dev/null | sed 's#^origin/##' || true)
        if [ -z "${'$'}base" ]; then
          if git show-ref --verify --quiet refs/remotes/origin/main; then base=main
          elif git show-ref --verify --quiet refs/remotes/origin/master; then base=master
          else base=main; fi
        fi
        branch=${'$'}(git branch --show-current)
        case "${'$'}branch" in main|master|"${'$'}base"|'') git switch -c ${quote(branchSeed)} ;; esac
        ${snapshot()}
    """.trimIndent()

    fun publish(baseBranch: String, commitMessage: String, title: String, body: String): String = """
        set -e
        branch=${'$'}(git branch --show-current)
        case "${'$'}branch" in ''|main|master|${quote(baseBranch)}) echo 'Refusing to publish from a protected branch'; exit 3 ;; esac
        git add -A
        if ! git diff --cached --quiet; then git commit -m ${quote(commitMessage)}; fi
        test "${'$'}(git rev-list --count origin/${quote(baseBranch)}..HEAD)" -gt 0 || { echo 'No commits to publish'; exit 4; }
        git push --set-upstream origin HEAD
        gh pr create --base ${quote(baseBranch)} --head "${'$'}branch" --title ${quote(title)} --body ${quote(body)}
    """.trimIndent()

    private fun quote(value: String): String = "'${value.replace("'", "'\\''")}'"
}
