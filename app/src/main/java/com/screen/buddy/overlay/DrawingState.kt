package com.screen.buddy.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class DrawOp {
    data class Ring(val x: Int, val y: Int, val label: String? = null) : DrawOp()
    data class HighlightBox(
        val ymin: Int,
        val xmin: Int,
        val ymax: Int,
        val xmax: Int,
        val label: String? = null,
    ) : DrawOp()
    data class Arrow(
        val fromX: Int,
        val fromY: Int,
        val toX: Int,
        val toY: Int,
        val label: String? = null,
    ) : DrawOp()
}

class DrawingState {
    private val _ops = MutableStateFlow<List<DrawOp>>(emptyList())
    val ops: StateFlow<List<DrawOp>> = _ops.asStateFlow()

    fun clear() {
        _ops.value = emptyList()
    }
}
