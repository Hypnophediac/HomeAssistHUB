package com.homeassisthub.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Small "data freshness" indicator shown next to live readings (P1 card on
 * Dashboard, live flow cards on the Energy tab). Colors:
 *  - green "Élő" if the reading is <90s old (P1 polls every ~60s)
 *  - yellow "X perce" if 90s-6min old (typical for Kiosk/cloud-scraped inverter data)
 *  - red "Elavult: X perce" if older than 6min (something's stuck)
 */
@Composable
fun FreshnessBadge(timestampMs: Long?, nowMs: Long = System.currentTimeMillis(), modifier: Modifier = Modifier) {
    val (dotColor, label) = when {
        timestampMs == null -> Color(0xFF64748B) to "Nincs adat"
        else -> {
            val ageMs = (nowMs - timestampMs).coerceAtLeast(0L)
            val ageSec = ageMs / 1000
            when {
                ageSec < 90 -> Color(0xFF10B981) to "Élő"
                ageSec < 360 -> Color(0xFFF59E0B) to "${ageSec / 60} perce"
                else -> Color(0xFFEF4444) to "Elavult: ${ageSec / 60} perce"
            }
        }
    }

    Row(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(50)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(8.dp)
                .background(color = dotColor, shape = CircleShape)
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Cloud sync status badge: shows when the Hub last successfully uploaded
 * raw readings to the Render/MongoDB backend. Displayed on the Dashboard
 * alongside the live P1 card so the user can see if historical data is
 * being synced.
 */
@Composable
fun CloudSyncBadge(lastSyncTimeMs: Long?, nowMs: Long = System.currentTimeMillis(), modifier: Modifier = Modifier) {
    val (dotColor, label) = when {
        lastSyncTimeMs == null || lastSyncTimeMs == 0L -> Color(0xFF64748B) to "Cloud: nincs sync"
        else -> {
            val ageMs = (nowMs - lastSyncTimeMs).coerceAtLeast(0L)
            val ageMin = ageMs / 60_000
            when {
                ageMin < 5 -> Color(0xFF10B981) to "Cloud: syncél"
                ageMin < 15 -> Color(0xFFF59E0B) to "Cloud: ${ageMin}p"
                else -> Color(0xFFEF4444) to "Cloud: ${ageMin}p"
            }
        }
    }

    Row(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(50)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(8.dp)
                .background(color = dotColor, shape = CircleShape)
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
