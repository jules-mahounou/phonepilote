package bj.phonepilote.app.push

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import bj.phonepilote.app.data.Repo
import java.util.concurrent.TimeUnit

/** Battement de cœur toutes les 30 min : état, commandes manquées, historique des positions. */
class Heartbeat(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        runCatching { Repo.heartbeat() }
        return Result.success()
    }

    companion object {
        /** Idempotent (KEEP) : appelé à chaque démarrage de l'app, la planification survit au redémarrage. */
        fun schedule(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<Heartbeat>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            runCatching {
                WorkManager.getInstance(ctx)
                    .enqueueUniquePeriodicWork("heartbeat", ExistingPeriodicWorkPolicy.KEEP, request)
            }
        }
    }
}
