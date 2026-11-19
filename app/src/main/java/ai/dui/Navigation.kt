package ai.dui

import androidx.compose.runtime.Composable
import ai.dui.ui.chat.ChatScreen
import ai.dui.ui.chat.ChatViewModel

@Composable
fun MainNavigation(viewModel: ChatViewModel? = null) {
    if (viewModel != null) {
        ChatScreen(viewModel = viewModel)
    } else {
        ChatScreen()
    }
}
