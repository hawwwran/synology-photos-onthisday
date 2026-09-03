package com.hawwwran.photosonthisday.ui.day

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hawwwran.photosonthisday.api.PhotoItem
import com.hawwwran.photosonthisday.core.DayBucket
import com.hawwwran.photosonthisday.core.MonthDay
import com.hawwwran.photosonthisday.core.nextDay
import com.hawwwran.photosonthisday.core.photosOn
import com.hawwwran.photosonthisday.core.previousDay
import com.hawwwran.photosonthisday.core.selectDay
import com.hawwwran.photosonthisday.data.DayIndexData
import com.hawwwran.photosonthisday.data.DayIndexRepository
import com.hawwwran.photosonthisday.likes.LikeRepository
import com.hawwwran.photosonthisday.likes.SyncResult
import com.hawwwran.photosonthisday.likes.likeKey
import com.hawwwran.photosonthisday.session.Session
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One year's photos of the chosen day, and how its load is going. */
data class YearSection(
    val year: Int,
    val expectedCount: Int,
    val items: List<PhotoItem> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

/**
 * A section of the day grid. Liked photos always sit at the top: either as one [Liked] blob or,
 * split by year, as several [LikedYear] groups. [Year] groups below hold only the unliked photos
 * of that year. Liked are never mixed into the [Year] groups.
 */
sealed interface DaySectionHeader {
    data object Liked : DaySectionHeader
    data class LikedYear(val year: Int) : DaySectionHeader
    data class Year(val year: Int) : DaySectionHeader
}

/** One rendered group: its header, its photos, and (for a year) its load state. */
data class DaySection(
    val header: DaySectionHeader,
    val items: List<com.hawwwran.photosonthisday.api.PhotoItem>,
    val loading: Boolean = false,
    val error: String? = null,
)

/** What the day screen shows, for the day currently chosen (today by default, or a browsed day). */
sealed interface DayViewState {
    data object Loading : DayViewState

    /** The whole library is empty; there is no day to browse. */
    data object NoPhotos : DayViewState

    /** No cached index yet and the first refresh failed (e.g. offline). [message] says why. */
    data class Problem(val message: String) : DayViewState

    data class Shown(
        val monthDay: MonthDay,
        val isToday: Boolean,
        /** Years holding this day, newest first; empty when a browsed day has no photos. */
        val years: List<DayBucket>,
        /** How far this day is from today, and which side, only meaningful for the auto pick. */
        val fallbackDays: Int,
        val inThePast: Boolean,
    ) : DayViewState {
        val hasPhotos: Boolean get() = years.isNotEmpty()
        val isFallback: Boolean get() = fallbackDays != 0
    }
}

/**
 * Drives the day screen. By default it shows today across the years (or the nearest day that has
 * photos); prev/next and the picker set an explicit day, which is then shown exactly, empty or
 * not. Each shown year is loaded from its own time range, cache first and network after.
 */
class DayViewModel(
    private val repository: DayIndexRepository,
    private val likes: LikeRepository,
    private val session: Session,
    initialToday: MonthDay,
    likedByYearFlow: kotlinx.coroutines.flow.Flow<Boolean>,
    private val todayProvider: () -> MonthDay = { com.hawwwran.photosonthisday.core.currentMonthDay() },
) : ViewModel() {

    /** Layout of the always-on-top liked group: false = one blob, true = split by year. */
    private val likedByYear = likedByYearFlow.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** "What is today", updatable so the app can roll to a new day when the date changes. */
    private val today = MutableStateFlow(initialToday)

    /** The keys currently liked, for the heart indicator and the liked-first order. */
    val likedKeys: StateFlow<Set<String>> =
        likes.likedKeys.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Null means "today, auto"; a value means the user chose that day. */
    private val selectedDay = MutableStateFlow<MonthDay?>(null)
    private val reloadTick = MutableStateFlow(0)

    /** Set when a refresh fails; only surfaced when there is no cached index to fall back on. */
    private val loadError = MutableStateFlow<String?>(null)

    val dayView: StateFlow<DayViewState> =
        combine(repository.observeDays(), selectedDay, today, loadError) { data, selected, td, err ->
            computeView(data, selected, td, err)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DayViewState.Loading)

    private val _sections = MutableStateFlow<List<YearSection>>(emptyList())

    /**
     * The liked set frozen at day load. Liking or unliking after that updates the heart (via
     * [likedKeys]) but does not move an item into or out of the top group under the user's finger;
     * membership is recomputed only when the day is (re)loaded.
     */
    private val orderKeys = MutableStateFlow<Set<String>>(emptySet())

    /** The grid layout as a list of sections, per the liked-by-year preference. */
    val display: StateFlow<List<DaySection>> =
        combine(_sections, orderKeys, likedByYear) { years, order, byYear ->
            buildSections(years, order, byYear)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * A snapshot of the shown day as one display-ordered sequence (liked first), for the viewer's
     * pager. Read directly from the current sections, not a lazily-started flow, so it is correct
     * the moment the viewer opens; the viewer holds this fixed so a like toggle does not reshuffle.
     */
    fun viewerSnapshot(): List<ViewerItem> =
        buildSections(_sections.value, orderKeys.value, likedByYear.value).flatMap { it.items.map(::ViewerItem) }

    /**
     * The day's sections in render order. Liked always come first: as one [DaySectionHeader.Liked]
     * blob, or (when [byYear]) as per-year [DaySectionHeader.LikedYear] groups. Then the years,
     * each holding only its unliked photos. Membership uses the frozen [orderKeys], so a like on
     * the current day never moves an item until the day reloads.
     */
    private fun buildSections(years: List<YearSection>, order: Set<String>, byYear: Boolean): List<DaySection> {
        fun liked(item: com.hawwwran.photosonthisday.api.PhotoItem) = order.contains(likeKey(item.space, item.unitId))
        fun shown(section: DaySection) = section.items.isNotEmpty() || (section.loading && section.error == null)

        val likedTop: List<DaySection> = if (byYear) {
            years.mapNotNull { y ->
                val likedItems = y.items.filter(::liked)
                if (likedItems.isEmpty()) null else DaySection(DaySectionHeader.LikedYear(y.year), likedItems)
            }
        } else {
            val likedItems = years.flatMap { y -> y.items.filter(::liked) }
            if (likedItems.isEmpty()) emptyList() else listOf(DaySection(DaySectionHeader.Liked, likedItems))
        }
        val yearGroups = years
            .map { y -> DaySection(DaySectionHeader.Year(y.year), y.items.filterNot(::liked), y.loading, y.error) }
            .filter(::shown)
        return likedTop + yearGroups
    }

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    /**
     * A likes sync failure to show, once per session: the plain-language reason, or null. Decision
     * 008 says a failed sync keeps the like and retries, so this is a notice, not an error state;
     * one is enough, every later sync would repeat it.
     */
    private val _likesNotice = MutableStateFlow<String?>(null)
    val likesNotice: StateFlow<String?> = _likesNotice
    private var likesNoticeShown = false

    fun likesNoticeShown() {
        _likesNotice.value = null
    }

    private suspend fun syncLikes() {
        val result = likes.sync(session)
        if (result is SyncResult.Failed && !likesNoticeShown) {
            likesNoticeShown = true
            _likesNotice.value = result.message
        }
    }

    /** Grid multi-selection, keyed like likes. Empty means not in selection mode. */
    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected

    init {
        viewModelScope.launch {
            val result = repository.refreshIfStale(session)
            loadError.value = (result as? com.hawwwran.photosonthisday.data.RefreshResult.Failed)?.message
        }
        viewModelScope.launch { syncLikes() } // pull the NAS likes so they show, push any pending
        // Reload the year sections whenever the shown day changes or a refresh is asked for. A
        // changed tick means the user asked, so the cached-count skip in fetchDay is bypassed.
        viewModelScope.launch {
            var lastTick = 0
            combine(dayView, reloadTick) { view, tick -> view to tick }
                .mapNotNull { (view, tick) -> (view as? DayViewState.Shown)?.let { Triple(it.monthDay, it.years, tick) } }
                .distinctUntilChanged()
                .collectLatest { (monthDay, years, tick) ->
                    val forced = tick != lastTick
                    lastTick = tick
                    loadSections(monthDay, years, forced)
                }
        }
    }

    fun showPreviousDay() {
        shownMonthDay()?.let { selectedDay.value = it.previousDay() }
    }

    fun showNextDay() {
        shownMonthDay()?.let { selectedDay.value = it.nextDay() }
    }

    fun showDay(monthDay: MonthDay) {
        selectedDay.value = monthDay
    }

    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            val result = repository.refresh(session)
            loadError.value = (result as? com.hawwwran.photosonthisday.data.RefreshResult.Failed)?.message
            reloadTick.update { it + 1 }
            _refreshing.value = false
        }
    }

    /** Retry after the first refresh failed with no cache to show (the Problem screen). */
    fun retry() = refresh()

    private fun computeView(data: DayIndexData, selected: MonthDay?, today: MonthDay, error: String?): DayViewState {
        if (data.days.isNotEmpty()) return shownFor(data.days, selected, today)
        return when {
            error != null -> DayViewState.Problem(error) // nothing to show and the last refresh failed
            data.refreshedAt != null -> DayViewState.NoPhotos
            else -> DayViewState.Loading
        }
    }

    /** Toggle the like locally at once, then reconcile with the NAS. */
    fun toggleLike(item: com.hawwwran.photosonthisday.api.PhotoItem) {
        viewModelScope.launch {
            likes.toggle(item.space, item.unitId)
            syncLikes()
        }
    }

    // ---- multi-selection ----

    fun startSelect(item: com.hawwwran.photosonthisday.api.PhotoItem) {
        _selected.update { it + likeKey(item.space, item.unitId) }
    }

    fun toggleSelect(item: com.hawwwran.photosonthisday.api.PhotoItem) {
        val key = likeKey(item.space, item.unitId)
        _selected.update { if (key in it) it - key else it + key }
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    /**
     * Roll to the current day. Called when the app comes to the front and the calendar date has
     * changed (e.g. midnight passed while the phone was locked), so it always reopens on "today"
     * without interrupting a same-day session.
     */
    fun refreshToday() {
        today.value = todayProvider()
        selectedDay.value = null
    }

    /** The selected items, resolved from the shown day, in display order. */
    fun selectedItems(): List<com.hawwwran.photosonthisday.api.PhotoItem> {
        val keys = _selected.value
        return _sections.value.flatMap { it.items }.filter { likeKey(it.space, it.unitId) in keys }
    }

    /**
     * Toggle the like on the selection: if every selected item is already liked, unlike them all;
     * otherwise like them all. Then reconcile and leave selection mode.
     */
    fun likeSelected() {
        viewModelScope.launch {
            val items = selectedItems()
            if (items.isEmpty()) return@launch
            val current = likedKeys.value
            val allLiked = items.all { current.contains(likeKey(it.space, it.unitId)) }
            val target = !allLiked
            likes.setLikedAll(items.map { it.space to it.unitId }, target)
            syncLikes()
            clearSelection()
        }
    }

    private fun shownMonthDay(): MonthDay? = (dayView.value as? DayViewState.Shown)?.monthDay

    private fun shownFor(days: List<DayBucket>, selected: MonthDay?, today: MonthDay): DayViewState.Shown {
        if (selected == null) {
            // Auto: today across the years, or the nearest day that holds anything.
            val auto = selectDay(days, today)!!
            return DayViewState.Shown(
                monthDay = auto.monthDay,
                isToday = auto.monthDay == today,
                years = auto.years,
                fallbackDays = auto.daysFromToday,
                inThePast = auto.inThePast,
            )
        }
        return DayViewState.Shown(
            monthDay = selected,
            isToday = selected == today,
            years = photosOn(days, selected),
            fallbackDays = 0,
            inThePast = false,
        )
    }

    private suspend fun loadSections(monthDay: MonthDay, years: List<DayBucket>, force: Boolean) = coroutineScope {
        // Freeze the liked-first order for this day visit. Read from the DAO, not from likedKeys:
        // that StateFlow starts with the first screen subscriber, after the first frame, and on a
        // cold start this runs before it, so its value would still be the empty initial set and
        // the liked group would be missing until the day reloaded.
        orderKeys.value = likes.likedKeys.first()
        _sections.value = years.map { YearSection(it.year, it.itemCount) }
        years.forEach { yearBucket ->
            val year = yearBucket.year
            launch {
                repository.observeDay(year, monthDay).collect { items ->
                    updateYear(year) { it.copy(items = items) }
                }
            }
            launch {
                val result = repository.fetchDay(session, year, monthDay, expectedCount = yearBucket.itemCount, force = force)
                updateYear(year) {
                    it.copy(loading = false, error = (result as? com.hawwwran.photosonthisday.data.RefreshResult.Failed)?.message)
                }
            }
        }
    }

    private fun updateYear(year: Int, block: (YearSection) -> YearSection) {
        _sections.update { list -> list.map { if (it.year == year) block(it) else it } }
    }
}
