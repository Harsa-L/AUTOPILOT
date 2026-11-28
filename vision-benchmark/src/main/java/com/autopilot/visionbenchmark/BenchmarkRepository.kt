package com.autopilot.visionbenchmark

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Play-Policy compliant scoped storage repository for Vision Benchmark Studio
 * custom degradation profiles, recorded MP4 sessions, session metadata JSON, and
 * SAF exports (zero READ_EXTERNAL_STORAGE / READ_MEDIA_* permissions).
 */
class BenchmarkRepository(private val context: Context) {

  private val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  private val rootDir: File
    get() = File(context.filesDir, "vision_benchmark").apply { mkdirs() }

  private val sessionsDir: File
    get() = File(rootDir, "sessions").apply { mkdirs() }

  private val profilesFile: File
    get() = File(rootDir, "custom_profiles.json")

  fun createSessionDir(sessionId: String): File =
    File(sessionsDir, sessionId).apply { mkdirs() }

  fun loadAllProfiles(): List<DegradationProfile> {
    val custom = loadCustomProfiles()
    return BuiltInDegradationProfiles.all + custom
  }

  fun loadCustomProfiles(): List<DegradationProfile> {
    val file = profilesFile
    if (!file.exists()) return emptyList()
    return runCatching { json.decodeFromString<List<DegradationProfile>>(file.readText()) }
      .getOrDefault(emptyList())
  }

  fun saveCustomProfile(
    name: String,
    description: String,
    config: ManualDegradationConfig,
    autoConfig: AutoDegradationConfig,
    existingId: String? = null,
  ): DegradationProfile {
    val current = loadCustomProfiles().toMutableList()
    val id = existingId?.takeIf { idCandidate -> !BuiltInDegradationProfiles.all.any { it.id == idCandidate } }
      ?: "custom_${UUID.randomUUID().toString().take(8)}"
    val profile =
      DegradationProfile(
        id = id,
        name = name.trim().ifBlank { "Custom Profile" },
        description = description.trim().ifBlank { "User-saved degradation preset" },
        isBuiltIn = false,
        config = config,
        autoConfig = autoConfig,
      )
    val idx = current.indexOfFirst { it.id == id }
    if (idx >= 0) current[idx] = profile else current.add(0, profile)
    writeTextAtomic(profilesFile, json.encodeToString(current))
    return profile
  }

  fun duplicateProfile(source: DegradationProfile, newName: String): DegradationProfile =
    saveCustomProfile(
      name = newName,
      description = "Duplicated from ${source.name}",
      config = source.config,
      autoConfig = source.autoConfig,
      existingId = null,
    )

  fun deleteCustomProfile(profileId: String): Boolean {
    if (BuiltInDegradationProfiles.all.any { it.id == profileId }) return false
    val updated = loadCustomProfiles().filterNot { it.id == profileId }
    writeTextAtomic(profilesFile, json.encodeToString(updated))
    return true
  }

  fun saveSessionMetadata(metadata: BenchmarkSessionMetadata): File {
    val dir = createSessionDir(metadata.sessionId)
    val metaFile = File(dir, "session_metadata.json")
    writeTextAtomic(metaFile, json.encodeToString(metadata))
    return metaFile
  }

  fun listSessions(): List<BenchmarkSessionMetadata> {
    val dirs = sessionsDir.listFiles()?.filter { it.isDirectory } ?: return emptyList()
    return dirs
      .mapNotNull { dir ->
        val metaFile = File(dir, "session_metadata.json")
        if (!metaFile.exists()) return@mapNotNull null
        runCatching { json.decodeFromString<BenchmarkSessionMetadata>(metaFile.readText()) }.getOrNull()
      }
      .sortedByDescending { it.createdAtMs }
  }

  fun deleteSession(sessionId: String): Boolean {
    val dir = File(sessionsDir, sessionId)
    return if (dir.exists()) dir.deleteRecursively() else false
  }

  fun exportFileToUri(sourceFile: File, destUri: Uri): Result<Long> =
    runCatching {
      check(sourceFile.exists()) { "Source file missing: ${sourceFile.name}" }
      context.contentResolver.openOutputStream(destUri)?.use { out ->
        sourceFile.inputStream().use { inp -> inp.copyTo(out) }
      } ?: error("Unable to open export destination URI")
      sourceFile.length()
    }

  fun encodeComparisonReportJson(entries: List<ModelComparisonEntry>): String =
    json.encodeToString(entries)

  private fun writeTextAtomic(target: File, content: String) {
    val tmp = File("${target.path}.tmp")
    tmp.writeText(content)
    if (!tmp.renameTo(target)) {
      tmp.copyTo(target, overwrite = true)
      tmp.delete()
    }
  }
}
