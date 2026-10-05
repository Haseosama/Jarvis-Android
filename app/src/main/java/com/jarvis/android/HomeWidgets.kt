package com.jarvis.android

import android.content.Context

/** Asks the home-screen widget, if one is placed, to show what changed (a reminder, the face's look). */
internal fun refreshHomeWidgets(context: Context) = com.jarvis.android.widget.JarvisWidgetProvider.refresh(context)
