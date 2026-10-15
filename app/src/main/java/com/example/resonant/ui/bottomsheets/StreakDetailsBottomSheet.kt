package com.example.resonant.ui.bottomsheets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.os.bundleOf
import com.example.resonant.R
import com.example.resonant.data.network.StreakStatusDTO
import com.example.resonant.streak.StreakTexts
import com.google.gson.Gson

/** Detail of the streak chip in Explorar: today's minutes, longest streak, freeze and next milestone. */
class StreakDetailsBottomSheet : ResonantBottomSheetDialogFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.bottom_sheet_streak_details, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val status = arguments?.getString(ARG_STATUS)
            ?.let { runCatching { Gson().fromJson(it, StreakStatusDTO::class.java) }.getOrNull() }
            ?: return dismissAllowingStateLoss()

        view.findViewById<TextView>(R.id.streakDetailsDays).text = StreakTexts.chip(status)
        view.findViewById<TextView>(R.id.streakDetailsToday).text = StreakTexts.todayProgress(status)
        view.findViewById<ProgressBar>(R.id.streakDetailsProgress).progress =
            if (status.goalSeconds > 0) (status.todaySeconds * 100 / status.goalSeconds).coerceIn(0, 100) else 0
        view.findViewById<TextView>(R.id.streakDetailsLongest).text = StreakTexts.longest(status)
        view.findViewById<TextView>(R.id.streakDetailsFreeze).text = StreakTexts.freeze(status)
        view.findViewById<TextView>(R.id.streakDetailsMilestone).apply {
            val milestone = StreakTexts.nextMilestone(status)
            text = milestone
            visibility = if (milestone != null) View.VISIBLE else View.GONE
        }
    }

    companion object {
        const val TAG = "StreakDetailsBottomSheet"
        private const val ARG_STATUS = "status"

        fun newInstance(status: StreakStatusDTO) = StreakDetailsBottomSheet().apply {
            arguments = bundleOf(ARG_STATUS to Gson().toJson(status))
        }
    }
}
