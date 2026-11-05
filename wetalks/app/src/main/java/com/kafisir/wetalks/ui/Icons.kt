package com.kafisir.wetalks.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Custom line icons (24 grid, 2px stroke). Tinted at draw time. */
object Ic {
    private fun icon(name: String, vararg d: String): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        d.forEach { addPath(PathParser().parsePathString(it).toNodes(), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) }
    }.build()

    val Back: ImageVector by lazy { icon("Back", "M15 5L8 12L15 19") }
    val Close: ImageVector by lazy { icon("Close", "M6 6L18 18M18 6L6 18") }
    val Plus: ImageVector by lazy { icon("Plus", "M12 5V19M5 12H19") }
    val Search: ImageVector by lazy { icon("Search", "M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14M16.5 16.5L21 21") }
    val Send: ImageVector by lazy { icon("Send", "M4 12L20 4L13 20L11 13Z") }
    val Mic: ImageVector by lazy { icon("Mic", "M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3ZM5 11a7 7 0 0 0 14 0M12 18v3") }
    val Lock: ImageVector by lazy { icon("Lock", "M6 11h12a1 1 0 0 1 1 1v7a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1v-7a1 1 0 0 1 1-1ZM8 11V8a4 4 0 0 1 8 0v3") }
    val Dots: ImageVector by lazy { icon("Dots", "M12 4.5a1.4 1.4 0 1 0 0 2.8a1.4 1.4 0 1 0 0-2.8M12 10.6a1.4 1.4 0 1 0 0 2.8a1.4 1.4 0 1 0 0-2.8M12 16.7a1.4 1.4 0 1 0 0 2.8a1.4 1.4 0 1 0 0-2.8") }
    val Chat: ImageVector by lazy { icon("Chat", "M20 12a8 8 0 0 1-11.5 7.2L4 20l1-4.3A8 8 0 1 1 20 12Z") }
    val Group: ImageVector by lazy { icon("Group", "M9 11a3 3 0 1 0 0-6a3 3 0 0 0 0 6ZM3 20c0-3 2.7-5 6-5s6 2 6 5M16 11a2.5 2.5 0 1 0 0-5M17 15c2.3.3 4 1.9 4 5") }
    val Mega: ImageVector by lazy { icon("Mega", "M4 10v4h3l7 4V6L7 10H4ZM17.5 9a4 4 0 0 1 0 6") }
    val User: ImageVector by lazy { icon("User", "M12 12a4 4 0 1 0 0-8a4 4 0 0 0 0 8ZM4 21c0-4 3.6-6.5 8-6.5s8 2.5 8 6.5") }
    val Check: ImageVector by lazy { icon("Check", "M5 12.5L10 17.5L19 7") }
    val Check2: ImageVector by lazy { icon("Check2", "M2 12.5L7 17.5L16 7M10 15l2.5 2.5L21.5 7") }
    val Image: ImageVector by lazy { icon("Image", "M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1ZM8.5 10a1.5 1.5 0 1 0 0-3a1.5 1.5 0 0 0 0 3ZM4 17l5-5l4 4l3-3l4 4") }
    val Smile: ImageVector by lazy { icon("Smile", "M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18ZM8 14c1 1.5 2.4 2 4 2s3-.5 4-2M9 9.5h.01M15 9.5h.01") }
    val Reply: ImageVector by lazy { icon("Reply", "M10 8V4L3 11l7 7v-4c6 0 9 2 11 6c-.5-6-4-10-11-12Z") }
    val Trash: ImageVector by lazy { icon("Trash", "M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13M10 11v6M14 11v6") }
    val Copy: ImageVector by lazy { icon("Copy", "M9 9h10a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V10a1 1 0 0 1 1-1ZM5 15V5a1 1 0 0 1 1-1h10") }
    val Edit: ImageVector by lazy { icon("Edit", "M4 20h4L19 9l-4-4L4 16v4ZM13.5 6.5l4 4") }
    val Play: ImageVector by lazy { icon("Play", "M7 4l13 8L7 20Z") }
    val Pause: ImageVector by lazy { icon("Pause", "M8 4v16M16 4v16") }
    val Stop: ImageVector by lazy { icon("Stop", "M6 6h12v12H6Z") }
    val UserPlus: ImageVector by lazy { icon("UserPlus", "M10 12a4 4 0 1 0 0-8a4 4 0 0 0 0 8ZM3 21c0-4 3-6.5 7-6.5M18 8v6M15 11h6") }
    val Bell: ImageVector by lazy { icon("Bell", "M6 16V11a6 6 0 0 1 12 0v5l2 2H4l2-2ZM10 21h4") }
    val BellOff: ImageVector by lazy { icon("BellOff", "M6 16V11a6 6 0 0 1 9-5M18 11v5l2 2H4M10 21h4M3 3l18 18") }
    val Moon: ImageVector by lazy { icon("Moon", "M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5Z") }
    val Shield: ImageVector by lazy { icon("Shield", "M12 3l8 3v6c0 5-3.5 8-8 9c-4.5-1-8-4-8-9V6l8-3ZM9 12l2.2 2.2L15.5 10") }
    val Logout: ImageVector by lazy { icon("Logout", "M10 4H5a1 1 0 0 0-1 1v14a1 1 0 0 0 1 1h5M15 8l4 4l-4 4M19 12H9") }
    val Eye: ImageVector by lazy { icon("Eye", "M2 12s4-7 10-7s10 7 10 7s-4 7-10 7S2 12 2 12ZM12 15a3 3 0 1 0 0-6a3 3 0 0 0 0 6Z") }
    val EyeOff: ImageVector by lazy { icon("EyeOff", "M3 3l18 18M10.6 6.2A9.6 9.6 0 0 1 12 6c6 0 10 6 10 6a17 17 0 0 1-3.2 3.7M6.6 7.6A16 16 0 0 0 2 12s4 7 10 7a9 9 0 0 0 4-1") }
    val Block: ImageVector by lazy { icon("Block", "M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18ZM5.6 5.6l12.8 12.8") }
    val Phone: ImageVector by lazy { icon("Phone", "M5 4h4l2 5l-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a1 1 0 0 1-1 1A16 16 0 0 1 4 5a1 1 0 0 1 1-1Z") }
    val Mail: ImageVector by lazy { icon("Mail", "M4 5h16a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1ZM3 7l9 6l9-6") }
    val Pin: ImageVector by lazy { icon("Pin", "M9 4h6l-1 6l3 3H7l3-3L9 4ZM12 13v7") }
    val Info: ImageVector by lazy { icon("Info", "M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18ZM12 11v5M12 8h.01") }
    val Chevron: ImageVector by lazy { icon("Chevron", "M9 5l7 7l-7 7") }
    val Camera: ImageVector by lazy { icon("Camera", "M4 8h3l2-3h6l2 3h3a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1ZM12 17a4 4 0 1 0 0-8a4 4 0 0 0 0 8Z") }
    val Key: ImageVector by lazy { icon("Key", "M8 14a4 4 0 1 1 3.9-5H21v3h-2v2h-3v-2h-4.1A4 4 0 0 1 8 14Z") }
    val Palette: ImageVector by lazy { icon("Palette", "M12 3a9 9 0 1 0 0 18c1.5 0 2-1 1.5-2s0-2 1.5-2h2a4 4 0 0 0 4-4c0-5-4-10-9-10ZM7.5 11h.01M10 7.5h.01M14.5 7.5h.01") }
    val Trash2: ImageVector by lazy { icon("Trash2", "M5 7h14M9 7V5h6v2M7 7l1 13h8l1-13") }
    val Star: ImageVector by lazy { icon("Star", "M12 3l2.7 5.6l6.1.9l-4.4 4.3l1 6.1L12 17l-5.4 2.9l1-6.1L3.2 9.5l6.1-.9Z") }
    val Forward: ImageVector by lazy { icon("Forward", "M15 4l6 6l-6 6M21 10H9a6 6 0 0 0-6 6v4") }
    val Rotate: ImageVector by lazy { icon("Rotate", "M20 11a8 8 0 1 0-2.3 5.7M20 4v7h-7") }
    val Crop: ImageVector by lazy { icon("Crop", "M6 2v14a2 2 0 0 0 2 2h14M18 22V8a2 2 0 0 0-2-2H2") }
    val Brush: ImageVector by lazy { icon("Brush", "M4 20c2 0 3-1 3-3a3 3 0 0 1 3-3l9-9a2 2 0 0 0-3-3l-9 9a3 3 0 0 0-3 3c0 3-1 4-3 4Z") }
    val Sliders: ImageVector by lazy { icon("Sliders", "M4 7h10M18 7h2M4 17h2M10 17h10M14 4v6M6 14v6") }
    val Undo: ImageVector by lazy { icon("Undo", "M9 14L4 9l5-5M4 9h10a6 6 0 0 1 0 12h-3") }
    val Share: ImageVector by lazy { icon("Share", "M4 12v7a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-7M16 6l-4-4l-4 4M12 2v14") }
    val Bookmark: ImageVector by lazy { icon("Bookmark", "M6 3h12v18l-6-4l-6 4Z") }
    val Type: ImageVector by lazy { icon("Type", "M4 7V5h16v2M12 5v14M9 19h6") }
    val Book: ImageVector by lazy { icon("Book", "M4 5a2 2 0 0 1 2-2h13v16H6a2 2 0 0 0-2 2V5ZM8 7h7M8 11h7") }
    val Database: ImageVector by lazy { icon("Database", "M12 3c4.4 0 8 1.3 8 3s-3.6 3-8 3s-8-1.3-8-3s3.6-3 8-3ZM4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3") }
    val Fingerprint: ImageVector by lazy { icon("Fingerprint", "M12 11v3a6 6 0 0 1-1 3M8 9a4 4 0 0 1 8 0v2M5 12V9a7 7 0 0 1 14 0v3a10 10 0 0 1-2 6M8 14v1a8 8 0 0 1-1 3") }
    val Select: ImageVector by lazy { icon("Select", "M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18ZM8 12.5l2.7 2.7L16 9.5") }
    val Globe: ImageVector by lazy { icon("Globe", "M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18ZM3 12h18M12 3c3 3 3 15 0 18M12 3c-3 3-3 15 0 18") }
    val Invite: ImageVector by lazy { icon("Invite", "M4 6h16a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1ZM3 8l9 6l9-6") }
    val Text: ImageVector by lazy { icon("Text", "M5 6h14M12 6v13M9 19h6") }
    val Video: ImageVector by lazy { icon("Video", "M3 7h11a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1ZM15 11l6-3v8l-6-3") }
    val VideoOff: ImageVector by lazy { icon("VideoOff", "M3 7h11a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1ZM15 11l6-3v8l-6-3M3 3l18 18") }
    val MicOff: ImageVector by lazy { icon("MicOff", "M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3ZM5 11a7 7 0 0 0 14 0M12 18v3M3 3l18 18") }
    val Speaker: ImageVector by lazy { icon("Speaker", "M4 9v6h4l5 4V5L8 9H4ZM16.5 9a4 4 0 0 1 0 6M19 6.5a8 8 0 0 1 0 11") }
    val Scan: ImageVector by lazy { icon("Scan", "M4 8V5a1 1 0 0 1 1-1h3M16 4h3a1 1 0 0 1 1 1v3M20 16v3a1 1 0 0 1-1 1h-3M8 20H5a1 1 0 0 1-1-1v-3M4 12h16") }
    val Qr: ImageVector by lazy { icon("Qr", "M4 4h6v6H4ZM14 4h6v6h-6ZM4 14h6v6H4ZM14 14h2v2h-2ZM18 14h2M14 18h2v2M18 18h2v2") }
    val Link: ImageVector by lazy { icon("Link", "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1") }
    val Audio: ImageVector by lazy { icon("Audio", "M4 10v4M8 7v10M12 4v16M16 8v8M20 11v2") }
    val Download: ImageVector by lazy { icon("Download", "M12 4v11M7 11l5 5l5-5M5 20h14") }
    val Shapes: ImageVector by lazy { icon("Shapes", "M5 5h8v8H5ZM17 11a4 4 0 1 0 0 8a4 4 0 0 0 0-8ZM7 20l3-5l3 5Z") }
    val Pause2: ImageVector by lazy { icon("Pause2", "M9 5v14M15 5v14") }
}
