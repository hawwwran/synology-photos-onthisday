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
import com.hawwwran.photosonthisday.likes.likeKey
import com.hawwwran.photosonthisday.session.Session
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
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
 * The day laid out for the grid: all liked items in one group at the very top (across every year,
 * frozen at day load), then the year groups in date order with the liked ones removed.
 */
data class DayDisplay(
    val liked: List<ViewerItem>,
    val years: List<YearSection>,
)

/** What the day screen shows, for the day currently chosen (today by default, or a browsed day). */
sealed interface DayViewState {
    data object Loading : DayViewState

    /** The whole library is empty; there is no day to browse. */
    data object NoPhotos : DayViewState

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
    private val todayProvider: () -> MonthDay = { com.hawwwran.photosonthisday.core.currentMonthDay() },
) : ViewModel() {

    /** "What is today", updatable so the app can roll to a new day when the date changes. */
    private val today = MutableStateFlow(initialToday)

    /** The keys currently liked, for the heart indicator and the liked-first order. */
    val likedKeys: StateFlow<Set<String>> =
        likes.likedKeys.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Null means "today, auto"; a value means the user chose that day. */
    private val selectedDay = MutableStateFlow<MonthDay?>(null)
    private val reloadTick = MutableStateFlow(0)

    val dayView: StateFlow<DayViewState> =
        combine(repository.observeDays(), selectedDay, today) { data, selected, td -> computeView(data, selected, td) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DayViewState.Loading)

    private val _sections = MutableStateFlow<List<YearSection>>(emptyList())

    /**
     * The liked set frozen at day load. Liking or unliking after that updates the heart (via
     * [likedKeys]) but does not move an item into or out of the top group under the user's finger;
     * membership is recomputed only when the day is (re)loaded.
     */
    private val orderKeys = MutableStateFlow<Set<String>>(emptySet())

    /** The grid layout: a top "liked" group (all liked across the day) then the year groups. */
    val display: StateFlow<DayDisplay> =
        combine(_sections, orderKeys) { years, order ->
            fun liked(item: com.hawwwran.photosonthisday.api.PhotoItem) = order.contains(likeKey(item.space, item.unitId))
            val likedItems = years.flatMap { y -> y.items.filter(::liked).map { ViewerItem(y.year, it) } }
            val yearGroups = years
                .map { y -> y.copy(items = y.items.filterNot(::liked)) }
                .filter { it.items.isNotEmpty() || (it.loading && it.error == null) }
            DayDisplay(likedItems, yearGroups)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DayDisplay(emptyList(), emptyList()))

    /**
     * A snapshot of the shown day as one display-ordered sequence (liked first), for the viewer's
     * pager. Read directly from the current sections, not a lazily-started flow, so it is correct
     * the moment the viewer opens; the viewer holds this fixed so a like toggle does not reshuffle.
     */
    fun viewerSnapshot(): List<ViewerItem> {
        val order = orderKeys.value
        val years = _sections.value
        fun liked(item: com.hawwwran.photosonthisday.api.PhotoItem) = order.contains(likeKey(item.space, item.unitId))
        val likedItems = years.flatMap { y -> y.items.filter(::liked).map { ViewerItem(y.year, it) } }
        val rest = years.flatMap { y -> y.items.filterNot(::liked).map { ViewerItem(y.year, it) } }
        return likedItems + rest
    }

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    /** Grid multi-selection, keyed like likes. Empty means not in selection mode. */
    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected

    init {
        viewModelScope.launch { repository.refreshIfStale(session) }
        viewModelScope.launch { likes.sync(session) } // pull the NAS likes so they show, push any pending
        // Reload the year sections whenever the shown day changes or a refresh is asked for.
        viewModelScope.launch {
            combine(dayView, reloadTick) { view, tick -> view to tick }
                .mapNotNull { (view, tick) -> (view as? DayViewState.Shown)?.let { Triple(it.monthDay, it.years, tick) } }
                .distinctUntilChanged()
                .collectLatest { (monthDay, years, _) -> loadSections(monthDay, years) }
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
            repository.refresh(session)
            reloadTick.update { it + 1 }
            _refreshing.value = false
        }
    }

    private fun computeView(data: DayIndexData, selected: MonthDay?, today: MonthDay): DayViewState {
        if (data.days.isNotEmpty()) return shownFor(data.days, selected, today)
        return if (data.refreshedAt != null) DayViewState.NoPhotos else DayViewState.Loading
    }

    /** Toggle the like locally at once, then reconcile with the NAS. */
    fun toggleLike(item: com.hawwwran.photosonthisday.api.PhotoItem) {
        viewModelScope.launch {
            likes.toggle(item.space, item.unitId)
            likes.sync(session)
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
            items.forEach { likes.setLiked(it.space, it.unitId, target) }
            likes.sync(session)
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

    private suspend fun loadSections(monthDay: MonthDay, years: List<DayBucket>) = coroutineScope {
        orderKeys.value = likedKeys.value // freeze the liked-first order for this day visit
        _sections.value = years.map { YearSection(it.year, it.itemCount) }
        years.forEach { yearBucket ->
            val year = yearBucket.year
            launch {
                repository.observeDay(year, monthDay).collect { items ->
                    updateYear(year) { it.copy(items = items) }
                }
            }
            launch {
                val result = repository.fetchDay(session, year, monthDay)
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
