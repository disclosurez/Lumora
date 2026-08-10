package com.lumora.data.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import com.lumora.data.local.LumoraDatabase
import com.lumora.data.local.entity.*
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*

/**
 * Manages export/import of app data for backup and restore.
 * Exports providers, favorites, preferences, watch history to JSON.
 */
class BackupManager(private val context: Context) {

    private val TAG = "BackupManager"
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private     companion object {
        const val BACKUP_VERSION = 1
    }

    data class BackupData(
        val version: Int = BACKUP_VERSION,
        val createdAt: String = "",
        val appVersion: String = "2.0",
        val providers: List<ProviderBackup> = emptyList(),
        val epgSources: List<EpgSourceBackup> = emptyList(),
        val customGroups: List<CustomGroupBackup> = emptyList(),
        val favorites: List<String> = emptyList(),
        val watchHistory: List<WatchHistoryBackup> = emptyList(),
        val recordingStorage: RecordingStorageBackup? = null,
        val recordingSchedules: List<RecordingScheduleBackup> = emptyList(),
        val checksum: String = ""
    )

    data class ProviderBackup(
        val id: String, val name: String, val type: String,
        val serverUrl: String?, val username: String?, val password: String?,
        val m3uUrl: String?, val userAgent: String?, val macAddress: String?,
        val serialNumber: String?, val active: Boolean,
        val syncEnabled: Boolean, val epgSyncEnabled: Boolean
    )

    data class EpgSourceBackup(
        val id: String, val name: String, val url: String,
        val enabled: Boolean, val priority: Int
    )

    data class CustomGroupBackup(
        val id: String, val name: String, val mediaType: String,
        val isHidden: Boolean, val members: List<String> = emptyList()
    )

    data class WatchHistoryBackup(
        val channelId: String, val channelName: String,
        val mediaType: String, val positionMs: Long,
        val durationMs: Long, val status: String,
        val lastWatchedAt: Long
    )

    data class RecordingStorageBackup(
        val maxSimultaneous: Int, val retentionDays: Int,
        val fileNamePattern: String
    )

    data class RecordingScheduleBackup(
        val channelId: String, val channelName: String,
        val programTitle: String, val startTimeUtc: Long,
        val stopTimeUtc: Long, val recurringRule: String?
    )

    data class ImportResult(
        val providersImported: Int = 0,
        val epgSourcesImported: Int = 0,
        val customGroupsImported: Int = 0,
        val watchHistoryImported: Int = 0,
        val recordingSchedulesImported: Int = 0,
        val conflicts: Int = 0
    )

    /**
     * Export app data to a URI (SAF document).
     */
    suspend fun exportTo(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val db = LumoraDatabase.getInstance(context)
            val data = collectBackupData(db)

            val json = gson.toJson(data)
            val checksum = md5(json)
            val finalData = data.copy(checksum = checksum)
            val finalJson = gson.toJson(finalData)

            val stream = context.contentResolver.openOutputStream(uri)
            if (stream == null) {
                // SAF refused the URI (no provider, no permission) - nothing was written,
                // so this must not report success.
                Log.e(TAG, "Export failed: could not open output stream for $uri")
                return@withContext false
            }
            stream.use { outputStream ->
                OutputStreamWriter(outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(finalJson)
                    writer.flush()
                }
            }

            Log.d(TAG, "Backup exported successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Export failed: ${e.message}")
            false
        }
    }

    /**
     * Import app data from a URI (SAF document).
     */
    suspend fun importFrom(uri: Uri, confirmed: Boolean = false): ImportResult = withContext(Dispatchers.IO) {
        try {
            val json = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).readText()
            } ?: return@withContext ImportResult()

            val data = gson.fromJson(json, BackupData::class.java)

            // Verify checksum
            if (data.checksum.isNotBlank()) {
                val expectedChecksum = md5(gson.toJson(data.copy(checksum = "")))
                if (data.checksum != expectedChecksum) {
                    Log.e(TAG, "Backup checksum mismatch - rejecting import")
                    return@withContext ImportResult()
                }
            }

            // If not confirmed and there is existing data, return conflicts to prompt confirmation
            if (!confirmed && hasExistingData()) {
                val existingCount = countExistingData()
                Log.d(TAG, "Existing data detected ($existingCount providers), confirmation required")
                return@withContext ImportResult(conflicts = existingCount)
            }

            val result = restoreBackupData(data)
            Log.d(TAG, "Import completed: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Import failed: ${e.message}")
            ImportResult()
        }
    }

    private suspend fun hasExistingData(): Boolean {
        val db = LumoraDatabase.getInstance(context)
        return db.providerDao().getAll().isNotEmpty()
    }

    private suspend fun countExistingData(): Int {
        val db = LumoraDatabase.getInstance(context)
        return db.providerDao().getAll().size
    }

    private suspend fun collectBackupData(db: LumoraDatabase): BackupData {
        val providers = db.providerDao().getAll()
        val epgSources = db.epgSourceDao().getAll()
        val customGroups = db.customGroupDao().getAll()
        val watchHistory = db.watchHistoryDao().getRecent()
        val recordingStorage = db.recordingDao().getStorageConfig()
        val recordings = db.recordingDao().getAll()

        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")

        return BackupData(
            version = BACKUP_VERSION,
            createdAt = dateFormat.format(Date()),
            appVersion = "2.0",
            providers = providers.map { ProviderBackup(
                id = it.id, name = it.name, type = it.type,
                serverUrl = it.serverUrl, username = it.username,
                password = it.passwordEncrypted,
                m3uUrl = it.m3uUrl, userAgent = it.userAgent,
                macAddress = it.macAddress, serialNumber = it.serialNumber,
                active = it.active, syncEnabled = it.syncEnabled,
                epgSyncEnabled = it.epgSyncEnabled
            )},
            epgSources = epgSources.map { EpgSourceBackup(
                id = it.id, name = it.name, url = it.url,
                enabled = it.enabled, priority = it.priority
            )},
            customGroups = customGroups.map { group ->
                val members = db.customGroupDao().getMembers(group.id)
                CustomGroupBackup(
                    id = group.id, name = group.name,
                    mediaType = group.mediaType, isHidden = group.isHidden,
                    members = members.map { it.channelId }
                )
            },
            favorites = emptyList(),
            watchHistory = watchHistory.map { WatchHistoryBackup(
                channelId = it.channelId, channelName = it.channelName,
                mediaType = it.mediaType, positionMs = it.positionMs,
                durationMs = it.durationMs, status = it.status,
                lastWatchedAt = it.lastWatchedAt
            )},
            recordingStorage = recordingStorage?.let { RecordingStorageBackup(
                maxSimultaneous = it.maxSimultaneous,
                retentionDays = it.retentionDays,
                fileNamePattern = it.fileNamePattern
            )},
            recordingSchedules = recordings.filter { it.status == "SCHEDULED" }.map {
                RecordingScheduleBackup(
                    channelId = it.channelId, channelName = it.channelName,
                    programTitle = it.programTitle,
                    startTimeUtc = it.startTimeUtc, stopTimeUtc = it.stopTimeUtc,
                    recurringRule = it.recurringRule
                )
            }
        )
    }

    private suspend fun restoreBackupData(data: BackupData): ImportResult {
        val db = LumoraDatabase.getInstance(context)
        var result = ImportResult()

        // Restore providers
        for (p in data.providers) {
            db.providerDao().insert(
                ProviderEntity(
                    id = p.id, name = p.name, type = p.type,
                    serverUrl = p.serverUrl, username = p.username,
                    passwordEncrypted = p.password, m3uUrl = p.m3uUrl,
                    userAgent = p.userAgent, macAddress = p.macAddress,
                    serialNumber = p.serialNumber, active = p.active,
                    syncEnabled = p.syncEnabled, epgSyncEnabled = p.epgSyncEnabled
                )
            )
            result = result.copy(providersImported = result.providersImported + 1)
        }

        // Restore EPG sources
        for (e in data.epgSources) {
            db.epgSourceDao().insert(
                EpgSourceEntity(
                    id = e.id, name = e.name, url = e.url,
                    enabled = e.enabled, priority = e.priority
                )
            )
            result = result.copy(epgSourcesImported = result.epgSourcesImported + 1)
        }

        // Restore custom groups
        for (g in data.customGroups) {
            db.customGroupDao().insert(
                CustomGroupEntity(
                    id = g.id, name = g.name,
                    mediaType = g.mediaType, isHidden = g.isHidden
                )
            )
            if (g.members.isNotEmpty()) {
                val members = g.members.mapIndexed { index, chId ->
                    CustomGroupMemberEntity(
                        id = "${g.id}_$chId",
                        groupId = g.id, channelId = chId,
                        sortOrder = index
                    )
                }
                db.customGroupDao().insertMembers(members)
            }
            result = result.copy(customGroupsImported = result.customGroupsImported + 1)
        }

        // Restore watch history
        for (h in data.watchHistory) {
            db.watchHistoryDao().insert(
                WatchHistoryEntity(
                    id = "backup_${h.channelId}",
                    channelId = h.channelId, channelName = h.channelName,
                    mediaType = h.mediaType, positionMs = h.positionMs,
                    durationMs = h.durationMs, status = h.status,
                    lastWatchedAt = h.lastWatchedAt,
                    firstWatchedAt = h.lastWatchedAt
                )
            )
            result = result.copy(watchHistoryImported = result.watchHistoryImported + 1)
        }

        // Restore recording schedules
        for (r in data.recordingSchedules) {
            db.recordingDao().insert(
                RecordingEntity(
                    id = UUID.randomUUID().toString(),
                    channelId = r.channelId, channelName = r.channelName,
                    programTitle = r.programTitle,
                    startTimeUtc = r.startTimeUtc, stopTimeUtc = r.stopTimeUtc,
                    recurringRule = r.recurringRule
                )
            )
            result = result.copy(recordingSchedulesImported = result.recordingSchedulesImported + 1)
        }

        return result
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
