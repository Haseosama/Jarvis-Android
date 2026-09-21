package com.jarvis.android.update

import android.content.pm.PackageInstaller
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateInstallTest {
    private val failures = listOf(
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, PackageInstaller.STATUS_FAILURE_STORAGE, PackageInstaller.STATUS_FAILURE_BLOCKED,
        PackageInstaller.STATUS_FAILURE_ABORTED, PackageInstaller.STATUS_FAILURE_INVALID, PackageInstaller.STATUS_FAILURE_CONFLICT,
    )

    @Test fun `every refusal has its own explanation`() {
        val messages = failures.map { installFailureMessage(it, null) }
        assertTrue(messages.all { it.isNotBlank() })
        assertTrue("two refusals share a message", messages.toSet().size == messages.size)
    }

    @Test fun `a different signature says so`() {
        assertTrue(installFailureMessage(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, null).contains("signé"))
    }

    @Test fun `an unknown failure shows what Android said, or its code`() {
        assertTrue(installFailureMessage(PackageInstaller.STATUS_FAILURE, "INSTALL_FAILED_VERSION_DOWNGRADE").contains("INSTALL_FAILED_VERSION_DOWNGRADE"))
        assertTrue(installFailureMessage(PackageInstaller.STATUS_FAILURE, "").contains(PackageInstaller.STATUS_FAILURE.toString()))
        assertNotEquals(installFailureMessage(PackageInstaller.STATUS_FAILURE, "x"), installFailureMessage(PackageInstaller.STATUS_FAILURE_STORAGE, "x"))
    }
}
