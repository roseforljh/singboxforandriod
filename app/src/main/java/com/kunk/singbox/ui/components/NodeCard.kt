package com.kunk.singbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kunk.singbox.ui.theme.AppBackground
import com.kunk.singbox.ui.theme.Divider
import com.kunk.singbox.ui.theme.PureWhite
import com.kunk.singbox.ui.theme.SurfaceCard
import com.kunk.singbox.ui.theme.TextPrimary
import com.kunk.singbox.ui.theme.TextSecondary
import androidx.compose.ui.graphics.Color

@Composable
fun NodeCard(
    name: String,
    type: String,
    latency: Long? = null,
    isSelected: Boolean,
    onClick: () -> Unit,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceCard)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) PureWhite else Divider,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Status Indicator
            if (isSelected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = "Selected",
                    tint = AppBackground,
                    modifier = Modifier
                        .size(24.dp)
                        .background(PureWhite, CircleShape)
                        .padding(4.dp)
                )
            } else {
                Spacer(modifier = Modifier.size(24.dp))
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = type,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    
                    if (latency != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        val latencyColor = when {
                            latency < 0 -> Color.Red
                            latency < 200 -> Color(0xFF4CAF50) // Green
                            latency < 500 -> Color(0xFFFFC107) // Amber
                            else -> Color.Red
                        }
                        
                        Text(
                            text = if (latency < 0) "Timeout" else "${latency}ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = latencyColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        IconButton(onClick = onMenuClick) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = "More",
                tint = TextSecondary
            )
        }
    }
}