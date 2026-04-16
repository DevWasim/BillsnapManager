package com.billsnap.manager.ui.gallery

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.billsnap.manager.R

/**
 * ItemTouchHelper.Callback for swipe-to-pay (right) and swipe-to-delete (left)
 * with coloured background and icon drawn on canvas.
 */
class SwipeCallback(
    private val onSwipeRight: (Int) -> Unit,
    private val onSwipeLeft: (Int) -> Unit
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean = false

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        val position = viewHolder.adapterPosition
        if (direction == ItemTouchHelper.RIGHT) onSwipeRight(position)
        else onSwipeLeft(position)
    }

    override fun onChildDraw(
        c: Canvas, recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
    ) {
        val itemView = viewHolder.itemView
        val ctx = recyclerView.context
        val paint = Paint()

        if (dX > 0) {
            // Swiping right → Mark Paid (green)
            paint.color = ContextCompat.getColor(ctx, R.color.status_paid)
            val bg = RectF(itemView.left.toFloat(), itemView.top.toFloat(), dX, itemView.bottom.toFloat())
            c.drawRect(bg, paint)
            drawIcon(c, itemView, ContextCompat.getDrawable(ctx, R.drawable.ic_check), dX, true)
        } else if (dX < 0) {
            // Swiping left → Delete (red)
            paint.color = ContextCompat.getColor(ctx, R.color.status_unpaid)
            val bg = RectF(itemView.right.toFloat() + dX, itemView.top.toFloat(), itemView.right.toFloat(), itemView.bottom.toFloat())
            c.drawRect(bg, paint)
            drawIcon(c, itemView, ContextCompat.getDrawable(ctx, R.drawable.ic_delete), dX, false)
        }

        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
    }

    private fun drawIcon(c: Canvas, itemView: View, icon: Drawable?, dX: Float, isLeft: Boolean) {
        if (icon == null) return
        val iconSize = 48
        val iconMargin = 32
        val iconTop = itemView.top + (itemView.height - iconSize) / 2
        val iconBottom = iconTop + iconSize

        if (isLeft) {
            val iconLeft = itemView.left + iconMargin
            val iconRight = iconLeft + iconSize
            icon.setBounds(iconLeft, iconTop, iconRight, iconBottom)
        } else {
            val iconRight = itemView.right - iconMargin
            val iconLeft = iconRight - iconSize
            icon.setBounds(iconLeft, iconTop, iconRight, iconBottom)
        }
        icon.setTint(0xFFFFFFFF.toInt())
        icon.draw(c)
    }
}
