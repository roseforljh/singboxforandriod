package com.kunk.singbox.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.animateColor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.kunk.singbox.ui.theme.OLEDBlack
import com.kunk.singbox.ui.theme.PureWhite

@Composable
fun BigToggle(
    isRunning: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // Scale animation on press
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else 1f,
        animationSpec = tween(durationMillis = 100, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "ScaleAnimation"
    )

    // Use updateTransition for coordinated animations
    val transition = updateTransition(targetState = isRunning, label = "BigToggleTransition")
    
    // Vertical offset animation - 关闭时下移 (使用明确时长的 tween 动画)
    val verticalOffset by transition.animateDp(
        transitionSpec = {
            tween(
                durationMillis = 600,
                easing = androidx.compose.animation.core.FastOutSlowInEasing
            )
        },
        label = "VerticalOffset"
    ) { running ->
        if (running) 0.dp else 20.dp
    }

    // Color animations
    val backgroundColor by transition.animateColor(
        transitionSpec = { tween(durationMillis = 300, easing = androidx.compose.animation.core.FastOutSlowInEasing) },
        label = "BackgroundColor"
    ) { running ->
        if (running) OLEDBlack else PureWhite
    }
    
    val iconColor by transition.animateColor(
        transitionSpec = { tween(durationMillis = 300, easing = androidx.compose.animation.core.FastOutSlowInEasing) },
        label = "IconColor"
    ) { running ->
        if (running) PureWhite else OLEDBlack
    }
    
    val borderColor by transition.animateColor(
        transitionSpec = { tween(durationMillis = 300, easing = androidx.compose.animation.core.FastOutSlowInEasing) },
        label = "BorderColor"
    ) { running ->
        if (running) OLEDBlack else Color(0xFFE0E0E0)
    }

    // Ripple/Breathing animation when running
    val infiniteTransition = rememberInfiniteTransition(label = "BreathingTransition")
    val rippleScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRunning) 1.2f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "RippleScale"
    )
    
    val rippleAlpha by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (isRunning) 0.1f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "RippleAlpha"
    )
    
    // Smooth visibility transition for ripple (prevents instant disappear)
    val rippleVisibility by animateFloatAsState(
        targetValue = if (isRunning) 1f else 0f,
        animationSpec = tween(durationMillis = 300, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "RippleVisibility"
    )

    // 使用 Column 来让按钮可以自然地向下偏移
    androidx.compose.foundation.layout.Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(top = 60.dp) // 基础顶部间距让按钮看起来居中
    ) {
        // 动态 spacer - 关闭时增加顶部空间，让按钮下移 (运行时0dp，关闭时20dp)
        androidx.compose.foundation.layout.Spacer(
            modifier = Modifier.height(verticalOffset)
        )
        
        Box(
            contentAlignment = Alignment.Center
        ) {
            // Ripple Effect Layer
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .scale(if (rippleVisibility > 0f) rippleScale else 1f)
                    .clip(CircleShape)
                    .background(OLEDBlack.copy(alpha = rippleAlpha * rippleVisibility))
            )

            // Main Button
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(200.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(backgroundColor)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick
                    )
                    .graphicsLayer {
                        shadowElevation = if (isRunning) 10.dp.toPx() else 2.dp.toPx()
                        shape = CircleShape
                        clip = true
                    }
                    .background(backgroundColor)
            ) {
                Icon(
                    imageVector = Icons.Rounded.PowerSettingsNew,
                    contentDescription = "Toggle VPN",
                    tint = iconColor,
                    modifier = Modifier.size(80.dp)
                )
            }
        }
    }
}
