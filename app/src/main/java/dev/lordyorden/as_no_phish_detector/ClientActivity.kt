package dev.lordyorden.as_no_phish_detector

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.clerk.api.Clerk
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.messaging.FirebaseMessaging
import com.vmadalin.easypermissions.EasyPermissions
import dev.lordyorden.as_no_phish_detector.databinding.ActivityClientBinding
import dev.lordyorden.as_no_phish_detector.databinding.AttackDetailsBottomSheetBinding
import dev.lordyorden.as_no_phish_detector.databinding.UrlItemBinding
import dev.lordyorden.as_no_phish_detector.models.AttackDetails
import dev.lordyorden.as_no_phish_detector.repositories.CircleMembersRepository
import dev.lordyorden.as_no_phish_detector.services.FCMService
import dev.lordyorden.as_no_phish_detector.services.UploadForegroundService
import dev.lordyorden.as_no_phish_detector.clerk.UserStateViewModel
import dev.lordyorden.as_no_phish_detector.clerk.UserUiState
import dev.lordyorden.as_no_phish_detector.ui.settings.PermsViewModel
import dev.lordyorden.as_no_phish_detector.utilities.Constants
import dev.lordyorden.as_no_phish_detector.utilities.ConvexHelper
import dev.lordyorden.as_no_phish_detector.utilities.ImageLoader
import dev.lordyorden.as_no_phish_detector.utilities.MaliciousNotificationStore
import dev.lordyorden.as_no_phish_detector.utilities.NetworkMonitor
import kotlinx.coroutines.launch

class ClientActivity : AppCompatActivity(), EasyPermissions.RationaleCallbacks,
    EasyPermissions.PermissionCallbacks {

    private lateinit var binding: ActivityClientBinding
    private val permsViewModel: PermsViewModel by viewModels()
    private val userStateViewModel: UserStateViewModel by viewModels()
    private lateinit var navController: NavController
    private var signedInObserved = false
    private var signedOutHandled = false
    private var clientGraphInitialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityClientBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setCurrentCircleIdFromIntent(intent)
        //startActivity(Intent(ACTION_NOTIFICATION_LISTENER_SETTINGS))
        initViews()
        handleIntent(intent)
    }

    private fun initViews() {
        setupNav()
        observeConnectionState()
        observeAuthState()

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> {
                    navController.navigate(R.id.to_settings)
                }

                else -> super.onOptionsItemSelected(item)
            }
            true
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            when (destination.id) {
                R.id.nev_history, R.id.nev_circle_history, R.id.nev_settings-> {
                    binding.toolbar.visibility = View.GONE
                }

                else -> {
                    binding.toolbar.visibility = View.VISIBLE
                }
            }
        }

    }

    private fun observeConnectionState() {
        val networkMonitor = NetworkMonitor.getInstance()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                networkMonitor.isOnline.collect { isOnline ->
                    if (isOnline) {
                        binding.offlineBanner.visibility = View.GONE
                    } else {
                        binding.offlineBanner.text = getString(R.string.offline_banner)
                        binding.offlineBanner.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun observeAuthState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                userStateViewModel.uiState.collect { userState ->
                    when (userState) {
                        UserUiState.SignedIn -> {
                            signedInObserved = true
                            runCatching {
                                val circleId = ensureCurrentCircleId()
                                setupFCM(circleId)
                                setupClientGraph()
                            }.onFailure { error ->
                                Log.e(TAG, "Failed to ensure current circle id", error)
                                if (error !is MissingCircleException) {
                                    Toast.makeText(
                                        this@ClientActivity,
                                        getString(R.string.general_error_redirect),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                moveToMainActivity()
                            }
                        }

                        UserUiState.SignedOut -> {
                            if (!signedOutHandled) {
                                signedOutHandled = true
                                if (signedInObserved) {
                                    Toast.makeText(
                                        this@ClientActivity,
                                        getString(R.string.session_expired),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                moveToMainActivity()
                            }
                        }

                        else -> Unit
                    }
                }
            }
        }
    }

    private suspend fun ensureCurrentCircleId(): String {
        val circleMembersRepository = CircleMembersRepository.getInstance()
        circleMembersRepository.currentCircleId()?.let { cachedCircleId ->
            if (cachedCircleId == Constants.Onboarding.ACTION_GENERATE) {
                throw MissingCircleException()
            }
            return cachedCircleId
        }


        val circleId = ConvexHelper.getInstance()
            .convexClient
            .mutation<String>("circles:get_my_circles")

        if (circleId == Constants.Onboarding.ACTION_GENERATE) {
            throw MissingCircleException()
        }

        circleMembersRepository.setCurrentCircleId(circleId)
        return circleId
    }

    private fun setupFCM(circleId: String) {

        val topicName = "circle_$circleId"

        FirebaseMessaging.getInstance().subscribeToTopic(topicName)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d("FCM", "Successfully subscribed to topic!")
                } else {
                    Log.e("FCM", "Subscription failed")
                }
            }
    }

    private fun setupNav() {
        val navView = binding.navView

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment_activity_client) as NavHostFragment
        navController = navHostFragment.navController
        binding.toolbar.visibility = View.GONE
        navView.visibility = View.GONE
        // Passing each menu ID as a set of Ids because each
        // menu should be considered as top level destinations.
//        val appBarConfiguration = AppBarConfiguration(
//            setOf(
//                R.id.navigation_home, R.id.navigation_dashboard, R.id.navigation_notifications
//            )
//        )
        //setupActionBarWithNavController(navController, appBarConfiguration)

        navView.setupWithNavController(navController)
    }

    private fun setupClientGraph() {
        if (clientGraphInitialized) return

        navController.setGraph(R.navigation.client_navigation)
        binding.navView.visibility = View.VISIBLE
        clientGraphInitialized = true
    }

    private fun setCurrentCircleIdFromIntent(intent: Intent) {
        val circleId = intent.getStringExtra(Constants.Circle.CIRCLE_ID_KEY) ?: return
        CircleMembersRepository.getInstance().setCurrentCircleId(circleId)
    }

    private fun moveToMainActivity() {
        startActivity(
            Intent(this@ClientActivity, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        )
    }


    private fun commandToService(action: String) {
        val intent = Intent(this, UploadForegroundService::class.java)
        intent.setAction(action)
        startForegroundService(intent)
    }

    override fun onStart() {
        super.onStart()
        commandToService(UploadForegroundService.ACTION_START)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // EasyPermissions handles the request result.
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this)
    }

    override fun onPermissionsDenied(
        requestCode: Int,
        perms: List<String>
    ) {
        Toast.makeText(this, "perms denied $requestCode", Toast.LENGTH_SHORT).show()
        permsViewModel.setRejected(requestCode)
    }

    override fun onPermissionsGranted(
        requestCode: Int,
        perms: List<String>
    ) {
        Toast.makeText(this, "perms granted $requestCode", Toast.LENGTH_SHORT).show()
        permsViewModel.setGranted(requestCode)
    }

    override fun onRationaleAccepted(requestCode: Int) {
        Toast.makeText(this, "rel granted $requestCode", Toast.LENGTH_SHORT).show()
        //permsViewModel.setGranted(requestCode)
    }

    override fun onRationaleDenied(requestCode: Int) {
        Toast.makeText(this, "rel denied $requestCode", Toast.LENGTH_SHORT).show()
        //permsViewModel.setRejected(requestCode)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Clerk.auth.handle(intent.data)
        setCurrentCircleIdFromIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        Log.e("intent client", "checking action")

        when (intent.action) {
            FCMService.SHOW_DETAILS_ACTION -> {
                intent.extras?.let {
                    val eventId = it.getString("eventId").orEmpty()
                    val contentHash = it.getString("contentHash").orEmpty()

                    lifecycleScope.launch {
                        MaliciousNotificationStore.getInstance()
                            .takeIf { eventId.isNotBlank() && contentHash.isNotBlank() }
                            ?.getValidated(eventId, contentHash)
                            ?.let { details -> showDetailsBottomSheet(details) }
                            ?: Toast.makeText(
                                this@ClientActivity,
                                getString(R.string.msg_unavailable_event),
                                Toast.LENGTH_SHORT
                            ).show()
                    }
                }

            }
        }
    }

    fun showDetailsBottomSheet(details: AttackDetails) {
        val sheet = BottomSheetDialog(this)
        val sheetView = AttackDetailsBottomSheetBinding.inflate(layoutInflater)
        sheet.behavior.peekHeight = 1000

        ImageLoader.getInstance().loadAppIcon(details.packageName, sheetView.ivAppIcon)
        sheetView.tvBody.text = details.body

        sheetView.tvAppName.text = details.packageName

        try {
            details.urls.forEach { url ->
                val urlItem = UrlItemBinding.inflate(layoutInflater, sheetView.root, false)
                urlItem.tvUrl.text = url
                sheetView.listUrls.addView(urlItem.root)
            }

            if (details.urls.isNotEmpty()) {
                sheetView.tvNoUrl.visibility = View.GONE
            }
        } catch (e: NoSuchElementException) {
            sheetView.tvNoUrl.visibility = View.VISIBLE
        }

        sheet.setCancelable(true)
        sheet.setContentView(sheetView.root)
        sheet.show()
    }

    companion object {
        const val TAG = "ClientActivity"
    }

    private class MissingCircleException : IllegalStateException("Authenticated user has no circle")
}
