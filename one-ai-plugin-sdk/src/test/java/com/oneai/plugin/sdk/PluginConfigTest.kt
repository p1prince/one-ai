package com.oneai.plugin.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plugin API level 3: the bounds a plugin applies to a settings snapshot, and the holder that keeps the latest valid one. */
class PluginConfigTest {
    private fun parse(version: Int = 1, vararg pairs: Pair<String, String>) =
        PluginBounds.parseConfig(version, pairs.map { it.first }, pairs.map { it.second })

    @Test fun acceptsAValidSnapshotAndExposesTypedValues() {
        val config = parse(1, "greeting_prefix" to "Hi", "loud" to "true", "repeat" to "3").getOrThrow()
        assertEquals(1, config.schemaVersion)
        assertEquals("Hi", config.string("greeting_prefix"))
        assertTrue(config.boolean("loud"))
        assertEquals(3, config.int("repeat"))
        assertEquals("fallback for a missing key", "x", config.string("missing", "x"))
        assertFalse(config.boolean("missing"))
        assertEquals(7, config.int("missing", 7))
        assertNull(config["missing"])
    }

    @Test fun badTypedValuesFallBackInsteadOfThrowing() {
        val config = parse(1, "loud" to "maybe", "repeat" to "many").getOrThrow()
        assertTrue(config.boolean("loud", true))
        assertEquals(2, config.int("repeat", 2))
    }

    @Test fun rejectsMalformedOrOutOfBoundsSnapshotsWhole() {
        assertTrue(parse(0, "a" to "b").isFailure)
        assertTrue(PluginBounds.parseConfig(1, listOf("a"), listOf("b", "c")).isFailure)
        assertTrue(PluginBounds.parseConfig(1, null, null).isFailure)
        assertTrue(PluginBounds.parseConfig(1, listOf("a", "a"), listOf("1", "2")).isFailure)
        assertTrue("uppercase key", parse(1, "Bad" to "x").isFailure)
        assertTrue("key starting with a digit", parse(1, "1bad" to "x").isFailure)
        assertTrue("key too long", parse(1, ("a" + "b".repeat(40)) to "x").isFailure)
        assertTrue("value too long", parse(1, "a" to "x".repeat(PluginBounds.MAX_CONFIG_VALUE + 1)).isFailure)
        assertTrue(parse(1, "a" to "x".repeat(PluginBounds.MAX_CONFIG_VALUE)).isSuccess)
    }

    @Test fun rejectsMoreThanTheMaximumNumberOfSettings() {
        val keys = (1..PluginBounds.MAX_CONFIG_ENTRIES + 1).map { "key$it" }
        assertTrue(PluginBounds.parseConfig(1, keys, keys.map { "v" }).isFailure)
        assertTrue(PluginBounds.parseConfig(1, keys.dropLast(1), keys.dropLast(1).map { "v" }).isSuccess)
    }

    @Test fun rejectsASnapshotOverTheByteLimit() {
        // 24 settings x ~400 bytes is over 8 KB although each value is within its own limit.
        val keys = (1..PluginBounds.MAX_CONFIG_ENTRIES).map { "key$it" }
        assertTrue(PluginBounds.parseConfig(1, keys, keys.map { "x".repeat(400) }).isFailure)
    }

    @Test fun theHolderKeepsThePreviousSnapshotWhenANewOneIsRejected() {
        val holder = PluginConfigHolder()
        assertSame("nothing delivered yet", PluginConfig.EMPTY, holder.current)
        assertTrue(holder.apply(1, listOf("mode"), listOf("plain")).isSuccess)
        assertEquals("plain", holder.current.string("mode"))
        assertTrue(holder.apply(1, listOf("mode"), listOf("x".repeat(PluginBounds.MAX_CONFIG_VALUE + 1))).isFailure)
        assertEquals("a rejected snapshot changes nothing", "plain", holder.current.string("mode"))
        assertTrue(holder.apply(1, listOf("mode"), listOf("fancy")).isSuccess)
        assertEquals("fancy", holder.current.string("mode"))
    }

    @Test fun theConfigurationSnapshotIsImmutableToCallers() {
        val source = linkedMapOf("mode" to "plain")
        val config = PluginConfig(1, source)
        source["mode"] = "changed"
        assertEquals("plain", config.string("mode"))
        val copy = config.all as MutableMap<String, String>
        copy["mode"] = "tampered"
        assertEquals("plain", config.string("mode"))
    }

    @Test fun configCapacityMatchesTheHostSchemaLimits() {
        assertEquals(24, PluginBounds.MAX_CONFIG_ENTRIES)
        assertEquals(500, PluginBounds.MAX_CONFIG_VALUE)
        assertEquals(8 * 1024, PluginBounds.MAX_CONFIG_SNAPSHOT_BYTES)
        assertEquals(3, PluginBounds.CONFIG_MIN_API)
    }
}
