package com.betterhv.note

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

class MainActivity : ComponentActivity() {
    private var penView: PenDrawView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventLog.log("MainActivity", "onCreate")
        NativeSelfTest.run()
        setContent { AppRoot(onView = { penView = it }, onClear = { penView?.clear() }) }
    }

    override fun onDestroy() {
        penView?.teardown()
        super.onDestroy()
    }
}

@Composable
private fun AppRoot(onView: (PenDrawView) -> Unit, onClear: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.White)) {
        // Full-screen writing canvas (hosts the ROM EPD pen overlay).
        AndroidView(
            factory = { ctx -> PenDrawView(ctx).also(onView) },
            modifier = Modifier.fillMaxSize()
        )
        LogOverlay(
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp).width(360.dp)
        )
        Button(onClick = onClear, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            Text("Clear")
        }
    }
}

@Composable
private fun LogOverlay(modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(EventLog.lines.size) {
        if (EventLog.lines.isNotEmpty()) listState.scrollToItem(EventLog.lines.size - 1)
    }
    Box(modifier.background(Color(0xCCFFFFFF))) {
        LazyColumn(state = listState) {
            items(EventLog.lines) { line ->
                Text(
                    line,
                    color = Color.Black,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
    }
}
