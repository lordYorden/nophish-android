package dev.lordyorden.as_no_phish_detector.ui.events

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.convex.android.ConvexClient
import dev.convex.android.WebSocketState
import dev.lordyorden.as_no_phish_detector.models.CircleEvent
import dev.lordyorden.as_no_phish_detector.models.Event
import dev.lordyorden.as_no_phish_detector.models.PaginationResult
import dev.lordyorden.as_no_phish_detector.repositories.CircleMembersRepository
import dev.lordyorden.as_no_phish_detector.repositories.CircleMembersState
import dev.lordyorden.as_no_phish_detector.utilities.Constants
import dev.lordyorden.as_no_phish_detector.utilities.ConvexHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CircleEventsViewModel : ViewModel() {

    private val client: ConvexClient = ConvexHelper.getInstance().convexClient
    private val circleMembersRepository = CircleMembersRepository.getInstance()

    private val _uiState = MutableStateFlow(CircleEventsUiState())
    val uiState: StateFlow<CircleEventsUiState> = _uiState.asStateFlow()

    private var eventsJob: Job? = null
    private var membersStateJob: Job? = null
    private var webSocketJob: Job? = null
    private var requestedItemCount = Constants.HistoryPagination.PAGE_SIZE
    private var circleId: String? = null
    private var latestEvents = emptyList<CircleEvent>()
    private var circleMembersStateFlow: StateFlow<CircleMembersState>? = null
    private var alertScope = CircleAlertScope.ActionRequired

    fun start(circleId: String) {
        require(circleId.isNotBlank()) { "circleId must not be blank" }

        if (this.circleId == circleId && eventsJob != null && membersStateJob != null) return
        this.circleId = circleId
        observeReconnects()
        observeMembers(circleId)
        subscribeToEvents(HistoryLoading.Initial)
    }

    fun setAlertScope(scope: CircleAlertScope) {
        if (alertScope == scope) return
        alertScope = scope
        requestedItemCount = Constants.HistoryPagination.PAGE_SIZE
        latestEvents = emptyList()
        if (!hasCircleId()) {
            publishPendingState(HistoryLoading.Initial)
            return
        }

        subscribeToEvents(HistoryLoading.Initial)
    }

    fun loadNextPage() {
        val state = _uiState.value
        if (state.loading != HistoryLoading.Idle || state.endReached || state.errorMessage != null) return
        if (!hasCircleId()) {
            publishPendingState(HistoryLoading.Initial)
            return
        }

        requestedItemCount += Constants.HistoryPagination.PAGE_SIZE
        subscribeToEvents(HistoryLoading.Append)
    }

    fun retry() {
        val loading = if (_uiState.value.events.isEmpty()) {
            HistoryLoading.Initial
        } else {
            HistoryLoading.Append
        }
        if (!hasCircleId()) {
            publishPendingState(loading)
            return
        }

        subscribeToEvents(loading)
    }

    suspend fun blockEvent(event: Event) {
        require(event.eventId.isNotBlank()) { "eventId must not be blank" }

        client.mutation<String>(
            "blocks:blockFromEvent",
            mapOf("eventId" to event.eventId),
        )
    }

    suspend fun resolveEvent(event: Event) {
        require(event.eventId.isNotBlank()) { "eventId must not be blank" }

        client.mutation<Unit?>(
            "blocks:releaseForEvent",
            mapOf("eventId" to event.eventId),
        )
    }

    private fun observeMembers(circleId: String) {
        membersStateJob?.cancel()
        val membersStateFlow = circleMembersRepository.observe(circleId)
        circleMembersStateFlow = membersStateFlow
        membersStateJob = viewModelScope.launch {
            membersStateFlow.collect { state ->
                if (state.errorMessage != null) {
                    publishError(IllegalStateException(state.errorMessage))
                    return@collect
                }

                publishResolvedEvents(membersState = state)
            }
        }
    }

    private fun subscribeToEvents(loading: HistoryLoading) {
        val circleId = this.circleId
        if (circleId.isNullOrBlank()) {
            publishPendingState(loading)
            return
        }

        eventsJob?.cancel()

        _uiState.update {
            it.copy(
                loading = loading,
                errorMessage = null,
                errorKind = null,
                endReached = if (loading == HistoryLoading.Initial) false else it.endReached,
                alertScope = alertScope,
            )
        }

        val args = circleEventsArgs(circleId)
        Log.d(TAG, "subscribing to circle events with args: $args")

        eventsJob = viewModelScope.launch {
            try {
                client.subscribe<PaginationResult<CircleEvent>>("events:get_by_circle", args).collect { result ->
                    result.onSuccess { page ->
                        latestEvents = page.page
                        Log.d(TAG, "received ${page.page.size} circle events; isDone=${page.isDone}")
                        publishResolvedEvents(
                            loading = HistoryLoading.Idle,
                            endReached = page.isDone,
                        )
                    }.onFailure { error ->
                        publishError(error)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                publishError(error)
            }
        }
    }

    private fun hasCircleId(): Boolean = !circleId.isNullOrBlank()

    private fun publishPendingState(loading: HistoryLoading) {
        _uiState.value = CircleEventsUiState(
            loading = loading,
            alertScope = alertScope,
        )
    }

    private fun publishResolvedEvents(
        loading: HistoryLoading = _uiState.value.loading,
        endReached: Boolean = _uiState.value.endReached,
        membersState: CircleMembersState? = circleMembersStateFlow?.value,
    ) {
        if (membersState?.errorMessage != null) {
            _uiState.update {
                it.copy(
                    loading = HistoryLoading.Idle,
                    errorMessage = membersState.errorMessage,
                    errorKind = CircleEventsErrorKind.Generic,
                    alertScope = alertScope,
                )
            }
            return
        }

        if (membersState == null || !membersState.loaded) {
            _uiState.update {
                it.copy(
                    loading = if (latestEvents.isEmpty()) loading else HistoryLoading.Initial,
                    errorMessage = null,
                    errorKind = null,
                    endReached = endReached,
                    alertScope = alertScope,
                )
            }
            return
        }

        val items = latestEvents.map { circleEvent ->
            val member = membersState.membersByUserId[circleEvent.userId]
            if (member == null) {
                val message = "Missing circle member for eventId=${circleEvent.eventId}"
                Log.e(TAG, message)
                _uiState.update {
                    it.copy(
                        loading = HistoryLoading.Idle,
                        errorMessage = message,
                        errorKind = CircleEventsErrorKind.MissingCircleMember,
                        alertScope = alertScope,
                    )
                }
                return
            }
            CircleEventUiItem(
                event = circleEvent.toEvent(),
                member = member,
                isBlocked = circleEvent.isBlocked,
            )
        }

        _uiState.value = CircleEventsUiState(
            events = items,
            loading = loading,
            endReached = endReached,
            alertScope = alertScope,
        )
    }

    private fun observeReconnects() {
        if (webSocketJob != null) return

        webSocketJob = viewModelScope.launch {
            client.webSocketStateFlow
                .drop(1)
                .distinctUntilChanged()
                .collect { state ->
                    if (state == WebSocketState.CONNECTED && _uiState.value.errorMessage != null) {
                        retry()
                    }
                }
        }
    }

    private fun publishError(error: Throwable) {
        val message = error.message ?: error::class.java.simpleName
        Log.e(TAG, "circle events subscription failed", error)
        _uiState.update {
            it.copy(
                loading = HistoryLoading.Idle,
                errorMessage = message,
                errorKind = CircleEventsErrorKind.Generic,
                alertScope = alertScope,
            )
        }
    }

    private fun circleEventsArgs(circleId: String): Map<String, Any?> {
        val args = mutableMapOf<String, Any?>(
            "circleId" to circleId,
            "paginationOpts" to mapOf(
                "cursor" to null,
                "numItems" to requestedItemCount.toFloat()
            )
        )

        if (alertScope == CircleAlertScope.ActionRequired) {
            args["requiresAction"] = true
        }

        return args
    }

    companion object {
        const val TAG = "CircleEventsViewModel"
    }
}
