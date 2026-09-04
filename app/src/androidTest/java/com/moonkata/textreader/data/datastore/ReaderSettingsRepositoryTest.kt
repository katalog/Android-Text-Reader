package com.moonkata.textreader.data.datastore

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ReaderSettingsRepository` has only ever been exercised indirectly, through the other sheet
 * tests. Here, the repository itself is the direct target — verifying round-trip storage of
 * representative types (String/Float/Boolean/Int/enum/Set), plus
 * `updateSupabaseSharedSecret`/`updatePcSyncConnection`, which have real conditional logic rather
 * than a simple round trip. Uses the real device's actual DataStore file, so every field touched
 * has its starting value remembered and restored at the end.
 */
@RunWith(AndroidJUnit4::class)
class ReaderSettingsRepositoryTest {

    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val repository = ReaderSettingsRepository(application)
    private lateinit var original: ReaderSettings

    @Before
    fun captureOriginal() {
        original = runBlocking { repository.settingsFlow.first() }
    }

    @After
    fun restoreOriginal() = runBlocking {
        repository.updateFontSizeSp(original.fontSizeSp)
        repository.updateKeepScreenOnEnabled(original.keepScreenOnEnabled)
        repository.updateChapterJumpDivisions(original.chapterJumpDivisions)
        repository.updateThemePreset(original.themePreset)
        repository.updateChapterPatternEnabledIds(original.chapterPatternEnabledIds)
        repository.updateLastUsedSafTreeUri(original.lastUsedSafTreeUri)
        repository.updateSupabaseSharedSecret(original.supabaseSharedSecret, original.supabaseVerifiedSecret)
        restorePcSyncConnection(original)
    }

    private suspend fun restorePcSyncConnection(target: ReaderSettings) {
        repository.updatePcSyncConnection(
            target.pcSyncVerifiedHost, target.pcSyncVerifiedSecret,
            verified = true, fingerprint = target.pcSyncPinnedFingerprint,
        )
        if (target.pcSyncHost != target.pcSyncVerifiedHost || target.pcSyncSecret != target.pcSyncVerifiedSecret) {
            repository.updatePcSyncConnection(target.pcSyncHost, target.pcSyncSecret, verified = false)
        }
    }

    @Test
    fun floatValue_roundTripsThroughDataStore() = runBlocking {
        val target = if (original.fontSizeSp < 30f) original.fontSizeSp + 3f else original.fontSizeSp - 3f
        repository.updateFontSizeSp(target)
        assertEquals(target, repository.settingsFlow.first().fontSizeSp)
    }

    @Test
    fun booleanValue_roundTripsThroughDataStore() = runBlocking {
        val target = !original.keepScreenOnEnabled
        repository.updateKeepScreenOnEnabled(target)
        assertEquals(target, repository.settingsFlow.first().keepScreenOnEnabled)
    }

    @Test
    fun intValue_roundTripsThroughDataStore() = runBlocking {
        val target = original.chapterJumpDivisions + 1
        repository.updateChapterJumpDivisions(target)
        assertEquals(target, repository.settingsFlow.first().chapterJumpDivisions)
    }

    @Test
    fun enumValue_roundTripsThroughDataStore_storedByName() = runBlocking {
        val target = if (original.themePreset == ThemePreset.DARK) ThemePreset.SEPIA else ThemePreset.DARK
        repository.updateThemePreset(target)
        assertEquals(target, repository.settingsFlow.first().themePreset)
    }

    @Test
    fun stringSetValue_roundTripsThroughDataStore() = runBlocking {
        val target = setOf("custom-pattern-1", "custom-pattern-2")
        repository.updateChapterPatternEnabledIds(target)
        assertEquals(target, repository.settingsFlow.first().chapterPatternEnabledIds)
    }

    @Test
    fun nullableStringValue_passingNull_actuallyRemovesTheKey_ratherThanStoringLiteralNull() = runBlocking {
        repository.updateLastUsedSafTreeUri("content://some/tree")
        assertEquals("content://some/tree", repository.settingsFlow.first().lastUsedSafTreeUri)

        repository.updateLastUsedSafTreeUri(null)

        assertNull(
            "Passing null must remove the key itself — safely falls back to the default (null)",
            repository.settingsFlow.first().lastUsedSafTreeUri,
        )
    }

    @Test
    fun updateSupabaseSharedSecret_withoutVerifiedSecret_leavesTheVerifiedFieldUnchanged() = runBlocking {
        repository.updateSupabaseSharedSecret("first-secret", verifiedSecret = "first-secret")
        assertEquals("first-secret", repository.settingsFlow.first().supabaseVerifiedSecret)

        // When verifiedSecret isn't passed (e.g. only the secret was edited but the connection
        // test hasn't been run yet), the verified value must stay unchanged — the "Connected"
        // badge must not incorrectly linger or incorrectly disappear.
        repository.updateSupabaseSharedSecret("second-secret", verifiedSecret = null)

        val settings = repository.settingsFlow.first()
        assertEquals("second-secret", settings.supabaseSharedSecret)
        assertEquals("Calling without verifiedSecret must not change the verified value", "first-secret", settings.supabaseVerifiedSecret)
    }

    @Test
    fun updateSupabaseSharedSecret_withVerifiedSecret_commitsBothInOneCall() = runBlocking {
        repository.updateSupabaseSharedSecret("verified-secret", verifiedSecret = "verified-secret")
        val settings = repository.settingsFlow.first()
        assertEquals("verified-secret", settings.supabaseSharedSecret)
        assertEquals("verified-secret", settings.supabaseVerifiedSecret)
    }

    @Test
    fun updatePcSyncConnection_unverified_leavesVerifiedFieldsAndFingerprintUntouched() = runBlocking {
        repository.updatePcSyncConnection("192.168.0.1", "secret-a", verified = true, fingerprint = "AA:BB")
        val afterVerified = repository.settingsFlow.first()
        assertEquals("192.168.0.1", afterVerified.pcSyncVerifiedHost)
        assertEquals("AA:BB", afterVerified.pcSyncPinnedFingerprint)

        // Only the secret was edited again (the connection test hasn't been rerun yet) — the draft
        // changes, but the verified value/fingerprint must remain at their previous values, so the
        // "Connected" judgment automatically falls out of sync with the draft.
        repository.updatePcSyncConnection("192.168.0.1", "secret-b", verified = false)

        val settings = repository.settingsFlow.first()
        assertEquals("secret-b", settings.pcSyncSecret)
        assertEquals("Unchanged — verified=false must not touch the verified field", "secret-a", settings.pcSyncVerifiedSecret)
        assertEquals("Unchanged — verified=false must not touch the fingerprint either", "AA:BB", settings.pcSyncPinnedFingerprint)
    }

    @Test
    fun updatePcSyncConnection_verifiedWithoutFingerprint_leavesAnyPreviousFingerprintUntouched() = runBlocking {
        repository.updatePcSyncConnection("192.168.0.2", "secret", verified = true, fingerprint = "OLD:FINGERPRINT")
        repository.updatePcSyncConnection("192.168.0.2", "secret", verified = true, fingerprint = null)

        assertEquals(
            "When fingerprint is null (the caller didn't pass one), the previous fingerprint must not be erased",
            "OLD:FINGERPRINT",
            repository.settingsFlow.first().pcSyncPinnedFingerprint,
        )
    }
}
