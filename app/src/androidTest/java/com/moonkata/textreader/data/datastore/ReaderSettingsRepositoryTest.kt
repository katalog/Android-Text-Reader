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
 * `ReaderSettingsRepository`는 다른 시트 테스트들을 통해서만 간접적으로 exercise돼 왔다. 여기서는
 * 저장소 자체가 직접 겨냥 대상이다 — 대표적인 타입(String/Float/Boolean/Int/enum/Set)의 왕복 저장과,
 * 단순 왕복이 아니라 실제 조건부 로직이 있는 `updateSupabaseSharedSecret`/`updatePcSyncConnection`을
 * 검증한다. 실기기의 실제 DataStore 파일을 그대로 쓰므로, 건드리는 필드는 전부 시작 전 값을 기억해뒀다가
 * 끝나면 복원한다.
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
            "null을 넘기면 키 자체가 제거돼야 함 — 기본값(null)으로 안전하게 폴백",
            repository.settingsFlow.first().lastUsedSafTreeUri,
        )
    }

    @Test
    fun updateSupabaseSharedSecret_withoutVerifiedSecret_leavesTheVerifiedFieldUnchanged() = runBlocking {
        repository.updateSupabaseSharedSecret("first-secret", verifiedSecret = "first-secret")
        assertEquals("first-secret", repository.settingsFlow.first().supabaseVerifiedSecret)

        // verifiedSecret을 안 넘기면(예: 시크릿만 고치고 아직 연결 테스트는 안 한 상태) 검증된 값은
        // 그대로여야 한다 — "연결됨" 배지가 잘못 남거나 잘못 사라지면 안 되므로.
        repository.updateSupabaseSharedSecret("second-secret", verifiedSecret = null)

        val settings = repository.settingsFlow.first()
        assertEquals("second-secret", settings.supabaseSharedSecret)
        assertEquals("verifiedSecret 없이 부르면 검증된 값은 안 바뀌어야 함", "first-secret", settings.supabaseVerifiedSecret)
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

        // 시크릿만 다시 고쳐본 것(연결 테스트는 아직 다시 안 함) — draft는 바뀌지만 검증된 값/지문은
        // 이전 값 그대로 남아있어야 "연결됨" 판정이 draft와 자동으로 어긋난다.
        repository.updatePcSyncConnection("192.168.0.1", "secret-b", verified = false)

        val settings = repository.settingsFlow.first()
        assertEquals("secret-b", settings.pcSyncSecret)
        assertEquals("변경 안 됨 — verified=false는 검증된 필드를 안 건드림", "secret-a", settings.pcSyncVerifiedSecret)
        assertEquals("변경 안 됨 — verified=false는 지문도 안 건드림", "AA:BB", settings.pcSyncPinnedFingerprint)
    }

    @Test
    fun updatePcSyncConnection_verifiedWithoutFingerprint_leavesAnyPreviousFingerprintUntouched() = runBlocking {
        repository.updatePcSyncConnection("192.168.0.2", "secret", verified = true, fingerprint = "OLD:FINGERPRINT")
        repository.updatePcSyncConnection("192.168.0.2", "secret", verified = true, fingerprint = null)

        assertEquals(
            "fingerprint가 null이면(호출부가 안 넘긴 경우) 이전 지문을 지우면 안 됨",
            "OLD:FINGERPRINT",
            repository.settingsFlow.first().pcSyncPinnedFingerprint,
        )
    }
}
