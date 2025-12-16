package com.kunk.singbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kunk.singbox.ui.theme.Divider
import com.kunk.singbox.ui.theme.Neutral500
import com.kunk.singbox.ui.theme.Neutral900
import com.kunk.singbox.ui.theme.PureWhite
import com.kunk.singbox.ui.theme.TextSecondary

@Composable
fun StatusChip(
    label: String,
    icon: @Composable (() -> Unit)? = null,
    isActive: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val modifier = Modifier
        .clip(CircleShape)
        .background(if (isActive) PureWhite else Neutral900)
        .border(1.dp, if (isActive) PureWhite else Divider, CircleShape)
        .let {
            if (onClick != null) it.clickable(onClick = onClick) else it
        }
        .padding(horizontal = 12.dp, vertical = 6.dp)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Box(modifier = Modifier.size(16.dp)) {
                icon()
            }
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = if (isActive) Color.Black else TextSecondary
        )
    }
}

@Composable
fun ModeChip(
    mode: String,
    isRunning: Boolean = false,
    onClick: () -> Unit
) {
    StatusChip(
        label = mode,
        icon = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(16.dp) // Ensure container size matches icon slot
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(if (isRunning) PureWhite else Neutral500, CircleShape)
                )
            }
        },
        onClick = onClick
    )
}