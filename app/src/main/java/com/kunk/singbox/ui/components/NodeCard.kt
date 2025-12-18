package com.kunk.singbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kunk.singbox.ui.theme.*

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

    val borderColor = if (isSelected) PureWhite else Divider
    val borderWidth = if (isSelected) 2.dp else 1.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceCard, RoundedCornerShape(16.dp))
            .border(borderWidth, borderColor, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(PureWhite, CircleShape)
                            .padding(4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = "Selected",
                            tint = AppBackground,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.size(24.dp))
                }
                
                Spacer(modifier = Modifier.width(16.dp))
                
                Column {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = type,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        if (isTesting) {
                            Text(
                                text = "Testing...",
                                style = MaterialTheme.typography.labelSmall,
                                color = PureWhite.copy(alpha = 0.7f),
                                fontWeight = FontWeight.Medium
                            )
                        } else if (latency != null) {
                            val latencyColor = remember(latency) {
                                when {
                                    latency < 0 -> Color.Red
                                    latency < 200 -> Color(0xFF4CAF50)
                                    latency < 500 -> Color(0xFFFFC107)
                                    else -> Color.Red
                                }
                            }
                            val latencyText = remember(latency) {
                                if (latency < 0) "Timeout" else "${latency}ms"
                            }
                            
                            Text(
                                text = latencyText,
                                style = MaterialTheme.typography.labelSmall,
                                color = latencyColor,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Box(modifier = Modifier.wrapContentSize(Alignment.TopStart)) {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = "More",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
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
