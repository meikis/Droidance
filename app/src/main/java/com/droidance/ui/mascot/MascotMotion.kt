// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.mascot

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Presentation states only. These never determine agent behavior or task success. */
enum class MascotMood { WELCOME, DANCING, LISTENING, THINKING, OPERATING, TAKEOVER }
enum class MascotFraming { FULL, PORTRAIT, HEAD }

data class MascotPose(
    val headTilt: Float = 0f,
    val gazeX: Float = 0f,
    val gazeY: Float = 0f,
    val eyeOpen: Float = 1f,
    val breath: Float = 0f,
    val nod: Float = 0f,
    val smile: Float = 1f,
    val sway: Float = 0f,
    val lift: Float = 0f,
    val bodyTilt: Float = 0f,
    val squash: Float = 0f
)

/** Retarget from the pose currently on screen, including an unfinished transition. */
internal data class MascotTransition(
    private val from: MascotPose,
    private val mood: MascotMood,
    private val startedAtMs: Long
) {
    constructor(mood: MascotMood) : this(MascotMotion.pose(0, mood), mood, 0)

    fun retarget(elapsedMs: Long, mood: MascotMood): MascotTransition =
        if (mood == this.mood) this else MascotTransition(pose(elapsedMs), mood, elapsedMs)

    fun pose(elapsedMs: Long, reducedMotion: Boolean = false): MascotPose {
        val target = MascotMotion.pose(elapsedMs, mood, reducedMotion)
        return if (reducedMotion) target else MascotMotion.blend(from, target,
            (elapsedMs - startedAtMs) / 220f)
    }
}

object MascotMotion {
    const val CYCLE_MS = 8_000L

    /** Runtime islands dance throughout execution, including long waits between tool calls. */
    fun loopingDancePose(elapsedMs: Long, reducedMotion: Boolean = false): MascotPose {
        if (reducedMotion) return MascotPose()
        val t = (elapsedMs.coerceAtLeast(0L) % CYCLE_MS).toFloat()
        val beat = t / 1600f * 2f * PI + .35 * sin(t / CYCLE_MS * 2f * PI)
        val step = sin(beat).toFloat()
        val bounce = (0.5 - 0.5 * cos(beat * 2)).toFloat() * (.9f + .1f * step)
        return pose(elapsedMs, MascotMood.LISTENING).copy(
            headTilt = -5f * sin(beat - .45).toFloat(), gazeX = step * .012f,
            nod = bounce * .5f, smile = 1.3f,
            sway = step * 4f * (.96f + .04f * step), lift = bounce * 3f,
            bodyTilt = sin(beat - .10).toFloat() * 5f,
            squash = cos(beat * 2).toFloat() * .018f
        )
    }

    fun blend(from: MascotPose, to: MascotPose, progress: Float): MascotPose {
        val u = smooth(progress)
        fun mix(a: Float, b: Float) = a + (b - a) * u
        return MascotPose(mix(from.headTilt, to.headTilt), mix(from.gazeX, to.gazeX),
            mix(from.gazeY, to.gazeY), mix(from.eyeOpen, to.eyeOpen),
            mix(from.breath, to.breath), mix(from.nod, to.nod), mix(from.smile, to.smile),
            mix(from.sway, to.sway), mix(from.lift, to.lift),
            mix(from.bodyTilt, to.bodyTilt), mix(from.squash, to.squash))
    }

    fun pose(elapsedMs: Long, mood: MascotMood, reducedMotion: Boolean = false): MascotPose {
        if (reducedMotion) return MascotPose()
        val t = (elapsedMs.coerceAtLeast(0L) % CYCLE_MS).toFloat()
        val breath = sin(t / CYCLE_MS * 2f * PI).toFloat()
        // A blink and a brief double-blink separated by long, quiet holds.
        val blink = maxOf(pulse(t, 2800f, 150f), pulse(t, 6220f, 140f), pulse(t, 6500f, 130f))
        val look = window(t, 1050f, 1700f, 3150f, 3850f)
        val follow = window(t, 1230f, 2000f, 3300f, 4100f)
        return when (mood) {
            MascotMood.WELCOME -> {
                val idleTilt = 0.6f * follow
                // Ease into the greeting, then settle onto the idle pose without a jump.
                val greetingWeight = 1f - smooth((elapsedMs - 1600L) / 600f)
                val greetingTilt = if (greetingWeight > 0f)
                    3.5f * sin(elapsedMs / 2200f * 2f * PI).toFloat() * smooth(elapsedMs / 200f)
                else idleTilt
                MascotPose(
                    headTilt = idleTilt + (greetingTilt - idleTilt) * greetingWeight,
                    gazeY = if (elapsedMs < 1500L) 0.035f * (1f - smooth(elapsedMs / 1500f)) else 0f,
                    eyeOpen = 1f - blink, breath = breath,
                    nod = if (elapsedMs < 1800L) pulse(elapsedMs.toFloat(), 900f, 650f) else 0f,
                    smile = 1f + if (elapsedMs < 1800L) 0.35f * pulse(elapsedMs.toFloat(), 900f, 650f) else 0f
                )
            }
            MascotMood.DANCING -> {
                // Let the pace breathe across the phrase, with a lighter return step.
                // The envelope still settles at zero velocity before the quiet pause.
                val energy = window(t, 200f, 1000f, 5000f, 6400f)
                val steadyBeat = t / 1600f * 2f * PI
                val beat = steadyBeat + .35 * sin(steadyBeat / 3)
                val step = sin(beat).toFloat()
                val bounce = (0.5 - 0.5 * cos(beat * 2)).toFloat() * (.9f + .1f * step)
                MascotPose(
                    headTilt = -5f * sin(beat - .45).toFloat() * energy,
                    gazeX = step * .012f * energy,
                    eyeOpen = 1f - blink, breath = breath,
                    nod = bounce * energy * .5f, smile = 1f + .3f * energy,
                    sway = step * 4f * (.96f + .04f * step) * energy,
                    lift = bounce * 3f * energy,
                    bodyTilt = sin(beat - .10).toFloat() * 5f * energy,
                    squash = cos(beat * 2).toFloat() * .018f * energy
                )
            }
            MascotMood.LISTENING -> MascotPose(headTilt = 0.7f * follow, eyeOpen = 1f - blink, breath = breath)
            MascotMood.THINKING -> {
                // A brief glance leads each upright head tilt; a small spring brings it home.
                val leftLook = window(t, 1050f, 1400f, 1950f, 2400f)
                val leftTilt = window(t, 1230f, 1650f, 2100f, 2650f)
                val rightLook = window(t, 4450f, 4800f, 5350f, 5800f)
                val rightTilt = window(t, 4650f, 5100f, 5500f, 6050f)
                MascotPose(
                    headTilt = -5f * leftTilt + 5f * rightTilt +
                        .65f * pulse(t, 2800f, 240f) - .65f * pulse(t, 6200f, 240f),
                    gazeX = -.018f * leftLook + .018f * rightLook,
                    eyeOpen = 1f - blink, breath = breath * .8f,
                    sway = -.6f * leftTilt + .6f * rightTilt,
                    bodyTilt = -.7f * leftTilt + .7f * rightTilt
                )
            }
            MascotMood.OPERATING -> {
                // Upright weight shifts, one buoyant rebound, then a quiet hold.
                // The head counterbalances a little later than the body, without bowing.
                val workTime = t % 4000f
                val leftShift = window(workTime, 200f, 600f, 850f, 1250f)
                val rightShift = window(workTime, 1350f, 1750f, 1950f, 2350f)
                val headLeft = window(workTime, 380f, 780f, 1000f, 1450f)
                val headRight = window(workTime, 1550f, 1950f, 2100f, 2550f)
                val rebound = pulse(workTime, 2000f, 480f)
                MascotPose(
                    headTilt = .9f * headLeft - .9f * headRight,
                    gazeX = .006f * (rightShift - leftShift),
                    eyeOpen = 1f - blink, breath = breath * .8f,
                    sway = -1.8f * leftShift + 1.6f * rightShift,
                    lift = 2.6f * rebound,
                    bodyTilt = -3f * leftShift + 2.6f * rightShift,
                    squash = -.010f * pulse(workTime, 620f, 330f) + .007f * rebound -
                        .010f * pulse(workTime, 2450f, 250f)
                )
            }
            MascotMood.TAKEOVER -> MascotPose(headTilt = 3f * follow, gazeX = 0.016f * look,
                eyeOpen = 1f - blink, breath = breath * 0.4f, smile = .65f)
        }
    }

    private fun pulse(t: Float, center: Float, halfWidth: Float): Float {
        val u = (t - center) / halfWidth
        return if (u <= -1f || u >= 1f) 0f else (0.5 + 0.5 * cos(u * PI)).toFloat()
    }
    private fun window(t: Float, a: Float, b: Float, c: Float, d: Float): Float = when {
        t < a -> 0f
        t < b -> smooth((t - a) / (b - a))
        t < c -> 1f
        t < d -> 1f - smooth((t - c) / (d - c))
        else -> 0f
    }
    private fun smooth(t: Float): Float = t.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
}
