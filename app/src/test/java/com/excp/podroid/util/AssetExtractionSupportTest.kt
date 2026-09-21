package com.excp.podroid.util

import java.io.File
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AssetExtractionSupportTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun runTasks_reportsExtractionFailureAfterAllTasksFinish() {
        var laterTaskRan = false

        val allSucceeded = AssetExtractionSupport.runTasks(
            listOf(
                { throw IOException("asset read failed") },
                { laterTaskRan = true },
            ),
        )

        assertFalse(allSucceeded)
        assertTrue(laterTaskRan)
    }

    @Test
    fun deleteStaleTemporaryFile_onlyRemovesDestinationSibling() {
        val destination = File(temporaryFolder.root, "assets/vmlinuz-virt").apply {
            parentFile.mkdirs()
        }
        val destinationTemporary = AssetExtractionSupport.temporaryFileFor(destination)
        val unrelatedTemporary = File(temporaryFolder.root, "unrelated.tmp")
        destinationTemporary.writeText("stale")
        unrelatedTemporary.writeText("keep")

        assertTrue(AssetExtractionSupport.deleteStaleTemporaryFile(destination))

        assertFalse(destinationTemporary.exists())
        assertTrue(unrelatedTemporary.exists())
    }

    @Test
    fun deleteStaleTemporaryFile_reportsFailureWithoutThrowing() {
        val destination = File(temporaryFolder.root, "assets/initrd.img").apply {
            parentFile.mkdirs()
        }
        val destinationTemporary = AssetExtractionSupport.temporaryFileFor(destination)
        destinationTemporary.mkdirs()
        File(destinationTemporary, "child").writeText("keep")

        assertFalse(AssetExtractionSupport.deleteStaleTemporaryFile(destination))
        assertTrue(destinationTemporary.exists())
    }
}
