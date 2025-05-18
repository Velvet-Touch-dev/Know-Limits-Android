package com.example.randomsceneapp.cardstack

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.min

class CardStackView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var adapter: RecyclerView.Adapter<RecyclerView.ViewHolder>? = null
    private var topCardPosition = 0
    private var swipeThreshold = 0.3f // percentage of view width to trigger swipe
    private var maxRotation = 15f // maximum rotation in degrees
    private var swipeDirection = SwipeDirection.NONE
    private val visibleCardCount = 2 // number of cards visible at once
    private val cardGap = dpToPx(8) // gap between cards
    private val scaleDecrement = 0.05f // scale down factor for each card behind top card
    
    private var onCardSwipedListener: ((direction: SwipeDirection, position: Int) -> Unit)? = null
    private lateinit var gestureDetector: GestureDetector
    
    private var initialX = 0f
    private var initialY = 0f
    private var dx = 0f
    private var dy = 0f
    private var isBeingDragged = false
    
    enum class SwipeDirection {
        LEFT, RIGHT, NONE
    }
    
    init {
        setupGestureDetector()
    }
    
    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                return true
            }
        })
    }
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        
        if (childCount == 0) return false
        
        val topCard = getChildAt(childCount - 1)
        
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = event.x
                initialY = event.y
                isBeingDragged = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isBeingDragged) return false
                
                dx = event.x - initialX
                dy = event.y - initialY
                
                // Move the card
                topCard.translationX = dx
                topCard.translationY = dy
                
                // Calculate rotation based on horizontal movement
                val rotation = calculateRotation(dx)
                topCard.rotation = rotation
                
                // Determine swipe direction
                swipeDirection = when {
                    dx > width * swipeThreshold -> SwipeDirection.RIGHT
                    dx < -width * swipeThreshold -> SwipeDirection.LEFT
                    else -> SwipeDirection.NONE
                }
                
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isBeingDragged = false
                
                if (swipeDirection != SwipeDirection.NONE) {
                    // Card has passed the threshold for a swipe, animate it off screen
                    animateSwipe(topCard, swipeDirection)
                } else {
                    // Card hasn't passed the threshold, return to center
                    animateReturn(topCard)
                }
                
                return true
            }
        }
        
        return false
    }
    
    private fun calculateRotation(dx: Float): Float {
        // Calculate rotation based on how far the card has moved horizontally
        // Maximum rotation is maxRotation degrees in either direction
        val percentage = min(abs(dx) / (width / 2), 1f)
        return maxRotation * percentage * if (dx > 0) 1 else -1
    }
    
    private fun animateSwipe(view: View, direction: SwipeDirection) {
        val targetX = if (direction == SwipeDirection.RIGHT) width * 1.5f else -width * 1.5f
        
        val translationAnimator = ObjectAnimator.ofFloat(view, "translationX", view.translationX, targetX)
        translationAnimator.duration = 200
        translationAnimator.interpolator = AccelerateDecelerateInterpolator()
        
        translationAnimator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                removeView(view)
                onCardSwipedListener?.invoke(direction, topCardPosition)
                
                if (adapter != null && topCardPosition < (adapter?.itemCount ?: 0) - 1) {
                    // Increment to next card
                    topCardPosition++
                    addNextCard()
                } else if (childCount == 0) {
                    // Reset to beginning if we've reached the end
                    topCardPosition = 0
                    populateCards()
                }
            }
        })
        
        translationAnimator.start()
    }
    
    private fun animateReturn(view: View) {
        val translationXAnimator = ObjectAnimator.ofFloat(view, "translationX", view.translationX, 0f)
        val translationYAnimator = ObjectAnimator.ofFloat(view, "translationY", view.translationY, 0f)
        val rotationAnimator = ObjectAnimator.ofFloat(view, "rotation", view.rotation, 0f)
        
        translationXAnimator.duration = 200
        translationYAnimator.duration = 200
        rotationAnimator.duration = 200
        
        translationXAnimator.interpolator = DecelerateInterpolator()
        translationYAnimator.interpolator = DecelerateInterpolator()
        rotationAnimator.interpolator = DecelerateInterpolator()
        
        translationXAnimator.start()
        translationYAnimator.start()
        rotationAnimator.start()
    }
    
    @Suppress("UNCHECKED_CAST")
    fun setAdapter(adapter: RecyclerView.Adapter<*>) {
        this.adapter = adapter as RecyclerView.Adapter<RecyclerView.ViewHolder>
        topCardPosition = 0
        removeAllViews()
        
        if (adapter.itemCount > 0) {
            populateCards()
        }
        
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                removeAllViews()
                topCardPosition = 0
                populateCards()
            }
        })
    }
    
    private fun populateCards() {
        removeAllViews()
        
        // Add cards from bottom to top
        val cardCount = min(visibleCardCount, adapter?.itemCount ?: 0)
        for (i in 0 until cardCount) {
            val position = topCardPosition + i
            if (position < (adapter?.itemCount ?: 0)) {
                addCard(position, i)
            }
        }
    }
    
    private fun addNextCard() {
        val nextPosition = topCardPosition + childCount
        if (nextPosition < (adapter?.itemCount ?: 0)) {
            addCard(nextPosition, childCount)
        }
    }
    
    private fun addCard(adapterPosition: Int, stackPosition: Int) {
        val viewHolder = adapter?.createViewHolder(this, adapter?.getItemViewType(adapterPosition) ?: 0)
        if (viewHolder != null) {
            adapter?.onBindViewHolder(viewHolder, adapterPosition)
            val cardView = viewHolder.itemView
            
            val layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            
            // Scale down and position cards behind the top card
            val scale = 1f - (scaleDecrement * stackPosition)
            cardView.scaleX = scale
            cardView.scaleY = scale
            cardView.translationY = cardGap * stackPosition.toFloat()
            
            // Add to the bottom of the stack
            addView(cardView, 0, layoutParams)
        }
    }
    
    fun setOnCardSwipedListener(listener: (direction: SwipeDirection, position: Int) -> Unit) {
        onCardSwipedListener = listener
    }
    
    fun swipeTopCardLeft() {
        if (childCount > 0) {
            val topCard = getChildAt(childCount - 1)
            animateSwipe(topCard, SwipeDirection.LEFT)
        }
    }
    
    fun swipeTopCardRight() {
        if (childCount > 0) {
            val topCard = getChildAt(childCount - 1)
            animateSwipe(topCard, SwipeDirection.RIGHT)
        }
    }
    
    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
