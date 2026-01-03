package com.example.myapplication

import android.app.Application
import com.mapbox.common.MapboxOptions

/**
 * Application class for SenseSafe
 * 
 * Initializes:
 * - Mapbox SDK with access token
 * - Other app-wide configurations
 */
class SenseSafeApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize Mapbox with access token
        val mapboxToken = getString(R.string.mapbox_access_token)
        MapboxOptions.accessToken = mapboxToken
    }
}
