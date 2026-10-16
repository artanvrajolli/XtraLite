package com.github.andreyasadchy.xtra.ui.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.databinding.AppIconListItemBinding
import com.github.andreyasadchy.xtra.util.AppIcons

class AppIconAdapter(
    private var selected: AppIcons.AppIcon,
    private val onSelected: (AppIcons.AppIcon) -> Unit,
) : RecyclerView.Adapter<AppIconAdapter.ViewHolder>() {

    class ViewHolder(val binding: AppIconListItemBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(AppIconListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = AppIcons.icons[position]
        with(holder.binding) {
            icon.setImageResource(item.icon)
            label.setText(item.label)
            root.isChecked = item == selected
            root.contentDescription = root.context.getString(item.label)
            root.setOnClickListener {
                if (item != selected) {
                    val previous = AppIcons.icons.indexOf(selected)
                    selected = item
                    notifyItemChanged(previous)
                    notifyItemChanged(holder.bindingAdapterPosition)
                    onSelected(item)
                }
            }
        }
    }

    override fun getItemCount() = AppIcons.icons.size
}
