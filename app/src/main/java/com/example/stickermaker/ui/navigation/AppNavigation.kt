package com.example.stickermaker.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import com.example.stickermaker.LocalAppContainer
import com.example.stickermaker.ui.components.LocalSharedTransitionScope
import com.example.stickermaker.ui.screens.create.ArrangeScreen
import com.example.stickermaker.ui.screens.designkit.DesignKitScreen
import com.example.stickermaker.ui.screens.editor.EditorScreen
import com.example.stickermaker.ui.screens.home.HomeScreen
import com.example.stickermaker.ui.screens.settings.SettingsScreen
import com.example.stickermaker.ui.theme.Motion

@Composable
fun AppNavigation() {
    // Navigation 3: the back stack is just a saveable list of @Serializable keys.
    val backStack = rememberNavBackStack(HomeRoute)
    val session = LocalAppContainer.current.session

    // An image shared in from another app opens the create flow wherever the user is.
    val openRequested by session.openRequested.collectAsStateWithLifecycle()
    LaunchedEffect(openRequested) {
        if (openRequested) {
            // Shared images join the open session: go back to Arrange if it's in the stack.
            if (ArrangeRoute in backStack) {
                while (backStack.last() != ArrangeRoute) backStack.removeAt(backStack.lastIndex)
            } else {
                backStack.push(ArrangeRoute)
            }
            session.openRequested.value = false
        }
    }

    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
            NavDisplay(
                backStack = backStack,
                onBack = { backStack.removeLastOrNull() },
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                sharedTransitionScope = this,
                transitionSpec = { push() },
                popTransitionSpec = { pop() },
                // Same motion for the predictive-back gesture as for the back button; the
                // gesture just scrubs it.
                predictivePopTransitionSpec = { pop() },
                entryProvider = entryProvider {
                    entry<HomeRoute> {
                        HomeScreen(
                            onOpenSettings = { backStack.push(SettingsRoute) },
                            onOpenCreate = { backStack.push(ArrangeRoute) },
                        )
                    }
                    entry<SettingsRoute> {
                        SettingsScreen(
                            onBack = { backStack.removeLastOrNull() },
                            onOpenDesignKit = { backStack.push(DesignKitRoute) },
                        )
                    }
                    entry<ArrangeRoute> {
                        ArrangeScreen(
                            onBack = {
                                session.clear()
                                backStack.removeLastOrNull()
                            },
                            onCutOut = { backStack.push(EditorRoute) },
                        )
                    }
                    entry<EditorRoute> {
                        EditorScreen(onBack = { backStack.removeLastOrNull() })
                    }
                    entry<DesignKitRoute> {
                        DesignKitScreen(onBack = { backStack.removeLastOrNull() })
                    }
                },
            )
        }
    }
}

/** Ignores a double-tap that would stack the same screen twice. */
private fun NavBackStack<NavKey>.push(key: NavKey) {
    if (lastOrNull() != key) add(key)
}

// New screen slides over from the right; the old one drifts a quarter left and dims.
// Pop reverses it. Every screen paints an opaque canvas, so the overlap reads as layers.
private fun AnimatedContentTransitionScope<Scene<NavKey>>.push(): ContentTransform =
    (slideInHorizontally(Motion.nav()) { it }) togetherWith
        (slideOutHorizontally(Motion.nav()) { -it / 4 } + fadeOut(Motion.nav(), targetAlpha = 0.6f))

private fun AnimatedContentTransitionScope<Scene<NavKey>>.pop(): ContentTransform =
    (slideInHorizontally(Motion.nav()) { -it / 4 } + fadeIn(Motion.nav(), initialAlpha = 0.6f)) togetherWith
        (slideOutHorizontally(Motion.nav()) { it })
