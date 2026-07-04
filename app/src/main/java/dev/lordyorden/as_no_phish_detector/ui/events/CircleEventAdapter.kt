package dev.lordyorden.as_no_phish_detector.ui.events

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import dev.lordyorden.as_no_phish_detector.R
import dev.lordyorden.as_no_phish_detector.databinding.ItemCircleEventBinding
import dev.lordyorden.as_no_phish_detector.models.Event
import dev.lordyorden.as_no_phish_detector.utilities.ImageLoader
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class CircleEventAdapter(
    private val onDetailsClick: (Event) -> Unit,
    private val onBlockClick: (Event) -> Unit,
    private val onResolveClick: (Event) -> Unit,
) : RecyclerView.Adapter<CircleEventAdapter.CircleEventViewHolder>() {

    private var events = emptyList<CircleEventUiItem>()
    private val dateFormatter = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US)

    fun submitList(nextEvents: List<CircleEventUiItem>) {
        val diff = DiffUtil.calculateDiff(EventDiffCallback(events, nextEvents))
        events = nextEvents
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CircleEventViewHolder {
        val binding = ItemCircleEventBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CircleEventViewHolder(binding)
    }

    override fun getItemCount() = events.size

    override fun onBindViewHolder(holder: CircleEventViewHolder, position: Int) {
        holder.bind(events[position])
    }

    private fun getEvent(position: Int) = events.getOrNull(position)?.event

    private fun formatTimestamp(timestampMillis: Long): String {
        return Instant.ofEpochMilli(timestampMillis)
            .atZone(ZoneId.systemDefault())
            .format(dateFormatter)
    }

    inner class CircleEventViewHolder(private val binding: ItemCircleEventBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.btnDetails.setOnClickListener {
                getEvent(bindingAdapterPosition)?.let(onDetailsClick)
            }
            binding.btnBlock.setOnClickListener {
                getEvent(bindingAdapterPosition)?.let(onBlockClick)
            }
            binding.btnResolve.setOnClickListener {
                getEvent(bindingAdapterPosition)?.let(onResolveClick)
            }
        }

        fun bind(item: CircleEventUiItem) {
            val event = item.event
            binding.tvTitle.text = event.action
            binding.tvName.text = item.member.name
            binding.tvTime.text = formatTimestamp(event.timestamp.toLong())
            binding.llcAction.isVisible = event.requiresAction == true
            binding.btnBlock.isEnabled = !item.isBlocked
            binding.btnBlock.text = if (item.isBlocked) {
                binding.root.context.getString(R.string.blocked)
            } else {
                binding.root.context.getString(R.string.block)
            }
            binding.btnBlock.backgroundTintList = ContextCompat.getColorStateList(
                binding.root.context,
                if (item.isBlocked) R.color.surface_text else R.color.tertiary
            )
            binding.btnResolve.text = if (item.isBlocked) {
                binding.root.context.getString(R.string.release)
            } else {
                binding.root.context.getString(R.string.resolve_btn_text)
            }

            event.packageName?.let {
                ImageLoader.getInstance().loadAppIcon(it, binding.ivAppSource, R.drawable.ic_phone)
            } ?: run {
                binding.ivAppSource.setImageResource(R.drawable.ic_phone)
            }
        }
    }
}

private class EventDiffCallback(
    private val oldEvents: List<CircleEventUiItem>,
    private val newEvents: List<CircleEventUiItem>,
) : DiffUtil.Callback() {
    override fun getOldListSize() = oldEvents.size

    override fun getNewListSize() = newEvents.size

    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return oldEvents[oldItemPosition].event.eventId == newEvents[newItemPosition].event.eventId
    }

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return oldEvents[oldItemPosition] == newEvents[newItemPosition]
    }
}
