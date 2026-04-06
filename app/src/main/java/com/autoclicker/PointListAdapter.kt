package com.autoclicker

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.autoclicker.databinding.ItemClickPointBinding

class PointListAdapter(
    private val points: MutableList<ClickPoint>,
    private val onListChanged: () -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<PointListAdapter.VH>() {

    inner class VH(val binding: ItemClickPointBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(point: ClickPoint, position: Int) {
            val title = if (point.label.isNotBlank()) {
                "#${position + 1} · ${point.label}"
            } else {
                "#${position + 1}"
            }
            binding.tvPointTitle.text = title
            val baseDelay = if (point.delayAfterMs >= 0) "${point.delayAfterMs}ms"
                           else binding.root.context.getString(R.string.delay_uses_global)
            val delayText = if (point.randomVarianceMs > 0) "$baseDelay ±${point.randomVarianceMs}ms"
                            else baseDelay
            val gestureLine = when (point.gesture) {
                GestureType.TAP -> binding.root.context.getString(R.string.gesture_label_tap)
                GestureType.LONG_PRESS -> binding.root.context.getString(
                    R.string.gesture_label_long,
                    point.longPressDurationMs
                )
                GestureType.SWIPE -> binding.root.context.getString(
                    R.string.gesture_label_swipe,
                    point.x,
                    point.y,
                    point.endX,
                    point.endY,
                    point.swipeDurationMs
                )
            }
            val triggerLine = if (point.trigger != null) {
                val actionStr = if (point.trigger.action == TriggerAction.SKIP)
                    binding.root.context.getString(R.string.trigger_action_skip)
                else
                    binding.root.context.getString(R.string.trigger_action_retry)
                "\n트리거: ${point.trigger.colorHex()} @ (${point.trigger.checkX},${point.trigger.checkY}) → $actionStr"
            } else ""
            binding.tvPointDetail.text = binding.root.context.getString(
                R.string.point_row_detail_fmt,
                gestureLine,
                point.x,
                point.y,
                delayText
            ) + triggerLine

            binding.btnDeletePoint.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    points.removeAt(pos)
                    notifyItemRemoved(pos)
                    notifyItemRangeChanged(pos, points.size - pos)
                    onListChanged()
                }
            }

            binding.tvDragHandle.setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_DOWN) {
                    onStartDrag(this)
                }
                false
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemClickPointBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(points[position], position)
    }

    override fun getItemCount(): Int = points.size

    fun moveItem(from: Int, to: Int) {
        if (from == to) return
        val item = points.removeAt(from)
        points.add(to, item)
        notifyItemMoved(from, to)
        val start = minOf(from, to)
        notifyItemRangeChanged(start, points.size - start)
        onListChanged()
    }

    fun snapshot(): List<ClickPoint> = points.toList()
}
