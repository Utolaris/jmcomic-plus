package com.par9uet.jm.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.par9uet.jm.ui.navigation.returnToHome
import com.par9uet.jm.ui.screens.LocalMainNavController

@Composable
internal fun BackIconButton(onClick: (() -> Unit)? = null) {
    val navController = LocalMainNavController.current
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .combinedClickable(
                role = Role.Button,
                onClick = { onClick?.invoke() ?: navController.popBackStack() },
                onLongClickLabel = "返回首页",
                onLongClick = { navController.returnToHome() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回上一页，长按返回首页")
    }
}
