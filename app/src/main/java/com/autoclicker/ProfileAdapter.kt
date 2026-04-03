package com.autoclicker

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.autoclicker.databinding.ItemProfileBinding

class ProfileAdapter(
    private val profiles: List<Profile>,
    private val onLoad: (Profile) -> Unit,
    private val onExport: (Profile) -> Unit,
    private val onSetApp: (Profile) -> Unit,
    private val onDelete: (Profile) -> Unit
) : RecyclerView.Adapter<ProfileAdapter.VH>() {

    inner class VH(val binding: ItemProfileBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(p: Profile) {
            binding.tvProfileName.text = p.name
            val pts = p.config.points.size
            val rep = if (p.config.repeatCount == 0) "무한" else "${p.config.repeatCount}회"
            val app = if (p.linkedAppPackage.isEmpty()) "" else "\n앱 연동: ${p.linkedAppPackage}"
            binding.tvProfileDetail.text = "포인트 ${pts}개 · 반복 $rep$app"
            binding.btnProfileLoad.setOnClickListener { onLoad(p) }
            binding.btnProfileExport.setOnClickListener { onExport(p) }
            binding.btnProfileSetApp.setOnClickListener { onSetApp(p) }
            binding.btnProfileDelete.setOnClickListener { onDelete(p) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemProfileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(profiles[position])
    override fun getItemCount(): Int = profiles.size
}
