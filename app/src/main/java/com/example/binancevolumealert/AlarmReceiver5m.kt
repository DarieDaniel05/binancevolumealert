package com.example.binancevolumealert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager

class AlarmReceiver5m : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val request = OneTimeWorkRequestBuilder<VolumeCheck5mWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(VolumeCheck5mWorker.WORK_TAG)
            .build()

        WorkManager.getInstance(context).enqueue(request)
        AlarmScheduler5m.scheduleNext(context)
    }
}