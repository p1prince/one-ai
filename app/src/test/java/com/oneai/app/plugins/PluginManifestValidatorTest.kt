package com.oneai.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginManifestValidatorTest {
    private fun manifest(
        id: String = "dev.example.notes",
        version: String = "1.2.0",
        capabilities: List<String> = listOf("commands"),
        dependencies: List<String> = emptyList()
    ) = PluginManifest(
        id=id,name=" Notes ",version=version,description=" A safe descriptor ",
        capabilities=capabilities,dependencies=dependencies
    )

    @Test fun normalizesValidManifest() {
        val result=PluginManifestValidator.validate(manifest())
        assertTrue(result.isSuccess)
        assertEquals("Notes",result.getOrThrow().name)
        assertEquals("dev.example.notes",result.getOrThrow().id)
    }

    @Test fun rejectsUnsupportedManifestVersion() {
        assertTrue(PluginManifestValidator.validate(manifest().copy(manifestVersion=99)).isFailure)
    }

    @Test fun rejectsInvalidIdAndVersion() {
        assertTrue(PluginManifestValidator.validate(manifest(id="notes")).isFailure)
        assertTrue(PluginManifestValidator.validate(manifest(version="latest")).isFailure)
    }

    @Test fun rejectsUnknownOrEmptyCapabilities() {
        assertTrue(PluginManifestValidator.validate(manifest(capabilities=listOf("execute_arbitrary_code"))).isFailure)
        assertTrue(PluginManifestValidator.validate(manifest(capabilities=emptyList())).isFailure)
    }

    @Test fun removesDuplicateCapabilitiesButRejectsDuplicateDependencies() {
        val valid=PluginManifestValidator.validate(manifest(capabilities=listOf("commands","commands"))).getOrThrow()
        assertEquals(listOf("commands"),valid.capabilities)
        assertTrue(PluginManifestValidator.validate(manifest(dependencies=listOf("dev.example.base","dev.example.base"))).isFailure)
    }

    @Test fun rejectsSelfDependencyAndMalformedDependency() {
        assertTrue(PluginManifestValidator.validate(manifest(dependencies=listOf("dev.example.notes"))).isFailure)
        assertFalse(PluginManifestValidator.validate(manifest(dependencies=listOf("bad-id"))).isSuccess)
    }
}
