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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kunk.singbox.ui.theme.AppBackground
import com.kunk.singbox.ui.theme.Divider
import com.kunk.singbox.ui.theme.Neutral700
import com.kunk.singbox.ui.theme.PureWhite
import com.kunk.singbox.ui.theme.SurfaceCard
import com.kunk.singbox.ui.theme.TextPrimary
import com.kunk.singbox.ui.theme.TextSecondary

@Composable
fun NodeCard(
    name: String,
    type: String,
    latency: Long? = null,
    isSelected: Boolean,
    isTesting: Boolean = false,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onExport: () -> Unit,
    onLatency: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    androidx.compose.material3.Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = SurfaceCard,
        border = androidx.compose.foundation.BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) PureWhite else Divider
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
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
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        Box {
                            androidx.compose.animation.AnimatedVisibility(
                                visible = isTesting,
                                enter = fadeIn(),
                                exit = fadeOut()
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    color = PureWhite,
                                    strokeWidth = 2.dp
                                )
                            }

                            androidx.compose.animation.AnimatedVisibility(
                                visible = !isTesting && latency != null,
                                enter = fadeIn(),
                                exit = fadeOut()
                            ) {
                                if (latency != null) {
                                    val latencyColor = when {
                                        latency < 0 -> Color.Red
                                        latency < 200 -> Color(0xFF4CAF50)
                                        latency < 500 -> Color(0xFFFFC107)
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
                }
            }

            Box(modifier = Modifier.wrapContentSize(Alignment.TopEnd)) {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = "More",
                        tint = TextSecondary
                    )
                }
                MaterialTheme(
                    shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(12.dp))
                ) {
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier
                            .background(Neutral700)
                            .width(100.dp)
                    ) {
                        DropdownMenuItem(
                            text = {
                                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Text("编辑", color = PureWhite)
                                }
                            },
                            onClick = {
                                showMenu = false
                                onEdit()
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Text("导出", color = PureWhite)
                                }
                            },
                            onClick = {
                                showMenu = false
                                onExport()
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Text("延迟", color = PureWhite)
                                }
                            },
                            onClick = {
                                showMenu = false
                                onLatency()
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Text("删除", color = PureWhite)
                                }
                            },
                            onClick = {
                                showMenu = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
    }
}
