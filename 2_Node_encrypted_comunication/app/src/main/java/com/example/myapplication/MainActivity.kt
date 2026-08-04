package com.pair.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest


class MainActivity : AppCompatActivity() {

    @Volatile private var isAuthenticated: Boolean = false
    @Volatile private var isAuthenticating: Boolean = false
    @Volatile private var arePermissionsGranted: Boolean = false
    @Volatile private var lastBiometricAuthTimestamp: Long = 0L
    private var apkLastUpdateTime: Long = 0L
    private var apkBinaryHash: String = ""
    private val requiredPermissions: Array<String> by lazy(LazyThreadSafetyMode.NONE) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            )
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
        ::handlePermissionResult
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        Log.i(TAG, "FLAG_SECURE applied successfully.")
        super.onCreate(savedInstanceState)
        extractApkMetadataAndHash()
        if (!performSecurityAudit()) {
            showFatalErrorDialog(
                title = "Security Violation",
                message = "Unauthorized modification, sideload, dynamic hook, or virtual space detected. Report dispatched to peering device."
            )
            return
        }
        if (!isNativeLibraryLoaded) {
            showFatalErrorDialog(
                title = "Initialization Error",
                message = "The native core binary ('libcouples.so') failed to load. The application cannot start safely."
            )
            return
        }
        safeNativeCall("nativeOnCreate") { nativeOnCreate() }
        authenticateUser()
    }

    override fun onResume() {
        super.onResume()
        if (!performSecurityAudit() || !isNativeLibraryLoaded) return
        if (!isAuthenticated && !isAuthenticating) {
            authenticateUser()
        } else if (isAuthenticated) {
            if (hasAllPermissions()) {
                arePermissionsGranted = true
                safeNativeCall("nativeOnResume") { nativeOnResume() }
            } else {
                arePermissionsGranted = false
                checkAndRequestPermissions()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (!isAuthenticating) {
            isAuthenticated = false
        }
        safeNativeCall("nativeOnPause") { nativeOnPause() }
    }

    override fun onDestroy() {
        super.onDestroy()
        safeNativeCall("nativeOnDestroy") { nativeOnDestroy() }
    }


    private fun extractApkMetadataAndHash() {
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            apkLastUpdateTime = packageInfo.lastUpdateTime

            val apkFile = File(applicationInfo.sourceDir)
            if (apkFile.exists()) {
                val digest = MessageDigest.getInstance("SHA-256")
                FileInputStream(apkFile).use { fis ->
                    val buffer = ByteArray(16384) // 16KB buffer for faster I/O throughput
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        digest.update(buffer, 0, bytesRead)
                    }
                }
                apkBinaryHash = bytesToHex(digest.digest())
                Log.i(TAG, "APK Binary Hash: $apkBinaryHash | Last Updated: $apkLastUpdateTime")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compute physical APK binary hash", e)
            apkBinaryHash = "UNKNOWN_HASH_ERROR"
        }
    }

    private fun performSecurityAudit(): Boolean {
        if (isApkModified()) {
            notifyPeerDeviceOfSecurityBreach("MODDED_APK_SIGNATURE", "App signature hash mismatch detected.")
            return false
        }

        if (isSideloadedFromOutsidePlayStore()) {
            val installer = getInstallerPackageName()
            Log.w(TAG, "SECURITY NOTICE: Installed outside Play Store (Installer: $installer)")
        }

        if (isHookingFrameworkDetected()) {
            notifyPeerDeviceOfSecurityBreach("HOOKING_FRAMEWORK", "Frida, Xposed, or shared memory hook detected.")
            return false
        }

        if (isRunningInVirtualContainer()) {
            notifyPeerDeviceOfSecurityBreach("VIRTUAL_CONTAINER", "App execution inside Parallel/Dual space detected.")
            return false
        }

        if (isDebuggerAttached()) {
            notifyPeerDeviceOfSecurityBreach("DEBUGGER_ATTACHED", "Active debugger attached to process.")
            return false
        }

        return true
    }

    private fun isSideloadedFromOutsidePlayStore(): Boolean {
        return getInstallerPackageName() != "com.android.vending"
    }

    private fun getInstallerPackageName(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val info = packageManager.getInstallSourceInfo(packageName)
                info.installingPackageName ?: info.initiatingPackageName ?: "UNKNOWN_SIDELOAD"
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstallerPackageName(packageName) ?: "UNKNOWN_SIDELOAD"
            }
        } catch (_: Exception) {
            "UNKNOWN_INSTALLER_ERROR"
        }
    }

    private fun isApkModified(): Boolean {
        if (EXPECTED_SIGNATURE_SHA256.isEmpty()) return false
        return !EXPECTED_SIGNATURE_SHA256.equals(getAppSignatureSha256(), ignoreCase = true)
    }

    private fun getAppSignatureSha256(): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            }

            val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }

            if (!signatures.isNullOrEmpty()) {
                val digest = MessageDigest.getInstance("SHA-256")
                bytesToHex(digest.digest(signatures[0].toByteArray()))
            } else ""
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compute app signature hash", e)
            ""
        }
    }

    private fun isHookingFrameworkDetected(): Boolean {
        try {
            Class.forName("de.robv.android.xposed.XposedBridge")
            return true
        } catch (_: ClassNotFoundException) {}

        try {
            val mapsFile = File("/proc/self/maps")
            if (mapsFile.exists() && mapsFile.canRead()) {
                mapsFile.useLines { lines ->
                    return lines.any { line ->
                        line.contains("frida", ignoreCase = true) ||
                                line.contains("xposed", ignoreCase = true) ||
                                line.contains("libgadget.so", ignoreCase = true)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to inspect memory maps", e)
        }

        return false
    }

    private fun isRunningInVirtualContainer(): Boolean {
        val path = applicationContext.filesDir.absolutePath
        val suspiciousPaths = arrayOf("/dual/", "/virtual/", "/parallel/", "/999/", "/clone/", "com.lbe.parallel")
        return suspiciousPaths.any { path.contains(it, ignoreCase = true) }
    }

    private fun isDebuggerAttached(): Boolean {
        val isDebuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return !isDebuggable && (Debug.isDebuggerConnected() || Debug.isWaitingForDebugger())
    }
    private fun notifyPeerDeviceOfSecurityBreach(eventType: String, reasonDetails: String) {
        val payload = JSONObject().apply {
            put("event_type", eventType)
            put("reason", reasonDetails)
            put("report_timestamp", System.currentTimeMillis())
            put("biometric_auth_timestamp", lastBiometricAuthTimestamp)
            put("apk_modification_timestamp", apkLastUpdateTime)
            put("apk_binary_sha256", apkBinaryHash)
            put("installer_package", getInstallerPackageName())
            put("is_sideloaded", isSideloadedFromOutsidePlayStore())
        }.toString()

        Log.e(TAG, "DISPATCHING TAMPER REPORT TO PEER DEVICE: $payload")

        safeNativeCall("nativeReportSecurityEvent") {
            nativeReportSecurityEvent(eventType, payload)
        }
    }

    private inline fun safeNativeCall(tag: String, block: () -> Unit) {
        if (!isNativeLibraryLoaded) return
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "UnsatisfiedLinkError in $tag()", e)
            showFatalErrorDialog("Native Link Error", "Required C++ symbols are missing in native binary.")
        } catch (e: Exception) {
            Log.e(TAG, "Exception during $tag execution", e)
        }
    }


    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            hexChars[i * 2] = HEX_ARRAY[v ushr 4]
            hexChars[i * 2 + 1] = HEX_ARRAY[v and 0x0F]
        }
        return String(hexChars)
    }

    private fun authenticateUser() {
        if (isAuthenticated || isAuthenticating) return
        val biometricManager = BiometricManager.from(this)
        val combinedAuthenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL

        val deviceCredentialOnly = BiometricManager.Authenticators.DEVICE_CREDENTIAL

        when (biometricManager.canAuthenticate(combinedAuthenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                executeBiometricPrompt(combinedAuthenticators)
            }
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
                if (biometricManager.canAuthenticate(deviceCredentialOnly) == BiometricManager.BIOMETRIC_SUCCESS) {
                    Log.i(TAG, "Biometric hardware unavailable. Falling back to phone credentials.")
                    executeBiometricPrompt(deviceCredentialOnly)
                } else {
                    showSecurityConfigurationDialog(
                        "Security Setup Required",
                        "This application requires a secure Device Lock Screen (PIN, Pattern, or Password) to be set up."
                    )
                }
            }
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {

                if (biometricManager.canAuthenticate(deviceCredentialOnly) == BiometricManager.BIOMETRIC_SUCCESS) {
                    executeBiometricPrompt(deviceCredentialOnly)
                } else {
                    showSecurityConfigurationDialog(
                        "Security Setup Required",
                        "This application requires a secure Lock Screen, PIN, Pattern, Password, or Biometric to be configured in settings."
                    )
                }
            }
            else -> {
                // Final fallback check to phone password/PIN before declaring error
                if (biometricManager.canAuthenticate(deviceCredentialOnly) == BiometricManager.BIOMETRIC_SUCCESS) {
                    executeBiometricPrompt(deviceCredentialOnly)
                } else {
                    showFatalErrorDialog("Security Failure", "Device authentication check failed.")
                }
            }
        }
    }

    private fun executeBiometricPrompt(authenticators: Int) {
        isAuthenticating = true
        val executor = ContextCompat.getMainExecutor(this)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                isAuthenticating = false
                Log.e(TAG, "Authentication Error [$errorCode]: $errString")

                if (!isFinishing && !isDestroyed) {
                    when (errorCode) {
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_CANCELED -> exitApplication()
                        else -> {
                            Toast.makeText(applicationContext, "Auth Error: $errString", Toast.LENGTH_SHORT).show()
                            exitApplication()
                        }
                    }
                }
            }

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                isAuthenticating = false
                lastBiometricAuthTimestamp = System.currentTimeMillis()
                Log.i(TAG, "Authentication successful at timestamp: $lastBiometricAuthTimestamp")
                onAuthenticationSuccess()
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                Log.w(TAG, "Authentication attempt failed.")
            }
        }

        try {
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Couples Hub Access")
                .setSubtitle("Authenticate using Biometrics or Phone Device Credentials")
                .setAllowedAuthenticators(authenticators)
                .build()

            BiometricPrompt(this, executor, callback).authenticate(promptInfo)
        } catch (e: Exception) {
            isAuthenticating = false
            Log.e(TAG, "Failed to instantiate BiometricPrompt", e)
            showFatalErrorDialog("Authentication Exception", "Could not trigger authentication prompt.")
        }
    }

    private fun onAuthenticationSuccess() {
        isAuthenticated = true
        checkAndRequestPermissions()
    }

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions.all { perm ->
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkAndRequestPermissions() {
        val missingPermissions = requiredPermissions.filter { perm ->
            ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            arePermissionsGranted = true
            onAllPermissionsGranted()
        } else {
            arePermissionsGranted = false
            Log.i(TAG, "Requesting runtime permissions.")
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun handlePermissionResult(results: Map<String, Boolean>) {
        val allGranted = results.values.all { it }
        val permanentlyDenied = results.any { (perm, isGranted) ->
            !isGranted && !shouldShowRequestPermissionRationale(perm)
        }

        if (allGranted) {
            arePermissionsGranted = true
            Log.i(TAG, "All runtime permissions granted.")
            onAllPermissionsGranted()
        } else {
            arePermissionsGranted = false
            if (isFinishing || isDestroyed) return

            val builder = AlertDialog.Builder(this)
                .setTitle("Permissions Required")
                .setCancelable(false)
                .setNegativeButton("Exit") { _, _ -> exitApplication() }

            if (permanentlyDenied) {
                builder.setMessage("Required permissions were permanently denied. Please enable Camera and Microphone access in application settings.")
                    .setPositiveButton("Settings") { _, _ ->
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", packageName, null)
                        }
                        startActivity(intent)
                        exitApplication()
                    }
            } else {
                builder.setMessage("Camera and Microphone permissions are required for encrypted voice/video sessions.")
                    .setPositiveButton("Grant") { _, _ -> checkAndRequestPermissions() }
            }

            builder.show()
        }
    }

    private fun onAllPermissionsGranted() {
        safeNativeCall("nativeOnPermissionsGranted") { nativeOnPermissionsGranted() }
    }

    private fun exitApplication() {
        finishAffinity()
        Process.killProcess(Process.myPid())
    }

    private fun showFatalErrorDialog(title: String, message: String) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Close App") { _, _ -> exitApplication() }
            .show()
    }

    private fun showSecurityConfigurationDialog(title: String, message: String) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Open Settings") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
                exitApplication()
            }
            .setNegativeButton("Exit") { _, _ -> exitApplication() }
            .show()
    }

    // JNI External Functions
    private external fun nativeOnCreate()
    private external fun nativeOnResume()
    private external fun nativeOnPause()
    private external fun nativeOnDestroy()
    private external fun nativeOnPermissionsGranted()
    private external fun nativeReportSecurityEvent(eventType: String, reportJsonPayload: String)

    companion object {
        private const val TAG = "Couples_MainActivity"
        private const val NATIVE_LIB_NAME = "couples"
        private const val EXPECTED_SIGNATURE_SHA256 = ""

        private val HEX_ARRAY = "0123456789ABCDEF".toCharArray()

        @JvmStatic
        var isNativeLibraryLoaded: Boolean = false
            private set

        init {
            try {
                System.loadLibrary(NATIVE_LIB_NAME)
                isNativeLibraryLoaded = true
                Log.i(TAG, "Native library 'lib${NATIVE_LIB_NAME}.so' loaded successfully.")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "CRITICAL: Unable to load native library 'lib${NATIVE_LIB_NAME}.so'", e)
                isNativeLibraryLoaded = false
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error loading native library 'lib${NATIVE_LIB_NAME}.so'", e)
                isNativeLibraryLoaded = false
            }
        }
    }
}