package com.synapse.data.repository

import android.net.Uri
import com.synapse.data.storage.ProjectStorage
import com.synapse.data.storage.VaultManager
import com.synapse.model.Project
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectRepositoryImplTest {

    private lateinit var projectStorage: ProjectStorage
    private lateinit var vaultManager: VaultManager
    private lateinit var repo: ProjectRepositoryImpl

    private val testProject = Project(
        id = "p1",
        name = "Test",
        pathUri = "content://test/path",
        defaultFile = "notes.md"
    )

    @Before
    fun setup() {
        projectStorage = mockk(relaxed = true)
        vaultManager = mockk(relaxed = true)
        repo = ProjectRepositoryImpl(projectStorage, vaultManager)
    }

    // --- addProject ---

    @Test
    fun `addProject delegates to storage with valid inputs`() = runTest {
        coEvery { projectStorage.addProject(any(), any(), any()) } returns testProject

        val result = repo.addProject("Test", "content://test/path", "notes.md")
        assertEquals("p1", result.id)
        coVerify { projectStorage.addProject("Test", "content://test/path", "notes.md") }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `addProject rejects blank name`() = runTest {
        repo.addProject("", "content://path", "notes.md")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `addProject rejects blank pathUri`() = runTest {
        repo.addProject("Test", "", "notes.md")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `addProject rejects blank defaultFile`() = runTest {
        repo.addProject("Test", "content://path", "")
    }

    // --- getProjects / getProject ---

    @Test
    fun `getProjects delegates to storage`() = runTest {
        coEvery { projectStorage.getProjects() } returns listOf(testProject)

        val result = repo.getProjects()
        assertEquals(1, result.size)
        assertEquals("p1", result[0].id)
    }

    @Test
    fun `getProject returns project when found`() = runTest {
        coEvery { projectStorage.getProject("p1") } returns testProject

        val result = repo.getProject("p1")
        assertEquals("p1", result?.id)
    }

    @Test
    fun `getProject returns null when not found`() = runTest {
        coEvery { projectStorage.getProject("missing") } returns null

        assertNull(repo.getProject("missing"))
    }

    // --- updateProject ---

    @Test
    fun `updateProject delegates to storage`() = runTest {
        repo.updateProject(testProject)
        coVerify { projectStorage.updateProject(testProject) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `updateProject rejects blank name`() = runTest {
        repo.updateProject(testProject.copy(name = ""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `updateProject rejects blank pathUri`() = runTest {
        repo.updateProject(testProject.copy(pathUri = ""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `updateProject rejects blank defaultFile`() = runTest {
        repo.updateProject(testProject.copy(defaultFile = ""))
    }

    // --- deleteProject ---

    @Test
    fun `deleteProject delegates to storage`() = runTest {
        repo.deleteProject("p1")
        coVerify { projectStorage.deleteProject("p1") }
    }

    // --- setLastUsedFile ---

    @Test
    fun `setLastUsedFile delegates to storage`() = runTest {
        repo.setLastUsedFile("p1", "my-notes.md")
        coVerify { projectStorage.setLastUsedFile("p1", "my-notes.md") }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `setLastUsedFile rejects blank filename`() = runTest {
        repo.setLastUsedFile("p1", "")
    }

    // --- observeProjects ---

    @Test
    fun `observeProjects delegates to storage`() {
        every { projectStorage.observeProjects() } returns flowOf(listOf(testProject))

        val flow = repo.observeProjects()
        // Verifies the flow is wired through
        coVerify { projectStorage.observeProjects() }
    }
}
