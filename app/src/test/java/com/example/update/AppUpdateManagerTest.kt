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
        assertEquals("1.0.3", AppUpdateManager.normalizeVersion("v1.0.3-beta.1"))
        assertEquals("1.0.3", AppUpdateManager.normalizeVersion("1.0.3+20260829"))
    }

    @Test
    fun testSameVersionIsNotNewer() {
        assertFalse(AppUpdateManager.isVersionNewer("v1.0.3", "1.0.3"))
        assertFalse(AppUpdateManager.isVersionNewer("1.0.3", "1.0.3"))
        assertFalse(AppUpdateManager.isVersionNewer("1.0.0", "1.0.0"))
        assertFalse(AppUpdateManager.isVersionNewer("v1.0", "1.0.0"))
    }

    @Test
    fun testOlderVersionIsNotNewer() {
        assertFalse(AppUpdateManager.isVersionNewer("v1.0.2", "1.0.3"))
        assertFalse(AppUpdateManager.isVersionNewer("1.0.0", "1.0.3"))
        assertFalse(AppUpdateManager.isVersionNewer("0.9.9", "1.0.0"))
    }

    @Test
    fun testNewerPatchVersionIsNewer() {
        assertTrue(AppUpdateManager.isVersionNewer("v1.0.3", "1.0.2"))
        assertTrue(AppUpdateManager.isVersionNewer("1.0.3", "1.0.2"))
        assertTrue(AppUpdateManager.isVersionNewer("v1.0.10", "1.0.9"))
    }

    @Test
    fun testNewerMinorAndMajorVersion() {
        assertTrue(AppUpdateManager.isVersionNewer("v1.1.0", "1.0.9"))
        assertTrue(AppUpdateManager.isVersionNewer("v2.0.0", "1.9.9"))
    }
}
