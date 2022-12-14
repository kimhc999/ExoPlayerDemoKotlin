/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.demo

import android.app.Notification
import android.content.Context
import com.google.android.exoplayer2.offline.Download
import com.google.android.exoplayer2.offline.DownloadManager
import com.google.android.exoplayer2.offline.DownloadService
import com.google.android.exoplayer2.scheduler.PlatformScheduler
import com.google.android.exoplayer2.scheduler.Requirements.RequirementFlags
import com.google.android.exoplayer2.scheduler.Scheduler
import com.google.android.exoplayer2.ui.DownloadNotificationHelper
import com.google.android.exoplayer2.util.NotificationUtil
import com.google.android.exoplayer2.util.Util

/** A service for downloading media.  */
class DemoDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    DemoUtil.DOWNLOAD_NOTIFICATION_CHANNEL_ID,
    R.string.exo_download_notification_channel_name,
    0
) {
    override fun getDownloadManager(): DownloadManager {
        // This will only happen once, because getDownloadManager is guaranteed to be called only once
        // in the life cycle of the process.
        val downloadManager = DemoUtil.getDownloadManager(this)
        val downloadNotificationHelper = DemoUtil.getDownloadNotificationHelper(this)
        downloadManager.addListener(
            TerminalStateNotificationHelper(
                this, downloadNotificationHelper, FOREGROUND_NOTIFICATION_ID + 1
            )
        )
        return downloadManager
    }

    override fun getScheduler(): Scheduler? {
        return if (Util.SDK_INT >= 21) PlatformScheduler(this, JOB_ID) else null
    }

    override fun getForegroundNotification(
        downloads: List<Download>, notMetRequirements: @RequirementFlags Int
    ): Notification {
        return DemoUtil.getDownloadNotificationHelper(this)
            .buildProgressNotification(
                this,
                R.drawable.ic_download,
                null,
                null,
                downloads,
                notMetRequirements
            )
    }

    /**
     * Creates and displays notifications for downloads when they complete or fail.
     *
     *
     * This helper will outlive the lifespan of a single instance of [DemoDownloadService].
     * It is static to avoid leaking the first [DemoDownloadService] instance.
     */
    private class TerminalStateNotificationHelper(
        context: Context, notificationHelper: DownloadNotificationHelper?, firstNotificationId: Int
    ) : DownloadManager.Listener {
        private val context: Context
        private val notificationHelper: DownloadNotificationHelper?
        private var nextNotificationId: Int

        init {
            this.context = context.applicationContext
            this.notificationHelper = notificationHelper
            nextNotificationId = firstNotificationId
        }

        override fun onDownloadChanged(
            downloadManager: DownloadManager, download: Download, finalException: Exception?
        ) {
            val notification: Notification = when (download.state) {
                Download.STATE_COMPLETED -> {
                    notificationHelper!!.buildDownloadCompletedNotification(
                        context,
                        R.drawable.ic_download_done,
                        null,
                        Util.fromUtf8Bytes(download.request.data)
                    )
                }
                Download.STATE_FAILED -> {
                    notificationHelper!!.buildDownloadFailedNotification(
                        context,
                        R.drawable.ic_download_done,
                        null,
                        Util.fromUtf8Bytes(download.request.data)
                    )
                }
                else -> {
                    return
                }
            }
            NotificationUtil.setNotification(context, nextNotificationId++, notification)
        }
    }

    companion object {
        private const val JOB_ID = 1
        private const val FOREGROUND_NOTIFICATION_ID = 1
    }
}