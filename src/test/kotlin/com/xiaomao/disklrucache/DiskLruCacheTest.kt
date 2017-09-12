package com.xiaomao.disklrucache

import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.*
import java.util.*

class DiskLruCacheTest {
    private val appVersion = 100
    private lateinit var cacheDir: File
    private var journalFile: File? = null
    private var journalBkpFile: File? = null
    private var cache: DiskLruCache? = null

    var tempDir = TemporaryFolder()

    @Before
    @Throws(Exception::class)
    fun setUp() {
        tempDir.create()
        cacheDir = tempDir.newFolder("DiskLruCacheTest")
        journalFile = File(cacheDir, DiskLruCache.JOURNAL_FILE)
        journalBkpFile = File(cacheDir, DiskLruCache.JOURNAL_FILE_BACKUP)
        for (file in cacheDir.listFiles()) {
            file.delete()
        }
        cache = DiskLruCache.open(cacheDir, appVersion, 2, Integer.MAX_VALUE.toLong())
    }

    @After
    @Throws(Exception::class)
    fun tearDown() {
        cache!!.close()
        tempDir.delete()
    }

    @Test
    @Throws(Exception::class)
    fun emptyCache() {
        cache!!.close()
        assertJournalEquals()
    }

    @Test
    @Throws(Exception::class)
    fun validateKey() {
        var key: String? = null
        try {
            key = "has_space "
            cache!!.edit(key)
            Assert.fail("Exepcting an IllegalArgumentException as the key was invalid.")
        } catch (iae: IllegalArgumentException) {
            Assert.assertEquals(iae.message, "keys must match regex [a-z0-9_-]{1,64}: \"" + key + "\"")
        }

        try {
            key = "has_CR\r"
            cache!!.edit(key)
            Assert.fail("Exepcting an IllegalArgumentException as the key was invalid.")
        } catch (iae: IllegalArgumentException) {
            Assert.assertEquals(iae.message, "keys must match regex [a-z0-9_-]{1,64}: \"" + key + "\"")
        }

        try {
            key = "has_LF\n"
            cache!!.edit(key)
            Assert.fail("Exepcting an IllegalArgumentException as the key was invalid.")
        } catch (iae: IllegalArgumentException) {
            Assert.assertEquals(iae.message,
                    "keys must match regex [a-z0-9_-]{1,64}: \"" + key + "\"")
        }

        try {
            key = "has_invalid/"
            cache!!.edit(key)
            Assert.fail("Exepcting an IllegalArgumentException as the key was invalid.")
        } catch (iae: IllegalArgumentException) {
            Assert.assertEquals(iae.message,
                    "keys must match regex [a-z0-9_-]{1,64}: \"" + key + "\"")
        }

        try {
            key = "has_invalid\u2603"
            cache!!.edit(key)
            Assert.fail("Exepcting an IllegalArgumentException as the key was invalid.")
        } catch (iae: IllegalArgumentException) {
            Assert.assertEquals(iae.message,
                    "keys must match regex [a-z0-9_-]{1,64}: \"" + key + "\"")
        }

        try {
            key = "this_is_way_too_long_this_is_way_too_long_this_is_way_too_long_" + "this_is_way_too_long_this_is_way_too_long_this_is_way_too_long"
            cache!!.edit(key)
            Assert.fail("Exepcting an IllegalArgumentException as the key was too long.")
        } catch (iae: IllegalArgumentException) {
            Assert.assertEquals(iae.message,
                    "keys must match regex [a-z0-9_-]{1,64}: \"" + key + "\"")
        }

        // Test valid cases.

        // Exactly 120.
        //key = "0123456789012345678901234567890123456789012345678901234567890123456789" + "01234567890123456789012345678901234567890123456789"
        key = "01234567890123456789012345678901234567890123456789"
        cache!!.edit(key)!!.abort()
        // Contains all valid characters.
        key = "abcdefghijklmnopqrstuvwxyz_0123456789"
        cache!!.edit(key)!!.abort()
        // Contains dash.
        key = "-20384573948576"
        cache!!.edit(key)!!.abort()
    }

    @Test
    @Throws(Exception::class)
    fun writeAndReadEntry() {
        val creator = cache!!.edit("k1")
        creator!![0] = "ABC"
        creator[1] = "DE"
        Assert.assertNull(creator.getString(0))
        Assert.assertNull(creator.newInputStream(0))
        Assert.assertNull(creator.getString(1))
        Assert.assertNull(creator.newInputStream(1))
        creator.commit()

        val snapshot = cache!!["k1"]!!
        Assert.assertEquals(snapshot.getString(0), "ABC")
        Assert.assertEquals(snapshot.getLength(0), 3)
        Assert.assertEquals(snapshot.getString(1), "DE")
        Assert.assertEquals(snapshot.getLength(1), 2)
    }

    @Test
    @Throws(Exception::class)
    fun readAndWriteEntryAcrossCacheOpenAndClose() {
        val creator = cache!!.edit("k1")!!
        creator[0] = "A"
        creator[1] = "B"
        creator.commit()
        cache!!.close()

        cache = DiskLruCache.open(cacheDir, appVersion, 2, Integer.MAX_VALUE.toLong())
        val snapshot = cache!!["k1"]!!
        Assert.assertEquals(snapshot.getString(0), "A")
        Assert.assertEquals(snapshot.getLength(0), 1)
        Assert.assertEquals(snapshot.getString(1), "B")
        Assert.assertEquals(snapshot.getLength(1), 1)
        snapshot.close()
    }

    @Test
    @Throws(Exception::class)
    fun readAndWriteEntryWithoutProperClose() {
        val creator = cache!!.edit("k1")!!
        creator[0] = "A"
        creator[1] = "B"
        creator.commit()

        // Simulate a dirty close of 'cache' by opening the cache directory again.
        val cache2 = DiskLruCache.open(cacheDir, appVersion, 2, Integer.MAX_VALUE.toLong())
        val snapshot = cache2["k1"]!!
        Assert.assertEquals(snapshot.getString(0), "A")
        Assert.assertEquals(snapshot.getLength(0), 1)
        Assert.assertEquals(snapshot.getString(1), "B")
        Assert.assertEquals(snapshot.getLength(1), 1)
        snapshot.close()
        cache2.close()
    }

    @Test
    @Throws(Exception::class)
    fun journalWithEditAndPublish() {
        val creator = cache!!.edit("k1")!!
        assertJournalEquals("DIRTY k1") // DIRTY must always be flushed.
        creator[0] = "AB"
        creator[1] = "C"
        creator.commit()
        cache!!.close()
        assertJournalEquals("DIRTY k1", "CLEAN k1 2 1")
    }

    @Test
    @Throws(Exception::class)
    fun revertedNewFileIsRemoveInJournal() {
        val creator = cache!!.edit("k1")!!
        assertJournalEquals("DIRTY k1") // DIRTY must always be flushed.
        creator[0] = "AB"
        creator[1] = "C"
        creator.abort()
        cache!!.close()
        assertJournalEquals("DIRTY k1", "REMOVE k1")
    }

    @Test
    @Throws(Exception::class)
    fun unterminatedEditIsRevertedOnClose() {
        cache!!.edit("k1")
        cache!!.close()
        assertJournalEquals("DIRTY k1", "REMOVE k1")
    }

    @Test
    @Throws(Exception::class)
    fun journalDoesNotIncludeReadOfYetUnpublishedValue() {
        val creator = cache!!.edit("k1")!!
        Assert.assertNull(cache!!["k1"])
        creator[0] = "A"
        creator[1] = "BC"
        creator.commit()
        cache!!.close()
        assertJournalEquals("DIRTY k1", "CLEAN k1 1 2")
    }

    @Test
    @Throws(Exception::class)
    fun journalWithEditAndPublishAndRead() {
        val k1Creator = cache!!.edit("k1")!!
        k1Creator[0] = "AB"
        k1Creator[1] = "C"
        k1Creator.commit()
        val k2Creator = cache!!.edit("k2")!!
        k2Creator[0] = "DEF"
        k2Creator[1] = "G"
        k2Creator.commit()
        val k1Snapshot = cache!!["k1"]!!
        k1Snapshot.close()
        cache!!.close()
        assertJournalEquals("DIRTY k1", "CLEAN k1 2 1", "DIRTY k2", "CLEAN k2 3 1", "READ k1")
    }

    @Test
    @Throws(Exception::class)
    fun cannotOperateOnEditAfterPublish() {
        val editor = cache!!.edit("k1")!!
        editor[0] = "A"
        editor[1] = "B"
        editor.commit()
        assertInoperable(editor)
    }

    @Test
    @Throws(Exception::class)
    fun cannotOperateOnEditAfterRevert() {
        val editor = cache!!.edit("k1")!!
        editor[0] = "A"
        editor[1] = "B"
        editor.abort()
        assertInoperable(editor)
    }

    @Test
    @Throws(Exception::class)
    fun explicitRemoveAppliedToDiskImmediately() {
        val editor = cache!!.edit("k1")!!
        editor[0] = "ABC"
        editor[1] = "B"
        editor.commit()
        val k1 = getCleanFile("k1", 0)
        Assert.assertEquals(readFile(k1), "ABC")
        cache!!.remove("k1")
        Assert.assertFalse(k1.exists())
    }

    /**
     * Each read sees a snapshot of the file at the time read was called.
     * This means that two reads of the same key can see different data.
     */
    @Test
    @Throws(Exception::class)
    fun readAndWriteOverlapsMaintainConsistency() {
        val v1Creator = cache!!.edit("k1")!!
        v1Creator[0] = "AAaa"
        v1Creator[1] = "BBbb"
        v1Creator.commit()

        val snapshot1 = cache!!["k1"]!!
        val inV1 = snapshot1.getInputStream(0)
        Assert.assertEquals(inV1.read(), 'A'.toInt())
        Assert.assertEquals(inV1.read(), 'A'.toInt())
        Assert.assertEquals(inV1.read(), 'a'.toInt())

        val v1Updater = cache!!.edit("k1")!!
        v1Updater[0] = "CCcc"
        v1Updater[1] = "DDdd"
        v1Updater.commit()

        val snapshot2 = cache!!["k1"]!!
        Assert.assertEquals(snapshot2.getString(0), "CCcc")
        Assert.assertEquals(snapshot2.getLength(0), 4)
        Assert.assertEquals(snapshot2.getString(1), "DDdd")
        Assert.assertEquals(snapshot2.getLength(1), 4)
        snapshot2.close()

        //on windows, file cannot read and write at the same time
        Assert.assertEquals(inV1.read(), 'a')
        Assert.assertEquals(inV1.read(), 'a')
        Assert.assertEquals(snapshot1.getString(1), "BBbb")
        Assert.assertEquals(snapshot1.getLength(1), 4)
        snapshot1.close()
    }

    @Test
    @Throws(Exception::class)
    fun openWithDirtyKeyDeletesAllFilesForThatKey() {
        cache!!.close()
        val cleanFile0 = getCleanFile("k1", 0)
        val cleanFile1 = getCleanFile("k1", 1)
        val dirtyFile0 = getDirtyFile("k1", 0)
        val dirtyFile1 = getDirtyFile("k1", 1)
        writeFile(cleanFile0, "A")
        writeFile(cleanFile1, "B")
        writeFile(dirtyFile0, "C")
        writeFile(dirtyFile1, "D")
        createJournal("CLEAN k1 1 1", "DIRTY   k1")
        cache = DiskLruCache.open(cacheDir, appVersion, 2, Int.MAX_VALUE.toLong())
        Assert.assertFalse(cleanFile0.exists())
        Assert.assertFalse(cleanFile1.exists())
        Assert.assertFalse(dirtyFile0.exists())
        Assert.assertFalse(dirtyFile1.exists())
        Assert.assertNull(cache!!["k1"])
    }

    @Test
    @Throws(Exception::class)
    fun openWithInvalidVersionClearsDirectory() {
        cache!!.close()
        generateSomeGarbageFiles()
        createJournalWithHeader(DiskLruCache.MAGIC, "0", "100", "2", "")
        cache = DiskLruCache.open(cacheDir, appVersion, 2, Integer.MAX_VALUE.toLong())
        assertGarbageFilesAllDeleted()
    }

    @Test
    @Throws(Exception::class)
    fun openWithInvalidAppVersionClearsDirectory() {
        cache!!.close()
        generateSomeGarbageFiles()
        createJournalWithHeader(DiskLruCache.MAGIC, "1", "101", "2", "")
        cache = DiskLruCache.open(cacheDir, appVersion, 2, Integer.MAX_VALUE.toLong())
        assertGarbageFilesAllDeleted()
    }

    @Test
    @Throws(Exception::class)
    fun openWithInvalidValueCountClearsDirectory() {
        cache!!.close()
        generateSomeGarbageFiles()
        createJournalWithHeader(DiskLruCache.MAGIC, "1", "100", "1", "")
        cache = DiskLruCache.open(cacheDir, appVersion, 2, Integer.MAX_VALUE.toLong())
        assertGarbageFilesAllDeleted()
    }

    @Test
    @Throws(Exception::class)
    fun openWithInvalidBlankLineClearsDirectory() {
        cache!!.close()
        generateSomeGarbageFiles()
        createJournalWithHeader(DiskLruCache.MAGIC, "1", "100", "2", "x")
        cache = DiskLruCache.open(cacheDir, appVersion, 2, Integer.MAX_VALUE.toLong())
        assertGarbageFilesAllDeleted()
    }

    @Test
    @Throws(Exception::class)
    fun openWithInvalidJournalLineClearsDirectory() {
        cache!!.close()
        generateSomeGarbageFiles()
        createJournal("CLEAN k1 1 1", "BOGUS")
        cache = DiskLruCache.open(cacheDir, appVersion, 2, Integer.MAX_VALUE.toLong())
        assertGarbageFilesAllDeleted()
        Assert.assertNull(cache!!["k1"])
    }

    @Test
    @Throws(Exception::class)
    fun openWithInvalidFileSizeClearsDirectory() {
        cache!!.close()
        generateSomeGarbageFiles()
        createJournal("CLEAN k1 0000x001 1")
        cache = DiskLruCache.open(cacheDir, appVersion, 2, Integer.MAX_VALUE.toLong())
        assertGarbageFilesAllDeleted()
        Assert.assertNull(cache!!["k1"])
    }

    @Test
    @Throws(Exception::class)
    fun openWithTruncatedLineDiscardsThatLine() {
        cache!!.close()
        writeFile(getCleanFile("k1", 0), "A")
        writeFile(getCleanFile("k1", 1), "B")
        val writer = FileWriter(journalFile)
        writer.write(DiskLruCache.MAGIC + "\n" + DiskLruCache.VERSION_1 + "\n100\n2\n\nCLEAN k1 1 1") // no trailing newline
        writer.close()
        cache = DiskLruCache.open(cacheDir, appVersion, 2, Integer.MAX_VALUE.toLong())
        Assert.assertNull(cache!!["k1"])

        // The journal is not corrupt when editing after a truncated line.
        set("k1", "C", "D")
        cache!!.close()
        cache = DiskLruCache.open(cacheDir, appVersion, 2, Integer.MAX_VALUE.toLong())
        assertValue("k1", "C", "D")
    }

    @Test
    @Throws(Exception::class)
    fun openWithTooManyFileSizesClearsDirectory() {
        cache!!.close()
        generateSomeGarbageFiles()
        createJournal("CLEAN k1 1 1 1")
        cache = DiskLruCache.open(cacheDir, appVersion, 2, Integer.MAX_VALUE.toLong())
        assertGarbageFilesAllDeleted()
        Assert.assertNull(cache!!["k1"])
    }

    @Test
    @Throws(Exception::class)
    fun keyWithSpaceNotPermitted() {
        try {
            cache!!.edit("my key")
            Assert.fail()
        } catch (expected: IllegalArgumentException) {
        }

    }

    @Test
    @Throws(Exception::class)
    fun keyWithNewlineNotPermitted() {
        try {
            cache!!.edit("my\nkey")
            Assert.fail()
        } catch (expected: IllegalArgumentException) {
        }

    }

    @Test
    @Throws(Exception::class)
    fun keyWithCarriageReturnNotPermitted() {
        try {
            cache!!.edit("my\rkey")
            Assert.fail()
        } catch (expected: IllegalArgumentException) {
        }

    }

    @Test
    @Throws(Exception::class)
    fun createNewEntryWithTooFewValuesFails() {
        val creator = cache!!.edit("k1")!!
        creator[1] = "A"
        try {
            creator.commit()
            Assert.fail()
        } catch (expected: IllegalStateException) {
        }

        Assert.assertFalse(getCleanFile("k1", 0).exists())
        Assert.assertFalse(getCleanFile("k1", 1).exists())
        Assert.assertFalse(getDirtyFile("k1", 0).exists())
        Assert.assertFalse(getDirtyFile("k1", 1).exists())
        Assert.assertNull(cache!!["k1"])

        val creator2 = cache!!.edit("k1")!!
        creator2[0] = "B"
        creator2[1] = "C"
        creator2.commit()
    }

    @Test
    @Throws(Exception::class)
    fun revertWithTooFewValues() {
        val creator = cache!!.edit("k1")!!
        creator[1] = "A"
        creator.abort()
        Assert.assertFalse(getCleanFile("k1", 0).exists())
        Assert.assertFalse(getCleanFile("k1", 1).exists())
        Assert.assertFalse(getDirtyFile("k1", 0).exists())
        Assert.assertFalse(getDirtyFile("k1", 1).exists())
        Assert.assertNull(cache!!["k1"])
    }

    @Test
    @Throws(Exception::class)
    fun updateExistingEntryWithTooFewValuesReusesPreviousValues() {
        val creator = cache!!.edit("k1")!!
        creator[0] = "A"
        creator[1] = "B"
        creator.commit()

        val updater = cache!!.edit("k1")!!
        updater[0] = "C"
        updater.commit()

        val snapshot = cache!!["k1"]!!
        Assert.assertEquals(snapshot.getString(0), "C")
        Assert.assertEquals(snapshot.getLength(0), 1)
        Assert.assertEquals(snapshot.getString(1), "B")
        Assert.assertEquals(snapshot.getLength(1), 1)
        snapshot.close()
    }

    @Test
    @Throws(Exception::class)
    fun growMaxSize() {
        cache!!.close()
        cache = DiskLruCache.open(cacheDir, appVersion, 2, 10)
        set("a", "a", "aaa") // size 4
        set("b", "bb", "bbbb") // size 6
        cache!!.setMaxSize(20)
        set("c", "c", "c") // size 12
        Assert.assertEquals(cache!!.size(), 12)
    }

    @Test
    @Throws(Exception::class)
    fun shrinkMaxSizeEvicts() {
        cache!!.close()
        cache = DiskLruCache.open(cacheDir, appVersion, 2, 20)
        set("a", "a", "aaa") // size 4
        set("b", "bb", "bbbb") // size 6
        set("c", "c", "c") // size 12
        cache!!.setMaxSize(10)
        Assert.assertEquals(cache!!.executorService.queue.size, 1)
        cache!!.executorService.purge()
    }

    @Test
    @Throws(Exception::class)
    fun evictOnInsert() {
        cache!!.close()
        cache = DiskLruCache.open(cacheDir, appVersion, 2, 10)

        set("a", "a", "aaa") // size 4
        set("b", "bb", "bbbb") // size 6
        Assert.assertEquals(cache!!.size(), 10)

        // Cause the size to grow to 12 should evict 'A'.
        set("c", "c", "c")
        cache!!.flush()
        Assert.assertEquals(cache!!.size(), 8)
        assertAbsent("a")
        assertValue("b", "bb", "bbbb")
        assertValue("c", "c", "c")

        // Causing the size to grow to 10 should evict nothing.
        set("d", "d", "d")
        cache!!.flush()
        Assert.assertEquals(cache!!.size(), 10)
        assertAbsent("a")
        assertValue("b", "bb", "bbbb")
        assertValue("c", "c", "c")
        assertValue("d", "d", "d")

        // Causing the size to grow to 18 should evict 'B' and 'C'.
        set("e", "eeee", "eeee")
        cache!!.flush()
        Assert.assertEquals(cache!!.size(), 10)
        assertAbsent("a")
        assertAbsent("b")
        assertAbsent("c")
        assertValue("d", "d", "d")
        assertValue("e", "eeee", "eeee")
    }

    @Test
    @Throws(Exception::class)
    fun evictOnUpdate() {
        cache!!.close()
        cache = DiskLruCache.open(cacheDir, appVersion, 2, 10)

        set("a", "a", "aa") // size 3
        set("b", "b", "bb") // size 3
        set("c", "c", "cc") // size 3
        Assert.assertEquals(cache!!.size(), 9)

        // Causing the size to grow to 11 should evict 'A'.
        set("b", "b", "bbbb")
        cache!!.flush()
        Assert.assertEquals(cache!!.size(), 8)
        assertAbsent("a")
        assertValue("b", "b", "bbbb")
        assertValue("c", "c", "cc")
    }

    @Test
    @Throws(Exception::class)
    fun evictionHonorsLruFromCurrentSession() {
        cache!!.close()
        cache = DiskLruCache.open(cacheDir, appVersion, 2, 10)
        set("a", "a", "a")
        set("b", "b", "b")
        set("c", "c", "c")
        set("d", "d", "d")
        set("e", "e", "e")
        cache!!["b"]!!.close() // 'B' is now least recently used.

        // Causing the size to grow to 12 should evict 'A'.
        set("f", "f", "f")
        // Causing the size to grow to 12 should evict 'C'.
        set("g", "g", "g")
        cache!!.flush()
        Assert.assertEquals(cache!!.size(), 10)
        assertAbsent("a")
        assertValue("b", "b", "b")
        assertAbsent("c")
        assertValue("d", "d", "d")
        assertValue("e", "e", "e")
        assertValue("f", "f", "f")
    }

    @Test
    @Throws(Exception::class)
    fun evictionHonorsLruFromPreviousSession() {
        set("a", "a", "a")
        set("b", "b", "b")
        set("c", "c", "c")
        set("d", "d", "d")
        set("e", "e", "e")
        set("f", "f", "f")
        cache!!["b"]!!.close() // 'B' is now least recently used.
        Assert.assertEquals(cache!!.size(), 12)
        cache!!.close()
        cache = DiskLruCache.open(cacheDir, appVersion, 2, 10)

        set("g", "g", "g")
        cache!!.flush()
        Assert.assertEquals(cache!!.size(), 10)
        assertAbsent("a")
        assertValue("b", "b", "b")
        assertAbsent("c")
        assertValue("d", "d", "d")
        assertValue("e", "e", "e")
        assertValue("f", "f", "f")
        assertValue("g", "g", "g")
    }

    @Test
    @Throws(Exception::class)
    fun cacheSingleEntryOfSizeGreaterThanMaxSize() {
        cache!!.close()
        cache = DiskLruCache.open(cacheDir, appVersion, 2, 10)
        set("a", "aaaaa", "aaaaaa") // size=11
        cache!!.flush()
        assertAbsent("a")
    }

    @Test
    @Throws(Exception::class)
    fun cacheSingleValueOfSizeGreaterThanMaxSize() {
        cache!!.close()
        cache = DiskLruCache.open(cacheDir, appVersion, 2, 10)
        set("a", "aaaaaaaaaaa", "a") // size=12
        cache!!.flush()
        assertAbsent("a")
    }

    @Test
    @Throws(Exception::class)
    fun constructorDoesNotAllowZeroCacheSize() {
        try {
            DiskLruCache.open(cacheDir, appVersion, 2, 0)
            Assert.fail()
        } catch (expected: IllegalArgumentException) {
        }

    }

    @Test
    @Throws(Exception::class)
    fun constructorDoesNotAllowZeroValuesPerEntry() {
        try {
            DiskLruCache.open(cacheDir, appVersion, 0, 10)
            Assert.fail()
        } catch (expected: IllegalArgumentException) {
        }

    }

    @Test
    @Throws(Exception::class)
    fun removeAbsentElement() {
        cache!!.remove("a")
    }

    @Test
    @Throws(Exception::class)
    fun readingTheSameStreamMultipleTimes() {
        set("a", "a", "b")
        val snapshot = cache!!["a"]
        Assert.assertEquals(snapshot?.getInputStream(0), snapshot?.getInputStream(0))
        snapshot?.close()
    }

    @Test
    @Throws(Exception::class)
    fun rebuildJournalOnRepeatedReads() {
        set("a", "a", "a")
        set("b", "b", "b")
        var lastJournalLength: Long = 0
        while (true) {
            val journalLength = journalFile!!.length()
            assertValue("a", "a", "a")
            assertValue("b", "b", "b")
            if (journalLength < lastJournalLength) {
                System.out
                        .printf("Journal compacted from %s bytes to %s bytes\n", lastJournalLength,
                                journalLength)
                break // Test passed!
            }
            lastJournalLength = journalLength
        }
    }

    @Test
    @Throws(Exception::class)
    fun rebuildJournalOnRepeatedEdits() {
        var lastJournalLength: Long = 0
        while (true) {
            val journalLength = journalFile!!.length()
            set("a", "a", "a")
            set("b", "b", "b")
            if (journalLength < lastJournalLength) {
                System.out
                        .printf("Journal compacted from %s bytes to %s bytes\n", lastJournalLength,
                                journalLength)
                break
            }
            lastJournalLength = journalLength
        }

        // Sanity check that a rebuilt journal behaves normally.
        assertValue("a", "a", "a")
        assertValue("b", "b", "b")
    }

    /** @see [Issue .28](https://github.com/JakeWharton/DiskLruCache/issues/28)
     */
    @Test
    @Throws(Exception::class)
    fun rebuildJournalOnRepeatedReadsWithOpenAndClose() {
        set("a", "a", "a")
        set("b", "b", "b")
        var lastJournalLength: Long = 0
        while (true) {
            val journalLength = journalFile!!.length()
            assertValue("a", "a", "a")
            assertValue("b", "b", "b")
            cache!!.close()
            cache = DiskLruCache.open(cacheDir, appVersion, 2, Integer.MAX_VALUE.toLong())
            if (journalLength < lastJournalLength) {
                System.out
                        .printf("Journal compacted from %s bytes to %s bytes\n", lastJournalLength,
                                journalLength)
                break // Test passed!
            }
            lastJournalLength = journalLength
        }
    }

    /** @see [Issue .28](https://github.com/JakeWharton/DiskLruCache/issues/28)
     */
    @Test
    @Throws(Exception::class)
    fun rebuildJournalOnRepeatedEditsWithOpenAndClose() {
        var lastJournalLength: Long = 0
        while (true) {
            val journalLength = journalFile!!.length()
            set("a", "a", "a")
            set("b", "b", "b")
            cache!!.close()
            cache = DiskLruCache.open(cacheDir, appVersion, 2, Integer.MAX_VALUE.toLong())
            if (journalLength < lastJournalLength) {
                System.out
                        .printf("Journal compacted from %s bytes to %s bytes\n", lastJournalLength,
                                journalLength)
                break
            }
            lastJournalLength = journalLength
        }
    }

    @Test
    @Throws(Exception::class)
    fun restoreBackupFile() {
        val creator = cache!!.edit("k1")!!
        creator[0] = "ABC"
        creator[1] = "DE"
        creator.commit()
        cache!!.close()

        Assert.assertTrue(journalFile!!.renameTo(journalBkpFile))
        Assert.assertFalse(journalFile!!.exists())

        cache = DiskLruCache.open(cacheDir, appVersion, 2, Integer.MAX_VALUE.toLong())

        val snapshot = cache!!["k1"]!!
        Assert.assertEquals(snapshot.getString(0), "ABC")
        Assert.assertEquals(snapshot.getLength(0), 3)
        Assert.assertEquals(snapshot.getString(1), "DE")
        Assert.assertEquals(snapshot.getLength(1), 2)

        Assert.assertFalse(journalBkpFile!!.exists())
        Assert.assertTrue(journalFile!!.exists())
    }

    @Test
    @Throws(Exception::class)
    fun journalFileIsPreferredOverBackupFile() {
        var creator = cache!!.edit("k1")!!
        creator[0] = "ABC"
        creator[1] = "DE"
        creator.commit()
        cache!!.flush()

        DiskLruCache.renameTo(journalFile!!, journalBkpFile!!, true)

        creator = cache!!.edit("k2")!!
        creator[0] = "F"
        creator[1] = "GH"
        creator.commit()
        cache!!.close()

        Assert.assertTrue(journalFile!!.exists())
        Assert.assertTrue(journalBkpFile!!.exists())

        cache = DiskLruCache.open(cacheDir, appVersion, 2, Integer.MAX_VALUE.toLong())

        val snapshotA = cache!!["k1"]!!
        Assert.assertEquals(snapshotA.getString(0), "ABC")
        Assert.assertEquals(snapshotA.getLength(0), 3)
        Assert.assertEquals(snapshotA.getString(1), "DE")
        Assert.assertEquals(snapshotA.getLength(1), 2)

        val snapshotB = cache!!["k2"]!!
        Assert.assertEquals(snapshotB.getString(0), "F")
        Assert.assertEquals(snapshotB.getLength(0), 1)
        Assert.assertEquals(snapshotB.getString(1), "GH")
        Assert.assertEquals(snapshotB.getLength(1), 2)

        Assert.assertFalse(journalBkpFile!!.exists())
        Assert.assertTrue(journalFile!!.exists())
    }

    @Test
    @Throws(Exception::class)
    fun openCreatesDirectoryIfNecessary() {
        cache!!.close()
        val dir = tempDir.newFolder("testOpenCreatesDirectoryIfNecessary")
        cache = DiskLruCache.open(dir, appVersion, 2, Integer.MAX_VALUE.toLong())
        set("a", "a", "a")
        Assert.assertTrue(File(dir, "a.0").exists())
        Assert.assertTrue(File(dir, "a.1").exists())
        Assert.assertTrue(File(dir, "journal").exists())
    }

    @Test
    @Throws(Exception::class)
    fun fileDeletedExternally() {
        set("a", "a", "a")
        getCleanFile("a", 1).delete()
        Assert.assertNull(cache!!["a"])
    }

    @Test
    @Throws(Exception::class)
    fun editSameVersion() {
        set("a", "a", "a")
        val snapshot = cache!!["a"]!!
        val editor = snapshot.edit()!!
        editor[1] = "a2"
        editor.commit()
        assertValue("a", "a", "a2")
    }

    @Test
    @Throws(Exception::class)
    fun editSnapshotAfterChangeAborted() {
        set("a", "a", "a")
        val snapshot = cache!!["a"]!!
        val toAbort = snapshot.edit()!!
        toAbort[0] = "b"
        toAbort.abort()
        val editor = snapshot.edit()!!
        editor[1] = "a2"
        editor.commit()
        assertValue("a", "a", "a2")
    }

    @Test
    @Throws(Exception::class)
    fun editSnapshotAfterChangeCommitted() {
        set("a", "a", "a")
        val snapshot = cache!!["a"]!!
        val toAbort = snapshot.edit()!!
        toAbort[0] = "b"
        toAbort.commit()
        Assert.assertNull(snapshot.edit())
    }

    @Test
    @Throws(Exception::class)
    fun editSinceEvicted() {
        cache!!.close()
        cache = DiskLruCache.open(cacheDir, appVersion, 2, 10)
        set("a", "aa", "aaa") // size 5
        val snapshot = cache!!["a"]!!
        set("b", "bb", "bbb") // size 5
        set("c", "cc", "ccc") // size 5; will evict 'A'
        cache!!.flush()
        Assert.assertNull(snapshot.edit())
    }

    @Test
    @Throws(Exception::class)
    fun editSinceEvictedAndRecreated() {
        cache!!.close()
        cache = DiskLruCache.open(cacheDir, appVersion, 2, 10)
        set("a", "aa", "aaa") // size 5
        val snapshot = cache!!["a"]!!
        set("b", "bb", "bbb") // size 5
        set("c", "cc", "ccc") // size 5; will evict 'A'
        set("a", "a", "aaaa") // size 5; will evict 'B'
        cache!!.flush()
        Assert.assertNull(snapshot.edit())
    }

    /** @see [Issue .2](https://github.com/JakeWharton/DiskLruCache/issues/2)
     */
    @Test
    @Throws(Exception::class)
    fun aggressiveClearingHandlesWrite() {
        DiskLruCache.deleteContents(cacheDir)
        set("a", "a", "a")
        assertValue("a", "a", "a")
    }

    /** @see [Issue .2](https://github.com/JakeWharton/DiskLruCache/issues/2)
     */
    @Test
    @Throws(Exception::class)
    fun aggressiveClearingHandlesEdit() {
        set("a", "a", "a")
        val a = cache!!["a"]!!.edit()!!
        DiskLruCache.deleteContents(cacheDir)
        a[1] = "a2"
        a.commit()
    }

    @Test
    @Throws(Exception::class)
    fun removeHandlesMissingFile() {
        set("a", "a", "a")
        getCleanFile("a", 0).delete()
        cache!!.remove("a")
    }

    /** @see [Issue .2](https://github.com/JakeWharton/DiskLruCache/issues/2)
     */
    @Test
    @Throws(Exception::class)
    fun aggressiveClearingHandlesPartialEdit() {
        set("a", "a", "a")
        set("b", "b", "b")
        val a = cache!!["a"]!!.edit()!!
        a[0] = "a1"
        DiskLruCache.deleteContents(cacheDir)
        a[1] = "a2"
        a.commit()
        Assert.assertNull(cache!!["a"])
    }

    /** @see [Issue .2](https://github.com/JakeWharton/DiskLruCache/issues/2)
     */
    @Test
    @Throws(Exception::class)
    fun aggressiveClearingHandlesRead() {
        DiskLruCache.deleteContents(cacheDir)
        Assert.assertNull(cache!!["a"])
    }

    @Throws(Exception::class)
    private fun assertJournalEquals(vararg expectedBodyLines: String) {
        val expectedLines = ArrayList<String>()
        expectedLines.add(DiskLruCache.MAGIC)
        expectedLines.add(DiskLruCache.VERSION_1)
        expectedLines.add("100")
        expectedLines.add("2")
        expectedLines.add("")
        expectedLines.addAll(expectedBodyLines)
        Assert.assertEquals(readJournalLines(), expectedLines)
    }

    @Throws(Exception::class)
    private fun createJournal(vararg bodyLines: String) {
        createJournalWithHeader(DiskLruCache.MAGIC, DiskLruCache.VERSION_1, "100", "2", "", *bodyLines)
    }

    @Throws(Exception::class)
    private fun createJournalWithHeader(magic: String, version: String, appVersion: String,
                                        valueCount: String, blank: String, vararg bodyLines: String) {
        val writer = FileWriter(journalFile)
        writer.write(magic + "\n")
        writer.write(version + "\n")
        writer.write(appVersion + "\n")
        writer.write(valueCount + "\n")
        writer.write(blank + "\n")
        for (line in bodyLines) {
            writer.write(line)
            writer.write(charArrayOf('\n'))
        }
        writer.close()
    }

    @Throws(Exception::class)
    private fun readJournalLines(): List<String> {
        val result = ArrayList<String>()
        val reader = BufferedReader(FileReader(journalFile))
        while (reader.readLine()?.apply { result.add(this) } != null) {
        }
        reader.close()
        return result
    }

    private fun getCleanFile(key: String, index: Int): File = File(cacheDir, key + "." + index)

    private fun getDirtyFile(key: String, index: Int): File = File(cacheDir, "$key.$index.tmp")

    @Throws(Exception::class)
    private fun generateSomeGarbageFiles() {
        val dir1 = File(cacheDir, "dir1")
        val dir2 = File(dir1, "dir2")
        writeFile(getCleanFile("g1", 0), "A")
        writeFile(getCleanFile("g1", 1), "B")
        writeFile(getCleanFile("g2", 0), "C")
        writeFile(getCleanFile("g2", 1), "D")
        writeFile(getCleanFile("g2", 1), "D")
        writeFile(File(cacheDir, "otherFile0"), "E")
        dir1.mkdir()
        dir2.mkdir()
        writeFile(File(dir2, "otherFile1"), "F")
    }

    @Throws(Exception::class)
    private fun assertGarbageFilesAllDeleted() {
        Assert.assertFalse(getCleanFile("g1", 0).exists())
        Assert.assertFalse(getCleanFile("g1", 1).exists())
        Assert.assertFalse(getCleanFile("g2", 0).exists())
        Assert.assertFalse(getCleanFile("g2", 1).exists())
        Assert.assertFalse(File(cacheDir, "otherFile0").exists())
        Assert.assertFalse(File(cacheDir, "dir1").exists())
    }

    @Throws(Exception::class)
    private operator fun set(key: String, value0: String, value1: String) {
        val editor = cache!!.edit(key)!!
        editor[0] = value0
        editor[1] = value1
        editor.commit()
    }

    @Throws(Exception::class)
    private fun assertAbsent(key: String) {
        val snapshot = cache!![key]
        if (snapshot != null) {
            snapshot.close()
            Assert.fail()
        }
        Assert.assertFalse(getCleanFile(key, 0).exists())
        Assert.assertFalse(getCleanFile(key, 1).exists())
        Assert.assertFalse(getDirtyFile(key, 0).exists())
        Assert.assertFalse(getDirtyFile(key, 1).exists())
    }

    @Throws(Exception::class)
    private fun assertValue(key: String, value0: String, value1: String) {
        val snapshot = cache!![key]!!
        Assert.assertEquals(snapshot.getString(0), value0)
        Assert.assertEquals(snapshot.getLength(0).toInt(), value0.length)
        Assert.assertEquals(snapshot.getString(1), value1)
        Assert.assertEquals(snapshot.getLength(1).toInt(), value1.length)
        Assert.assertTrue(getCleanFile(key, 0).exists())
        Assert.assertTrue(getCleanFile(key, 1).exists())
        snapshot.close()
    }

    companion object {

        @Throws(Exception::class)
        private fun readFile(file: File): String {
            val reader = FileReader(file)
            val writer = StringWriter()
            val buffer = CharArray(1024)
            while (reader.read(buffer).takeIf { it != -1 }?.apply { writer.write(buffer, 0, this) } != null) {
            }
            reader.close()
            return writer.toString()
        }

        @Throws(Exception::class)
        fun writeFile(file: File, content: String) {
            val writer = FileWriter(file)
            writer.write(content)
            writer.close()
        }

        @Throws(Exception::class)
        private fun assertInoperable(editor: DiskLruCache.Editor) {
            try {
                editor.getString(0)
                Assert.fail()
            } catch (expected: IllegalStateException) {
            }

            try {
                editor[0] = "A"
                Assert.fail()
            } catch (expected: IllegalStateException) {
            }

            try {
                editor.newInputStream(0)
                Assert.fail()
            } catch (expected: IllegalStateException) {
            }

            try {
                editor.newOutputStream(0)
                Assert.fail()
            } catch (expected: IllegalStateException) {
            }

            try {
                editor.commit()
                Assert.fail()
            } catch (expected: IllegalStateException) {
            }

            try {
                editor.abort()
                Assert.fail()
            } catch (expected: IllegalStateException) {
            }

        }
    }
}