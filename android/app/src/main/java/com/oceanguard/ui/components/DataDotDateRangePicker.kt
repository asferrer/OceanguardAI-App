package com.oceanguard.ai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

// ---------------------------------------------------------------------------
// DataDotDateRangePicker
// ---------------------------------------------------------------------------

/**
 * A fully custom monthly calendar composable that replaces the standard
 * Material 3 DateRangePicker.
 *
 * Features:
 * - Navigable month-by-month with chevron arrows
 * - Colored dot indicator beneath day numbers that belong to [datesWithData]
 * - Range selection: first tap = start, second tap = end (if after start),
 *   third tap on an active selection = reset
 * - "Before-start" tap resets selection and starts a new range from that day
 * - Days between start and end receive a translucent band highlight
 * - Selected endpoints receive a filled primary-color circle with white text
 * - Today receives an outlined circle border when not selected
 * - Out-of-month cells are empty spacers (invisible)
 * - Week header row is locale-aware (Mon-first via [DayOfWeek.MONDAY])
 *
 * @param initialStartDate  Pre-selected range start, or null.
 * @param initialEndDate    Pre-selected range end, or null.
 * @param datesWithData     Set of [LocalDate]s that should show a dot.
 * @param onRangeChanged    Callback fired whenever the selection changes.
 * @param modifier          Standard Compose modifier.
 */
@Composable
fun DataDotDateRangePicker(
    initialStartDate: LocalDate?,
    initialEndDate: LocalDate?,
    datesWithData: Set<LocalDate>,
    onRangeChanged: (start: LocalDate?, end: LocalDate?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------
    var currentMonth by remember {
        mutableStateOf(
            if (initialStartDate != null) YearMonth.from(initialStartDate)
            else YearMonth.now()
        )
    }
    var rangeStart by remember { mutableStateOf(initialStartDate) }
    var rangeEnd   by remember { mutableStateOf(initialEndDate) }

    // ------------------------------------------------------------------
    // Tap handler
    // ------------------------------------------------------------------
    fun onDayTapped(tapped: LocalDate) {
        val start = rangeStart
        val end   = rangeEnd

        when {
            // Third tap on an already-complete range: reset
            start != null && end != null -> {
                rangeStart = null
                rangeEnd   = null
                onRangeChanged(null, null)
            }
            // First tap, or tapping before an existing start: new start
            start == null || tapped < start -> {
                rangeStart = tapped
                rangeEnd   = null
                onRangeChanged(tapped, null)
            }
            // Tapping the existing start again: treat as single-day range
            tapped == start -> {
                rangeEnd = tapped
                onRangeChanged(start, tapped)
            }
            // Second tap after start: set end
            else -> {
                rangeEnd = tapped
                onRangeChanged(start, tapped)
            }
        }
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------
    Column(modifier = modifier.fillMaxWidth()) {
        MonthHeader(
            month      = currentMonth,
            onPrevious = { currentMonth = currentMonth.minusMonths(1) },
            onNext     = { currentMonth = currentMonth.plusMonths(1) },
        )

        Spacer(modifier = Modifier.height(4.dp))

        WeekDayHeader()

        Spacer(modifier = Modifier.height(2.dp))

        MonthGrid(
            month        = currentMonth,
            rangeStart   = rangeStart,
            rangeEnd     = rangeEnd,
            datesWithData = datesWithData,
            today        = LocalDate.now(),
            onDayTapped  = ::onDayTapped,
        )
    }
}

// ---------------------------------------------------------------------------
// Month header: "← March 2025 →"
// ---------------------------------------------------------------------------

@Composable
private fun MonthHeader(
    month: YearMonth,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val label = remember(month) {
        val monthName = month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
        "$monthName ${month.year}"
    }

    Row(
        modifier              = Modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(
            onClick  = onPrevious,
            modifier = Modifier.semantics {
                contentDescription = "Previous month"
            }
        ) {
            Icon(
                imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.onSurface,
            )
        }

        Text(
            text      = label,
            style     = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color     = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier  = Modifier.weight(1f),
        )

        IconButton(
            onClick  = onNext,
            modifier = Modifier.semantics {
                contentDescription = "Next month"
            }
        ) {
            Icon(
                imageVector        = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Week-day header row: Mon Tue Wed Thu Fri Sat Sun (locale-aware order)
// ---------------------------------------------------------------------------

@Composable
private fun WeekDayHeader() {
    // Build ordered list starting from Monday
    val days = remember {
        val ordered = mutableListOf<DayOfWeek>()
        var day = DayOfWeek.MONDAY
        repeat(7) {
            ordered.add(day)
            day = day.plus(1)
        }
        ordered
    }

    Row(modifier = Modifier.fillMaxWidth()) {
        days.forEach { dow ->
            Text(
                text      = dow.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                modifier  = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp),
                textAlign = TextAlign.Center,
                style     = MaterialTheme.typography.labelSmall,
                color     = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Month grid: 6 rows x 7 columns
// ---------------------------------------------------------------------------

@Composable
private fun MonthGrid(
    month: YearMonth,
    rangeStart: LocalDate?,
    rangeEnd: LocalDate?,
    datesWithData: Set<LocalDate>,
    today: LocalDate,
    onDayTapped: (LocalDate) -> Unit,
) {
    // Calculate the offset from Monday to the first day of the month.
    // DayOfWeek.MONDAY = 1, so offset = (dayOfWeek.value - 1) % 7
    val firstDay       = month.atDay(1)
    val startOffset    = (firstDay.dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7
    val daysInMonth    = month.lengthOfMonth()

    // Total cells including leading spacers and trailing spacers to complete
    // the last row up to a multiple of 7 (max 6 rows = 42 cells).
    val totalCells  = startOffset + daysInMonth
    val rowCount    = (totalCells + 6) / 7   // ceiling division

    Column(modifier = Modifier.fillMaxWidth()) {
        repeat(rowCount) { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
            ) {
                repeat(7) { col ->
                    val cellIndex = row * 7 + col
                    val dayNumber = cellIndex - startOffset + 1

                    if (dayNumber < 1 || dayNumber > daysInMonth) {
                        // Out-of-month spacer
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                        )
                    } else {
                        val localDate = month.atDay(dayNumber)
                        DayCell(
                            date          = localDate,
                            today         = today,
                            rangeStart    = rangeStart,
                            rangeEnd      = rangeEnd,
                            hasData       = localDate in datesWithData,
                            onTap         = { onDayTapped(localDate) },
                            modifier      = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// DayCell — single calendar cell
// ---------------------------------------------------------------------------

private enum class DayCellState {
    NONE,
    RANGE_START,
    RANGE_END,
    IN_RANGE,
    TODAY_ONLY,
}

@Composable
private fun DayCell(
    date: LocalDate,
    today: LocalDate,
    rangeStart: LocalDate?,
    rangeEnd: LocalDate?,
    hasData: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary

    val state = remember(date, rangeStart, rangeEnd) {
        when {
            date == rangeStart && date == rangeEnd -> DayCellState.RANGE_START
            date == rangeStart                     -> DayCellState.RANGE_START
            date == rangeEnd                       -> DayCellState.RANGE_END
            rangeStart != null && rangeEnd != null
                    && date > rangeStart && date < rangeEnd -> DayCellState.IN_RANGE
            date == today                          -> DayCellState.TODAY_ONLY
            else                                   -> DayCellState.NONE
        }
    }

    val isEndpoint = state == DayCellState.RANGE_START || state == DayCellState.RANGE_END
    val isInRange  = state == DayCellState.IN_RANGE
    val isToday    = state == DayCellState.TODAY_ONLY

    // Band highlight spans the full cell width for in-range days.
    val cellBackground: Color = when {
        isInRange -> primary.copy(alpha = 0.12f)
        else      -> Color.Transparent
    }

    // Circle background for endpoints only.
    val circleBackground: Color = when {
        isEndpoint -> primary
        else       -> Color.Transparent
    }

    val textColor: Color = when {
        isEndpoint -> Color.White
        else       -> MaterialTheme.colorScheme.onSurface
    }

    val cellA11y = remember(date, state, hasData) {
        val stateDesc = when (state) {
            DayCellState.RANGE_START -> ", range start"
            DayCellState.RANGE_END   -> ", range end"
            DayCellState.IN_RANGE    -> ", in selected range"
            DayCellState.TODAY_ONLY  -> ", today"
            DayCellState.NONE        -> ""
        }
        val dataDesc = if (hasData) ", has detection data" else ""
        "${date.dayOfMonth}$stateDesc$dataDesc"
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .background(cellBackground)
            .clickable(onClick = onTap)
            .semantics { contentDescription = cellA11y },
        contentAlignment = Alignment.Center,
    ) {
        // Endpoint circle (filled) or today outline
        val circleModifier = when {
            isEndpoint -> Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(circleBackground)
            isToday -> Modifier
                .size(34.dp)
                .clip(CircleShape)
                .border(
                    width = 1.5.dp,
                    color = primary,
                    shape = CircleShape,
                )
            else -> Modifier.size(34.dp)
        }

        Column(
            modifier            = circleModifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text      = date.dayOfMonth.toString(),
                style     = MaterialTheme.typography.bodySmall,
                fontWeight = if (isEndpoint) FontWeight.Bold else FontWeight.Normal,
                color     = textColor,
                textAlign = TextAlign.Center,
            )

            // Data dot: 4dp radius circle, only visible when hasData
            if (hasData) {
                Spacer(modifier = Modifier.height(1.dp))
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(
                            if (isEndpoint) Color.White.copy(alpha = 0.85f)
                            else primary
                        ),
                )
            }
        }
    }
}
