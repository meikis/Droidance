// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-FileCopyrightText: 2026 Lucide Icons and Contributors
// SPDX-FileCopyrightText: 2013-present Cole Bemis
// SPDX-License-Identifier: Apache-2.0 AND ISC AND MIT

package com.droidance.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Most functional UI icons share a 24-unit canvas and a 1.5-unit rounded outline.
 * Paths adapted from Lucide (ISC / Feather MIT):
 * https://github.com/lucide-icons/lucide/tree/1fae58d0a9c661a338036838caf3399b5507bab6/icons
 * Full license: assets/licenses/lucide-LICENSE.txt.
 */
internal object DroidanceIcons {
    // Lucide: plus.
    val Add: ImageVector by lazy {
        outlineIcon("Add",
            "M5 12h14",
            "M12 5v14",
        )
    }

    // Lucide: download.
    val Download: ImageVector by lazy {
        outlineIcon("Download",
            "M12 15V3",
            "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4",
            "m7 10 5 5 5-5",
        )
    }

    // Lucide: alarm-clock.
    val Alarm: ImageVector by lazy {
        outlineIcon("Alarm",
            "M4,13a8,8 0 1,0 16,0a8,8 0 1,0 -16,0Z",
            "M12 9v4l2 2",
            "M5 3 2 6",
            "m22 6-3-3",
            "M6.38 18.7 4 21",
            "M17.64 18.67 20 21",
        )
    }

    // Lucide: arrow-left.
    val ArrowBack: ImageVector by lazy {
        outlineIcon("ArrowBack",
            "m12 19-7-7 7-7",
            "M19 12H5",
            autoMirror = true
        )
    }

    // Lucide: battery-charging.
    val BatteryCharging: ImageVector by lazy {
        outlineIcon("BatteryCharging",
            "m11 7-3 5h4l-3 5",
            "M14.856 6H16a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-2.935",
            "M22 14v-4",
            "M5.14 18H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h2.936",
        )
    }

    // Lucide: battery.
    val Battery: ImageVector by lazy {
        outlineIcon("Battery",
            "M 22 14 L 22 10",
            "M4,6H16Q18,6 18,8V16Q18,18 16,18H4Q2,18 2,16V8Q2,6 4,6Z",
        )
    }

    // Lucide: zap.
    val Bolt: ImageVector by lazy {
        outlineIcon("Bolt",
            "M15.914 4a1.5 1.5 0 00-2.474-1.561l-9 9A1.5 1.5 0 005.5 14h4.002a.5.5 0 01.471.666L8.086 20a1.5 1.5 0 002.475 1.56l9-9A1.5 1.5 0 0018.5 10h-3.997a.5.5 0 01-.472-.667z",
        )
    }

    // Lucide: bot.
    val Bot: ImageVector by lazy {
        outlineIcon("Bot",
            "M12 8V4H8",
            "M6,8H18Q20,8 20,10V18Q20,20 18,20H6Q4,20 4,18V10Q4,8 6,8Z",
            "M2 14h2",
            "M20 14h2",
            "M15 13v2",
            "M9 13v2",
        )
    }

    // Lucide: calendar-days.
    val Calendar: ImageVector by lazy {
        outlineIcon("Calendar",
            "M8 2v3",
            "M16 2v3",
            "M5,3H19Q21,3 21,5V19Q21,21 19,21H5Q3,21 3,19V5Q3,3 5,3Z",
            "M3 9h18",
            "M8 13h.01",
            "M12 13h.01",
            "M16 13h.01",
            "M8 17h.01",
            "M12 17h.01",
            "M16 17h.01",
        )
    }

    // Lucide: check.
    val Check: ImageVector by lazy {
        outlineIcon("Check",
            "M20 6 9 17l-5-5",
        )
    }

    // Lucide: circle-check.
    val CheckCircle: ImageVector by lazy {
        outlineIcon("CheckCircle",
            "M2,12a10,10 0 1,0 20,0a10,10 0 1,0 -20,0Z",
            "m16 9-5.5 5.5L8 12",
        )
    }

    // Lucide: list-checks.
    val Checklist: ImageVector by lazy {
        outlineIcon("Checklist",
            "M13 5h8",
            "M13 12h8",
            "M13 19h8",
            "m3 17 2 2 4-4",
            "m3 7 2 2 4-4",
        )
    }

    // Lucide: chevron-down.
    val ChevronDown: ImageVector by lazy {
        outlineIcon("ChevronDown",
            "m6 9 6 6 6-6",
        )
    }

    // Lucide: chevron-right.
    val ChevronRight: ImageVector by lazy {
        outlineIcon("ChevronRight",
            "m9 18 6-6-6-6",
            autoMirror = true
        )
    }

    // Lucide: chevron-up.
    val ChevronUp: ImageVector by lazy {
        outlineIcon("ChevronUp",
            "m18 15-6-6-6 6",
        )
    }

    // Lucide: circle.
    val Circle: ImageVector by lazy {
        outlineIcon("Circle",
            "M2,12a10,10 0 1,0 20,0a10,10 0 1,0 -20,0Z",
        )
    }

    // Lucide: x.
    val Close: ImageVector by lazy {
        outlineIcon("Close",
            "M18 6 6 18",
            "m6 6 12 12",
        )
    }

    // Lucide: cloud-off.
    val CloudOff: ImageVector by lazy {
        outlineIcon("CloudOff",
            "M10.94 5.274A7 7 0 0 1 15.71 10h1.79a4.5 4.5 0 0 1 4.222 6.057",
            "M18.796 18.81A4.5 4.5 0 0 1 17.5 19H9A7 7 0 0 1 5.79 5.78",
            "m2 2 20 20",
        )
    }

    // Lucide: code.
    val Code: ImageVector by lazy {
        outlineIcon("Code",
            "m16 18 6-6-6-6",
            "m8 6-6 6 6 6",
        )
    }

    // Lucide: cpu.
    val Cpu: ImageVector by lazy {
        outlineIcon("Cpu",
            "M12 20v2",
            "M12 2v2",
            "M17 20v2",
            "M17 2v2",
            "M2 12h2",
            "M2 17h2",
            "M2 7h2",
            "M20 12h2",
            "M20 17h2",
            "M20 7h2",
            "M7 20v2",
            "M7 2v2",
            "M6,4H18Q20,4 20,6V18Q20,20 18,20H6Q4,20 4,18V6Q4,4 6,4Z",
            "M9,8H15Q16,8 16,9V15Q16,16 15,16H9Q8,16 8,15V9Q8,8 9,8Z",
        )
    }

    // Lucide: circle-alert.
    val Error: ImageVector by lazy {
        outlineIcon("Error",
            "M2,12a10,10 0 1,0 20,0a10,10 0 1,0 -20,0Z",
            "M12,8L12,12",
            "M12,16L12.01,16",
        )
    }

    // Lucide: eye.
    val Eye: ImageVector by lazy {
        outlineIcon("Eye",
            "M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0",
            "M9,12a3,3 0 1,0 6,0a3,3 0 1,0 -6,0Z",
        )
    }

    // Lucide: eye-off.
    val EyeOff: ImageVector by lazy {
        outlineIcon("EyeOff",
            "M10.733 5.076a10.744 10.744 0 0 1 11.205 6.575 1 1 0 0 1 0 .696 10.747 10.747 0 0 1-1.444 2.49",
            "M14.084 14.158a3 3 0 0 1-4.242-4.242",
            "M17.479 17.499a10.75 10.75 0 0 1-15.417-5.151 1 1 0 0 1 0-.696 10.75 10.75 0 0 1 4.446-5.143",
            "m2 2 20 20",
        )
    }

    // Lucide: file-text.
    val FileText: ImageVector by lazy {
        outlineIcon("FileText",
            "M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z",
            "M14 2v5a1 1 0 0 0 1 1h5",
            "M10 9H8",
            "M16 13H8",
            "M16 17H8",
        )
    }

    // Lucide: clock.
    val History: ImageVector by lazy {
        outlineIcon("History",
            "M2,12a10,10 0 1,0 20,0a10,10 0 1,0 -20,0Z",
            "M12 6v6l4 2",
        )
    }

    // Lucide: image.
    val Image: ImageVector by lazy {
        outlineIcon("Image",
            "M5,3H19Q21,3 21,5V19Q21,21 19,21H5Q3,21 3,19V5Q3,3 5,3Z",
            "M7,9a2,2 0 1,0 4,0a2,2 0 1,0 -4,0Z",
            "m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21",
        )
    }

    // Lucide: info.
    val Info: ImageVector by lazy {
        outlineIcon("Info",
            "M2,12a10,10 0 1,0 20,0a10,10 0 1,0 -20,0Z",
            "M12 16v-4",
            "M12 8h.01",
        )
    }

    // Lucide: languages.
    val Languages: ImageVector by lazy {
        outlineIcon("Languages",
            "m5 8 6 6",
            "m4 14 6-6 2-3",
            "M2 5h12",
            "M7 2h1",
            "m22 22-5-10-5 10",
            "M14 18h6",
        )
    }

    // Lucide: link.
    val Link: ImageVector by lazy {
        outlineIcon("Link",
            "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71",
            "M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71",
        )
    }

    // Lucide: lock-keyhole.
    val Lock: ImageVector by lazy {
        outlineIcon("Lock",
            "M11,16a1,1 0 1,0 2,0a1,1 0 1,0 -2,0Z",
            "M5,10H19Q21,10 21,12V20Q21,22 19,22H5Q3,22 3,20V12Q3,10 5,10Z",
            "M7 10V7a5 5 0 0 1 10 0v3",
        )
    }

    // Two-line sidebar toggle: a long and a short bar keep the top-left control light.
    val Menu: ImageVector by lazy {
        outlineIcon("Menu",
            "M4 9h16",
            "M4 15h10",
        )
    }

    // Lucide: message-square-check.
    val MessageCheck: ImageVector by lazy {
        outlineIcon("MessageCheck",
            "M22 17a2 2 0 0 1-2 2H6.828a2 2 0 0 0-1.414.586l-2.202 2.202A.7.7 0 0 1 2 21.286V5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2z",
            "m9 11 2 2 4-4",
            autoMirror = true
        )
    }

    // Lucide: ellipsis.
    val MoreHorizontal: ImageVector by lazy {
        outlineIcon("MoreHorizontal",
            "M11,12a1,1 0 1,0 2,0a1,1 0 1,0 -2,0Z",
            "M18,12a1,1 0 1,0 2,0a1,1 0 1,0 -2,0Z",
            "M4,12a1,1 0 1,0 2,0a1,1 0 1,0 -2,0Z",
        )
    }

    // Lucide: ellipsis-vertical.
    val MoreVertical: ImageVector by lazy {
        outlineIcon("MoreVertical",
            "M11,12a1,1 0 1,0 2,0a1,1 0 1,0 -2,0Z",
            "M11,5a1,1 0 1,0 2,0a1,1 0 1,0 -2,0Z",
            "M11,19a1,1 0 1,0 2,0a1,1 0 1,0 -2,0Z",
        )
    }

    // Lucide: network.
    val Network: ImageVector by lazy {
        outlineIcon("Network",
            "M17,16H21Q22,16 22,17V21Q22,22 21,22H17Q16,22 16,21V17Q16,16 17,16Z",
            "M3,16H7Q8,16 8,17V21Q8,22 7,22H3Q2,22 2,21V17Q2,16 3,16Z",
            "M10,2H14Q15,2 15,3V7Q15,8 14,8H10Q9,8 9,7V3Q9,2 10,2Z",
            "M5 16v-3a1 1 0 0 1 1-1h12a1 1 0 0 1 1 1v3",
            "M12 12V8",
        )
    }

    // Lucide: bell.
    val Notifications: ImageVector by lazy {
        outlineIcon("Notifications",
            "M10.268 21a2 2 0 0 0 3.464 0",
            "M3.262 15.326A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.673C19.41 13.956 18 12.499 18 8A6 6 0 0 0 6 8c0 4.499-1.411 5.956-2.738 7.326",
        )
    }

    // Lucide: palette.
    val Palette: ImageVector by lazy {
        outlineIcon("Palette",
            "M12 22a1 1 0 0 1 0-20 10 9 0 0 1 10 9 5 5 0 0 1-5 5h-2.25a1.75 1.75 0 0 0-1.4 2.8l.3.4a1.75 1.75 0 0 1-1.4 2.8z",
            "M13,6.5a0.5,0.5 0 1,0 1,0a0.5,0.5 0 1,0 -1,0Z",
            "M17,10.5a0.5,0.5 0 1,0 1,0a0.5,0.5 0 1,0 -1,0Z",
            "M6,12.5a0.5,0.5 0 1,0 1,0a0.5,0.5 0 1,0 -1,0Z",
            "M8,7.5a0.5,0.5 0 1,0 1,0a0.5,0.5 0 1,0 -1,0Z",
        )
    }

    // Lucide: user-round.
    val Person: ImageVector by lazy {
        outlineIcon("Person",
            "M7,8a5,5 0 1,0 10,0a5,5 0 1,0 -10,0Z",
            "M20 21a8 8 0 0 0-16 0",
        )
    }

    // Phone screen, using the shared outline geometry.
    val Smartphone: ImageVector by lazy {
        outlineIcon("Smartphone",
            "M7,2H17Q19,2 19,4V20Q19,22 17,22H7Q5,22 5,20V4Q5,2 7,2Z",
            "M12 18h.01",
        )
    }

    // Lucide: layers-2.
    val Layers: ImageVector by lazy {
        outlineIcon("Layers",
            "M10.5 2.5a3 3 0 0 1 3 0l8 4.6a1 1 0 0 1 0 1.8l-8 4.6a3 3 0 0 1-3 0l-8-4.6a1 1 0 0 1 0-1.8z",
            "m2 16 8.5 4.9a3 3 0 0 0 3 0L22 16",
        )
    }

    // Lucide: phone.
    val Phone: ImageVector by lazy {
        outlineIcon("Phone",
            "M13.832 16.568a1 1 0 0 0 1.213-.303l.355-.465A2 2 0 0 1 17 15h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2A18 18 0 0 1 2 4a2 2 0 0 1 2-2h3a2 2 0 0 1 2 2v3a2 2 0 0 1-.8 1.6l-.468.351a1 1 0 0 0-.292 1.233 14 14 0 0 0 6.392 6.384",
            autoMirror = true
        )
    }

    // Lucide: pencil.
    val Pencil: ImageVector by lazy {
        outlineIcon("Pencil",
            "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z",
            "m15 5 4 4",
        )
    }

    // Lucide: play.
    val Play: ImageVector by lazy {
        outlineIcon("Play",
            "M5 5a2 2 0 0 1 3.008-1.728l11.997 6.998a2 2 0 0 1 .003 3.458l-12 7A2 2 0 0 1 5 19z",
            autoMirror = true
        )
    }

    // Lucide: refresh-cw.
    val Refresh: ImageVector by lazy {
        outlineIcon("Refresh",
            "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8",
            "M21 3v5h-5",
            "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16",
            "M8 16H3v5",
        )
    }

    // Lucide: search.
    val Search: ImageVector by lazy {
        outlineIcon("Search",
            "m21 21-4.34-4.34",
            "M3,11a8,8 0 1,0 16,0a8,8 0 1,0 -16,0Z",
        )
    }

    // Lucide: settings.
    val Settings: ImageVector by lazy {
        outlineIcon("Settings",
            "M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915",
            "M9,12a3,3 0 1,0 6,0a3,3 0 1,0 -6,0Z",
        )
    }

    // Lucide: shield.
    val Shield: ImageVector by lazy {
        outlineIcon("Shield",
            "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z",
        )
    }

    // Lucide: sliders-horizontal.
    val Sliders: ImageVector by lazy {
        outlineIcon("Sliders",
            "M10 5H3",
            "M12 19H3",
            "M14 3v4",
            "M16 17v4",
            "M21 12h-9",
            "M21 19h-5",
            "M21 5h-7",
            "M8 10v4",
            "M8 12H3",
        )
    }

    // Droidance: one concave four-point star with a small glint, sized to the 24-unit outline set.
    val Sparkles: ImageVector by lazy {
        outlineIcon("Sparkles",
            "M10 5C10.6 10.4 12.6 12.4 18 13C12.6 13.6 10.6 15.6 10 21C9.4 15.6 7.4 13.6 2 13C7.4 12.4 9.4 10.4 10 5Z",
            "M18.5 2.5C18.75 4.75 19.25 5.25 21.5 5.5C19.25 5.75 18.75 6.25 18.5 8.5C18.25 6.25 17.75 5.75 15.5 5.5C17.75 5.25 18.25 4.75 18.5 2.5Z",
        )
    }

    // Lucide: database.
    val Storage: ImageVector by lazy {
        outlineIcon("Storage",
            "M3,5a9,3 0 1,0 18,0a9,3 0 1,0 -18,0Z",
            "M3 5V19A9 3 0 0 0 21 19V5",
            "M3 12A9 3 0 0 0 21 12",
        )
    }

    // ChatGPT queued-message steering arrow (21-unit canvas).
    val Steer: ImageVector by lazy {
        ImageVector.Builder(
            name = "Steer",
            defaultWidth = 21.dp,
            defaultHeight = 21.dp,
            viewportWidth = 21f,
            viewportHeight = 21f
        ).apply {
            addPath(
                pathData = PathParser().parsePathString(
                    "M13.1293 7.34753C13.3565 7.12027 13.7081 7.09207 13.9662 7.26257L14.0707 7.34753L18.0707 11.3475C18.3304 11.6072 18.3304 12.0292 18.0707 12.2889L14.0707 16.2889C13.811 16.5486 13.389 16.5486 13.1293 16.2889C12.8696 16.0292 12.8696 15.6072 13.1293 15.3475L15.9935 12.4833H6.59998C4.57585 12.4833 2.93494 10.8424 2.93494 8.81824V5.31824C2.93494 4.95097 3.23271 4.6532 3.59998 4.6532C3.96724 4.6532 4.26501 4.95097 4.26501 5.31824V8.81824C4.26501 10.1078 5.31039 11.1532 6.59998 11.1532H15.9935L13.1293 8.28894L13.0443 8.18445C12.8738 7.92632 12.902 7.5748 13.1293 7.34753Z"
                ).toNodes(),
                fill = SolidColor(Color.Black)
            )
        }.build()
    }

    // Lucide: trash.
    val Trash: ImageVector by lazy {
        outlineIcon("Trash",
            "M10 11v6",
            "M14 11v6",
            "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6",
            "M3 6h18",
            "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2",
        )
    }

    // Lucide: triangle-alert.
    val Warning: ImageVector by lazy {
        outlineIcon("Warning",
            "m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3",
            "M12 9v4",
            "M12 17h.01",
        )
    }

    val WifiOff: ImageVector by lazy {
        outlineIcon("WifiOff",
            "M2 2l20 20",
            "M12 20h.01",
            "M2 8.82a15 15 0 0 1 4-2.4",
            "M10.6 5.1A15 15 0 0 1 22 8.82",
            "M5 12.859a10 10 0 0 1 4-2.2",
            "M14.8 10.5a10 10 0 0 1 4.2 2.36",
            "M8.5 16.429a5 5 0 0 1 7 0",
        )
    }

    // Lucide: wifi.
    val Wifi: ImageVector by lazy {
        outlineIcon("Wifi",
            "M12 20h.01",
            "M2 8.82a15 15 0 0 1 20 0",
            "M5 12.859a10 10 0 0 1 14 0",
            "M8.5 16.429a5 5 0 0 1 7 0",
        )
    }

}

private fun outlineIcon(
    name: String,
    vararg paths: String,
    autoMirror: Boolean = false
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
    autoMirror = autoMirror
).apply {
    paths.forEach { data ->
        addPath(
            pathData = PathParser().parsePathString(data).toNodes(),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        )
    }
}.build()
