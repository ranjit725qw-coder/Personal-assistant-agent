package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AntigravityProtocolTest {
    @Test
    fun initExposesConversationId() {
        val event = AntigravityProtocolParser.parse(
            """{"event":"init","conversation_id":"conversation-early","init":{"model":"gemini"}}""",
        )
        assertEquals(AntigravityProtocolEvent.Initialized("conversation-early"), event)
    }

    @Test
    fun parsesStreamedResponseDelta() {
        val event = AntigravityProtocolParser.parse(
            """{"event":"step_update","step_update":{"state":"ACTIVE","step_type":"agent_response","text_delta":"hello"}}""",
        )
        assertEquals(AntigravityProtocolEvent.Text("hello"), event)
    }

    @Test
    fun parsesSuccessfulResult() {
        val event = AntigravityProtocolParser.parse(
            """{"event":"result","result":{"conversation_id":"conversation-1","status":"SUCCESS","response":"done"}}""",
        )
        assertEquals(
            AntigravityProtocolEvent.Result("conversation-1", "SUCCESS", "done", null),
            event,
        )
    }

    @Test
    fun mapsOfficialToolEvents() {
        val started = AntigravityProtocolParser.parse(
            """{"event":"step_update","step_update":{"state":"ACTIVE","step_type":"tool","tool_name":"run_command","tool_info":{"name":"run_command","parameters":{"CommandLine":"python3 hello.py"}}}}""",
        )
        val completed = AntigravityProtocolParser.parse(
            """{"event":"step_update","step_update":{"state":"DONE","step_type":"tool","tool_name":"write_to_file","tool_info":{"name":"write_to_file","parameters":{"TargetFile":"/workspace/app/main.py"}}}}""",
        )
        assertEquals(AntigravityProtocolEvent.ToolStarted("Bash", "python3 hello.py"), started)
        assertEquals(AntigravityProtocolEvent.ToolCompleted("Write", "/workspace/app/main.py"), completed)
    }

    @Test
    fun ignoresUnknownAndMalformedEvents() {
        assertNull(AntigravityProtocolParser.parse("not json"))
        assertNull(AntigravityProtocolParser.parse("""{"event":"future_event"}"""))
    }

    @Test
    fun extractsWrappedGooglePkceUrlWithoutTerminalLabels() {
        val output = """
            https://accounts.google.com/o/oauth2/auth?access_type=offline&client_id=client.apps.googleusercontent.com&code_chall
            enge=challenge&code_challenge_method=S256&prompt=consent&state=fresh-state
            Copy and paste the URL or click on the link below
        """.trimIndent()
        val url = extractAntigravityGoogleOAuthUrl(output)
        assertEquals(
            "https://accounts.google.com/o/oauth2/auth?access_type=offline&client_id=client.apps.googleusercontent.com&code_challenge=challenge&code_challenge_method=S256&prompt=consent&state=fresh-state",
            url,
        )
    }

    @Test
    fun commandSeparatesNewAndExistingConversations() {
        val existing = antigravityCommand("gemini-model", "high", "conversation-1")
        assertTrue(existing.containsAll(listOf("--model", "gemini-model", "--conversation", "conversation-1")))
        assertTrue("--effort" !in existing)
        assertTrue("--new-project" !in existing)

        val fresh = antigravityCommand("", "high", null)
        assertTrue(fresh.containsAll(listOf("--new-project", "--effort", "high")))
        assertTrue("--conversation" !in fresh)
    }

    @Test
    fun workspacePromptKeepsFilesInsideMountedProject() {
        val prompt = antigravityWorkspacePrompt("bold-kalam", "Create hello.py")
        assertTrue("/workspace/bold-kalam" in prompt)
        assertTrue("Do not create project output" in prompt)
        assertTrue(prompt.endsWith("Create hello.py"))
    }
}
