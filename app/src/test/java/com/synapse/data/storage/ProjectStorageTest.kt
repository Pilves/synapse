package com.synapse.data.storage

import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ProjectStorageTest {

    private lateinit var context: Context
    private lateinit var storage: ProjectStorage

    @Before
    fun setup() = runTest {
        context = RuntimeEnvironment.getApplication()
        storage = ProjectStorage(context)
        storage.initialize()
    }

    @Test
    fun `getProjects returns empty list initially`() = runTest {
        val projects = storage.getProjects()
        assertTrue(projects.isEmpty())
    }

    @Test
    fun `addProject creates project with unique ID`() = runTest {
        val project = storage.addProject("Test", "content://test", "notes.md")

        assertNotNull(project.id)
        assertTrue(project.id.isNotBlank())
        assertEquals("Test", project.name)
        assertEquals("content://test", project.pathUri)
        assertEquals("notes.md", project.defaultFile)
    }

    @Test
    fun `addProject persists to disk`() = runTest {
        storage.addProject("Test", "content://test", "notes.md")

        // Create a new instance to verify persistence
        val storage2 = ProjectStorage(context)
        storage2.initialize()

        val projects = storage2.getProjects()
        assertEquals(1, projects.size)
        assertEquals("Test", projects[0].name)
    }

    @Test
    fun `getProject returns project by ID`() = runTest {
        val created = storage.addProject("Test", "content://test", "notes.md")
        val retrieved = storage.getProject(created.id)

        assertNotNull(retrieved)
        assertEquals(created.id, retrieved!!.id)
        assertEquals("Test", retrieved.name)
    }

    @Test
    fun `getProject returns null for unknown ID`() = runTest {
        assertNull(storage.getProject("nonexistent"))
    }

    @Test
    fun `updateProject modifies existing project`() = runTest {
        val project = storage.addProject("Original", "content://test", "notes.md")
        val updated = project.copy(name = "Updated")
        storage.updateProject(updated)

        val retrieved = storage.getProject(project.id)
        assertEquals("Updated", retrieved!!.name)
    }

    @Test
    fun `deleteProject removes project`() = runTest {
        val project = storage.addProject("Test", "content://test", "notes.md")
        storage.deleteProject(project.id)

        assertNull(storage.getProject(project.id))
        assertTrue(storage.getProjects().isEmpty())
    }

    @Test
    fun `setLastUsedFile updates file tracking`() = runTest {
        val project = storage.addProject("Test", "content://test", "notes.md")
        assertNull(project.lastUsedFile)

        storage.setLastUsedFile(project.id, "recent.md")

        val updated = storage.getProject(project.id)
        assertEquals("recent.md", updated!!.lastUsedFile)
    }

    @Test
    fun `observeProjects emits updates`() = runTest {
        val flow = storage.observeProjects()
        assertTrue(flow.first().isEmpty())

        storage.addProject("Test", "content://test", "notes.md")

        val projects = flow.first()
        assertEquals(1, projects.size)
    }

    @Test
    fun `multiple projects can be added`() = runTest {
        storage.addProject("Project A", "content://a", "a.md")
        storage.addProject("Project B", "content://b", "b.md")
        storage.addProject("Project C", "content://c", "c.md")

        val projects = storage.getProjects()
        assertEquals(3, projects.size)
    }
}
