package com.lagradost.shiro.ui.downloads

import android.content.DialogInterface
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import com.lagradost.shiro.ui.player.PlayerData
import com.lagradost.shiro.utils.AppUtils.getNameFull
import com.lagradost.shiro.utils.AppUtils.loadPlayer
import com.lagradost.shiro.utils.DownloadManager.resumeEpisodeUsingWorker
import com.lagradost.shiro.utils.VideoDownloadManager
import com.lagradost.shiro.utils.VideoDownloadManager.currentDownloads

const val DOWNLOAD_ACTION_PLAY_FILE = 0
const val DOWNLOAD_ACTION_DELETE_FILE = 1
const val DOWNLOAD_ACTION_RESUME_DOWNLOAD = 2
const val DOWNLOAD_ACTION_PAUSE_DOWNLOAD = 3
const val DOWNLOAD_ACTION_DOWNLOAD = 4

object DownloadButtonSetup {
    fun handleDownloadClick(activity: FragmentActivity?, click: DownloadClickEvent, anilistId: Int?, malId: Int?) {
        val id = click.data.id
        if (click.data !is AllDataWithId) return
        when (click.action) {
            DOWNLOAD_ACTION_DELETE_FILE -> {
                activity?.let { ctx ->
                    val builder: AlertDialog.Builder = AlertDialog.Builder(ctx)
                    val dialogClickListener =
                        DialogInterface.OnClickListener { _, which ->
                            when (which) {
                                DialogInterface.BUTTON_POSITIVE -> {
                                    VideoDownloadManager.deleteFileAndUpdateSettings(ctx, id)
                                }
                                DialogInterface.BUTTON_NEGATIVE -> {
                                }
                            }
                        }

                    builder.setTitle("Delete File")
                    builder.setMessage(
                        "This will permanently delete ${
                            getNameFull(
                                click.data.title,
                                click.data.episode,
                                null
                            )
                        }\nAre you sure?"
                    )
                        .setTitle("Delete")
                        .setPositiveButton("Delete", dialogClickListener)
                        .setNegativeButton("Cancel", dialogClickListener)
                        .show()
                }
            }
            DOWNLOAD_ACTION_PAUSE_DOWNLOAD -> {
                VideoDownloadManager.downloadEvent.invoke(
                    Pair(id, VideoDownloadManager.DownloadActionType.Pause)
                )
            }
            DOWNLOAD_ACTION_RESUME_DOWNLOAD -> {
                activity?.let { ctx ->
                    val pkg = VideoDownloadManager.getDownloadResumePackage(ctx, id)
                    if (pkg != null && !currentDownloads.any { it == id }) {
                        resumeEpisodeUsingWorker(ctx, pkg)
                    } else {
                        VideoDownloadManager.downloadEvent.invoke(
                            Pair(id, VideoDownloadManager.DownloadActionType.Resume)
                        )
                    }
                }
            }
            DOWNLOAD_ACTION_PLAY_FILE -> {
                activity?.let { act ->
                    val info =
                        VideoDownloadManager.getDownloadFileInfoAndUpdateSettings(act, id)
                            ?: return

                    act.loadPlayer(
                        PlayerData(
                            "Episode ${click.data.episode + 1 + click.data.episodeOffset} · ${click.data.title}",
                            info.path.toString(),// child.videoPath,
                            click.data.episode,
                            0,
                            null,
                            null,
                            click.data.slug,
                            anilistId,
                            malId,
                            click.data.fillerEpisodes
                        )
                    )
                }
            }
        }
    }
}