package com.hawwwran.photosonthisday.ui.day

import com.hawwwran.photosonthisday.api.PhotoItem

/** One entry in the fullscreen pager: the photo and the year it was taken, for the overlay. */
data class ViewerItem(val year: Int, val item: PhotoItem)
