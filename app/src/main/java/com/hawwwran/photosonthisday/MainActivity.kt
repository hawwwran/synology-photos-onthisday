package com.hawwwran.photosonthisday

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hawwwran.photosonthisday.ui.theme.OnThisDayTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OnThisDayTheme {
                Scaffold { insets ->
                    Placeholder(Modifier.padding(insets))
                }
            }
        }
    }
}

// Replaced by the login screen in plan 002 and by the day screen in plan 004.
@Composable
private fun Placeholder(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("On This Day", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Skeleton only. See documents/plans/index.md.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
