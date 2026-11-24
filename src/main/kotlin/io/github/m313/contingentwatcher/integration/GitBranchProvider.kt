package io.github.m313.contingentwatcher.integration

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import git4idea.config.GitExecutableManager
import git4idea.repo.GitRepositoryManager
import io.github.m313.contingentwatcher.services.BranchInfo
import io.github.m313.contingentwatcher.services.BranchProvider
import java.io.File
import java.util.concurrent.TimeUnit

/** Branch info from the IDE's Git integration (only loaded when Git4Idea is enabled). */
class GitBranchProvider : BranchProvider {
    override fun branchInfo(project: Project, file: VirtualFile?): BranchInfo? {
        val mgr = GitRepositoryManager.getInstance(project)
        val repo = file?.let { mgr.getRepositoryForFileQuick(it) } ?: mgr.repositories.firstOrNull() ?: return null
        return BranchInfo(repo.root.name, repo.currentBranchName ?: "HEAD", repo.root.path, repo.currentRevision)
    }

    override fun commitSubject(project: Project, info: BranchInfo): String? {
        val commit = info.commit ?: return null
        val git = GitExecutableManager.getInstance().getPathToGit(project)
        val proc = ProcessBuilder(git, "log", "-1", "--format=%s", commit)
            .directory(File(info.rootPath))
            .redirectErrorStream(true)
            .start()
        if (!proc.waitFor(5, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return null
        }
        if (proc.exitValue() != 0) return null
        return proc.inputStream.bufferedReader().readText().trim().ifEmpty { null }
    }
}
