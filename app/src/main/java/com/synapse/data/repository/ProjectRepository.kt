package com.synapse.data.repository

import android.net.Uri
import com.synapse.data.storage.ProjectStorage
import com.synapse.data.storage.VaultManager
import com.synapse.model.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Repository interface for managing projects.
 *
 * Projects represent target directories/files where transcribed notes
 * will be appended. They typically point to Obsidian vaults, markdown
 * files, or other note-taking system directories.
 */
interface ProjectRepository {

    /**
     * Adds a new project.
     *
     * @param name Display name for the project
     * @param pathUri SAF URI to the project directory
     * @param defaultFile Default filename for appending notes
     * @return The created project
     */
    suspend fun addProject(name: String, pathUri: String, defaultFile: String): Project

    /**
     * Gets all projects.
     *
     * @return List of all projects
     */
    suspend fun getProjects(): List<Project>

    /**
     * Gets a project by ID.
     *
     * @param id The project ID
     * @return The project, or null if not found
     */
    suspend fun getProject(id: String): Project?

    /**
     * Updates an existing project.
     *
     * @param project The updated project
     */
    suspend fun updateProject(project: Project)

    /**
     * Deletes a project.
     *
     * This does not delete any synced files, only the project configuration.
     *
     * @param id The project ID to delete
     */
    suspend fun deleteProject(id: String)

    /**
     * Sets the last used file for a project.
     *
     * This tracks which file was last written to, allowing the UI
     * to suggest it as the default for future syncs.
     *
     * @param projectId The project ID
     * @param filename The filename that was last used
     */
    suspend fun setLastUsedFile(projectId: String, filename: String)

    /**
     * Observes all projects.
     *
     * @return Flow of project list
     */
    fun observeProjects(): Flow<List<Project>>

    /**
     * Syncs projects from vault folders.
     * Creates a project for each subfolder in the vault, plus the vault root.
     *
     * @param vaultUri The vault root URI
     */
    suspend fun syncProjectsFromVault(vaultUri: Uri)
}

/**
 * Default implementation of ProjectRepository.
 *
 * Uses ProjectStorage for persistence.
 */
class ProjectRepositoryImpl(
    private val projectStorage: ProjectStorage,
    private val vaultManager: VaultManager
) : ProjectRepository {

    init {
        // Initialize storage to load existing projects from disk
        CoroutineScope(Dispatchers.IO).launch {
            projectStorage.initialize()
        }
    }

    override suspend fun addProject(name: String, pathUri: String, defaultFile: String): Project {
        require(name.isNotBlank()) { "Project name cannot be blank" }
        require(pathUri.isNotBlank()) { "Project path URI cannot be blank" }
        require(defaultFile.isNotBlank()) { "Default file cannot be blank" }

        return projectStorage.addProject(name, pathUri, defaultFile)
    }

    override suspend fun getProjects(): List<Project> {
        return projectStorage.getProjects()
    }

    override suspend fun getProject(id: String): Project? {
        return projectStorage.getProject(id)
    }

    override suspend fun updateProject(project: Project) {
        require(project.name.isNotBlank()) { "Project name cannot be blank" }
        require(project.pathUri.isNotBlank()) { "Project path URI cannot be blank" }
        require(project.defaultFile.isNotBlank()) { "Default file cannot be blank" }

        projectStorage.updateProject(project)
    }

    override suspend fun deleteProject(id: String) {
        projectStorage.deleteProject(id)
    }

    override suspend fun setLastUsedFile(projectId: String, filename: String) {
        require(filename.isNotBlank()) { "Filename cannot be blank" }

        projectStorage.setLastUsedFile(projectId, filename)
    }

    override fun observeProjects(): Flow<List<Project>> {
        return projectStorage.observeProjects()
    }

    override suspend fun syncProjectsFromVault(vaultUri: Uri) {
        // Get existing projects to avoid duplicates
        val existingUris = projectStorage.getProjects().map { it.pathUri }.toSet()

        // Add vault root as a project if not already added
        val vaultUriString = vaultUri.toString()
        if (vaultUriString !in existingUris) {
            projectStorage.addProject(
                name = "Vault Root",
                pathUri = vaultUriString,
                defaultFile = "quick-notes.md"
            )
        }

        // List subfolders and add each as a project
        val folders = vaultManager.listFolders(vaultUri)
        for (folder in folders) {
            val folderUriString = folder.uri.toString()
            if (folderUriString !in existingUris) {
                projectStorage.addProject(
                    name = folder.name,
                    pathUri = folderUriString,
                    defaultFile = "quick-notes.md"
                )
            }
        }
    }
}
