package com.screen.buddy.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

sealed class DrawOp {
    abstract val captureOrientation: Int

    data class Ring(
        val x: Int,
        val y: Int,
        val label: String? = null,
        override val captureOrientation: Int = 0,
    ) : DrawOp()

    data class HighlightBox(
        val ymin: Int,
        val xmin: Int,
        val ymax: Int,
        val xmax: Int,
        val label: String? = null,
        override val captureOrientation: Int = 0,
    ) : DrawOp()

    data class Arrow(
        val fromX: Int,
        val fromY: Int,
        val toX: Int,
        val toY: Int,
        val label: String? = null,
        override val captureOrientation: Int = 0,
    ) : DrawOp()
}

@Singleton
class DrawingState @Inject constructor() {
    private val _ops = MutableStateFlow<List<DrawOp>>(emptyList())
    val ops: StateFlow<List<DrawOp>> = _ops.asStateFlow()

    fun add(op: DrawOp) {
        _ops.update { it + op }
    }

    fun clear() {
        _ops.value = emptyList()
    }
}
