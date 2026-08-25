package com.par9uet.jm.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.par9uet.jm.ui.glass.GlassModal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class SelectOption(val label: String, val value: String)

/**
 * Glass-backed radio selection dialog. Render from CommonScaffold overlayContent so the modal
 * samples live page content for its Gaussian backdrop.
 */
@Composable
fun SelectDialog(
    title: String,
    value: String?,
    selectOptionList: List<SelectOption> = listOf(),
    onSelect: (String) -> Unit = {},
    onDismissRequest: () -> Unit = {}
) {
    GlassModal(
        visible = true,
        onDismissRequest = onDismissRequest,
        surfaceId = "select-dialog-glass",
        modifier = Modifier.widthIn(max = 420.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            )
            HorizontalDivider()
            val screenHeight = LocalWindowInfo.current.containerSize.height.dp
            val maxHeight = screenHeight * 0.6f
            LazyColumn(
                modifier = Modifier.heightIn(max = maxHeight)
            ) {
                items(selectOptionList, key = { it.value }) { option ->
                    Row(
                        modifier = Modifier
                            .clickable(onClick = {
                                onSelect(option.value)
                            })
                            .padding(horizontal = 24.dp, vertical = 14.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = option.value == value,
                            onClick = {
                                onSelect(option.value)
                            }
                        )
                        Text(text = option.label)
                    }
                }
            }
            HorizontalDivider()
            Row(
                modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp)
            ) {
                Spacer(modifier = Modifier.weight(1f))
                TextButton(
                    onClick = {
                        onDismissRequest()
                    }
                ) {
                    Text("取消")
                }
            }
        }
    }
}
