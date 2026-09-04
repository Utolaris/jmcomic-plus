package com.par9uet.jm.ui.viewModel

import com.par9uet.jm.backup.BackupDraft
import com.par9uet.jm.backup.BackupRestoreOperations
import com.par9uet.jm.data.models.LocalSetting
import com.par9uet.jm.store.BACKUP_PROTECTION_BOTH
import com.par9uet.jm.store.BACKUP_PROTECTION_NONE
import com.par9uet.jm.store.BackupContentOptions
import com.par9uet.jm.store.BackupFile
import com.par9uet.jm.store.BackupManager
import com.par9uet.jm.store.ChapterBackup
import com.par9uet.jm.store.ComicCacheBackup
import com.par9uet.jm.store.ComicGroupBackup
import com.par9uet.jm.store.ToastManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackupRestoreViewModelTest {
    private val codec = BackupManager()
    private val group = ComicGroupBackup(12, "comic", emptyList(), emptyList(), listOf(ChapterBackup(13, "chapter", 0)))

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private inner class FakeOperations : BackupRestoreOperations {
        var cache = ComicCacheBackup(listOf(group))
        var backup = codec.parseBackup(codec.createBackup(
            LocalSetting(), cache, BackupContentOptions(true, true), BACKUP_PROTECTION_BOTH, "1234", "0123",
        )).getOrThrow()
        var load: suspend () -> ComicCacheBackup = { cache }
        var read: suspend () -> BackupFile = { backup }
        var writes = mutableListOf<BackupDraft>()
        var restores = mutableListOf<Pair<Boolean, List<ComicGroupBackup>>>()
        override suspend fun loadComicCache() = load()
        override suspend fun write(uri: String, draft: BackupDraft) { writes += draft }
        override suspend fun read(uri: String) = read()
        override suspend fun restore(backup: BackupFile, includeSettings: Boolean, groups: List<ComicGroupBackup>): String {
            restores += includeSettings to groups
            return "已恢复"
        }
    }

    @Test fun `both protections and cache snapshot survive file picker handoff`() = runTest {
        val ops = FakeOperations()
        val vm = BackupRestoreViewModel(ops, codec, ToastManager())
        vm.beginBackup()
        vm.changeContent(BackupContentOptions(true, true))
        vm.confirmContent()
        runCurrent()
        assertEquals(BackupStep.SelectProtection, vm.state.value.backupStep)
        vm.selectProtection(BACKUP_PROTECTION_BOTH)
        vm.setPassword("1234")
        assertEquals(BackupStep.SetPattern, vm.state.value.backupStep)
        assertNull(vm.state.value.createDocumentName)
        vm.setPattern("0123")
        assertTrue(vm.state.value.createDocumentName!!.startsWith("jm-mobile-backup-"))
        vm.documentPickerLaunched()
        vm.beginBackup()
        assertTrue(vm.state.value.awaitingDocument)
        vm.writeDocument("content://backup")
        runCurrent()
        val draft = ops.writes.single()
        assertEquals("1234", draft.password)
        assertEquals("0123", draft.pattern)
        assertEquals(ops.cache, draft.comicCache)
        assertFalse(vm.state.value.busy)
        assertFalse(vm.state.value.awaitingDocument)
    }

    @Test fun `canceling file selection clears protection for the next backup`() = runTest {
        val ops = FakeOperations()
        val vm = BackupRestoreViewModel(ops, codec, ToastManager())
        vm.beginBackup(); vm.confirmContent(); runCurrent()
        vm.selectProtection(BACKUP_PROTECTION_BOTH); vm.setPassword("1234"); vm.setPattern("0123")
        vm.documentPickerLaunched(); vm.writeDocument(null)
        vm.beginBackup(); vm.confirmContent(); runCurrent()
        vm.selectProtection(BACKUP_PROTECTION_NONE); vm.documentPickerLaunched(); vm.writeDocument("content://next")
        runCurrent()
        assertNull(ops.writes.single().password)
        assertNull(ops.writes.single().pattern)
        assertEquals(BACKUP_PROTECTION_NONE, ops.writes.single().protectionType)
    }

    @Test fun `empty cache-only backup stays at content selection`() = runTest {
        val ops = FakeOperations().apply { cache = ComicCacheBackup() }
        val vm = BackupRestoreViewModel(ops, codec, ToastManager())
        vm.beginBackup(); vm.changeContent(BackupContentOptions(false, true)); vm.confirmContent()
        runCurrent()
        assertEquals(BackupStep.SelectContent, vm.state.value.backupStep)
        assertTrue(vm.state.value.contentOptions.isEmpty)
        assertNull(vm.state.value.createDocumentName)
        assertTrue(ops.writes.isEmpty())
    }

    @Test fun `restore requires password then pattern before applying selected contents`() = runTest {
        val ops = FakeOperations()
        val vm = BackupRestoreViewModel(ops, codec, ToastManager())
        assertTrue(vm.beginRestore())
        vm.readDocument("content://backup"); runCurrent()
        vm.selectRestoreContent(BackupContentOptions(true, true))
        vm.restoreSelected(listOf(group)); runCurrent()
        assertTrue(ops.restores.isEmpty())
        assertFalse(vm.verifyPattern("0123"))
        assertFalse(vm.verifyPassword("9999"))
        assertEquals(RestoreStep.VerifyPassword, vm.state.value.restoreStep)
        assertTrue(vm.verifyPassword("1234"))
        assertEquals(RestoreStep.VerifyPattern, vm.state.value.restoreStep)
        assertFalse(vm.verifyPattern("9876"))
        assertTrue(vm.verifyPattern("0123"))
        vm.selectRestoreContent(BackupContentOptions(false, true))
        assertEquals(listOf(group), vm.state.value.restoreGroups)
        vm.restoreSelected(listOf(group)); vm.restoreSelected(listOf(group)); runCurrent()
        assertEquals(listOf(false to listOf(group)), ops.restores)
        assertEquals(RestoreStep.None, vm.state.value.restoreStep)
    }

    @Test fun `skipping cache restores settings without queuing downloads`() = runTest {
        val ops = FakeOperations()
        val vm = BackupRestoreViewModel(ops, codec, ToastManager())
        vm.beginRestore(); vm.readDocument("content://backup"); runCurrent()
        vm.verifyPassword("1234"); vm.verifyPattern("0123")
        vm.selectRestoreContent(BackupContentOptions(true, true)); vm.skipComicCache(); runCurrent()
        assertEquals(listOf(true to emptyList<ComicGroupBackup>()), ops.restores)
    }

    @Test fun `canceled old cache load cannot advance or clear a new operation`() = runTest {
        val old = CompletableDeferred<ComicCacheBackup>()
        val next = CompletableDeferred<ComicCacheBackup>()
        val ops = FakeOperations().apply { load = { withContext(NonCancellable) { old.await() } } }
        val vm = BackupRestoreViewModel(ops, codec, ToastManager())
        vm.beginBackup(); vm.changeContent(BackupContentOptions(true, true)); vm.confirmContent(); runCurrent()
        vm.cancelBackup()
        ops.load = { next.await() }
        vm.beginBackup(); vm.changeContent(BackupContentOptions(false, true)); vm.confirmContent(); runCurrent()
        old.complete(ops.cache); runCurrent()
        assertTrue(vm.state.value.busy)
        assertEquals(BackupStep.SelectContent, vm.state.value.backupStep)
        next.complete(ops.cache); runCurrent()
        assertFalse(vm.state.value.busy)
        assertEquals(BackupStep.SelectProtection, vm.state.value.backupStep)
        assertFalse(vm.state.value.contentOptions.includeLocalSetting)
    }

    @Test fun `read error releases operation so another file can be selected`() = runTest {
        val ops = FakeOperations().apply { read = { error("bad document") } }
        val vm = BackupRestoreViewModel(ops, codec, ToastManager())
        vm.beginRestore(); vm.readDocument("content://bad"); runCurrent()
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.restoreBackup)
        assertTrue(vm.beginRestore())
        ops.read = { ops.backup }
        vm.readDocument("content://good"); runCurrent()
        assertEquals(RestoreStep.VerifyPassword, vm.state.value.restoreStep)
    }
}
