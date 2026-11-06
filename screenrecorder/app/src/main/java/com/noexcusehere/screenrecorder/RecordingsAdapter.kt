package com.noexcusehere.screenrecorder

import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.noexcusehere.screenrecorder.databinding.ItemRecordingBinding

data class Recording(val uri: Uri, val name: String, val durationMs: Long, val sizeBytes: Long, val dateMillis: Long)

class RecordingsAdapter(
    private val onClick: (Recording) -> Unit,
    private val onLongClick: (Recording) -> Unit,
) : RecyclerView.Adapter<RecordingsAdapter.Holder>() {

    private val items = mutableListOf<Recording>()

    fun submit(list: List<Recording>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    class Holder(val binding: ItemRecordingBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemRecordingBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val r = items[position]
        val ctx = holder.itemView.context
        holder.binding.name.text = r.name
        val duration = DateUtils.formatElapsedTime(r.durationMs / 1000)
        val size = Formatter.formatShortFileSize(ctx, r.sizeBytes)
        val date = DateUtils.getRelativeDateTimeString(ctx, r.dateMillis, DateUtils.MINUTE_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0)
        holder.binding.details.text = "$duration  •  $size  •  $date"
        holder.itemView.setOnClickListener { onClick(r) }
        holder.itemView.setOnLongClickListener { onLongClick(r); true }
    }
}
