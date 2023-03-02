/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.credentialmanager

import android.app.slice.Slice
import android.app.slice.SliceItem
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.credentials.Credential.TYPE_PASSWORD_CREDENTIAL
import android.credentials.ui.AuthenticationEntry
import android.credentials.ui.CreateCredentialProviderData
import android.credentials.ui.DisabledProviderData
import android.credentials.ui.Entry
import android.credentials.ui.GetCredentialProviderData
import android.credentials.ui.RequestInfo
import android.graphics.drawable.Drawable
import android.service.credentials.CredentialEntry
import android.text.TextUtils
import android.util.Log
import com.android.credentialmanager.common.Constants
import com.android.credentialmanager.common.CredentialType
import com.android.credentialmanager.createflow.ActiveEntry
import com.android.credentialmanager.createflow.CreateCredentialUiState
import com.android.credentialmanager.createflow.CreateOptionInfo
import com.android.credentialmanager.createflow.CreateScreenState
import com.android.credentialmanager.createflow.DisabledProviderInfo
import com.android.credentialmanager.createflow.EnabledProviderInfo
import com.android.credentialmanager.createflow.RemoteInfo
import com.android.credentialmanager.createflow.RequestDisplayInfo
import com.android.credentialmanager.getflow.ActionEntryInfo
import com.android.credentialmanager.getflow.AuthenticationEntryInfo
import com.android.credentialmanager.getflow.CredentialEntryInfo
import com.android.credentialmanager.getflow.ProviderInfo
import com.android.credentialmanager.getflow.RemoteEntryInfo
import androidx.credentials.CreateCredentialRequest
import androidx.credentials.CreateCustomCredentialRequest
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialOption
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential.Companion.TYPE_PUBLIC_KEY_CREDENTIAL
import androidx.credentials.provider.Action
import androidx.credentials.provider.AuthenticationAction
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CustomCredentialEntry
import androidx.credentials.provider.PasswordCredentialEntry
import androidx.credentials.provider.PublicKeyCredentialEntry
import androidx.credentials.provider.RemoteCreateEntry
import androidx.credentials.provider.RemoteCredentialEntry
import org.json.JSONObject

// TODO: remove all !! checks
private fun getAppLabel(
    pm: PackageManager,
    appPackageName: String
): String? {
    return try {
        val pkgInfo = pm.getPackageInfo(appPackageName, PackageManager.PackageInfoFlags.of(0))
        pkgInfo.applicationInfo.loadSafeLabel(
            pm, 0f,
            TextUtils.SAFE_STRING_FLAG_FIRST_LINE or TextUtils.SAFE_STRING_FLAG_TRIM
        ).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        Log.e(Constants.LOG_TAG, "Caller app not found", e)
        null
    }
}

private fun getServiceLabelAndIcon(
    pm: PackageManager,
    providerFlattenedComponentName: String
): Pair<String, Drawable>? {
    var providerLabel: String? = null
    var providerIcon: Drawable? = null
    val component = ComponentName.unflattenFromString(providerFlattenedComponentName)
    if (component == null) {
        // Test data has only package name not component name.
        // TODO: remove once test data is removed
        try {
            val pkgInfo = pm.getPackageInfo(
                providerFlattenedComponentName,
                PackageManager.PackageInfoFlags.of(0)
            )
            providerLabel =
                pkgInfo.applicationInfo.loadSafeLabel(
                    pm, 0f,
                    TextUtils.SAFE_STRING_FLAG_FIRST_LINE or TextUtils.SAFE_STRING_FLAG_TRIM
                ).toString()
            providerIcon = pkgInfo.applicationInfo.loadIcon(pm)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(Constants.LOG_TAG, "Provider info not found", e)
        }
    } else {
        try {
            val si = pm.getServiceInfo(component, PackageManager.ComponentInfoFlags.of(0))
            providerLabel = si.loadSafeLabel(
                pm, 0f,
                TextUtils.SAFE_STRING_FLAG_FIRST_LINE or TextUtils.SAFE_STRING_FLAG_TRIM
            ).toString()
            providerIcon = si.loadIcon(pm)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(Constants.LOG_TAG, "Provider info not found", e)
        }
    }
    return if (providerLabel == null || providerIcon == null) {
        Log.d(
            Constants.LOG_TAG,
            "Failed to load provider label/icon for provider $providerFlattenedComponentName"
        )
        null
    } else {
        Pair(providerLabel, providerIcon)
    }
}

/** Utility functions for converting CredentialManager data structures to or from UI formats. */
class GetFlowUtils {
    companion object {
        // Returns the list (potentially empty) of enabled provider.
        fun toProviderList(
            providerDataList: List<GetCredentialProviderData>,
            context: Context,
        ): List<ProviderInfo> {
            val providerList: MutableList<ProviderInfo> = mutableListOf()
            providerDataList.forEach {
                val providerLabelAndIcon = getServiceLabelAndIcon(
                    context.packageManager,
                    it.providerFlattenedComponentName
                ) ?: return@forEach
                val (providerLabel, providerIcon) = providerLabelAndIcon
                providerList.add(
                    ProviderInfo(
                        id = it.providerFlattenedComponentName,
                        icon = providerIcon,
                        displayName = providerLabel,
                        credentialEntryList = getCredentialOptionInfoList(
                            it.providerFlattenedComponentName, it.credentialEntries, context
                        ),
                        authenticationEntryList = getAuthenticationEntryList(
                            it.providerFlattenedComponentName,
                            providerLabel,
                            providerIcon,
                            it.authenticationEntries),
                        remoteEntry = getRemoteEntry(
                            it.providerFlattenedComponentName,
                            it.remoteEntry
                        ),
                        actionEntryList = getActionEntryList(
                            it.providerFlattenedComponentName, it.actionChips, providerIcon
                        ),
                    )
                )
            }
            return providerList
        }

        fun toRequestDisplayInfo(
            requestInfo: RequestInfo,
            context: Context,
            originName: String?,
        ): com.android.credentialmanager.getflow.RequestDisplayInfo? {
            val getCredentialRequest = requestInfo.getCredentialRequest ?: return null
            val preferImmediatelyAvailableCredentials = getCredentialRequest.credentialOptions.any {
                val credentialOptionJetpack = CredentialOption.createFrom(
                    it.type,
                    it.credentialRetrievalData,
                    it.credentialRetrievalData,
                    it.isSystemProviderRequired
                )
                if (credentialOptionJetpack is GetPublicKeyCredentialOption) {
                    credentialOptionJetpack.preferImmediatelyAvailableCredentials
                } else {
                    false
                }
            }
            return com.android.credentialmanager.getflow.RequestDisplayInfo(
                appName = originName
                    ?: getAppLabel(context.packageManager, requestInfo.appPackageName)
                    ?: return null,
                preferImmediatelyAvailableCredentials = preferImmediatelyAvailableCredentials
            )
        }


        /**
         * Note: caller required handle empty list due to parsing error.
         */
        private fun getCredentialOptionInfoList(
            providerId: String,
            credentialEntries: List<Entry>,
            context: Context,
        ): List<CredentialEntryInfo> {
            val result: MutableList<CredentialEntryInfo> = mutableListOf()
            credentialEntries.forEach {
                val credentialEntry = parseCredentialEntryFromSlice(it.slice)
                when (credentialEntry) {
                    is PasswordCredentialEntry -> {
                        result.add(CredentialEntryInfo(
                            providerId = providerId,
                            entryKey = it.key,
                            entrySubkey = it.subkey,
                            pendingIntent = credentialEntry.pendingIntent,
                            fillInIntent = it.frameworkExtrasIntent,
                            credentialType = CredentialType.PASSWORD,
                            credentialTypeDisplayName = credentialEntry.typeDisplayName.toString(),
                            userName = credentialEntry.username.toString(),
                            displayName = credentialEntry.displayName?.toString(),
                            icon = credentialEntry.icon?.loadDrawable(context),
                            lastUsedTimeMillis = credentialEntry.lastUsedTime,
                        ))
                    }
                    is PublicKeyCredentialEntry -> {
                        result.add(CredentialEntryInfo(
                            providerId = providerId,
                            entryKey = it.key,
                            entrySubkey = it.subkey,
                            pendingIntent = credentialEntry.pendingIntent,
                            fillInIntent = it.frameworkExtrasIntent,
                            credentialType = CredentialType.PASSKEY,
                            credentialTypeDisplayName = credentialEntry.typeDisplayName.toString(),
                            userName = credentialEntry.username.toString(),
                            displayName = credentialEntry.displayName?.toString(),
                            icon = credentialEntry.icon?.loadDrawable(context),
                            lastUsedTimeMillis = credentialEntry.lastUsedTime,
                        ))
                    }
                    is CustomCredentialEntry -> {
                        result.add(CredentialEntryInfo(
                            providerId = providerId,
                            entryKey = it.key,
                            entrySubkey = it.subkey,
                            pendingIntent = credentialEntry.pendingIntent,
                            fillInIntent = it.frameworkExtrasIntent,
                            credentialType = CredentialType.UNKNOWN,
                            credentialTypeDisplayName = credentialEntry.typeDisplayName.toString(),
                            userName = credentialEntry.title.toString(),
                            displayName = credentialEntry.subtitle?.toString(),
                            icon = credentialEntry.icon?.loadDrawable(context),
                            lastUsedTimeMillis = credentialEntry.lastUsedTime,
                        ))
                    }
                    else -> Log.d(
                        Constants.LOG_TAG,
                        "Encountered unrecognized credential entry ${it.slice.spec?.type}"
                    )
                }
            }
            // TODO: handle empty list due to parsing error.
            return result
        }

        private fun parseCredentialEntryFromSlice(slice: Slice): CredentialEntry? {
            try {
                when (slice.spec?.type) {
                    TYPE_PASSWORD_CREDENTIAL -> return PasswordCredentialEntry.fromSlice(slice)!!
                    TYPE_PUBLIC_KEY_CREDENTIAL -> return PublicKeyCredentialEntry.fromSlice(slice)!!
                    else -> return CustomCredentialEntry.fromSlice(slice)!!
                }
            } catch (e: Exception) {
                // Try CustomCredentialEntry.fromSlice one last time in case the cause was a failed
                // password / passkey parsing attempt.
                return CustomCredentialEntry.fromSlice(slice)
            }
        }

        /**
         * Note: caller required handle empty list due to parsing error.
         */
        private fun getAuthenticationEntryList(
            providerId: String,
            providerDisplayName: String,
            providerIcon: Drawable,
            authEntryList: List<AuthenticationEntry>,
        ): List<AuthenticationEntryInfo> {
            val result: MutableList<AuthenticationEntryInfo> = mutableListOf()
            authEntryList.forEach { entry ->
                val structuredAuthEntry =
                    AuthenticationAction.fromSlice(entry.slice) ?: return@forEach

                // TODO: replace with official jetpack code.
                val titleItem: SliceItem? = entry.slice.items.firstOrNull {
                    it.hasHint(
                        "androidx.credentials.provider.authenticationAction.SLICE_HINT_TITLE")
                }
                val title: String = titleItem?.text?.toString() ?: providerDisplayName

                result.add(AuthenticationEntryInfo(
                    providerId = providerId,
                    entryKey = entry.key,
                    entrySubkey = entry.subkey,
                    pendingIntent = structuredAuthEntry.pendingIntent,
                    fillInIntent = entry.frameworkExtrasIntent,
                    title = title,
                    providerDisplayName = providerDisplayName,
                    icon = providerIcon,
                    isUnlockedAndEmpty = entry.status != AuthenticationEntry.STATUS_LOCKED,
                    isLastUnlocked =
                    entry.status == AuthenticationEntry.STATUS_UNLOCKED_BUT_EMPTY_MOST_RECENT
                ))
            }
            return result
        }

        private fun getRemoteEntry(providerId: String, remoteEntry: Entry?): RemoteEntryInfo? {
            if (remoteEntry == null) {
                return null
            }
            val structuredRemoteEntry = RemoteCredentialEntry.fromSlice(remoteEntry.slice)
                ?: return null
            return RemoteEntryInfo(
                providerId = providerId,
                entryKey = remoteEntry.key,
                entrySubkey = remoteEntry.subkey,
                pendingIntent = structuredRemoteEntry.pendingIntent,
                fillInIntent = remoteEntry.frameworkExtrasIntent,
            )
        }

        /**
         * Note: caller required handle empty list due to parsing error.
         */
        private fun getActionEntryList(
            providerId: String,
            actionEntries: List<Entry>,
            providerIcon: Drawable,
        ): List<ActionEntryInfo> {
            val result: MutableList<ActionEntryInfo> = mutableListOf()
            actionEntries.forEach {
                val actionEntryUi = Action.fromSlice(it.slice) ?: return@forEach
                result.add(ActionEntryInfo(
                    providerId = providerId,
                    entryKey = it.key,
                    entrySubkey = it.subkey,
                    pendingIntent = actionEntryUi.pendingIntent,
                    fillInIntent = it.frameworkExtrasIntent,
                    title = actionEntryUi.title.toString(),
                    icon = providerIcon,
                    subTitle = actionEntryUi.subtitle?.toString(),
                ))
            }
            // TODO: handle empty list
            return result
        }
    }
}

class CreateFlowUtils {
    companion object {
        /**
         * Note: caller required handle empty list due to parsing error.
         */
        fun toEnabledProviderList(
            providerDataList: List<CreateCredentialProviderData>,
            context: Context,
        ): List<EnabledProviderInfo> {
            val providerList: MutableList<EnabledProviderInfo> = mutableListOf()
            providerDataList.forEach {
                val providerLabelAndIcon = getServiceLabelAndIcon(
                    context.packageManager,
                    it.providerFlattenedComponentName
                ) ?: return@forEach
                val (providerLabel, providerIcon) = providerLabelAndIcon
                providerList.add(EnabledProviderInfo(
                    id = it.providerFlattenedComponentName,
                    displayName = providerLabel,
                    icon = providerIcon,
                    createOptions = toCreationOptionInfoList(
                        it.providerFlattenedComponentName, it.saveEntries, context
                    ),
                    remoteEntry = toRemoteInfo(it.providerFlattenedComponentName, it.remoteEntry),
                ))
            }
            return providerList
        }

        /**
         * Note: caller required handle empty list due to parsing error.
         */
        fun toDisabledProviderList(
            providerDataList: List<DisabledProviderData>?,
            context: Context,
        ): List<DisabledProviderInfo> {
            val providerList: MutableList<DisabledProviderInfo> = mutableListOf()
            providerDataList?.forEach {
                val providerLabelAndIcon = getServiceLabelAndIcon(
                    context.packageManager,
                    it.providerFlattenedComponentName
                ) ?: return@forEach
                val (providerLabel, providerIcon) = providerLabelAndIcon
                providerList.add(DisabledProviderInfo(
                    icon = providerIcon,
                    id = it.providerFlattenedComponentName,
                    displayName = providerLabel,
                ))
            }
            return providerList
        }

        fun toRequestDisplayInfo(
            requestInfo: RequestInfo,
            context: Context,
            originName: String?,
        ): RequestDisplayInfo? {
            val appLabel = originName
                ?: getAppLabel(context.packageManager, requestInfo.appPackageName)
                ?: return null
            val createCredentialRequest = requestInfo.createCredentialRequest ?: return null
            val createCredentialRequestJetpack = CreateCredentialRequest.createFrom(
                createCredentialRequest.type,
                createCredentialRequest.credentialData,
                createCredentialRequest.candidateQueryData,
                createCredentialRequest.isSystemProviderRequired
            )
            return when (createCredentialRequestJetpack) {
                is CreatePasswordRequest -> RequestDisplayInfo(
                    createCredentialRequestJetpack.id,
                    createCredentialRequestJetpack.password,
                    CredentialType.PASSWORD,
                    appLabel,
                    context.getDrawable(R.drawable.ic_password) ?: return null,
                    preferImmediatelyAvailableCredentials = false,
                )
                is CreatePublicKeyCredentialRequest -> {
                    newRequestDisplayInfoFromPasskeyJson(
                        requestJson = createCredentialRequestJetpack.requestJson,
                        appLabel = appLabel,
                        context = context,
                        preferImmediatelyAvailableCredentials =
                        createCredentialRequestJetpack.preferImmediatelyAvailableCredentials,
                    )
                }
                is CreateCustomCredentialRequest -> {
                    // TODO: directly use the display info once made public
                    val displayInfo = CreateCredentialRequest.DisplayInfo
                        .parseFromCredentialDataBundle(createCredentialRequest.credentialData)
                        ?: return null
                    RequestDisplayInfo(
                        title = displayInfo.userId.toString(),
                        subtitle = displayInfo.userDisplayName?.toString(),
                        type = CredentialType.UNKNOWN,
                        appName = appLabel,
                        typeIcon = displayInfo.credentialTypeIcon?.loadDrawable(context)
                            ?: context.getDrawable(R.drawable.ic_other_sign_in) ?: return null,
                        preferImmediatelyAvailableCredentials = false,
                    )
                }
                else -> null
            }
        }

        fun toCreateCredentialUiState(
            enabledProviders: List<EnabledProviderInfo>,
            disabledProviders: List<DisabledProviderInfo>?,
            defaultProviderId: String?,
            requestDisplayInfo: RequestDisplayInfo,
            isOnPasskeyIntroStateAlready: Boolean,
            isPasskeyFirstUse: Boolean,
        ): CreateCredentialUiState? {
            var lastSeenProviderWithNonEmptyCreateOptions: EnabledProviderInfo? = null
            var remoteEntry: RemoteInfo? = null
            var defaultProvider: EnabledProviderInfo? = null
            var createOptionsPairs:
                MutableList<Pair<CreateOptionInfo, EnabledProviderInfo>> = mutableListOf()
            enabledProviders.forEach { enabledProvider ->
                if (defaultProviderId != null) {
                    if (enabledProvider.id == defaultProviderId) {
                        defaultProvider = enabledProvider
                    }
                }
                if (enabledProvider.createOptions.isNotEmpty()) {
                    lastSeenProviderWithNonEmptyCreateOptions = enabledProvider
                    enabledProvider.createOptions.forEach {
                        createOptionsPairs.add(Pair(it, enabledProvider))
                    }
                }
                val currRemoteEntry = enabledProvider.remoteEntry
                if (currRemoteEntry != null) {
                    if (remoteEntry != null) {
                        // There can only be at most one remote entry
                        Log.d(Constants.LOG_TAG, "Found more than one remote entry.")
                        return null
                    }
                    remoteEntry = currRemoteEntry
                }
            }
            val initialScreenState = toCreateScreenState(
                /*createOptionSize=*/createOptionsPairs.size,
                /*isOnPasskeyIntroStateAlready=*/isOnPasskeyIntroStateAlready,
                /*requestDisplayInfo=*/requestDisplayInfo,
                /*defaultProvider=*/defaultProvider, /*remoteEntry=*/remoteEntry,
                /*isPasskeyFirstUse=*/isPasskeyFirstUse
            ) ?: return null
            return CreateCredentialUiState(
                enabledProviders = enabledProviders,
                disabledProviders = disabledProviders,
                currentScreenState = initialScreenState,
                requestDisplayInfo = requestDisplayInfo,
                sortedCreateOptionsPairs = createOptionsPairs.sortedWith(
                    compareByDescending { it.first.lastUsedTime }
                ),
                hasDefaultProvider = defaultProvider != null,
                activeEntry = toActiveEntry(
                    /*defaultProvider=*/defaultProvider,
                    /*createOptionSize=*/createOptionsPairs.size,
                    /*lastSeenProviderWithNonEmptyCreateOptions=*/
                    lastSeenProviderWithNonEmptyCreateOptions,
                    /*remoteEntry=*/remoteEntry
                ),
                remoteEntry = remoteEntry,
            )
        }

        private fun toCreateScreenState(
            createOptionSize: Int,
            isOnPasskeyIntroStateAlready: Boolean,
            requestDisplayInfo: RequestDisplayInfo,
            defaultProvider: EnabledProviderInfo?,
            remoteEntry: RemoteInfo?,
            isPasskeyFirstUse: Boolean,
        ): CreateScreenState? {
            return if (isPasskeyFirstUse && requestDisplayInfo.type ==
                CredentialType.PASSKEY && !isOnPasskeyIntroStateAlready) {
                CreateScreenState.PASSKEY_INTRO
            } else if ((defaultProvider == null || defaultProvider.createOptions.isEmpty()) &&
                createOptionSize > 1) {
                CreateScreenState.PROVIDER_SELECTION
            } else if (((defaultProvider == null || defaultProvider.createOptions.isEmpty()) &&
                    createOptionSize == 1) || (defaultProvider != null &&
                    defaultProvider.createOptions.isNotEmpty())) {
                CreateScreenState.CREATION_OPTION_SELECTION
            } else if (createOptionSize == 0 && remoteEntry != null) {
                CreateScreenState.EXTERNAL_ONLY_SELECTION
            } else {
                Log.d(
                    Constants.LOG_TAG,
                    "Unexpected failure: the screen state failed to instantiate" +
                        " because the provider list is empty."
                )
                null
            }
        }

        private fun toActiveEntry(
            defaultProvider: EnabledProviderInfo?,
            createOptionSize: Int,
            lastSeenProviderWithNonEmptyCreateOptions: EnabledProviderInfo?,
            remoteEntry: RemoteInfo?,
        ): ActiveEntry? {
            return if (
                defaultProvider != null && defaultProvider.createOptions.isEmpty() &&
                remoteEntry != null
            ) {
                ActiveEntry(defaultProvider, remoteEntry)
            } else if (
                defaultProvider != null && defaultProvider.createOptions.isNotEmpty()
            ) {
                ActiveEntry(defaultProvider, defaultProvider.createOptions.first())
            } else if (createOptionSize == 1) {
                ActiveEntry(
                    lastSeenProviderWithNonEmptyCreateOptions!!,
                    lastSeenProviderWithNonEmptyCreateOptions.createOptions.first()
                )
            } else null
        }

        /**
         * Note: caller required handle empty list due to parsing error.
         */
        private fun toCreationOptionInfoList(
            providerId: String,
            creationEntries: List<Entry>,
            context: Context,
        ): List<CreateOptionInfo> {
            val result: MutableList<CreateOptionInfo> = mutableListOf()
            creationEntries.forEach {
                val createEntry = CreateEntry.fromSlice(it.slice) ?: return@forEach
                result.add(CreateOptionInfo(
                    providerId = providerId,
                    entryKey = it.key,
                    entrySubkey = it.subkey,
                    pendingIntent = createEntry.pendingIntent,
                    fillInIntent = it.frameworkExtrasIntent,
                    userProviderDisplayName = createEntry.accountName.toString(),
                    profileIcon = createEntry.icon?.loadDrawable(context),
                    passwordCount = createEntry.getPasswordCredentialCount(),
                    passkeyCount = createEntry.getPublicKeyCredentialCount(),
                    totalCredentialCount = createEntry.getTotalCredentialCount(),
                    lastUsedTime = createEntry.lastUsedTime,
                    footerDescription = createEntry.description?.toString()
                ))
            }
            return result
        }

        private fun toRemoteInfo(
            providerId: String,
            remoteEntry: Entry?,
        ): RemoteInfo? {
            return if (remoteEntry != null) {
                val structuredRemoteEntry = RemoteCreateEntry.fromSlice(remoteEntry.slice)
                    ?: return null
                RemoteInfo(
                    providerId = providerId,
                    entryKey = remoteEntry.key,
                    entrySubkey = remoteEntry.subkey,
                    pendingIntent = structuredRemoteEntry.pendingIntent,
                    fillInIntent = remoteEntry.frameworkExtrasIntent,
                )
            } else null
        }

        private fun newRequestDisplayInfoFromPasskeyJson(
            requestJson: String,
            appLabel: String,
            context: Context,
            preferImmediatelyAvailableCredentials: Boolean,
        ): RequestDisplayInfo? {
            val json = JSONObject(requestJson)
            var name = ""
            var displayName = ""
            if (json.has("user")) {
                val user: JSONObject = json.getJSONObject("user")
                name = user.getString("name")
                displayName = user.getString("displayName")
            }
            return RequestDisplayInfo(
                name,
                displayName,
                CredentialType.PASSKEY,
                appLabel,
                context.getDrawable(R.drawable.ic_passkey) ?: return null,
                preferImmediatelyAvailableCredentials,
            )
        }
    }
}
