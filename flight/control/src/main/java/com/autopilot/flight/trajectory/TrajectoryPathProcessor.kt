package com.autopilot.flight.trajectory

import kotlin.math.abs
import kotlin.math.hypot

data class NormPoint(
  val x: Float,
  val y: Float,
)

data class TrajectoryToolConfig(
  val minimumPointSpacing: Float = 0.015f,
  val smoothingPasses: Int = 1,
  val simplificationTolerance: Float = 0.018f,
  val minimumSegmentLength: Float = 0.025f,
  val maxSegments: Int = 12,
  val requireExplicitConfirmation: Boolean = true,
)

data class TrajectoryWaypoint(
  val index: Int,
  val point: NormPoint,
  val headingDx: Float,
  val headingDy: Float,
  val segmentLengthNorm: Float,
)

data class CleanedTrajectoryPath(
  val rawPointCount: Int,
  val cleanedPoints: List<NormPoint>,
  val waypoints: List<TrajectoryWaypoint>,
  val totalNormalizedLength: Float,
  val hasShortSegmentWarning: Boolean,
  val isValidForPreview: Boolean,
)

/**
 * Separates raw finger ink from cleaned/smoothed geometry and directional waypoint vectors
 * without inventing fake metric altitudes, GPS coordinates, or physical distances.
 */
object TrajectoryPathProcessor {

  fun process(
    rawPoints: List<NormPoint>,
    config: TrajectoryToolConfig = TrajectoryToolConfig(),
  ): CleanedTrajectoryPath {
    if (rawPoints.size < 2) {
      return CleanedTrajectoryPath(
        rawPointCount = rawPoints.size,
        cleanedPoints = rawPoints,
        waypoints = emptyList(),
        totalNormalizedLength = 0f,
        hasShortSegmentWarning = false,
        isValidForPreview = false,
      )
    }

    val deduped = mutableListOf(rawPoints.first())
    for (i in 1 until rawPoints.size) {
      val prev = deduped.last()
      val cur = rawPoints[i]
      if (hypot(cur.x - prev.x, cur.y - prev.y) >= config.minimumPointSpacing) {
        deduped += cur
      }
    }
    if (deduped.size == 1 && hypot(rawPoints.last().x - deduped.first().x, rawPoints.last().y - deduped.first().y) >= 0.004f) {
      deduped += rawPoints.last()
    }

    var smoothed: List<NormPoint> = deduped
    repeat(config.smoothingPasses.coerceIn(0, 2)) {
      if (smoothed.size >= 3) {
        val next = mutableListOf(smoothed.first())
        for (i in 1 until smoothed.lastIndex) {
          val a = smoothed[i - 1]
          val b = smoothed[i]
          val c = smoothed[i + 1]
          next += NormPoint(x = (a.x + 2f * b.x + c.x) * 0.25f, y = (a.y + 2f * b.y + c.y) * 0.25f)
        }
        next += smoothed.last()
        smoothed = next
      }
    }

    val simplified = rdpSimplify(smoothed, config.simplificationTolerance).take(config.maxSegments + 1)

    var totalLen = 0f
    var shortWarn = false
    val waypoints = mutableListOf<TrajectoryWaypoint>()
    for (i in 0 until simplified.lastIndex) {
      val a = simplified[i]
      val b = simplified[i + 1]
      val dx = b.x - a.x
      val dy = b.y - a.y
      val segLen = hypot(dx, dy)
      if (segLen < config.minimumSegmentLength) {
        shortWarn = true
        continue
      }
      totalLen += segLen
      waypoints +=
        TrajectoryWaypoint(
          index = waypoints.size + 1,
          point = b,
          headingDx = dx / segLen,
          headingDy = dy / segLen,
          segmentLengthNorm = segLen,
        )
    }

    return CleanedTrajectoryPath(
      rawPointCount = rawPoints.size,
      cleanedPoints = simplified,
      waypoints = waypoints,
      totalNormalizedLength = totalLen,
      hasShortSegmentWarning = shortWarn && waypoints.isEmpty(),
      isValidForPreview = waypoints.isNotEmpty() && totalLen >= config.minimumSegmentLength,
    )
  }

  private fun rdpSimplify(points: List<NormPoint>, epsilon: Float): List<NormPoint> {
    if (points.size < 3) return points
    val first = points.first()
    val last = points.last()
    var maxDist = 0f
    var maxIdx = 0
    for (i in 1 until points.lastIndex) {
      val d = perpendicularDistance(points[i], first, last)
      if (d > maxDist) {
        maxDist = d
        maxIdx = i
      }
    }
    return if (maxDist > epsilon) {
      val left = rdpSimplify(points.subList(0, maxIdx + 1), epsilon)
      val right = rdpSimplify(points.subList(maxIdx, points.size), epsilon)
      left.dropLast(1) + right
    } else {
      listOf(first, last)
    }
  }

  private fun perpendicularDistance(p: NormPoint, a: NormPoint, b: NormPoint): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len = hypot(dx, dy)
    if (len < 1e-5f) return hypot(p.x - a.x, p.y - a.y)
    return abs(dy * p.x - dx * p.y + b.x * a.y - b.y * a.x) / len
  }
}
