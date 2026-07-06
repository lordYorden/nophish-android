package dev.lordyorden.as_no_phish_detector.ui

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.lordyorden.as_no_phish_detector.ClientActivity
import dev.lordyorden.as_no_phish_detector.R
import dev.lordyorden.as_no_phish_detector.databinding.FragmentCircleEventsBinding
import dev.lordyorden.as_no_phish_detector.repositories.CircleMembersRepository
import dev.lordyorden.as_no_phish_detector.ui.events.CircleAlertScope
import dev.lordyorden.as_no_phish_detector.ui.events.CircleEventAdapter
import dev.lordyorden.as_no_phish_detector.ui.events.CircleEventsScreenRenderer
import dev.lordyorden.as_no_phish_detector.ui.events.CircleEventsViewModel
import dev.lordyorden.as_no_phish_detector.utilities.Constants
import dev.lordyorden.as_no_phish_detector.utilities.MaliciousNotificationStore
import kotlinx.coroutines.launch

class CircleEventsFragment : Fragment() {

    private var binding: FragmentCircleEventsBinding? = null
    private lateinit var adapter: CircleEventAdapter
    private var renderer: CircleEventsScreenRenderer? = null
    private lateinit var localStore: MaliciousNotificationStore
    private val viewModel: CircleEventsViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val viewBinding = FragmentCircleEventsBinding.inflate(inflater, container, false)
        binding = viewBinding
        initViews()
        return viewBinding.root
    }

    private fun initViews() {
        val binding = binding ?: error("CircleEventsFragment binding is not initialized")
        localStore = MaliciousNotificationStore.getInstance()
        adapter = CircleEventAdapter(
            onDetailsClick = { event ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val details = localStore.getValidated(event.eventId, event.contentHash)
                    if (details == null) {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.msg_unavailable_event),
                            Toast.LENGTH_SHORT
                        ).show()
                        return@launch
                    }

                    val client = requireActivity() as ClientActivity
                    client.showDetailsBottomSheet(details)
                }
            },
            onBlockClick = { event ->
                viewLifecycleOwner.lifecycleScope.launch {
                    runCatching {
                        viewModel.blockEvent(event)
                    }.onSuccess {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.app_blocked),
                            Toast.LENGTH_SHORT
                        ).show()
                    }.onFailure { error ->
                        Log.e(TAG, "Failed to block app for eventId=${event.eventId}", error)
                        Toast.makeText(
                            requireContext(),
                            error.message ?: getString(R.string.circle_events_error),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            },
            onResolveClick = { event ->
                viewLifecycleOwner.lifecycleScope.launch {
                    runCatching {
                        viewModel.resolveEvent(event)
                    }.onSuccess {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.event_resolved),
                            Toast.LENGTH_SHORT
                        ).show()
                    }.onFailure { error ->
                        Log.e(TAG, "Failed to resolve eventId=${event.eventId}", error)
                        Toast.makeText(
                            requireContext(),
                            error.message ?: getString(R.string.circle_events_error),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )

        binding.rvCircleEvents.layoutManager = LinearLayoutManager(requireContext())
        binding.rvCircleEvents.adapter = adapter
        val screenRenderer = CircleEventsScreenRenderer(binding, adapter, requireContext())
        renderer = screenRenderer

        binding.btnCircleEventsRetry.setOnClickListener {
            viewModel.retry()
        }

        binding.toggleAlertScope.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.btn_action_required -> viewModel.setAlertScope(CircleAlertScope.ActionRequired)
                R.id.btn_all_alerts -> viewModel.setAlertScope(CircleAlertScope.AllAlerts)
            }
        }

        binding.rvCircleEvents.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return

                val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                val totalItemCount = layoutManager.itemCount
                val lastVisibleItem = layoutManager.findLastVisibleItemPosition()

                if (lastVisibleItem >= totalItemCount - 2) {
                    viewModel.loadNextPage()
                }
            }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    screenRenderer.render(state)
                }
            }
        }

        val circleId = arguments?.getString(Constants.Circle.CIRCLE_ID_KEY)
            ?: CircleMembersRepository.getInstance().currentCircleId()
        if (circleId.isNullOrBlank()) {
            Log.e(TAG, "CircleEventsFragment opened without a valid circleId")
            screenRenderer.renderContractError()
            return
        }

        viewModel.start(circleId)
    }

    override fun onDestroyView() {
        renderer = null
        binding = null
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "CircleEventsFragment"
    }
}
