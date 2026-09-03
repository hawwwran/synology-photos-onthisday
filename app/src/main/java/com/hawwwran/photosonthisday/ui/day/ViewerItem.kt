package com.hawwwran.photosonthisday.ui.day

import com.hawwwran.photosonthisday.api.PhotoItem

/** One entry in the fullscreen pager. The overlay reads the date from the photo's own timestamp. */
data class ViewerItem(val item: PhotoItem)
