package com.example.stickermaker

import android.app.Application

class StickerMakerApp : Application() {

    /**
     * Built on first access, not in onCreate: nothing in the container is needed to draw the
     * first frame except settings, and those are only read once the Activity asks.
     */
    val container: AppContainer by lazy { AppContainer(this) }
}
