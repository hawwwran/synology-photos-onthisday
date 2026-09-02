package com.hawwwran.photosonthisday

import android.app.Application

/**
 * Application entry point and the single place that owns long-lived objects
 * (HTTP client, Room database, session store). Manual construction rather than a
 * DI framework: the graph is small enough to read in one screen.
 */
class OnThisDayApp : Application()
