package io.github.chsbuffer.miuihelper.prefs

import org.junit.Assert.*
import org.junit.Test

class HookPreferencesTest {
    private fun readyPreferences(): FakeSharedPreferences = FakeSharedPreferences().apply {
        values[HookPreferences.READY] = true
        values["save_to_pictures"] = false
        assertTrue(HookPreferences.attach(this))
    }

    @Test fun emptyGroupIsRejectedWithoutReplacingTheLastReadyGroup() {
        val original = readyPreferences()
        assertFalse(HookPreferences.attach(FakeSharedPreferences()))
        assertSame(original, HookPreferences.preferences)
        assertFalse(HookPreferences.preferences.getBoolean("save_to_pictures", true))
    }

    @Test fun falseReadyMarkerIsRejectedWithoutReplacingTheLastReadyGroup() {
        val original = readyPreferences()
        val unpublished = FakeSharedPreferences().apply { values[HookPreferences.READY] = false }
        assertFalse(HookPreferences.attach(unpublished))
        assertSame(original, HookPreferences.preferences)
    }

    @Test fun wrongTypedReadyMarkersAreRejected() {
        val original = readyPreferences()
        for (marker in listOf("true", 1, 1L, 1.0f, setOf("true"))) {
            val malformed = FakeSharedPreferences().apply { values[HookPreferences.READY] = marker }
            assertFalse("Unexpected marker accepted: $marker", HookPreferences.attach(malformed))
            assertSame(original, HookPreferences.preferences)
        }
    }

    @Test fun trueReadyMarkerSelectsTheActualRemotePreferenceValues() {
        readyPreferences()
        val next = FakeSharedPreferences().apply {
            values[HookPreferences.READY] = true
            values["lock_one_hundred"] = false
        }
        assertTrue(HookPreferences.attach(next))
        assertSame(next, HookPreferences.preferences)
        assertFalse(HookPreferences.preferences.getBoolean("lock_one_hundred", true))
    }

    @Test fun publishedLocalFalseAndDeletedKeyReachTheHookReader() {
        // save_to_pictures is default-on in the upstream hook. A stored false must
        // survive the complete app -> publisher -> framework group -> hook path.
        val local = FakeSharedPreferences().apply { values["save_to_pictures"] = false }
        val framework = FakeSharedPreferences()
        val publisher = RemotePreferencesPublisher(local)
        assertTrue(publisher.connect(framework))
        assertTrue(HookPreferences.attach(framework))
        assertFalse(HookPreferences.preferences.getBoolean("save_to_pictures", true))

        local.values.remove("save_to_pictures")
        assertTrue(publisher.publish())
        assertTrue(HookPreferences.attach(framework))
        assertTrue(HookPreferences.preferences.getBoolean("save_to_pictures", true))
        assertFalse(HookPreferences.preferences.contains("save_to_pictures"))
    }
}
