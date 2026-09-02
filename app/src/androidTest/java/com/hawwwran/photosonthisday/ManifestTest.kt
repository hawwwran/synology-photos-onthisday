package com.hawwwran.photosonthisday

import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Decision 003: allowBackup=false, so no session id leaves the device in a cloud backup. Not run
 * offline this session (no device), but it fails the moment the flag is flipped back on.
 */
@RunWith(AndroidJUnit4::class)
class ManifestTest {
    @Test
    fun backups_are_disabled() {
        val info = ApplicationProvider.getApplicationContext<android.content.Context>().applicationInfo
        assertEquals(0, info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }
}
