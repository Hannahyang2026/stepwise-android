package com.hannah.stepwise

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 页面底色与单元格共用暖色阶。深绿用于操作，朱红只用于日期和提醒强调。
internal val stepPaper = Color(0xFFFFFDF7)
internal val stepCell = Color(0xFFF5F1E8)
internal val stepInk = Color(0xFF262923)
internal val stepAccent = Color(0xFF255A49)
internal val stepLine = Color(0xFFE7E7E8)
internal val stepMuted = Color(0xFF6D6B62)
// 较浅的灰用于年份。地点等小字沿用更深的灰，表单纸面与页面灰底分开。
internal val stepYear = Color(0xFF8A8A8D)
internal val stepFormPaper = Color(0xFFF4F1ED)
internal val stepFormGroup = ContinuousCornerShape(24.dp)
internal val stepFormPanel = ContinuousCornerShape(32.dp)
internal val stepEventShape = ContinuousCornerShape(6.dp)
// 日期、时间标记和操作提示统一使用此前的较深朱红。
internal val stepRed = Color(0xFFBA3D34)

// 同一套文字角色在窄窗缩小一档。sp 是会跟随 Android 系统字号缩放的单位。
private fun stepType(heading: Int, section: Int, item: Int, body: Int): Typography {
    val base = Typography()
    return Typography(
        titleLarge = base.titleLarge.copy(fontSize = heading.sp, lineHeight = (heading + 8).sp, fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontSize = section.sp, lineHeight = (section + 6).sp, fontWeight = FontWeight.Normal),
        headlineSmall = base.bodyLarge.copy(fontSize = item.sp, lineHeight = (item + 5).sp, fontWeight = FontWeight.Bold),
        bodyLarge = base.bodyMedium.copy(fontSize = body.sp, lineHeight = (body + 6).sp),
        bodyMedium = base.bodyMedium.copy(fontSize = body.sp, lineHeight = (body + 6).sp),
        bodySmall = base.bodySmall.copy(fontSize = body.sp, lineHeight = (body + 6).sp)
    )
}
internal val stepTypography = stepType(20, 18, 15, 14)
internal val stepCompactTypography = stepType(18, 16, 14, 13)
internal val stepItemTitle: androidx.compose.ui.text.TextStyle
    @Composable get() = MaterialTheme.typography.headlineSmall

// 以待办摘要为基准统一圆面大小。每个按钮外层仍保留 48 dp 点按区，内外同心。
internal val stepIconTouchSize = 48.dp
internal val stepRoundDiameter = 34.dp
internal val stepRoundInset = (stepIconTouchSize - stepRoundDiameter) / 2

@Composable
internal fun StepRoundAction(glyph: StepGlyph, label: String,
    fill: Color = Color.Transparent, ink: Color = stepAccent, outline: Color? = null,
    enabled: Boolean = true, iconSize: Dp = 24.dp, faceDiameter: Dp = stepRoundDiameter, onClick: () -> Unit) {
    // 外层负责触控与反馈，圆面与图案在同一个中心。
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(stepIconTouchSize)) {
        StepRoundFace(glyph, label, fill, ink, outline, enabled, iconSize, faceDiameter)
    }
}

// 也用于图标与文字共用点按区的操作，label 为空时由外层文字提供读屏名称。
@Composable
internal fun StepRoundFace(glyph: StepGlyph, label: String? = null,
    fill: Color = Color.Transparent, ink: Color = stepAccent, outline: Color? = null, enabled: Boolean = true,
    iconSize: Dp = 24.dp, faceDiameter: Dp = stepRoundDiameter) {
    // iconSize 缩放里面的图案，faceDiameter 只改变可见圆面。外部点按区保持 48 dp。
    val opacity = if (enabled) 1f else .38f
    Surface(Modifier.size(faceDiameter), shape = CircleShape, color = fill.copy(alpha = fill.alpha * opacity),
        border = outline?.let { BorderStroke(1.dp, it.copy(alpha = it.alpha * opacity)) }) {
        Box(contentAlignment = Alignment.Center) {
            StepIcon(glyph, label, ink.copy(alpha = ink.alpha * opacity), diameter = iconSize)
        }
    }
}

// 弹层选项共用胶囊。点击区保持 48 dp；顶栏可固定圆面高度，筛选项则允许随字号增高。
@Composable
internal fun StepMenuPill(label: String, modifier: Modifier = Modifier, stretch: Boolean = false,
    active: Boolean = false, fixedHeight: Boolean = false, enabled: Boolean = true,
    onLongClickLabel: String? = null, onLongClick: (() -> Unit)? = null,
    horizontalPadding: Dp = 12.dp, accessibilityLabel: String = label,
    trailingGlyph: StepGlyph? = null, onClick: () -> Unit) {
    val face = if (fixedHeight) Modifier.height(stepRoundDiameter) else Modifier.heightIn(min = stepRoundDiameter)
    Box(modifier.heightIn(min = stepIconTouchSize)
        .then(if (accessibilityLabel == label) Modifier else Modifier.semantics { contentDescription = accessibilityLabel })
        .combinedClickable(enabled = enabled, role = Role.Button, onLongClickLabel = onLongClickLabel,
            onLongClick = onLongClick, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Surface(face.then(if (stretch) Modifier.fillMaxWidth() else Modifier), shape = CircleShape,
            color = if (active) stepAccent.copy(alpha = .12f) else stepPaper,
            border = BorderStroke(1.dp, if (active) stepAccent.copy(alpha = .28f) else stepLine)) {
            // 先给尾图标分配固定宽度，再让名称使用余下空间。空间变窄时只省略文字。
            val ink = if (active) stepAccent else if (enabled) stepInk else stepMuted
            Row(Modifier.padding(horizontal = horizontalPadding, vertical = if (fixedHeight) 0.dp else 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically) {
                val textWidth = if (trailingGlyph == null) Modifier else Modifier.weight(1f, fill = false)
                // 自定义读屏说明已包含完整名称，文字本身无需读第二遍。
                val spoken = if (accessibilityLabel == label) Modifier else Modifier.clearAndSetSemantics {}
                Text(label, textWidth.then(spoken),
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = TextUnit.Unspecified),
                    color = ink, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                trailingGlyph?.let { StepIcon(it, color = ink, diameter = 16.dp) }
            }
        }
    }
}

// 弹层先按锚点边缘对齐，再约束到窗口内。末端对齐时扣除圆钮外侧的 7 dp 点按留白。
@Composable
internal fun StepAnchoredPanel(expanded: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier,
    alignEnd: Boolean = false, content: @Composable () -> Unit) {
    if (!expanded) return
    val (margin, inset) = with(LocalDensity.current) { 8.dp.roundToPx() to stepRoundInset.roundToPx() }
    val position = remember(margin, inset, alignEnd) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
                popupContentSize: IntSize): IntOffset {
                val ltr = layoutDirection == LayoutDirection.Ltr
                val x = when {
                    alignEnd && ltr -> anchorBounds.right - inset - popupContentSize.width
                    alignEnd -> anchorBounds.left + inset
                    ltr -> anchorBounds.left
                    else -> anchorBounds.right - popupContentSize.width
                }
                val y = anchorBounds.bottom + margin / 2
                return IntOffset(
                    x.coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)),
                    y.coerceIn(margin, (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin))
                )
            }
        }
    }
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Surface(modifier, shape = RoundedCornerShape(stepIconTouchSize / 2), color = stepCell,
            border = BorderStroke(1.dp, stepLine), shadowElevation = 5.dp, content = content)
    }
}

@Composable
internal fun StepAddButton(label: String, onClick: () -> Unit) {
    StepRoundAction(StepGlyph.Add, label, fill = stepAccent.copy(alpha = .8f),
        ink = Color.White, onClick = onClick)
}

// 主页面共用一个设置入口，同用细描边和深绿色图案。
@Composable
internal fun StepSettingsButton(onClick: () -> Unit) {
    StepRoundAction(StepGlyph.Settings, "设置", outline = stepLine, onClick = onClick)
}

// 颜色按已保存的类别取值：色条辨类，浅底适合覆盖网格，深色文字容易扫读。
internal data class CalendarPalette(val stripe: Color, val fill: Color, val ink: Color)
private val ordinaryPalette = CalendarPalette(Color(0xFF4B886C), Color(0xFFE0F2E8), stepAccent)
private val assignmentPalette = CalendarPalette(Color(0xFFB96B35), Color(0xFFFFEDDD), Color(0xFF84461C))
private val coursePalette = CalendarPalette(Color(0xFFA653C4), Color(0xFFF3E5FA), Color(0xFF62367A))
internal fun calendarPalette(kind: String): CalendarPalette = when (kind) {
    "assignment" -> assignmentPalette
    "course" -> coursePalette
    else -> ordinaryPalette
}

// 短色条在行内缩进两端，圆头与同样的日程类别色保持一致。
@Composable
internal fun CalendarStripe(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxHeight().width(4.dp).background(color, CircleShape))
}

// 日历筛选和表单切换共用操作色，由选中底色与文字一起表达状态。
@Composable
internal fun calendarChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = stepAccent.copy(alpha = .12f),
    selectedLabelColor = stepAccent,
    selectedLeadingIconColor = stepAccent
)
// 不同尺寸共用一套曲线。圆形日期和图标仍使用同心的正圆。
internal val stepTagShape = ContinuousCornerShape(6.dp)
internal val stepControlShape = ContinuousCornerShape(12.dp)
internal val stepShape = ContinuousCornerShape(17.dp)
internal val stepDialogShape = ContinuousCornerShape(22.dp)
internal val stepShapes = Shapes(
    extraSmall = stepTagShape, small = stepControlShape, medium = stepShape,
    large = stepDialogShape, extraLarge = ContinuousCornerShape(26.dp)
)
private val stepCheckShape = ContinuousCornerShape(3.dp)
internal val stepInset = 16.dp

internal val stepCheckedDiameter = 14.dp
internal val agendaMarkerWidth = 48.dp

internal enum class StepGlyph { Near, Agenda, Agent, Goal, Settings, Api, Memory, Tools, Clock, Bell, Add, Next,
    Wrench, Draft, SendUp, Trash, Check, Alarm, ChevronLeft, ChevronRight, ChevronDown, Conversation, Idea }
internal val tabGlyphs = listOf(StepGlyph.Near, StepGlyph.Agenda, StepGlyph.Agent, StepGlyph.Goal)

// 线稿图标共用 24 格坐标；顶部与底栏传入同一个图标名称。
@Composable
internal fun StepIcon(glyph: StepGlyph, label: String? = null, color: Color = LocalContentColor.current, diameter: Dp = 24.dp) {
    Canvas(Modifier.size(diameter).then(if (label == null) Modifier else Modifier.semantics { contentDescription = label })) {
        // 图标以最短边等比缩放，再将坐标原点平移到中心；圆环和内部笔画共用同一坐标。
        val k = size.minDimension / 24f
        val stroke = 1.55f * k
        val left = (size.width - 24 * k) / 2
        val top = (size.height - 24 * k) / 2
        fun o(x: Int, y: Int) = Offset(left + x * k, top + y * k)
        fun line(x: Int, y: Int, a: Int, b: Int) = drawLine(color, o(x, y), o(a, b), stroke, StrokeCap.Round)
        fun ring(x: Int, y: Int, radius: Int) = drawCircle(color, radius * k, o(x, y), style = Stroke(stroke))
        fun panel(x: Int, y: Int, w: Int, h: Int) {
            val origin = o(x, y)
            val radius = 2f * k
            translate(origin.x, origin.y) {
                drawPath(continuousRect(Size(w * k, h * k), radius, radius, radius, radius), color, style = Stroke(stroke))
            }
        }
        when (glyph) {
            StepGlyph.Near -> {
                panel(3, 5, 18, 16); line(3, 10, 21, 10); line(8, 3, 8, 7); line(16, 3, 16, 7)
                line(8, 15, 10, 15); line(14, 15, 16, 15)
            }
            StepGlyph.Agenda -> {
                panel(4, 3, 16, 18); line(8, 8, 16, 8); line(8, 12, 16, 12); line(8, 16, 14, 16)
            }
            StepGlyph.Agent -> {
                line(12, 3, 14, 10); line(14, 10, 21, 12); line(21, 12, 14, 14); line(14, 14, 12, 21)
                line(12, 21, 10, 14); line(10, 14, 3, 12); line(3, 12, 10, 10); line(10, 10, 12, 3)
            }
            StepGlyph.Goal -> { ring(12, 12, 9); ring(12, 12, 5); drawCircle(color, 1.4f * k, o(12, 12)) }
            StepGlyph.Settings, StepGlyph.Tools -> {
                line(4, 6, 20, 6); line(4, 12, 20, 12); line(4, 18, 20, 18)
                line(8, 4, 8, 8); line(16, 10, 16, 14); line(10, 16, 10, 20)
            }
            StepGlyph.Api -> { ring(8, 9, 4); line(11, 12, 20, 21); line(16, 17, 19, 14); line(19, 20, 21, 18) }
            StepGlyph.Memory -> { panel(5, 3, 14, 18); line(8, 3, 8, 21); line(11, 8, 16, 8); line(11, 12, 16, 12) }
            StepGlyph.Clock -> { ring(12, 12, 9); line(12, 7, 12, 12); line(12, 12, 16, 14) }
            StepGlyph.Bell -> {
                line(5, 17, 19, 17); line(7, 17, 7, 9); line(17, 17, 17, 9)
                line(7, 9, 10, 5); line(10, 5, 14, 5); line(14, 5, 17, 9); line(11, 20, 13, 20)
            }
            // 扳手、草稿、闹钟和提交图案只画内部笔画。各页面可以自行添加外圈。
            StepGlyph.Wrench -> {
                line(11, 10, 4, 17); line(4, 17, 4, 20); line(4, 20, 7, 20); line(7, 20, 14, 13)
                line(11, 10, 11, 6); line(11, 6, 15, 3); line(15, 3, 17, 3); line(17, 3, 15, 7)
                line(15, 7, 17, 9); line(17, 9, 21, 7); line(21, 7, 21, 10); line(21, 10, 18, 13); line(18, 13, 14, 13)
            }
            // 对话气泡和带灯座的灯泡分别表示聊天与存为记忆。
            StepGlyph.Conversation -> {
                line(4, 5, 20, 5); line(20, 5, 20, 16); line(20, 16, 10, 16)
                line(10, 16, 5, 20); line(5, 20, 5, 16); line(5, 16, 4, 16); line(4, 16, 4, 5)
                line(8, 10, 16, 10)
            }
            StepGlyph.Idea -> {
                drawArc(color, 148f, 244f, false, o(6, 3), Size(12 * k, 12 * k),
                    style = Stroke(stroke, cap = StrokeCap.Round))
                line(7, 12, 9, 16); line(17, 12, 15, 16); line(9, 16, 15, 16)
                line(10, 19, 14, 19); line(3, 9, 2, 9); line(21, 9, 22, 9)
            }
            StepGlyph.Draft -> { panel(5, 3, 14, 18); line(8, 8, 16, 8); line(8, 12, 16, 12); line(8, 16, 13, 16) }
            StepGlyph.SendUp -> { line(12, 19, 12, 5); line(6, 11, 12, 5); line(12, 5, 18, 11) }
            StepGlyph.Trash -> {
                line(4, 7, 20, 7); line(9, 4, 15, 4); line(6, 7, 7, 20); line(7, 20, 17, 20); line(17, 20, 18, 7)
                line(10, 10, 10, 17); line(14, 10, 14, 17)
            }
            StepGlyph.Check -> { line(5, 12, 10, 17); line(10, 17, 19, 7) }
            StepGlyph.Alarm -> {
                ring(12, 12, 7); line(12, 8, 12, 12); line(12, 12, 15, 13)
                line(4, 6, 7, 3); line(17, 3, 20, 6); line(5, 20, 7, 17); line(19, 20, 17, 17)
            }
            StepGlyph.Add -> { line(12, 5, 12, 19); line(5, 12, 19, 12) }
            // 圆形返回和月份按钮把外圈交给统一按钮。列表行末尾的小箭头保留自身的圆环。
            StepGlyph.ChevronLeft -> { line(14, 7, 9, 12); line(9, 12, 14, 17) }
            // 小尖角以胶囊中文字为基准居中，指向下方会展开的选项。
            StepGlyph.ChevronDown -> { line(7, 10, 12, 15); line(12, 15, 17, 10) }
            StepGlyph.ChevronRight, StepGlyph.Next -> {
                if (glyph == StepGlyph.Next) ring(12, 12, 9)
                line(10, 8, 14, 12); line(14, 12, 10, 16)
            }
        }
    }
}

@Composable
internal fun CircleArrow(label: String = "返回", next: Boolean = false,
    faceDiameter: Dp = stepRoundDiameter, onClick: () -> Unit) {
    StepRoundAction(if (next) StepGlyph.ChevronRight else StepGlyph.ChevronLeft, label, outline = stepAccent,
        faceDiameter = faceDiameter, iconSize = minOf(24.dp, faceDiameter - 6.dp), onClick = onClick)
}

@Composable
internal fun SectionTitle(title: String, back: () -> Unit) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        CircleArrow(onClick = back)
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun StepGroup(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = stepShape, color = stepCell, content = content)
}

@Composable
internal fun SettingLine(glyph: StepGlyph, title: String, click: () -> Unit) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = click)
        .heightIn(min = 50.dp).padding(horizontal = stepInset),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StepIcon(glyph, color = stepAccent)
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        StepIcon(StepGlyph.Next, color = stepMuted)
    }
}

// 同一个组件支持自身点按或由整行接管点按。待办使用小圆形，其他选择器沿用圆角方形。
@Composable
internal fun StepCheckbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier, enabled: Boolean = true, circular: Boolean = false) {
    val shape = if (circular) CircleShape else stepCheckShape
    val diameter = when {
        !circular -> 18.dp
        checked -> stepCheckedDiameter
        else -> 16.dp
    }
    val color = if (checked) stepAccent else stepMuted
    val stroke = if (enabled) color else color.copy(alpha = .42f)
    val interaction = if (onCheckedChange == null) Modifier else Modifier
        .minimumInteractiveComponentSize()
        .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
    Box(modifier.then(interaction), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(diameter).background(if (checked) stroke else Color.Transparent, shape)
            .border(1.35.dp, stroke, shape)) {
            if (checked) {
                val width = 1.6.dp.toPx()
                val first = Offset(size.width * .26f, size.height * .51f)
                val middle = Offset(size.width * .43f, size.height * .68f)
                val last = Offset(size.width * .75f, size.height * .34f)
                drawLine(stepPaper, first, middle, width, StrokeCap.Round)
                drawLine(stepPaper, middle, last, width, StrokeCap.Round)
            }
        }
    }
}
