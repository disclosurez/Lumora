package com.lumora.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lumora.R
import com.lumora.data.local.entity.EpgSourceEntity

class EpgSourceAdapter(
    private val onDelete: (EpgSourceEntity) -> Unit
) : ListAdapter<EpgSourceEntity, EpgSourceAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleText: TextView = itemView.findViewById(android.R.id.text1)
        private val subtitleText: TextView = itemView.findViewById(android.R.id.text2)
        private var current: EpgSourceEntity? = null

        init {
            itemView.setOnLongClickListener {
                current?.let(onDelete); true
            }
        }

        fun bind(source: EpgSourceEntity) {
            current = source
            titleText.text = source.name
            titleText.setTextColor(itemView.context.getColor(R.color.text_primary))
            val lastSync = source.lastRefreshedAt?.let {
                java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it))
            } ?: "never"
            subtitleText.text = "${source.url.take(60)} · Last sync: $lastSync"
            subtitleText.setTextColor(itemView.context.getColor(R.color.text_secondary))
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<EpgSourceEntity>() {
        override fun areItemsTheSame(old: EpgSourceEntity, new: EpgSourceEntity): Boolean = old.id == new.id
        override fun areContentsTheSame(old: EpgSourceEntity, new: EpgSourceEntity): Boolean = old == new
    }
}
