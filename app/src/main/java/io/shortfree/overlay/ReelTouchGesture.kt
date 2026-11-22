// 릴스 화면에서 받은 터치가 다음 영상으로 향하는 한 손가락 스와이프인지 판정합니다.
package io.shortfree.overlay

import kotlin.math.abs

internal data class TouchPoint(val x: Float, val y: Float)

internal data class TouchStroke(
    val points: List<TouchPoint>,
    val startedMs: Long,
    val durationMs: Long,
)

internal class ReelTouchGesture(private val swipeDistance: Float, private val downTime: Long) {
    private data class Track(
        val startedMs: Long,
        val points: MutableList<TouchPoint>,
        var endedMs: Long,
    )

    private val tracks = linkedMapOf<Int, Track>()
    private var movedUp = false

    val isNextReelSwipe get() = tracks.size == 1 && movedUp

    fun start(pointerId: Int, point: TouchPoint, atMs: Long) {
        val offset = (atMs - downTime).coerceAtLeast(0)
        tracks[pointerId] = Track(offset, mutableListOf(point), offset)
    }

    fun sample(pointerId: Int, point: TouchPoint, atMs: Long) {
        val track = tracks[pointerId] ?: return
        val start = track.points.first()
        val up = start.y - point.y
        movedUp = movedUp || (up >= swipeDistance && up >= abs(point.x - start.x) * VERTICAL_BIAS)

        if (track.points.last() != point) {
            if (track.points.size == MAX_POINTS) track.points.removeAt(MAX_POINTS - 1)
            track.points.add(point)
        }
        track.endedMs = (atMs - downTime).coerceAtLeast(track.startedMs)
    }

    fun strokes(): List<TouchStroke> = tracks.values.map { track ->
        TouchStroke(
            points = track.points.toList(),
            startedMs = track.startedMs,
            durationMs = (track.endedMs - track.startedMs).coerceAtLeast(1),
        )
    }

    private companion object {
        const val VERTICAL_BIAS = 1.2f
        const val MAX_POINTS = 160
    }
}
