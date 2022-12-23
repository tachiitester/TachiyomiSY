package eu.kanade.domain.track.service

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.kanade.domain.track.interactor.GetTracks
import eu.kanade.domain.track.interactor.InsertTrack
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.util.lang.withIOContext
import eu.kanade.tachiyomi.util.system.logcat
import logcat.LogPriority
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class DelayedTrackingUpdateJob(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val getTracks = Injekt.get<GetTracks>()
        val insertTrack = Injekt.get<InsertTrack>()

        val trackManager = Injekt.get<TrackManager>()
        val delayedTrackingStore = Injekt.get<DelayedTrackingStore>()

        withIOContext {
            val tracks = delayedTrackingStore.getItems().mapNotNull {
                val track = getTracks.awaitOne(it.trackId)
                if (track == null) {
                    delayedTrackingStore.remove(it.trackId)
                }
                track
            }

            tracks.forEach { track ->
                try {
                    val service = trackManager.getService(track.syncId)
                    if (service != null && service.isLogged) {
                        service.update(track.toDbTrack(), true)
                        insertTrack.await(track)
                    }
                    delayedTrackingStore.remove(track.id)
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e)
                }
            }
        }

        return Result.success()
    }

    companion object {
        private const val TAG = "DelayedTrackingUpdate"

        fun setupTask(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<DelayedTrackingUpdateJob>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
