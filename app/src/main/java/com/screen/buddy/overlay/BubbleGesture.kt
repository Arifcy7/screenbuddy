package com.screen.buddy.overlay

sealed class BubbleGesture {
    data object Tap : BubbleGesture()
    data object HoldStart : BubbleGesture()
    data object HoldEnd : BubbleGesture()
}
