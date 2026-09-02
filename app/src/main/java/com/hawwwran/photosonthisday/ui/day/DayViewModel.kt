package com.hawwwran.photosonthisday.ui.day

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hawwwran.photosonthisday.api.PhotoItem
import com.hawwwran.photosonthisday.core.MonthDay
import com.hawwwran.photosonthisday.data.DayIndexRepository
import com.hawwwran.photosonthisday.data.DayIndexState
import com.hawwwran.photosonthisday.data.RefreshResult
import com.hawwwran.photosonthisday.session.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
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
 * Drives the day screen. The histogram (cache first) decides the day; each year of that day is
 * then loaded from its own time range, newest year first, cache first and network after. An
 * expired session is handled in the repository, which returns the app to sign-in on its own.
 */
class DayViewModel(
    private val repository: DayIndexRepository,
    private val session: Session,
) : ViewModel() {

    val state: StateFlow<DayIndexState> =
        repository.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DayIndexState.Loading)

    private val _sections = MutableStateFlow<List<YearSection>>(emptyList())
    val sections: StateFlow<List<YearSection>> = _sections

    /** The day's photos as one display-ordered sequence, so the viewer pages across all years. */
    val viewerItems: StateFlow<List<ViewerItem>> = _sections
        .map { list -> list.flatMap { section -> section.items.map { ViewerItem(section.year, it) } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    private var monthDay: MonthDay? = null

    init {
        viewModelScope.launch {
            // Fetch the histogram if it is stale or missing, so a fresh install has a day to show.
            repository.refreshIfStale(session)
            loadChosenDay()
        }
    }

    /** Pull to refresh: refetch the histogram, then reload the chosen day's years. */
    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            repository.refresh(session)
            loadChosenDay()
            _refreshing.value = false
        }
    }

    private suspend fun loadChosenDay() {
        val ready = state.filterIsInstance<DayIndexState.Ready>().first()
        val selection = ready.selection
        monthDay = selection.monthDay
        _sections.value = selection.years.map { YearSection(it.year, it.itemCount) }

        selection.years.forEach { yearBucket ->
            val year = yearBucket.year
            // Cache first: the stored items show at once and update as the fetch lands.
            viewModelScope.launch {
                repository.observeDay(year, selection.monthDay).collect { items ->
                    updateYear(year) { it.copy(items = items) }
                }
            }
            viewModelScope.launch {
                val result = repository.fetchDay(session, year, selection.monthDay)
                updateYear(year) {
                    it.copy(
                        loading = false,
                        error = (result as? RefreshResult.Failed)?.message,
                    )
                }
            }
        }
    }

    private fun updateYear(year: Int, block: (YearSection) -> YearSection) {
        _sections.update { list -> list.map { if (it.year == year) block(it) else it } }
    }
}
