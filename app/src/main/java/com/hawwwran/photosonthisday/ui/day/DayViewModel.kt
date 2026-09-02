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
    private val session: Session,
    private val today: MonthDay,
) : ViewModel() {

    /** Null means "today, auto"; a value means the user chose that day. */
    private val selectedDay = MutableStateFlow<MonthDay?>(null)
    private val reloadTick = MutableStateFlow(0)

    val dayView: StateFlow<DayViewState> =
        combine(repository.observeDays(), selectedDay, ::computeView)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DayViewState.Loading)

    private val _sections = MutableStateFlow<List<YearSection>>(emptyList())
    val sections: StateFlow<List<YearSection>> = _sections

    /** The shown day's photos as one display-ordered sequence, so the viewer pages across years. */
    val viewerItems: StateFlow<List<ViewerItem>> = _sections
        .map { list -> list.flatMap { section -> section.items.map { ViewerItem(section.year, it) } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    init {
        viewModelScope.launch { repository.refreshIfStale(session) }
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

    private fun computeView(data: DayIndexData, selected: MonthDay?): DayViewState {
        if (data.days.isNotEmpty()) return shownFor(data.days, selected)
        return if (data.refreshedAt != null) DayViewState.NoPhotos else DayViewState.Loading
    }

    private fun shownMonthDay(): MonthDay? = (dayView.value as? DayViewState.Shown)?.monthDay

    private fun shownFor(days: List<DayBucket>, selected: MonthDay?): DayViewState.Shown {
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
