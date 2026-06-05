package io.github.takahirom.arbigent

import xcuitest.installer.LocalXCTestInstaller
import java.io.File
import java.net.URI
import java.nio.file.FileSystem
import java.nio.file.FileSystemAlreadyExistsException
import java.nio.file.FileSystemNotFoundException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

internal fun materializeIosDriverResource(sourceDirectory: String): File {
  val target = File(ArbigentFiles.parentDir, sourceDirectory)
  if (target.exists()) {
    target.deleteRecursively()
  }
  target.mkdirs()

  val uri = LocalXCTestInstaller::class.java.classLoader.getResource(sourceDirectory)?.toURI()
    ?: throw IllegalArgumentException("Resource not found: $sourceDirectory")
  val sourcePath = uri.toResourcePath(sourceDirectory)

  Files.walk(sourcePath).use { paths ->
    paths
      .filter { Files.isRegularFile(it) }
      .forEach { file ->
        val relative = sourcePath.relativize(file)
        val targetPath = target.toPath().resolve(relative.toString())
        Files.createDirectories(targetPath.parent)
        Files.copy(file, targetPath, StandardCopyOption.REPLACE_EXISTING)
      }
  }

  return target
}

private fun URI.toResourcePath(sourceDirectory: String): Path {
  return if (scheme == "jar") {
    getOrCreateFileSystem().getPath(sourceDirectory)
  } else {
    Paths.get(this)
  }
}

private fun URI.getOrCreateFileSystem(): FileSystem {
  return try {
    FileSystems.getFileSystem(this)
  } catch (notFound: FileSystemNotFoundException) {
    try {
      FileSystems.newFileSystem(this, emptyMap<String, Any>())
    } catch (alreadyExists: FileSystemAlreadyExistsException) {
      FileSystems.getFileSystem(this)
    }
  }
}
