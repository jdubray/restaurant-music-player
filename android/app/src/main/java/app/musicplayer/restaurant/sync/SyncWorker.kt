package app.musicplayer.restaurant.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.musicplayer.restaurant.data.Settings
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = Settings(applicationContext)
        return try {
            val r = Syncer.sync(applicationContext)
            settings.recordSyncOk("${r.total} tracks (+${r.downloaded} -${r.deleted})")
            Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "sync failed", t)
            settings.recordSyncFail(t.message ?: t.javaClass.simpleName)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val DAILY_NAME = "music-sync-daily"
        private const val ONCE_NAME = "music-sync-once"

        /** Schedule the daily sync to fire at 04:00 local time. */
        fun scheduleDaily(context: Context) {
            val now = ZonedDateTime.now()
            val target = now.toLocalDate().atTime(LocalTime.of(4, 0)).atZone(now.zone).let {
                if (it.isAfter(now)) it else it.plusDays(1)
            }
            val initialDelay = Duration.between(now, target).toMillis()

            val request = PeriodicWorkRequestBuilder<SyncWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                DAILY_NAME, ExistingPeriodicWorkPolicy.UPDATE, request
            )
        }

        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONCE_NAME, ExistingWorkPolicy.REPLACE, request
            )
        }
    }
}
