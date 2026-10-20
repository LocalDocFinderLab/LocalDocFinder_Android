package com.example

import com.example.privacy.NetworkGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkGuardTest {

    @Test
    fun allowsDownloadsFromModelAndUpdateHosts() {
        assertNull(NetworkGuard.blockReason("GET", true, "huggingface.co", false))
        assertNull(NetworkGuard.blockReason("GET", true, "cdn-lfs.huggingface.co", false))
        assertNull(NetworkGuard.blockReason("GET", true, "api.github.com", false))
        assertNull(NetworkGuard.blockReason("GET", true, "objects.githubusercontent.com", false))
        assertNull(NetworkGuard.blockReason("HEAD", true, "github.com", false))
    }

    @Test
    fun neverUploadsAnything() {
        assertNotNull(NetworkGuard.blockReason("POST", true, "huggingface.co", true))
        assertNotNull(NetworkGuard.blockReason("PUT", true, "api.github.com", false))
        assertNotNull(NetworkGuard.blockReason("GET", true, "api.github.com", true))
    }

    @Test
    fun refusesPlainHttpAndUnknownHosts() {
        assertNotNull(NetworkGuard.blockReason("GET", false, "huggingface.co", false))
        assertNotNull(NetworkGuard.blockReason("GET", true, "example.com", false))
        assertNotNull(NetworkGuard.blockReason("GET", true, "unpkg.com", false))
        // a look-alike host must not pass the suffix check
        assertFalse(NetworkGuard.isHostAllowed("evilgithub.com"))
        assertFalse(NetworkGuard.isHostAllowed("github.com.evil.io"))
    }

    @Test
    fun aHostTheUserTypedInIsAllowedOnlyAfterTheyDoIt() {
        assertFalse(NetworkGuard.isHostAllowed("updates.my-company.example"))
        NetworkGuard.allowUserHost("Updates.My-Company.example")
        assertTrue(NetworkGuard.isHostAllowed("updates.my-company.example"))
    }
}
