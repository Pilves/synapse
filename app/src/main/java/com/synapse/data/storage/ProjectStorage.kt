package com.synapse.data.storage

import android.content.Context
import android.util.Log
import com.synapse.model.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Handles persistent storage for project metadata.
 *
 * Projects are stored in a single JSON file:
 * projects/projects.json
 *
 * This class manages the project list and provides reactive updates via Flow.
 */
class ProjectStorage(private val context: Context) {

    companion object {
        private const val TAG = "ProjectStorage"
        private const val PROJECTS_DIR = "projects"
        private const val PROJECTS_FILE = "projects.json"
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val mutex = Mutex()
    private val _projectsFlow = MutableStateFlow<List<Project>>(emptyList())

    private val projectsDir: File
        get() = File(context.filesDir, PROJECTS_DIR).also { it.mkdirs() }

    private val projectsFile: File
        get() = File(projectsDir, PROJECTS_FILE)

    /**
     * Initializes the storage and loads existing projects.
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        loadProjects()
    }

    /**
     * Observes all projects.
     *
     * @return Flow of project list
     */
    fun observeProjects(): Flow<List<Project>> = _projectsFlow.asStateFlow()

    /**
     * Gets all projects.
     *
     * @return List of all projects
     */
    suspend fun getProjects(): List<Project> = withContext(Dispatchers.IO) {
        _projectsFlow.value
    }

    /**
     * Gets a project by ID.
     *
     * @param projectId The project ID
     * @return The project, or null if not found
     */
    suspend fun getProject(projectId: String): Project? = withContext(Dispatchers.IO) {
        _projectsFlow.value.find { it.id == projectId }
    }

    /**
     * Adds a new project.
     *
     * @param name Display name for the project
     * @param pathUri SAF URI to the project directory
     * @param defaultFile Default filename for appending notes
     * @return The created project
     */
    suspend fun addProject(name: String, pathUri: String, defaultFile: String): Project = mutex.withLock {
        val project = Project(
            id = UUID.randomUUID().toString(),
            name = name,
            pathUri = pathUri,
            defaultFile = defaultFile,
            lastUsedFile = null
        )

        val projects = loadProjectsFromDisk().toMutableList()
        projects.add(project)
        saveProjectsToDisk(projects)
        _projectsFlow.value = projects

        Log.d(TAG, "Added project: ${project.id} - ${project.name}")
        project
    }

    /**
     * Updates an existing project.
     *
     * @param project The updated project
     */
    suspend fun updateProject(project: Project) = mutex.withLock {
        val projects = loadProjectsFromDisk().toMutableList()
        val index = projects.indexOfFirst { it.id == project.id }

        if (index != -1) {
            projects[index] = project
            saveProjectsToDisk(projects)
            _projectsFlow.value = projects
            Log.d(TAG, "Updated project: ${project.id}")
        } else {
            Log.w(TAG, "Project not found for update: ${project.id}")
        }
    }

    /**
     * Deletes a project.
     *
     * @param projectId The project ID to delete
     */
    suspend fun deleteProject(projectId: String) = mutex.withLock {
        val projects = loadProjectsFromDisk().toMutableList()
        val removed = projects.removeAll { it.id == projectId }

        if (removed) {
            saveProjectsToDisk(projects)
            _projectsFlow.value = projects
            Log.d(TAG, "Deleted project: $projectId")
        } else {
            Log.w(TAG, "Project not found for deletion: $projectId")
        }
    }

    /**
     * Sets the last used file for a project.
     *
     * @param projectId The project ID
     * @param filename The filename that was last used
     */
    suspend fun setLastUsedFile(projectId: String, filename: String) = mutex.withLock {
        val projects = loadProjectsFromDisk().toMutableList()
        val index = projects.indexOfFirst { it.id == projectId }

        if (index != -1) {
            projects[index] = projects[index].copy(lastUsedFile = filename)
            saveProjectsToDisk(projects)
            _projectsFlow.value = projects
            Log.d(TAG, "Set last used file for project $projectId: $filename")
        }
    }

    private suspend fun loadProjects() {
        val projects = loadProjectsFromDisk()
        _projectsFlow.value = projects
    }

    private fun loadProjectsFromDisk(): List<Project> {
        return try {
            if (!projectsFile.exists()) {
                return emptyList()
            }

            val content = projectsFile.readText()
            val dto = json.decodeFromString<ProjectsDto>(content)
            dto.projects.map { it.toProject() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load projects", e)
            emptyList()
        }
    }

    private fun saveProjectsToDisk(projects: List<Project>) {
        try {
            val dto = ProjectsDto(
                projects = projects.map { ProjectDto.fromProject(it) }
            )
            val tempFile = File(projectsFile.parent, "${projectsFile.name}.tmp")

            // Write to temp file first
            tempFile.writeText(json.encodeToString(dto))

            // Atomic rename
            if (projectsFile.exists()) {
                projectsFile.delete()
            }

            if (!tempFile.renameTo(projectsFile)) {
                tempFile.copyTo(projectsFile, overwrite = true)
                tempFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save projects", e)
        }
    }
}

/**
 * DTO for projects list serialization.
 */
@Serializable
private data class ProjectsDto(
    val projects: List<ProjectDto>
)

/**
 * DTO for project serialization.
 */
@Serializable
private data class ProjectDto(
    val id: String,
    val name: String,
    val pathUri: String,
    val defaultFile: String,
    val lastUsedFile: String? = null
) {
    fun toProject(): Project = Project(
        id = id,
        name = name,
        pathUri = pathUri,
        defaultFile = defaultFile,
        lastUsedFile = lastUsedFile
    )

    companion object {
        fun fromProject(project: Project): ProjectDto = ProjectDto(
            id = project.id,
            name = project.name,
            pathUri = project.pathUri,
            defaultFile = project.defaultFile,
            lastUsedFile = project.lastUsedFile
        )
    }
}
