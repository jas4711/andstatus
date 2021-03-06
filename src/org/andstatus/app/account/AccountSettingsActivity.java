/* 
 * Copyright (C) 2010-2012 yvolk (Yuri Volkov), http://yurivolkov.com
 * Copyright (C) 2010 Brion N. Emde, "BLOA" example, http://github.com/brione/Brion-Learns-OAuth 
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

package org.andstatus.app.account;

import android.accounts.AccountManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.preference.Preference.OnPreferenceChangeListener;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.widget.Toast;

import oauth.signpost.OAuth;
import oauth.signpost.OAuthConsumer;
import oauth.signpost.exception.OAuthCommunicationException;
import oauth.signpost.exception.OAuthExpectationFailedException;
import oauth.signpost.exception.OAuthMessageSignerException;
import oauth.signpost.exception.OAuthNotAuthorizedException;

import org.andstatus.app.ActivityRequestCode;
import org.andstatus.app.HelpActivity;
import org.andstatus.app.IntentExtra;
import org.andstatus.app.MyContextHolder;
import org.andstatus.app.MyPreferenceActivity;
import org.andstatus.app.MyService;
import org.andstatus.app.MyServiceManager;
import org.andstatus.app.R;
import org.andstatus.app.account.MyAccount.CredentialsVerificationStatus;
import org.andstatus.app.data.MyPreferences;
import org.andstatus.app.net.Connection;
import org.andstatus.app.net.ConnectionException;
import org.andstatus.app.origin.Origin;
import org.andstatus.app.util.MyLog;
import org.andstatus.app.util.SharedPreferencesUtil;
import org.andstatus.app.util.TriState;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Add new or edit existing account
 * 
 * @author yvolk@yurivolkov.com
 */
public class AccountSettingsActivity extends PreferenceActivity implements
        OnSharedPreferenceChangeListener, OnPreferenceChangeListener {
    private static final String TAG = AccountSettingsActivity.class.getSimpleName();

    /**
     * This is single list of (in fact, enums...) of Message/Dialog IDs
     */
    public static final int MSG_NONE = 1;

    public static final int MSG_ACCOUNT_VALID = 2;

    public static final int MSG_ACCOUNT_INVALID = 3;

    public static final int MSG_CONNECTION_EXCEPTION = 5;

    public static final int MSG_CREDENTIALS_OF_OTHER_USER = 7;

    // End Of the list ----------------------------------------

    /**
     * We are going to finish/restart this Activity
     */
    private boolean mIsFinishing = false;
    private boolean startPreferencesActivity = false;
    
    private StateOfAccountChangeProcess state = null;

    private CheckBoxPreference oAuthCheckBox;

    private EditTextPreference usernameText;

    private EditTextPreference passwordText;
    
    private ListPreference originNameList;

    private Preference addAccountOrVerifyCredentials;

    private EditTextPreference hostText;

    private CheckBoxPreference sslCheckBox;
    
    private boolean onSharedPreferenceChanged_busy = false;
    
    /**
     * Use this flag to return from this activity to “Accounts & Sync settings” screen
     */
    private boolean overrideBackButton = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        MyContextHolder.initialize(this, this);
        MyContextHolder.upgradeIfNeeded(this);
        if (!MyContextHolder.get().isReady()) {
            HelpActivity.startFromActivity(this, true, false);
        }
        
        addPreferencesFromResource(R.xml.account_settings);
        
        originNameList = (ListPreference) findPreference(MyAccount.Builder.KEY_ORIGIN_NAME);
        addAccountOrVerifyCredentials = findPreference(MyPreferences.KEY_VERIFY_CREDENTIALS);
        oAuthCheckBox = (CheckBoxPreference) findPreference(MyAccount.Builder.KEY_OAUTH);
        usernameText = (EditTextPreference) findPreference(MyAccount.Builder.KEY_USERNAME_NEW);
        passwordText = (EditTextPreference) findPreference(Connection.KEY_PASSWORD);
        hostText = (EditTextPreference) findPreference(Origin.KEY_HOST_OF_ORIGIN);
        sslCheckBox = (CheckBoxPreference) findPreference(Origin.KEY_SSL);

        restoreState(getIntent(), "onCreate");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        restoreState(intent, "onNewIntent");
    }

    /**
     * Restore previous state and set the Activity mode depending on input (Intent).
     * We should decide if we should use the stored state or a newly created one
     * @param intent
     * @param calledFrom - for logging only
     * @param savedInstanceState
     */
    private void restoreState(Intent intent, String calledFrom) {
        String message = "";
        if (state == null)  {
            state =  StateOfAccountChangeProcess.fromStoredState();
            message += (state.restored ? "Old state restored; " : "No previous state; ");
        } else {
            message += "State existed and " + (state.restored ? "was restored earlier; " : "was not restored earlier; ");
        }
        StateOfAccountChangeProcess newState = StateOfAccountChangeProcess.fromIntent(intent);
        if (state.actionCompleted || newState.useThisState) {
            message += "New state; ";
            state = newState;
            if (state.accountShouldBeSelected) {
                Intent i = new Intent(this, AccountSelector.class);
                startActivityForResult(i, ActivityRequestCode.SELECT_ACCOUNT.id);
                message += "Select account; ";
            }
            message += "action=" + state.getAccountAction() + "; ";

            showUserPreferences();
        }
        if (state.authenticatiorResponse != null) {
            message += "authenticatiorResponse; ";
        }
        MyLog.v(this, "setState from " + calledFrom +"; " + message + "intent=" + intent.toUri(0));
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (ActivityRequestCode.fromId(requestCode)) {
            case SELECT_ACCOUNT:
                if (resultCode == RESULT_OK) {
                    state.builder = MyAccount.Builder.newOrExistingFromAccountName(data.getStringExtra(IntentExtra.EXTRA_ACCOUNT_NAME.key), TriState.UNKNOWN);
                    if (!state.builder.isPersistent()) {
                        mIsFinishing = true;
                    }
                } else {
                    mIsFinishing = true;
                }
                if (!mIsFinishing) {
                    MyLog.v(this, "Switching to the selected account");
                    MyContextHolder.get().persistentAccounts().setCurrentAccount(state.builder.getAccount());
                    state.setAccountAction(Intent.ACTION_EDIT);
                    showUserPreferences();
                } else {
                    MyLog.v(this, "No account supplied, finishing");
                    finish();
                }
                break;
            default:
                super.onActivityResult(requestCode, resultCode, data);
                break;
        }
    }

    /**
     * Show values of all preferences in the "summaries".
     * @see <a href="http://stackoverflow.com/questions/531427/how-do-i-display-the-current-value-of-an-android-preference-in-the-preference-sum"> 
       How do I display the current value of an Android Preference 
       in the Preference summary?</a>
       
     * Some "preferences" may be changed in MyAccount object
     */
    private void showUserPreferences() {
        MyAccount ma = state.getAccount();
        
        originNameList.setValue(ma.getOriginName());
        SharedPreferencesUtil.showListPreference(this, MyAccount.Builder.KEY_ORIGIN_NAME, R.array.origin_system_entries, R.array.origin_system_entries, R.string.summary_preference_origin_system);

        originNameList.setEnabled(!state.builder.isPersistent() && TextUtils.isEmpty(ma.getUsername()));
        
        if (usernameText.getText() == null
                || ma.getUsername().compareTo(usernameText.getText()) != 0) {
            usernameText.setText(ma.getUsername());
        }
        StringBuilder summary;
        if (ma.getUsername().length() > 0) {
            summary = new StringBuilder(ma.getUsername());
        } else {
            summary = new StringBuilder(this.getText(ma.alternativeTermForResourceId(R.string.summary_preference_username)));
        }
        usernameText.setDialogTitle(this.getText(ma.alternativeTermForResourceId(R.string.dialog_title_preference_username)));
        usernameText.setTitle(this.getText(ma.alternativeTermForResourceId(R.string.title_preference_username)));
        usernameText.setSummary(summary);
        usernameText.setEnabled(!state.builder.isPersistent() && !ma.isUsernameValidToStartAddingNewAccount());

        // TODO: isOAuth should be a parameter of an Origin, not of an account
        // Changing this parameter should trigger clearing of all the origin users' credentials.
        boolean isNeeded = ma.canChangeOAuth();
        if (ma.isOAuth() != oAuthCheckBox.isChecked()) {
            oAuthCheckBox.setChecked(ma.isOAuth());
        }
        // In fact, we should hide it if not enabled, but I couldn't find an easy way for this...
        oAuthCheckBox.setEnabled(isNeeded);
        if (isNeeded) {
            oAuthCheckBox.setTitle(R.string.title_preference_oauth);
            oAuthCheckBox.setSummary(ma.isOAuth() ? R.string.summary_preference_oauth_on : R.string.summary_preference_oauth_off);
        } else {
            oAuthCheckBox.setTitle("");
            oAuthCheckBox.setSummary("");
        }

        isNeeded = ma.getConnection().isPasswordNeeded();
        if (passwordText.getText() == null
                || ma.getPassword().compareTo(passwordText.getText()) != 0) {
            passwordText.setText(ma.getPassword());
        }
        if (isNeeded) {
            passwordText.setTitle(R.string.title_preference_password);
            summary = new StringBuilder(this.getText(R.string.summary_preference_password));
            if (TextUtils.isEmpty(ma.getPassword())) {
                summary.append(": (" + this.getText(R.string.not_set) + ")");
            }
        } else {
            summary = null;
            passwordText.setTitle("");
        }
        passwordText.setSummary(summary);
        passwordText.setEnabled(isNeeded 
                && (ma.getCredentialsVerified()!=CredentialsVerificationStatus.SUCCEEDED));

        Origin origin = Origin.fromOriginId(ma.getOriginId());
        isNeeded = Origin.fromOriginId(ma.getOriginId()).canSetHostOfOrigin();
        boolean isEnabled = isNeeded && (ma.accountsOfThisOrigin() == 0) 
                && !state.builder.isPersistent();
        boolean originParametersPresent = true;
        hostText.setEnabled(isEnabled);
        if (isNeeded) {
            hostText.setTitle(R.string.title_preference_host);
            if (origin.hostIsValid()) {
                hostText.setSummary(origin.getHost());
            } else {
                hostText.setSummary(R.string.summary_preference_host);
                originParametersPresent = false;
            }
            hostText.setText(origin.getHost());
        } else {
            hostText.setTitle("");
            hostText.setSummary("");
        }

        isNeeded = origin.canChangeSsl();
        if (origin.isSsl() != sslCheckBox.isChecked()) {
            sslCheckBox.setChecked(origin.isSsl());
        }
        isEnabled = isNeeded && (ma.accountsOfThisOrigin() == 0) && !state.builder.isPersistent();
        sslCheckBox.setEnabled(isEnabled);
        if (isNeeded) {
            sslCheckBox.setTitle(R.string.title_preference_ssl);
            sslCheckBox.setSummary(origin.isSsl() ? R.string.summary_preference_ssl_on : R.string.summary_preference_ssl_off);
        } else {
            sslCheckBox.setTitle("");
            sslCheckBox.setSummary("");
        }
        
        int titleResId;
        boolean addAccountOrVerifyCredentialsEnabled = (ma.isOAuth() || ma.getCredentialsPresent()) 
                && originParametersPresent;
        switch (ma.getCredentialsVerified()) {
            case SUCCEEDED:
                titleResId = R.string.title_preference_verify_credentials;
                summary = new StringBuilder(
                        this.getText(R.string.summary_preference_verify_credentials));
                break;
            default:
                if (state.builder.isPersistent()) {
                    titleResId = R.string.title_preference_verify_credentials_failed;
                    summary = new StringBuilder(
                            this.getText(R.string.summary_preference_verify_credentials_failed));
                } else {
                    if (!ma.isUsernameValidToStartAddingNewAccount()) {
                        addAccountOrVerifyCredentialsEnabled = false;
                    }
                    titleResId = R.string.title_preference_add_account;
                    if (ma.isOAuth()) {
                        summary = new StringBuilder(
                                this.getText(R.string.summary_preference_add_account_oauth));
                    } else {
                        summary = new StringBuilder(
                                this.getText(R.string.summary_preference_add_account_basic));
                    }
                }
                break;
        }
        addAccountOrVerifyCredentials.setTitle(titleResId);
        addAccountOrVerifyCredentials.setSummary(summary);
        addAccountOrVerifyCredentials.setEnabled(addAccountOrVerifyCredentialsEnabled);
    }

    @Override
    protected void onResume() {
        super.onResume();

        MyContextHolder.initialize(this, this);
        MyServiceManager.setServiceUnavailable();
        MyServiceManager.stopService();
        
        showUserPreferences();
        MyPreferences.getDefaultSharedPreferences().registerOnSharedPreferenceChangeListener(this);
        
        Uri uri = getIntent().getData();
        if (uri != null) {
            if (MyLog.isLoggable(TAG, MyLog.DEBUG)) {
                MyLog.d(TAG, "uri=" + uri.toString());
            }
            if (Origin.CALLBACK_URI.getScheme().equals(uri.getScheme())) {
                // To prevent repeating of this task
                getIntent().setData(null);
                // This activity was started by Twitter ("Service Provider")
                // so start second step of OAuth Authentication process
                new OAuthAcquireAccessTokenTask().execute(uri);
                // and return back to default screen
                overrideBackButton = true;
            }
        }
    }

    /**
     * Verify credentials
     * 
     * @param reVerify true - Verify only if we didn't do this yet
     */
    private void verifyCredentials(boolean reVerify) {
        MyAccount ma = state.getAccount();
        if (reVerify || ma.getCredentialsVerified() == CredentialsVerificationStatus.NEVER) {
            MyServiceManager.setServiceUnavailable();
            MyService.ServiceState state = MyServiceManager.getServiceState(); 
            if (state != MyService.ServiceState.STOPPED) {
                MyServiceManager.stopService();
                if (state != MyService.ServiceState.UNKNOWN) {
                    Toast.makeText(this, getText(R.string.system_is_busy_try_later) + " (" + state + ")", 
                            Toast.LENGTH_LONG).show();
                    return;
                }
            }
            if (ma.getCredentialsPresent()) {
                // Credentials are present, so we may verify them
                // This is needed even for OAuth - to know Twitter Username
                new VerifyCredentialsTask().execute();
            } else {
                if (ma.isOAuth() && reVerify) {
                    // Credentials are not present,
                    // so start asynchronous OAuth Authentication process 
                    if (!ma.areClientKeysPresent()) {
                        new OAuthRegisterClientTask().execute();
                    } else {
                        new OAuthAcquireRequestTokenTask().execute();
                        // and return back to default screen
                        overrideBackButton = true;
                    }
                }
            }

        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        state.save();
        MyPreferences.getDefaultSharedPreferences().unregisterOnSharedPreferenceChangeListener(
                this);
        if (mIsFinishing) {
            MyContextHolder.release();;
            if (startPreferencesActivity) {
                MyLog.v(this, "Returning to our Preferences Activity");
                // On modifying activity back stack see http://stackoverflow.com/questions/11366700/modification-of-the-back-stack-in-android
                Intent i = new Intent(this, MyPreferenceActivity.class);
                i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(i);
            }
        }
    }

    /**
     * Only preferences with android:persistent="true" trigger this event!
     */
    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (somethingIsBeingProcessed) {
            return;
        }
        if (onSharedPreferenceChanged_busy || !MyContextHolder.get().initialized()) {
            return;
        }
        onSharedPreferenceChanged_busy = true;

        try {
            String value = "(not set)";
            if (sharedPreferences.contains(key)) {
                try {
                    value = sharedPreferences.getString(key, "");
                } catch (ClassCastException e) {
                    try {
                        value = Boolean.toString(sharedPreferences.getBoolean(key, false));
                    } catch (ClassCastException e2) {
                        value = "??";
                    }
                }
            }
            MyLog.d(TAG, "onSharedPreferenceChanged: " + key + "='" + value + "'");

            // Here and below:
            // Check if there are changes to avoid "ripples": don't set new
            // value if no changes

            if (key.equals(MyAccount.Builder.KEY_ORIGIN_NAME)) {
                if (state.getAccount().getOriginName().compareToIgnoreCase(originNameList.getValue()) != 0) {
                    // If we have changed the System, we should recreate the
                    // Account
                    state.builder = MyAccount.Builder.newOrExistingFromAccountName(
                            AccountName.fromOriginAndUserNames(originNameList.getValue(),
                                    state.getAccount().getUsername()).toString(),
                                    TriState.fromBoolean(state.getAccount().isOAuth()));
                    showUserPreferences();
                }
            }
            if (key.equals(MyAccount.Builder.KEY_OAUTH)) {
                if (state.getAccount().isOAuth() != oAuthCheckBox.isChecked()) {
                    state.builder = MyAccount.Builder.newOrExistingFromAccountName(
                            AccountName.fromOriginAndUserNames(originNameList.getValue(),
                                    state.getAccount().getUsername()).toString(),
                                    TriState.fromBoolean(oAuthCheckBox.isChecked()));
                    showUserPreferences();
                }
            }
            if (key.equals(MyAccount.Builder.KEY_USERNAME_NEW)) {
                String usernameNew = usernameText.getText();
                if (usernameNew.compareTo(state.getAccount().getUsername()) != 0) {
                    boolean isOAuth = state.getAccount().isOAuth();
                    String originName = state.getAccount().getOriginName();
                    state.builder = MyAccount.Builder.newOrExistingFromAccountName(
                            AccountName.fromOriginAndUserNames(originName, usernameNew).toString(),
                            TriState.fromBoolean(isOAuth));
                    showUserPreferences();
                }
            }
            if (key.equals(Connection.KEY_PASSWORD)) {
                if (state.getAccount().getPassword().compareTo(passwordText.getText()) != 0) {
                    state.builder.setPassword(passwordText.getText());
                    showUserPreferences();
                }
            }
            if (key.equals(Origin.KEY_HOST_OF_ORIGIN)) {
                Origin origin = Origin.fromOriginId(state.getAccount().getOriginId());
                if (origin.canSetHostOfOrigin()) {
                    String host = hostText.getText();
                    if (origin.hostIsValid(host)) {
                        if (!origin.getHost().equalsIgnoreCase(host)) {
                            origin.setHost(host);
                            origin.save();
                            state.builder = MyAccount.Builder.newOrExistingFromAccountName(
                                    AccountName.fromOriginAndUserNames(originNameList.getValue(),
                                            state.getAccount().getUsername()).toString(),
                                            TriState.fromBoolean(state.getAccount().isOAuth()));
                            showUserPreferences();
                        }
                    }
                }
            }
            if (key.equals(Origin.KEY_SSL)) {
                Origin origin = Origin.fromOriginId(state.getAccount().getOriginId());
                if (origin.canChangeSsl()) {
                    boolean isSsl = sslCheckBox.isChecked();
                    if (origin.isSsl() != isSsl) {
                        origin.setSsl(isSsl);
                        origin.save();
                        state.builder = MyAccount.Builder.newOrExistingFromAccountName(
                                AccountName.fromOriginAndUserNames(originNameList.getValue(),
                                        state.getAccount().getUsername()).toString(),
                                        TriState.fromBoolean(state.getAccount().isOAuth()));
                        showUserPreferences();
                    }
                }
            }
        } finally {
            onSharedPreferenceChanged_busy = false;
        }
    };

    @Override
    protected Dialog onCreateDialog(int id) {
        int titleId = 0;
        int summaryId = 0;
        Dialog dlg = null;

        switch (id) {
            case MSG_ACCOUNT_INVALID:
                if (titleId == 0) {
                    titleId = R.string.dialog_title_authentication_failed;
                    summaryId = R.string.dialog_summary_authentication_failed;
                }
            case MSG_CREDENTIALS_OF_OTHER_USER:
                if (titleId == 0) {
                    titleId = R.string.dialog_title_authentication_failed;
                    summaryId = R.string.error_credentials_of_other_user;
                }
                dlg = new AlertDialog.Builder(this)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setTitle(titleId)
                        .setMessage(summaryId)
                        .setPositiveButton(android.R.string.ok,
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface Dialog,
                                            int whichButton) {
                                    }
                                }).create();

            default:
                dlg = super.onCreateDialog(id);
        }
        return dlg;
    }

    /**
     * This semaphore helps to avoid ripple effect: changes in MyAccount cause
     * changes in this activity ...
     */
    private boolean somethingIsBeingProcessed = false;

    /*
     * (non-Javadoc)
     * @seeandroid.preference.PreferenceActivity#onPreferenceTreeClick(android.
     * preference.PreferenceScreen, android.preference.Preference)
     */
    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        MyLog.d(TAG, "Preference clicked:" + preference.toString());
        if (preference.getKey().compareTo(MyPreferences.KEY_VERIFY_CREDENTIALS) == 0) {
            verifyCredentials(true);
        }
        return super.onPreferenceTreeClick(preferenceScreen, preference);
    };
    
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.getRepeatCount() == 0) {
            // Explicitly save MyAccount only on "Back key" 
            state.builder.save();
            closeAndGoBack();
        }
        if (mIsFinishing) {
            return true;    
        } else {
            return super.onKeyDown(keyCode, event);
        }
    }

    /** 
     * Mark the action completed, close this activity and go back to the proper screen.
     * Return result to the caller if necessary.
     * See also {@link com.android.email.activity.setup.AccountSetupBasics.finish}
     * 
     * @return
     */
    private boolean closeAndGoBack() {
        boolean doFinish = false;
        String message = "";
        state.actionCompleted = true;
        if (state.authenticatiorResponse != null) {
            // We should return result back to AccountManager
            if (state.actionSucceeded) {
                if (state.builder.isPersistent()) {
                    doFinish = true;
                    // Pass the new/edited account back to the account manager
                    Bundle result = new Bundle();
                    result.putString(AccountManager.KEY_ACCOUNT_NAME, state.getAccount().getAccountName());
                    result.putString(AccountManager.KEY_ACCOUNT_TYPE,
                            AuthenticatorService.ANDROID_ACCOUNT_TYPE);
                    state.authenticatiorResponse.onResult(result);
                    message += "authenticatiorResponse; account.name=" + state.getAccount().getAccountName() + "; ";
                }
            } else {
                state.authenticatiorResponse.onError(AccountManager.ERROR_CODE_CANCELED, "canceled");
            }
        }
        // Forget old state
        state.forget();
        if (overrideBackButton) {
            doFinish = true;
        }
        if (doFinish) {
            MyLog.v(this, "finish: action=" + state.getAccountAction() + "; " + message);
            mIsFinishing = true;
            finish();
        }
        if (overrideBackButton) {
            startPreferencesActivity = true;
        }
        return mIsFinishing;
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (MyLog.isLoggable(TAG, MyLog.VERBOSE)) {
            MyLog.v(TAG, "onPreferenceChange: " + preference.toString() + " -> " + (newValue == null ? "null" : newValue.toString()));
        }
        return true;
    }
    
    /**
     * Start system activity which allow to manage list of accounts
     * See <a href="https://groups.google.com/forum/?fromgroups#!topic/android-developers/RfrIb5V_Bpo">per account settings in Jelly Beans</a>. 
     * For versions prior to Jelly Bean see <a href="http://stackoverflow.com/questions/3010103/android-how-to-create-intent-to-open-the-activity-that-displays-the-accounts">
     *  Android - How to Create Intent to open the activity that displays the “Accounts & Sync settings” screen</a>
     */
    public static void startManageAccountsActivity(android.content.Context context) {
        Intent intent;
        if (android.os.Build.VERSION.SDK_INT < 16 ) {  // before Jelly Bean
            intent = new Intent(android.provider.Settings.ACTION_SYNC_SETTINGS);
            // This gives some unstable results on v.4.0.x so I got rid of it:
            // intent.putExtra(android.provider.Settings.EXTRA_AUTHORITIES, new String[] {MyProvider.AUTHORITY});
        } else {
            intent = new Intent(android.provider.Settings.ACTION_SETTINGS);
            // TODO: Figure out some more specific intent...
        }
        context.startActivity(intent);
    }
    
    
    /**
     * Step 1 of 3 of the OAuth Authentication
     * Needed in case we don't have the AndStatus Client keys for the Microblogging system
     */
    private class OAuthRegisterClientTask extends AsyncTask<Void, Void, JSONObject> {
        private ProgressDialog dlg;

        @Override
        protected void onPreExecute() {
            dlg = ProgressDialog.show(AccountSettingsActivity.this,
                    getText(R.string.dialog_title_registering_client),
                    getText(R.string.dialog_summary_registering_client), true, // indeterminate
                    // duration
                    false); // not cancel-able
        }

        @Override
        protected JSONObject doInBackground(Void... arg0) {
            JSONObject jso = null;

            boolean requestSucceeded = false;
            String message = "";
            String message2 = "";

            try {
                state.builder.getOriginConfig();
                if (!state.getAccount().areClientKeysPresent()) {
                    state.builder.registerClient();
                } 
                requestSucceeded = state.getAccount().areClientKeysPresent();
            } catch (ConnectionException e) {
                message = e.getMessage();
            }
            
            try {
                if (!requestSucceeded) {
                    message2 = AccountSettingsActivity.this
                            .getString(R.string.dialog_title_authentication_failed);
                    if (message != null && message.length() > 0) {
                        message2 = message2 + ": " + message;
                    }
                    MyLog.d(TAG, message2);
                }

                jso = new JSONObject();
                jso.put("succeeded", requestSucceeded);
                jso.put("message", message2);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return jso;
        }

        // This is in the UI thread, so we can mess with the UI
        @Override
        protected void onPostExecute(JSONObject jso) {
            try {
                dlg.dismiss();
            } catch (Exception e) { 
                // Ignore this error  
            }
            if (jso != null) {
                try {
                    boolean succeeded = jso.getBoolean("succeeded");
                    String message = jso.getString("message");

                    if (succeeded) {
                        String accountName = state.getAccount().getAccountName();
                        MyContextHolder.get().persistentAccounts().reRead(MyContextHolder.get().context());
                        state.builder = MyAccount.Builder.newOrExistingFromAccountName(accountName, TriState.TRUE);
                        showUserPreferences();
                        new OAuthAcquireRequestTokenTask().execute();
                        // and return back to default screen
                        overrideBackButton = true;
                    } else {
                        Toast.makeText(AccountSettingsActivity.this, message, Toast.LENGTH_LONG).show();

                        state.builder.setCredentialsVerificationStatus(CredentialsVerificationStatus.FAILED);
                        showUserPreferences();
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }
    }
    
    
    /**
     * Task 2 of 3 required for OAuth Authentication.
     * See http://www.snipe.net/2009/07/writing-your-first-twitter-application-with-oauth/
     * for good OAuth Authentication flow explanation.
     *  
     * During this task:
     * 1. AndStatus ("Consumer") Requests "Request Token" from Twitter ("Service provider"), 
     * 2. Waits for that Request Token
     * 3. Consumer directs User to the Service Provider: opens Twitter site in Internet Browser window
     *    in order to Obtain User Authorization.
     * 4. This task ends.
     * 
     * What will occur later:
     * 5. After User Authorized AndStatus in the Internet Browser,
     *    Twitter site will redirect User back to
     *    AndStatus and then the second OAuth task will start.
     *   
     * @author yvolk@yurivolkov.com This code is based on "BLOA" example,
     *         http://github.com/brione/Brion-Learns-OAuth yvolk: I had to move
     *         this code from OAuthActivity here in order to be able to show
     *         ProgressDialog and to get rid of any "Black blank screens"
     */
    private class OAuthAcquireRequestTokenTask extends AsyncTask<Void, Void, JSONObject> {
        private ProgressDialog dlg;

        @Override
        protected void onPreExecute() {
            dlg = ProgressDialog.show(AccountSettingsActivity.this,
                    getText(R.string.dialog_title_acquiring_a_request_token),
                    getText(R.string.dialog_summary_acquiring_a_request_token), true, // indeterminate
                    // duration
                    false); // not cancel-able
        }

        @Override
        protected JSONObject doInBackground(Void... arg0) {
            JSONObject jso = null;

            boolean requestSucceeded = false;
            String message = "";
            String message2 = "";
            try {
                MyAccount ma = state.getAccount();
                MyLog.v(this, "Retrieving request token for " + ma);
                OAuthConsumer consumer = state.getAccount().getOAuthConsumerAndProvider().getConsumer();

                // This is really important. If you were able to register your
                // real callback Uri with Twitter, and not some fake Uri
                // like I registered when I wrote this example, you need to send
                // null as the callback Uri in this function call. Then
                // Twitter will correctly process your callback redirection
                String authUrl = state.getAccount().getOAuthConsumerAndProvider().getProvider()
                        .retrieveRequestToken(consumer, Origin.CALLBACK_URI.toString());
                state.setRequestTokenWithSecret(consumer.getToken(), consumer.getTokenSecret());

                // This is needed in order to complete the process after redirect
                // from the Browser to the same activity.
                state.actionCompleted = false;
                
                android.webkit.CookieSyncManager.createInstance(AccountSettingsActivity.this);
                android.webkit.CookieManager cookieManager = android.webkit.CookieManager.getInstance();
                cookieManager.removeAllCookie();                

                // Start Web view (looking just like Web Browser)
                Intent i = new Intent(AccountSettingsActivity.this, AccountSettingsWebActivity.class);
                i.putExtra(AccountSettingsWebActivity.EXTRA_URLTOOPEN, authUrl);
                AccountSettingsActivity.this.startActivity(i);

                requestSucceeded = true;
            } catch (OAuthMessageSignerException e) {
                message = e.getMessage();
                e.printStackTrace();
            } catch (OAuthNotAuthorizedException e) {
                message = e.getMessage();
                e.printStackTrace();
            } catch (OAuthExpectationFailedException e) {
                message = e.getMessage();
                e.printStackTrace();
            } catch (OAuthCommunicationException e) {
                message = e.getMessage();
                e.printStackTrace();
            }

            try {
                if (!requestSucceeded) {
                    message2 = AccountSettingsActivity.this
                            .getString(R.string.dialog_title_authentication_failed);
                    if (message != null && message.length() > 0) {
                        message2 = message2 + ": " + message;
                    }
                    MyLog.d(TAG, message2);
                    
                    state.builder.clearClientKeys();
                }

                jso = new JSONObject();
                jso.put("succeeded", requestSucceeded);
                jso.put("message", message2);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return jso;
        }

        // This is in the UI thread, so we can mess with the UI
        @Override
        protected void onPostExecute(JSONObject jso) {
            try {
                dlg.dismiss();
            } catch (Exception e1) { 
                // Ignore this error  
            }
            if (jso != null) {
                try {
                    boolean succeeded = jso.getBoolean("succeeded");
                    String message = jso.getString("message");

                    if (succeeded) {
                        // Finish this activity in order to start properly 
                        // after redirection from Browser
                        // Because of initializations in onCreate...
                        AccountSettingsActivity.this.finish();
                    } else {
                        Toast.makeText(AccountSettingsActivity.this, message, Toast.LENGTH_LONG).show();

                        state.builder.setCredentialsVerificationStatus(CredentialsVerificationStatus.FAILED);
                        showUserPreferences();
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }
    }
    
    /**
     * Task 3 of 3 required for OAuth Authentication.
     *  
     * During this task:
     * 1. AndStatus ("Consumer") exchanges "Request Token", 
     *    obtained earlier from Twitter ("Service provider"),
     *    for "Access Token". 
     * 2. Stores the Access token for all future interactions with Twitter.
     * 
     * @author yvolk@yurivolkov.com This code is based on "BLOA" example,
     *         http://github.com/brione/Brion-Learns-OAuth yvolk: I had to move
     *         this code from OAuthActivity here in order to be able to show
     *         ProgressDialog and to get rid of any "Black blank screens"
     */
    private class OAuthAcquireAccessTokenTask extends AsyncTask<Uri, Void, JSONObject> {
        private ProgressDialog dlg;

        @Override
        protected void onPreExecute() {
            dlg = ProgressDialog.show(AccountSettingsActivity.this,
                    getText(R.string.dialog_title_acquiring_an_access_token),
                    getText(R.string.dialog_summary_acquiring_an_access_token), true, // indeterminate
                    // duration
                    false); // not cancelable
        }

        @Override
        protected JSONObject doInBackground(Uri... uris) {
            JSONObject jso = null;

            String message = "";
            
            boolean authenticated = false;

            if (state.getAccount().getOAuthConsumerAndProvider() == null) {
                message = "Connection is not OAuth";
                MyLog.e(this, message);
            }
            else {
                // We don't need to worry about any saved states: we can reconstruct
                // the state

                Uri uri = uris[0];
                if (uri != null && Origin.CALLBACK_URI.getHost().equals(uri.getHost())) {
                    String token = state.getRequestToken();
                    String secret = state.getRequestSecret();

                    state.builder.setCredentialsVerificationStatus(CredentialsVerificationStatus.NEVER);;
                    try {
                        // Clear the request stuff, we've used it already
                        state.setRequestTokenWithSecret(null, null);

                        OAuthConsumer consumer = state.getAccount().getOAuthConsumerAndProvider().getConsumer();
                        if (!(token == null || secret == null)) {
                            consumer.setTokenWithSecret(token, secret);
                        }
                        String otoken = uri.getQueryParameter(OAuth.OAUTH_TOKEN);
                        String verifier = uri.getQueryParameter(OAuth.OAUTH_VERIFIER);

                        /*
                         * yvolk 2010-07-08: It appeared that this may be not true:
                         * Assert.assertEquals(otoken, mConsumer.getToken()); (e.g.
                         * if User denied access during OAuth...) hence this is not
                         * Assert :-)
                         */
                        if (otoken != null || consumer.getToken() != null) {
                            // We send out and save the request token, but the
                            // secret is not the same as the verifier
                            // Apparently, the verifier is decoded to get the
                            // secret, which is then compared - crafty
                            // This is a sanity check which should never fail -
                            // hence the assertion
                            // Assert.assertEquals(otoken,
                            // mConsumer.getToken());

                            // This is the moment of truth - we could throw here
                            state.getAccount().getOAuthConsumerAndProvider().getProvider()
                                .retrieveAccessToken(consumer, verifier);
                            // Now we can retrieve the goodies
                            token = consumer.getToken();
                            secret = consumer.getTokenSecret();
                            authenticated = true;
                        }
                    } catch (OAuthMessageSignerException e) {
                        message = e.getMessage();
                        e.printStackTrace();
                    } catch (OAuthNotAuthorizedException e) {
                        message = e.getMessage();
                        e.printStackTrace();
                    } catch (OAuthExpectationFailedException e) {
                        message = e.getMessage();
                        e.printStackTrace();
                    } catch (OAuthCommunicationException e) {
                        message = e.getMessage();
                        e.printStackTrace();
                    } finally {
                        if (authenticated) {
                            state.builder.setUserTokenWithSecret(token, secret);
                        }
                    }
                }
            }

            try {
                jso = new JSONObject();
                jso.put("succeeded", authenticated);
                jso.put("message", message);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return jso;
        }

        // This is in the UI thread, so we can mess with the UI
        @Override
        protected void onPostExecute(JSONObject jso) {
            try {
                dlg.dismiss();
            } catch (Exception e1) { 
                // Ignore this error  
            }
            if (jso != null) {
                try {
                    boolean succeeded = jso.getBoolean("succeeded");
                    String message = jso.getString("message");

                    MyLog.d(TAG, this.getClass().getName() + " ended, "
                            + (succeeded ? "authenticated" : "authentication failed"));
                    
                    if (succeeded) {
                        // Credentials are present, so we may verify them
                        // This is needed even for OAuth - to know Twitter Username
                        new VerifyCredentialsTask().execute();

                    } else {
                        String message2 = AccountSettingsActivity.this
                        .getString(R.string.dialog_title_authentication_failed);
                        if (message != null && message.length() > 0) {
                            message2 = message2 + ": " + message;
                            MyLog.d(TAG, message);
                        }
                        Toast.makeText(AccountSettingsActivity.this, message2, Toast.LENGTH_LONG).show();

                        state.builder.setCredentialsVerificationStatus(CredentialsVerificationStatus.FAILED);
                        showUserPreferences();
                    }
                    
                    // Now we can return to the AccountSettingsActivity
                    // We need new Intent in order to forget that URI from OAuth Service Provider
                    //Intent intent = new Intent(AccountSettingsActivity.this, AccountSettingsActivity.class);
                    //intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    //startActivity(intent);
                    
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * Assuming we already have credentials to verify, verify them
     * @author yvolk@yurivolkov.com
     */
    private class VerifyCredentialsTask extends AsyncTask<Void, Void, JSONObject> {
        private ProgressDialog dlg;
        private boolean skip = false;

        @Override
        protected void onPreExecute() {
            dlg = ProgressDialog.show(AccountSettingsActivity.this,
                    getText(R.string.dialog_title_checking_credentials),
                    getText(R.string.dialog_summary_checking_credentials), true, // indeterminate
                    // duration
                    false); // not cancel-able

            synchronized (AccountSettingsActivity.this) {
                if (somethingIsBeingProcessed) {
                    skip = true;
                } else {
                    somethingIsBeingProcessed = true;
                }
            }
        }

        @Override
        protected JSONObject doInBackground(Void... arg0) {
            JSONObject jso = null;

            int what = MSG_NONE;
            String message = "";
            
            if (!skip) {
                what = MSG_ACCOUNT_INVALID;
                try {
                    state.builder.getOriginConfig();
                    if (state.builder.verifyCredentials(true)) {
                        what = MSG_ACCOUNT_VALID;
                    }
                } catch (ConnectionException e) {
                    switch (e.getStatusCode()) {
                        case AUTHENTICATION_ERROR:
                            what = MSG_ACCOUNT_INVALID;
                            break;
                        case CREDENTIALS_OF_OTHER_USER:
                            what = MSG_CREDENTIALS_OF_OTHER_USER;
                            break;
                        default:
                            what = MSG_CONNECTION_EXCEPTION;
                    }
                    message = e.toString();
                }
            }

            try {
                jso = new JSONObject();
                jso.put("what", what);
                jso.put("message", message);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return jso;
        }

        /**
         * Credentials were verified just now!
         * This is in the UI thread, so we can mess with the UI
         */
        @Override
        protected void onPostExecute(JSONObject jso) {
            try {
                dlg.dismiss();
            } catch (Exception e1) { 
                // Ignore this error  
            }
            boolean succeeded = false;
            if (jso != null) {
                try {
                    int what = jso.getInt("what");
                    String message = jso.getString("message");

                    switch (what) {
                        case MSG_ACCOUNT_VALID:
                            Toast.makeText(AccountSettingsActivity.this, R.string.authentication_successful,
                                    Toast.LENGTH_SHORT).show();
                            succeeded = true;
                            break;
                        case MSG_ACCOUNT_INVALID:
                        case MSG_CREDENTIALS_OF_OTHER_USER:
                            showDialog(what);
                            break;
                        case MSG_CONNECTION_EXCEPTION:
                            Toast.makeText(AccountSettingsActivity.this, R.string.error_connection_error + " " + message, Toast.LENGTH_LONG).show();
                            break;

                    }
                    showUserPreferences();
                } catch (JSONException e) {
                    // Auto-generated catch block
                    e.printStackTrace();
                }
            }
            if (!skip) {
                StateOfAccountChangeProcess state = AccountSettingsActivity.this.state;
                // Note: MyAccount was already saved inside MyAccount.verifyCredentials
                // Now we only have to deal with the state
               
                state.actionSucceeded = succeeded;
                if (succeeded) {
                    state.actionCompleted = true;
                    if (state.getAccountAction().compareTo(Intent.ACTION_INSERT) == 0) {
                        state.setAccountAction(Intent.ACTION_EDIT);
                        showUserPreferences();
                        // TODO: Decide on this...
                        // closeAndGoBack();
                    }
                }
                somethingIsBeingProcessed = false;
            }
            showUserPreferences();
        }
    }
}
