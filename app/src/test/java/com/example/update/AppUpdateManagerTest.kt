package com.example.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {

    @Test
    fun testNormalizeVersion() {
        assertEquals("1.0.3", AppUpdateManager.normalizeVersion("v1.0.3"))
        assertEquals("1.0.3", AppUpdateManager.normalizeVersion("V1.0.3"))
        assertEquals("1.0.3", AppUpdateManager.normalizeVersion("1.0.3"))
        assertEquals("1.0.3", AppUpdateManager.normalizeVersion("  v1.0.3  "))
        assertEquals("1.0.3", AppUpdateManager.normalizeVersion("v1.0.3-beta.1"))
        assertEquals("1.0.3", AppUpdateManager.normalizeVersion("1.0.3+20260829"))
        assertEquals("1.0.6", AppUpdateManager.normalizeVersion("v1.0.6-rc2+build.42"))
        assertEquals("", AppUpdateManager.normalizeVersion(""))
        assertEquals("", AppUpdateManager.normalizeVersion("   "))
    }

    @Test
    fun testSameVersionIsNotNewer() {
        assertFalse(AppUpdateManager.isVersionNewer("v1.0.3", "1.0.3"))
        assertFalse(AppUpdateManager.isVersionNewer("1.0.3", "1.0.3"))
        assertFalse(AppUpdateManager.isVersionNewer("1.0.6", "1.0.6"))
        assertFalse(AppUpdateManager.isVersionNewer("v1.0.6", "1.0.6"))
        assertFalse(AppUpdateManager.isVersionNewer("1.0.0", "1.0.0"))
        assertFalse(AppUpdateManager.isVersionNewer("v1.0", "1.0.0"))
        assertFalse(AppUpdateManager.isVersionNewer("1.0.6", "v1.0.6"))
    }

    @Test
    fun testOlderVersionIsNotNewer() {
        assertFalse(AppUpdateManager.isVersionNewer("v1.0.2", "1.0.3"))
        assertFalse(AppUpdateManager.isVersionNewer("1.0.0", "1.0.3"))
        assertFalse(AppUpdateManager.isVersionNewer("0.9.9", "1.0.0"))
        assertFalse(AppUpdateManager.isVersionNewer("1.0.5", "1.0.6"))
        assertFalse(AppUpdateManager.isVersionNewer("v1.0.2", "1.0.6"))
    }

    @Test
    fun testNewerPatchVersionIsNewer() {
        assertTrue(AppUpdateManager.isVersionNewer("v1.0.3", "1.0.2"))
        assertTrue(AppUpdateManager.isVersionNewer("1.0.3", "1.0.2"))
        assertTrue(AppUpdateManager.isVersionNewer("v1.0.6", "1.0.5"))
        assertTrue(AppUpdateManager.isVersionNewer("v1.0.10", "1.0.9"))
        assertTrue(AppUpdateManager.isVersionNewer("1.0.10", "1.0.2"))
    }

    @Test
    fun testNewerMinorAndMajorVersion() {
        assertTrue(AppUpdateManager.isVersionNewer("v1.1.0", "1.0.9"))
        assertTrue(AppUpdateManager.isVersionNewer("v2.0.0", "1.9.9"))
        assertTrue(AppUpdateManager.isVersionNewer("2.0.0", "1.0.6"))
    }

    @Test
    fun testPreviousBugScenario() {
        // Bug: installed was stuck at 1.0.0 while releases were 1.0.2/1.0.3/1.0.6
        assertTrue(AppUpdateManager.isVersionNewer("v1.0.2", "1.0.0"))
        assertTrue(AppUpdateManager.isVersionNewer("v1.0.6", "1.0.0"))

        // Fixed: once installed is 1.0.6, remote 1.0.6 does NOT report update
        assertFalse(AppUpdateManager.isVersionNewer("v1.0.6", "1.0.6"))
        assertFalse(AppUpdateManager.isVersionNewer("1.0.6", "1.0.6"))
    }

    @Test
    fun testEdgeCases() {
        assertFalse(AppUpdateManager.isVersionNewer("", "1.0.0"))
        assertFalse(AppUpdateManager.isVersionNewer("1.0.0", ""))
        assertFalse(AppUpdateManager.isVersionNewer("  ", "  "))
        assertTrue(AppUpdateManager.isVersionNewer("v1.0.6-beta.1", "1.0.5"))
        assertFalse(AppUpdateManager.isVersionNewer("v1.0.5-beta.1", "1.0.5"))
    }
}
