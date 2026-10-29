package io.github.chsbuffer.miuihelper.prefs

import org.junit.Assert.*
import org.junit.Test

class RemotePreferencesPublisherTest {
    private val local = FakeSharedPreferences()
    private val remote = FakeSharedPreferences()
    private val publisher = RemotePreferencesPublisher(local)
    private val ready = "__miuiqol_api101_ready"

    @Test fun disconnectedPublisherDoesNotClaimSuccess() {
        assertFalse(publisher.publish())
        assertFalse(publisher.isSynchronized)
    }

    @Test fun publishesEveryStandardPreferenceTypeAndReadyMarker() {
        local.values.putAll(mapOf("bool" to false, "int" to 12, "long" to 4_000_000_000L,
            "float" to 1.25f, "string" to "保存路径", "set" to linkedSetOf("a", "b")))
        assertTrue(publisher.connect(remote))
        assertEquals(local.values + (ready to true), remote.values)
        assertEquals(1, remote.commitCalls)
        assertTrue(publisher.isSynchronized)
    }

    @Test fun removesDeletedKeysAndStaleRemoteValues() {
        local.values["enabled"] = false
        local.values["deleted"] = "old"
        remote.values["stale"] = 123
        publisher.connect(remote)
        local.values.remove("deleted")
        assertTrue(publisher.publish())
        assertEquals(mapOf("enabled" to false, ready to true), remote.values)
    }

    @Test fun clearingLocalConfigurationClearsRemoteConfiguration() {
        local.values["enabled"] = false
        publisher.connect(remote)
        local.values.clear()
        assertTrue(publisher.publish())
        assertEquals(mapOf(ready to true), remote.values)
    }

    @Test fun failedCommitMustBeRetriedEvenWhenRemoteMemoryAlreadyChanged() {
        local.values["enabled"] = false
        remote.commitResult = false
        assertFalse(publisher.connect(remote))
        assertFalse(publisher.isSynchronized)
        assertFalse(publisher.localStorageFailed)
        remote.commitResult = true
        assertTrue(publisher.publish())
        assertEquals(2, remote.commitCalls)
        assertTrue(publisher.isSynchronized)
    }

    @Test fun serviceExceptionDoesNotEscapeAndCanBeRetried() {
        remote.throwOnCommit = true
        assertFalse(publisher.connect(remote))
        assertFalse(publisher.isSynchronized)
        assertFalse(publisher.localStorageFailed)
        remote.throwOnCommit = false
        assertTrue(publisher.publish())
        assertEquals(2, remote.commitCalls)
    }

    @Test fun failedNewSnapshotInvalidatesAnEarlierSuccessfulSnapshot() {
        local.values["enabled"] = false
        assertTrue(publisher.connect(remote))
        local.values["enabled"] = true
        remote.commitResult = false
        assertFalse(publisher.publish())
        // Android commit(false) may already have updated remote memory. Returning to
        // an earlier local value must still write it back instead of using stale dedup.
        local.values["enabled"] = false
        remote.commitResult = true
        assertTrue(publisher.publish())
        assertEquals(false, remote.values["enabled"])
        assertEquals(3, remote.commitCalls)
    }

    @Test fun localReadFailureDoesNotDestroyRemoteConfiguration() {
        remote.values["enabled"] = false
        local.throwOnRead = true
        assertFalse(publisher.connect(remote))
        assertTrue(publisher.localStorageFailed)
        assertEquals(mapOf("enabled" to false), remote.values)
        assertEquals(0, remote.commitCalls)
        local.throwOnRead = false
        assertTrue(publisher.publish())
        assertFalse(publisher.localStorageFailed)
    }

    @Test fun unchangedSuccessfulSnapshotIsNotCommittedAgain() {
        local.values["enabled"] = false
        publisher.connect(remote)
        assertTrue(publisher.publish())
        assertEquals(1, remote.commitCalls)
    }

    @Test fun changedSnapshotAfterSuccessIsCommitted() {
        local.values["enabled"] = false
        publisher.connect(remote)
        local.values["enabled"] = true
        assertTrue(publisher.publish())
        assertEquals(true, remote.values["enabled"])
        assertEquals(2, remote.commitCalls)
    }

    @Test fun reconnectPublishesEvenWhenTheLocalSnapshotIsUnchanged() {
        local.values["enabled"] = false
        publisher.connect(remote)
        remote.values.clear()
        assertTrue(publisher.connect(remote))
        assertEquals(false, remote.values["enabled"])
        assertEquals(2, remote.commitCalls)
    }

    @Test fun disconnectInvalidatesStatusAndCannotPublish() {
        publisher.connect(remote)
        publisher.disconnect()
        assertFalse(publisher.isSynchronized)
        assertFalse(publisher.publish())
        assertEquals(1, remote.commitCalls)
    }

    @Test fun mutableStringSetDoesNotCorruptPublishedOrRememberedSnapshot() {
        val tags = linkedSetOf("first")
        local.values["tags"] = tags
        publisher.connect(remote)
        tags.add("second")
        assertEquals(setOf("first"), remote.values["tags"])
        assertTrue(publisher.publish())
        assertEquals(setOf("first", "second"), remote.values["tags"])
        assertEquals(2, remote.commitCalls)
    }

    @Test fun unsupportedValuePreventsPartialCommitAndSuccess() {
        local.values["good"] = true
        local.values["unsupported"] = byteArrayOf(1)
        remote.values["existing"] = false
        assertFalse(publisher.connect(remote))
        assertFalse(publisher.isSynchronized)
        assertEquals(mapOf("existing" to false), remote.values)
        assertEquals(0, remote.commitCalls)
    }

    @Test fun nonStringSetPreventsPartialCommit() {
        local.values["invalid"] = setOf(1)
        assertFalse(publisher.connect(remote))
        assertEquals(0, remote.commitCalls)
        assertFalse(publisher.isSynchronized)
    }

    @Test fun internalLocalBookkeepingIsNotPublishedAndCannotOverrideReady() {
        local.values["__miuiqol_migration_complete"] = true
        local.values[ready] = false
        local.values["enabled"] = false
        assertTrue(publisher.connect(remote))
        assertEquals(mapOf("enabled" to false, ready to true), remote.values)
    }

    @Test fun localDiskFailureBlocksRemoteCommit() {
        local.values["enabled"] = false
        local.commitResult = false
        remote.values["existing"] = true
        assertFalse(publisher.connect(remote))
        assertFalse(publisher.isSynchronized)
        assertEquals(mapOf("existing" to true), remote.values)
        assertTrue(publisher.localStorageFailed)
        assertEquals(0, remote.commitCalls)
    }

    @Test fun anEarlierFlushCannotAuthorizeANewerUnsavedSnapshot() {
        local.values["enabled"] = true
        // Model the old adapter's flush occurring before publisher reads its snapshot.
        assertTrue(local.edit().commit())
        local.beforeRead = {
            local.values["enabled"] = false
            local.commitResult = false
        }
        assertFalse(publisher.connect(remote))
        assertTrue(publisher.localStorageFailed)
        assertEquals(0, remote.commitCalls)
        assertEquals(2, local.commitCalls)
    }

    @Test fun localDiskExceptionIsContainedAndRetried() {
        local.values["enabled"] = false
        local.throwOnCommit = true
        assertFalse(publisher.connect(remote))
        assertTrue(publisher.localStorageFailed)
        assertEquals(0, remote.commitCalls)
        local.throwOnCommit = false
        assertTrue(publisher.publish())
        assertFalse(publisher.localStorageFailed)
        assertEquals(false, remote.values["enabled"])
        assertEquals(2, local.commitCalls)
    }

    @Test fun localFailureCannotBeHiddenByAnUnchangedSuccessfulSnapshot() {
        local.values["enabled"] = false
        assertTrue(publisher.connect(remote))
        local.commitResult = false
        assertFalse(publisher.publish())
        assertFalse(publisher.isSynchronized)
        assertTrue(publisher.localStorageFailed)
        assertEquals(1, remote.commitCalls)
        local.commitResult = true
        assertTrue(publisher.publish())
        assertFalse(publisher.localStorageFailed)
        assertEquals(2, remote.commitCalls)
    }

    @Test fun localFailureInvalidatesTheEarlierSuccessfulSnapshotForRetry() {
        local.values["enabled"] = false
        assertTrue(publisher.connect(remote))
        local.values["enabled"] = true
        local.commitResult = false
        assertFalse(publisher.publish())
        local.values["enabled"] = false
        local.commitResult = true
        assertTrue(publisher.publish())
        assertEquals(false, remote.values["enabled"])
        assertEquals(2, remote.commitCalls)
    }

    @Test fun localFailureFlagDoesNotLeakIntoALaterRemoteFailure() {
        local.commitResult = false
        assertFalse(publisher.connect(remote))
        assertTrue(publisher.localStorageFailed)
        local.commitResult = true
        remote.commitResult = false
        assertFalse(publisher.publish())
        assertFalse(publisher.localStorageFailed)
        assertFalse(publisher.isSynchronized)
        remote.commitResult = true
        assertTrue(publisher.publish())
        assertFalse(publisher.localStorageFailed)
    }
}
