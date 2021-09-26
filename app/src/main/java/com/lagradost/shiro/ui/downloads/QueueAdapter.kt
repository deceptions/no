package com.lagradost.shiro.ui.downloads

import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.shiro.R
import com.lagradost.shiro.ui.MainActivity.Companion.masterViewModel
import com.lagradost.shiro.utils.VideoDownloadManager
import com.lagradost.shiro.utils.VideoDownloadManager.downloadQueue
import com.lagradost.shiro.utils.VideoDownloadManager.saveQueue
import kotlinx.android.synthetic.main.episode_result_downloaded.view.cardTitle
import kotlinx.android.synthetic.main.episode_result_downloaded.view.progressBar
import kotlinx.android.synthetic.main.episode_result_queued.view.*

class QueueAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var queue = listOf<VideoDownloadManager.DownloadResumePackage>().apply {
        updateQueue()
    }

    fun updateQueue(){
        queue = downloadQueue.toList().distinctBy { it.item.ep.id }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        updateQueue()
        return QueueViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.episode_result_queued, parent, false)
        )
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is QueueViewHolder -> {
                holder.bind(queue[position])
            }

        }
    }

    override fun getItemCount(): Int {
        return queue.size
    }

    class QueueViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun bind(item: VideoDownloadManager.DownloadResumePackage) {
            itemView.progressBar.visibility = GONE
            itemView.cardPauseIcon.visibility = GONE
            val title = "${item.item.ep.episode?.let { "E$it" } ?: ""} ${item.item.ep.mainName}"
            itemView.cardTitle?.text = title

            itemView.cardRemoveIcon.setOnClickListener {
                downloadQueue.removeAll { it == item }
                masterViewModel?.downloadQueue?.postValue(downloadQueue)
                saveQueue(itemView.context)
                Toast.makeText(itemView.context, "Removed $title", Toast.LENGTH_SHORT).show()
            }
        }
    }
}