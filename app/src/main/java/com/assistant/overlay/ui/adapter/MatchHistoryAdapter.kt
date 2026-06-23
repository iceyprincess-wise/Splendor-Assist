package com.assistant.overlay.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.assistant.overlay.R
import com.assistant.overlay.database.MatchAnalyticsEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MatchHistoryAdapter(
    private val onMatchSelected: (MatchAnalyticsEntity) -> Unit
) : RecyclerView.Adapter<MatchHistoryAdapter.MatchViewHolder>() {

    private val matches = mutableListOf<MatchAnalyticsEntity>()
    private val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())

    fun submitList(newMatches: List<MatchAnalyticsEntity>) {
        matches.clear()
        matches.addAll(newMatches)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MatchViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_match_history, parent, false)
        return MatchViewHolder(view)
    }

    override fun onBindViewHolder(holder: MatchViewHolder, position: Int) {
        holder.bind(matches[position])
    }

    override fun getItemCount() = matches.size

    inner class MatchViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvId: TextView = itemView.findViewById(R.id.tv_match_id)
        private val tvStatus: TextView = itemView.findViewById(R.id.tv_match_status)
        private val tvPossession: TextView = itemView.findViewById(R.id.tv_possession)
        private val tvLongPass: TextView = itemView.findViewById(R.id.tv_long_pass)
        private val tvInterceptions: TextView = itemView.findViewById(R.id.tv_interceptions)

        fun bind(match: MatchAnalyticsEntity) {
            tvId.text = "Match Signature: ${match.matchId.take(8)}"

            if (match.isPermanentlySaved) {
                tvStatus.text = "SAVED TO ROM"
                tvStatus.setTextColor(Color.parseColor("#4CAF50"))
            } else {
                val endDate = dateFormat.format(Date(match.endTimestamp))
                tvStatus.text = "CACHED - $endDate"
                tvStatus.setTextColor(Color.parseColor("#FFC107"))
            }

            tvPossession.text = "POS: ${match.possessionPercentage}%"
            tvLongPass.text = "LBC ACC: ${match.longPassEfficiency}%"
            tvInterceptions.text = "INT: ${match.defensiveInterceptions}"

            itemView.setOnClickListener { onMatchSelected(match) }
        }
    }
}
