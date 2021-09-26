package com.lagradost.shiro.ui.downloads

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.jaredrummler.cyanea.Cyanea
import com.lagradost.shiro.R
import com.lagradost.shiro.ui.MainActivity
import com.lagradost.shiro.ui.MainActivity.Companion.masterViewModel
import com.lagradost.shiro.ui.result.ResultFragment.Companion.isInResults
import com.lagradost.shiro.utils.mvvm.observe
import kotlinx.android.synthetic.main.fragment_queue.*

interface InterfaceRefreshList {
    fun refreshListRequest()
}

class QueueFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_queue, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter: RecyclerView.Adapter<RecyclerView.ViewHolder> = QueueAdapter()
        queue_res_view?.adapter = adapter
        (queue_res_view?.adapter as? QueueAdapter)?.notifyDataSetChanged()

        fragment_queue_root?.background = ColorDrawable(Cyanea.instance.backgroundColor)

        download_go_back?.setOnClickListener {
            activity?.onBackPressed()
        }

        val topParams: LinearLayout.LayoutParams = LinearLayout.LayoutParams(
            LinearLayoutCompat.LayoutParams.MATCH_PARENT, // view width
            MainActivity.statusHeight // view height
        )
        top_padding_download_queue?.layoutParams = topParams

        observe(masterViewModel!!.downloadQueue) {
            (queue_res_view?.adapter as? QueueAdapter)?.updateQueue()
            (queue_res_view?.adapter as? QueueAdapter)?.notifyDataSetChanged()
        }

    }

    override fun onResume() {
        super.onResume()
        isInResults = true
    }

    override fun onDestroy() {
        isInResults = false
        super.onDestroy()
    }

    companion object {
        fun newInstance() =
            QueueFragment()
        /*WebViewFragment().apply {
            arguments = Bundle().apply {
                putString(URL, url)
            }
        }*/
    }
}