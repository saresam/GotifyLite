package com.gotify.lite

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * 兜底任务：进程被系统杀死后，由 JobScheduler 在 15 分钟后（有网络时）把前台服务重新拉起。
 * 相比原版的 AlarmManager.setExact，JobScheduler 由系统批量调度，几乎不耗电。
 */
class ReconnectJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        if (Prefs(this).isConfigured && !PushService.running.get()) {
            runCatching { PushService.start(this) }
        }
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    companion object {
        private const val JOB_ID = 1001
        private const val TAG = "ReconnectJob"

        fun schedule(ctx: Context) {
            try {
                val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, ReconnectJobService::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(15 * 60 * 1000L)
                    .build()
                js.schedule(job)
            } catch (e: Exception) {
                Log.w(TAG, "schedule failed", e)
            }
        }

        fun cancel(ctx: Context) {
            try {
                val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                js.cancel(JOB_ID)
            } catch (e: Exception) {
                Log.w(TAG, "cancel failed", e)
            }
        }
    }
}
