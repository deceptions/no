package com.lagradost.shiro.ui.library

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.shiro.utils.MALApi

class BaseViewHolder(container: ViewGroup) : RecyclerView.ViewHolder(container)

class BaseItemCallback<T : Any> : DiffUtil.ItemCallback<T>() {
    override fun areItemsTheSame(oldItem: T, newItem: T) = oldItem.toString() == newItem.toString()
    @SuppressLint("DiffUtilEquals")
    override fun areContentsTheSame(oldItem: T, newItem: T): Boolean {
        return if (oldItem is MALApi.Companion.Data && newItem is MALApi.Companion.Data){
            oldItem.node.id == newItem.node.id
        } else oldItem == newItem
    }
}


// https://proandroiddev.com/generic-listadapter-with-kotlin-write-once-use-more-recyclerview-viewpager-6314cbdced36
abstract class GenericListAdapter<T : Any>(
    val layoutId: Int,
    inline val bind: (item: T, holder: BaseViewHolder, itemCount: Int) -> Unit
) : ListAdapter<T, BaseViewHolder>(BaseItemCallback<T>()) {
    override fun onBindViewHolder(holder: BaseViewHolder, position: Int) {
        bind(getItem(position), holder, itemCount)
    }

    override fun getItemViewType(position: Int) = layoutId
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
        val root = LayoutInflater.from(parent.context).inflate(
            viewType, parent, false
        )
        return BaseViewHolder(root as ViewGroup)
    }

    override fun getItemCount() = currentList.size

}