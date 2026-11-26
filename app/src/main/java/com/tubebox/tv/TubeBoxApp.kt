package com.tubebox.tv

import android.app.Application
import com.tubebox.tv.data.OkHttpDownloader
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

class TubeBoxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val downloader = OkHttpDownloader.instance
        NewPipe.init(
            downloader,
            Localization("pt", "BR"),
            ContentCountry("BR")
        )
    }
}
