package com.excp.podroid.data.repository

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContainerBackupRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun createsMissingBackupDirectoryAndParents() {
        val dir = File(temporaryFolder.root, "Podroid/backups")

        assertTrue(ContainerBackupRepository.ensureBackupDirectory(dir))
        assertTrue(dir.isDirectory)
    }

    @Test
    fun acceptsExistingDirectoryWithoutChangingContents() {
        val dir = temporaryFolder.newFolder("backups")
        val archive = File(dir, "container.tar").apply { writeText("keep") }

        assertTrue(ContainerBackupRepository.ensureBackupDirectory(dir))
        assertEquals("keep", archive.readText())
    }

    @Test
    fun rejectsExistingFileWithoutDeletingIt() {
        val file = temporaryFolder.newFile("backups").apply { writeText("keep") }

        assertFalse(ContainerBackupRepository.ensureBackupDirectory(file))
        assertTrue(file.isFile)
        assertEquals("keep", file.readText())
    }

    @Test
    fun rejectsFileInParentPath() {
        val parent = temporaryFolder.newFile("Podroid").apply { writeText("keep") }

        assertFalse(ContainerBackupRepository.ensureBackupDirectory(File(parent, "backups")))
        assertEquals("keep", parent.readText())
    }
}
