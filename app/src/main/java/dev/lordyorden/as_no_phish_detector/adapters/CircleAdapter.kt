package dev.lordyorden.as_no_phish_detector.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import dev.lordyorden.as_no_phish_detector.R
import dev.lordyorden.as_no_phish_detector.databinding.BadgeActiveBinding
import dev.lordyorden.as_no_phish_detector.databinding.BadgePendingBinding
import dev.lordyorden.as_no_phish_detector.databinding.ItemCircleMemberBinding
import dev.lordyorden.as_no_phish_detector.models.CircleMember
import dev.lordyorden.as_no_phish_detector.utilities.ImageLoader

class CircleAdapter(
    private val onMemberClick: (CircleMember) -> Unit
) : RecyclerView.Adapter<CircleAdapter.CircleViewHolder>() {

    private var members = emptyList<CircleMember>()

    fun submitList(nextMembers: List<CircleMember>) {
        val diff = DiffUtil.calculateDiff(CircleMemberDiffCallback(members, nextMembers))
        members = nextMembers
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CircleViewHolder {
        val binding = ItemCircleMemberBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        val holder = CircleViewHolder(binding)

        return holder
    }

    override fun getItemCount(): Int = members.size

    private fun getItem(position: Int) = members[position]

    override fun onBindViewHolder(holder: CircleViewHolder, position: Int) {
        with(holder.binding){
            with(getItem(position)){
                tvName.text = buildString {
                    append(name)
                }

                tvRole.text = buildString {
                    append("(")
                    append(familyRole)
                    append(")")
                }

                ImageLoader.getInstance().loadImage(avatarUrl ?: "", ivIcon, R.drawable.bg_circle_avatar_gray)

/*                frameBadgeStatus.removeAllViews()
                when(isConnected){
                    true -> {
                        root.alpha = 1f
                        tvStatus.text = root.resources.getString(R.string.watching_over)
                        val activeBadge = BadgeActiveBinding.inflate(LayoutInflater.from(root.context))
                        frameBadgeStatus.addView(activeBadge.root)
                    }
                    false -> {
                        root.alpha = 0.65f
                        tvStatus.text = root.resources.getString(R.string.invitation_sent)
                        val pendingBadge = BadgePendingBinding.inflate(LayoutInflater.from(root.context))
                        frameBadgeStatus.addView(pendingBadge.root)
                    }
                }*/
            }
        }
    }

    inner class CircleViewHolder(val binding: ItemCircleMemberBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if(pos != RecyclerView.NO_POSITION)
                    onMemberClick(getItem(pos))
            }
        }
    }
}

private class CircleMemberDiffCallback(
    private val oldMembers: List<CircleMember>,
    private val newMembers: List<CircleMember>,
) : DiffUtil.Callback() {
    override fun getOldListSize() = oldMembers.size

    override fun getNewListSize() = newMembers.size

    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return oldMembers[oldItemPosition].userId == newMembers[newItemPosition].userId
    }

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return oldMembers[oldItemPosition] == newMembers[newItemPosition]
    }
}
