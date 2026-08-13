/*
 * Copyright (C) 2026 PlayFetch contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.vibe.playfetch

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.SpinnerAdapter
import android.widget.TextView

class MarketRegionAdapter(
    private val context: Context,
    regions: List<MarketRegion>
) : BaseAdapter(), SpinnerAdapter {
    private val rows: List<Row> = buildList {
        regions.forEachIndexed { index, region ->
            if (index == PINNED_COUNT) add(Row.Separator)
            add(Row.Region(region))
        }
    }

    override fun getCount(): Int = rows.size

    override fun getItem(position: Int): Any = rows[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun areAllItemsEnabled(): Boolean = false

    override fun isEnabled(position: Int): Boolean = rows[position] is Row.Region

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val region = regionAt(position) ?: firstRegion()
        return regionView(convertView, region, dropdown = false)
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup?): View =
        when (val row = rows[position]) {
            is Row.Region -> regionView(convertView, row.value, dropdown = true)
            Row.Separator -> separatorView(convertView)
        }

    fun regionAt(position: Int): MarketRegion? =
        (rows.getOrNull(position) as? Row.Region)?.value

    fun positionOf(countryCode: String?): Int = rows.indexOfFirst { row ->
        row is Row.Region && row.value.countryCode.equals(countryCode, ignoreCase = true)
    }

    private fun firstRegion(): MarketRegion =
        (rows.first { it is Row.Region } as Row.Region).value

    private fun regionView(
        convertView: View?,
        region: MarketRegion,
        dropdown: Boolean
    ): TextView {
        val view = (convertView as? TextView) ?: TextView(context)
        view.text = region.toString()
        view.gravity = Gravity.CENTER_VERTICAL
        view.setTextColor(resolveTextColor())
        view.textSize = if (dropdown) 17f else 16f
        val horizontal = dp(if (dropdown) 18 else 2)
        view.setPadding(horizontal, 0, horizontal, 0)
        view.minHeight = dp(if (dropdown) 52 else 48)
        return view
    }

    private fun separatorView(convertView: View?): View {
        val container = (convertView as? FrameLayout) ?: FrameLayout(context)
        container.removeAllViews()
        container.isClickable = false
        container.isFocusable = false
        container.minimumHeight = dp(17)
        container.addView(
            View(context).apply { setBackgroundColor(resolveDividerColor()) },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1),
                Gravity.CENTER_VERTICAL
            ).apply {
                marginStart = dp(14)
                marginEnd = dp(14)
            }
        )
        return container
    }

    private fun resolveTextColor(): Int {
        val values = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        return try {
            values.getColor(0, Color.BLACK)
        } finally {
            values.recycle()
        }
    }

    private fun resolveDividerColor(): Int {
        val values = context.obtainStyledAttributes(intArrayOf(android.R.attr.listDivider))
        return try {
            values.getDrawable(0)?.let { drawable ->
                // A theme divider may not be a solid color, so use the standard subdued tone.
                if (drawable.alpha > 0) 0x447F7F7F else 0x447F7F7F
            } ?: 0x447F7F7F
        } finally {
            values.recycle()
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private sealed interface Row {
        data class Region(val value: MarketRegion) : Row
        data object Separator : Row
    }

    private companion object {
        const val PINNED_COUNT = 2
    }
}
