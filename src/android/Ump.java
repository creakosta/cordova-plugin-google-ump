package com.jptrainor.plugins.ump;

import android.app.Activity;

import com.google.android.ump.ConsentDebugSettings;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.UserMessagingPlatform;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Cordova wrapper for Google UMP SDK 4.x.
 * JS API is unchanged: verifyConsent(isAgeConsent, isDebug, testDeviceHashId), forceForm(), reset().
 * Success payload is always { consent, hasShownDialog, formAvailable }.
 */
public class Ump extends CordovaPlugin {

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        try {
            switch (action) {
                case "verifyConsent": {
                    boolean isAgeConsent = args.optBoolean(0, false);
                    boolean isDebug = args.optBoolean(1, false);
                    String testDeviceHashId = args.isNull(2) ? null : args.optString(2, null);
                    if (testDeviceHashId != null && (testDeviceHashId.isEmpty() || testDeviceHashId.equals("null"))) {
                        testDeviceHashId = null;
                    }
                    verifyConsent(isAgeConsent, isDebug, testDeviceHashId, callbackContext);
                    return true;
                }
                case "forceForm":
                    forceForm(callbackContext);
                    return true;
                case "reset":
                    reset(callbackContext);
                    return true;
                default:
                    return false;
            }
        } catch (Exception e) {
            callbackContext.error(e.getMessage());
            return true;
        }
    }

    private void verifyConsent(boolean isAgeConsent, boolean isDebug, String testDeviceHashId, CallbackContext callbackContext) {
        final Activity activity = cordova.getActivity();

        ConsentRequestParameters.Builder paramsBuilder = new ConsentRequestParameters.Builder()
                .setTagForUnderAgeOfConsent(isAgeConsent);

        if (isDebug) {
            ConsentDebugSettings.Builder debugBuilder = new ConsentDebugSettings.Builder(activity)
                    .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA);
            if (testDeviceHashId != null) {
                debugBuilder.addTestDeviceHashedId(testDeviceHashId);
            }
            paramsBuilder.setConsentDebugSettings(debugBuilder.build());
        }

        final ConsentRequestParameters params = paramsBuilder.build();
        final ConsentInformation consentInformation = UserMessagingPlatform.getConsentInformation(activity);

        activity.runOnUiThread(() -> consentInformation.requestConsentInfoUpdate(
                activity,
                params,
                () -> {
                    if (consentInformation.isConsentFormAvailable()) {
                        loadForm(consentInformation, false, callbackContext);
                    } else {
                        success(true, false, false, callbackContext);
                    }
                },
                formError -> callbackContext.error(formError.getErrorCode())
        ));
    }

    private void forceForm(CallbackContext callbackContext) {
        loadForm(UserMessagingPlatform.getConsentInformation(cordova.getActivity()), true, callbackContext);
    }

    private void reset(CallbackContext callbackContext) {
        UserMessagingPlatform.getConsentInformation(cordova.getActivity()).reset();
        callbackContext.success();
    }

    private void loadForm(ConsentInformation consentInformation, boolean forceForm, CallbackContext callbackContext) {
        if (forceForm || consentInformation.getConsentStatus() == ConsentInformation.ConsentStatus.REQUIRED) {
            presentForm(consentInformation, callbackContext);
        } else {
            success(true, false, true, callbackContext);
        }
    }

    private void presentForm(ConsentInformation consentInformation, CallbackContext callbackContext) {
        final Activity activity = cordova.getActivity();
        activity.runOnUiThread(() -> UserMessagingPlatform.loadConsentForm(
                activity,
                consentForm -> consentForm.show(activity, formError -> {
                    boolean obtained = consentInformation.getConsentStatus() == ConsentInformation.ConsentStatus.OBTAINED;
                    success(obtained, true, true, callbackContext);
                }),
                formError -> callbackContext.error(formError.getMessage())
        ));
    }

    private void success(boolean consent, boolean hasShownDialog, boolean formAvailable, CallbackContext callbackContext) {
        JSONObject json = new JSONObject();
        try {
            json.put("consent", consent);
            json.put("hasShownDialog", hasShownDialog);
            json.put("formAvailable", formAvailable);
            callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK, json));
        } catch (JSONException e) {
            callbackContext.error(e.getMessage());
        }
    }
}
