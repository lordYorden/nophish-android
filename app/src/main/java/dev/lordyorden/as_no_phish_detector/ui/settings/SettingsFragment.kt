package dev.lordyorden.as_no_phish_detector.ui.settings

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
import android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS
import android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.clerk.api.Clerk
import com.vmadalin.easypermissions.EasyPermissions
import com.vmadalin.easypermissions.models.PermissionRequest
import dev.lordyorden.as_no_phish_detector.R
import dev.lordyorden.as_no_phish_detector.databinding.BadgeActiveBinding
import dev.lordyorden.as_no_phish_detector.databinding.BadgeDisabledBinding
import dev.lordyorden.as_no_phish_detector.databinding.FragmentSettingsBinding
import dev.lordyorden.as_no_phish_detector.repositories.CircleMembersRepository
import dev.lordyorden.as_no_phish_detector.services.AppBlockAccessibilityService
import dev.lordyorden.as_no_phish_detector.services.NotificationReceiverService
import dev.lordyorden.as_no_phish_detector.services.UploadForegroundService
import dev.lordyorden.as_no_phish_detector.utilities.Constants
import dev.lordyorden.as_no_phish_detector.utilities.MaliciousNotificationStore
import dev.lordyorden.as_no_phish_detector.utilities.PendingNotificationUploadStore
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private lateinit var binding: FragmentSettingsBinding
    private val permsViewModel: PermsViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        binding = FragmentSettingsBinding.inflate(inflater, container, false)
        val root: View = binding.root

        initViews()
        return root
    }



    private fun initViews() {
        binding.btnLogout.setOnClickListener {
            lifecycleScope.launch {
                Clerk.auth.signOut()
                PendingNotificationUploadStore.getInstance(requireContext()).clearAll()
                MaliciousNotificationStore.getInstance().clearAll()
                CircleMembersRepository.getInstance().clearAll()
                requireContext().startService(
                    Intent(requireContext(), UploadForegroundService::class.java).apply {
                        action = UploadForegroundService.ACTION_STOP
                    }
                )
                requireActivity().finish()
            }
        }

        binding.postNotifMs.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked)
                requestPerm("android.permission.POST_NOTIFICATIONS", Constants.Perms.POST_NOTIFICATION_CODE)

        }

        binding.smsMs.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked)
                requestPerm("android.permission.READ_SMS", Constants.Perms.READ_SMS_CODE)
        }

//        binding.readNotifMs.setOnCheckedChangeListener { _, isChecked ->
//            if (isChecked)
//                requestPerm("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", Constants.Perms.READ_NOTIFICATION_CODE)
//        }

        permsViewModel.permGranted.observe(viewLifecycleOwner) { granted->
            if(granted == null)
                return@observe

            activatePerm(granted)
        }

        permsViewModel.permRejected.observe(viewLifecycleOwner) { reject->
            if(reject == null)
                return@observe

            deniedPerm(reject)
        }

        renderSystemConnectionStates()
    }

    override fun onResume() {
        super.onResume()
        if (EasyPermissions.hasPermissions(requireActivity(), "android.permission.POST_NOTIFICATIONS")){
            activatePerm(Constants.Perms.POST_NOTIFICATION_CODE)
        }

        if (EasyPermissions.hasPermissions(requireActivity(), "android.permission.READ_SMS")){
            activatePerm(Constants.Perms.READ_SMS_CODE)
        }

        if (EasyPermissions.hasPermissions(requireActivity(), "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE")){
            activatePerm(Constants.Perms.READ_NOTIFICATION_CODE)
        }

        renderSystemConnectionStates()
    }



    private fun requestPerm(perm: String, code: Int){

        if (EasyPermissions.hasPermissions(requireActivity(), perm)){
            activatePerm(code)
            return
        }

        if (EasyPermissions.permissionPermanentlyDenied(requireActivity(), perm)){
            deniedPerm(code)
            return
        }

        val request = PermissionRequest.Builder(requireActivity())
            .code(code)
            .perms(arrayOf(perm))
/*            .theme(R.style.my_fancy_style)
            .rationale(R.string.camera_and_location_rationale)
            .positiveButtonText(R.string.rationale_ask_ok)
            .negativeButtonText(R.string.rationale_ask_cancel)*/
            .build()
        EasyPermissions.requestPermissions(requireActivity(), request)
    }

    private fun activatePerm(requestCode: Int) {
        when (requestCode) {
            Constants.Perms.POST_NOTIFICATION_CODE -> {
                binding.postNotifMs.isChecked = true
                binding.postNotifMs.isClickable = false
            }

            Constants.Perms.READ_SMS_CODE -> {
                binding.smsMs.isChecked = true
                binding.postNotifMs.isClickable = false
            }

            Constants.Perms.READ_NOTIFICATION_CODE -> {//binding.readNotifMs.isChecked = true
                //binding.postNotifMs.isActivated = false}
            }
        }

        renderSystemConnectionStates()
    }

    private fun deniedPerm(requestCode: Int) {
        when (requestCode) {
            Constants.Perms.POST_NOTIFICATION_CODE -> {
                startActivity(
                    Intent(
                        ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", requireActivity().packageName, null)
                    )
                )
                binding.postNotifMs.isChecked = false
            }

            Constants.Perms.READ_SMS_CODE -> {
                startActivity(
                    Intent(
                        ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", requireActivity().packageName, null)
                    )
                )
                binding.smsMs.isChecked = false
                binding.postNotifMs.isClickable = false
            }

            Constants.Perms.READ_NOTIFICATION_CODE -> {
                startActivity(Intent(ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }

        renderSystemConnectionStates()
    }

    private fun renderSystemConnectionStates() {
        renderConnectionAction(
            enabled = isNotificationListenerEnabled(),
            container = binding.readNotifActionContainer,
            card = binding.scamMonitoringCard,
            subtitle = binding.scamMonitoringSubtitle,
            enabledSubtitle = getString(R.string.connected_securely),
            disabledSubtitle = getString(R.string.tap_to_enable_monitoring),
            onEnableClick = { startActivity(Intent(ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        )
        renderConnectionAction(
            enabled = isAccessibilityServiceEnabled(),
            container = binding.accessibilityActionContainer,
            card = binding.safetyAssistantCard,
            subtitle = binding.safetyAssistantSubtitle,
            enabledSubtitle = getString(R.string.safety_assistant_active),
            disabledSubtitle = getString(R.string.enable_safety_assistant),
            onEnableClick = { startActivity(Intent(ACTION_ACCESSIBILITY_SETTINGS)) }
        )
    }

    private fun renderConnectionAction(
        enabled: Boolean,
        container: ViewGroup,
        card: View,
        subtitle: com.google.android.material.textview.MaterialTextView,
        enabledSubtitle: String,
        disabledSubtitle: String,
        onEnableClick: () -> Unit
    ) {
        container.removeAllViews()
        subtitle.text = if (enabled) enabledSubtitle else disabledSubtitle
        card.isClickable = true
        card.isFocusable = true
        card.setOnClickListener { onEnableClick() }

        when (enabled) {
            true -> {
                val activeBadge = BadgeActiveBinding.inflate(
                    LayoutInflater.from(container.context),
                    container,
                    false
                )
                container.addView(activeBadge.root)
            }

            false -> {
                val disabledBadge = BadgeDisabledBinding.inflate(
                    LayoutInflater.from(container.context),
                    container,
                    false
                )
                container.addView(disabledBadge.root)
            }
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabledListeners = Settings.Secure.getString(
            requireContext().contentResolver,
            "enabled_notification_listeners"
        ) ?: return false

        val expectedComponent = ComponentName(requireContext(), NotificationReceiverService::class.java)
        return enabledListeners.split(':').any { flattenedComponent ->
            ComponentName.unflattenFromString(flattenedComponent) == expectedComponent
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            requireContext().contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val expectedComponent = ComponentName(requireContext(), AppBlockAccessibilityService::class.java)
        return enabledServices.split(':').any { flattenedComponent ->
            ComponentName.unflattenFromString(flattenedComponent) == expectedComponent
        }
    }
}
