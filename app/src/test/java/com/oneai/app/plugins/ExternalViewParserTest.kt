package com.oneai.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalViewParserTest {
    private val manifest = ExternalPluginManifest(
        packageName = "dev.example.notes", versionName = "1", versionCode = 1, name = "Notes", minCoreApi = 1, targetCoreApi = 1,
        capabilities = listOf("files"),
        commands = listOf(ExternalCommandDeclaration("add_note", "Add note", "", "files")),
        bubble = ExternalBubbleDeclaration("notes", "Notes")
    )
    private fun parse(json: String?) = ExternalViewParser.parse(json, manifest)
    private fun valid(vararg extra: String) =
        """{"v":1,"elements":[{"t":"input","key":"text","hint":"Type"},${extra.joinToString(",").ifEmpty { """{"t":"label","text":"Hi"}""" }}]}"""

    @Test fun parsesLabelInputAndButton() {
        val view = parse("""{"v":1,"elements":[
            {"t":"label","text":"Hello","size":18},{"t":"input","key":"text","hint":"Type"},
            {"t":"button","label":"Save","command":"add_note","args":{"text":"@text","mode":"quick","at":"@@home"}}]}""").getOrThrow()
        assertEquals(3, view.elements.size)
        assertEquals(ExternalViewElement.Label("Hello", 18f), view.elements[0])
        val button = view.elements[2] as ExternalViewElement.Button
        assertEquals(ViewArg.FromInput("text"), button.args["text"])
        assertEquals(ViewArg.Literal("quick"), button.args["mode"])
        assertEquals(ViewArg.Literal("@home"), button.args["at"])
        assertEquals(mapOf("text" to "abc", "mode" to "quick", "at" to "@home"), view.resolveArgs(button, mapOf("text" to "abc")))
        assertEquals("", view.resolveArgs(button, emptyMap())["text"])
    }

    @Test fun rejectsMissingOrOversizedOrWrongVersion() {
        assertTrue(parse(null).isFailure)
        assertTrue(parse("").isFailure)
        assertTrue(parse("not json").isFailure)
        assertTrue(parse("""{"v":2,"elements":[]}""").isFailure)
        assertTrue(parse("""{"v":1}""").isFailure)
        assertTrue(parse("""{"v":1,"elements":[{"t":"label","text":"${"x".repeat(ExternalViewParser.MAX_JSON)}"}]}""").isFailure)
    }

    @Test fun rejectsUnknownElementsAndUndeclaredCommands() {
        assertTrue(parse(valid("""{"t":"webview","url":"x"}""")).isFailure)
        assertTrue(parse(valid("""{"t":"button","label":"Go","command":"delete_all"}""")).isFailure)
        assertTrue(parse(valid("""{"t":"button","label":"","command":"add_note"}""")).isFailure)
    }

    @Test fun rejectsBadInputKeysAndReferences() {
        assertTrue(parse("""{"v":1,"elements":[{"t":"input","key":"Bad Key","hint":""}]}""").isFailure)
        assertTrue(parse("""{"v":1,"elements":[{"t":"input","key":"a","hint":""},{"t":"input","key":"a","hint":""}]}""").isFailure)
        assertTrue(parse(valid("""{"t":"button","label":"Go","command":"add_note","args":{"x":"@missing"}}""")).isFailure)
    }

    @Test fun rejectsNonTextAndOversizedArguments() {
        assertTrue(parse(valid("""{"t":"button","label":"Go","command":"add_note","args":{"x":5}}""")).isFailure)
        assertTrue(parse(valid("""{"t":"button","label":"Go","command":"add_note","args":{"x":"${"a".repeat(ExternalViewParser.MAX_ARG_VALUE + 1)}"}}""")).isFailure)
        assertTrue(parse(valid("""{"t":"button","label":"Go","command":"add_note","args":{"bad key!":"v"}}""")).isFailure)
    }

    @Test fun rejectsTooManyElements() {
        val labels = (1..ExternalViewParser.MAX_ELEMENTS + 1).joinToString(",") { """{"t":"label","text":"a"}""" }
        assertTrue(parse("""{"v":1,"elements":[$labels]}""").isFailure)
    }

    @Test fun outOfRangeLabelSizeFallsBackToDefault() {
        val view = parse("""{"v":1,"elements":[{"t":"label","text":"a","size":500}]}""").getOrThrow()
        assertEquals(14f, (view.elements[0] as ExternalViewElement.Label).size)
    }
}
