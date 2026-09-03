package com.hawwwran.photosonthisday.data

import android.content.Context

/** The one `FileProvider` authority, as the manifest declares it: `${applicationId}.fileprovider`. */
fun fileProviderAuthority(context: Context): String = "${context.packageName}.fileprovider"
