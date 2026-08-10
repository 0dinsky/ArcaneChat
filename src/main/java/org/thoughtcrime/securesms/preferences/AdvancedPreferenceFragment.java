package org.thoughtcrime.securesms.preferences;

import static android.app.Activity.RESULT_OK;
import static android.text.InputType.TYPE_TEXT_VARIATION_URI;
import static org.thoughtcrime.securesms.connect.DcHelper.CONFIG_BCC_SELF;
import static org.thoughtcrime.securesms.connect.DcHelper.CONFIG_KEY_GEN_MODE;
import static org.thoughtcrime.securesms.connect.DcHelper.CONFIG_KEY_ROTATION_GRACE_DAYS;
import static org.thoughtcrime.securesms.connect.DcHelper.CONFIG_KEY_ROTATION_PERIOD;
import static org.thoughtcrime.securesms.connect.DcHelper.CONFIG_STATS_SENDING;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.CheckBoxPreference;
import androidx.preference.Preference;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import org.thoughtcrime.securesms.ApplicationPreferencesActivity;
import org.thoughtcrime.securesms.LogViewActivity;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.StatsSending;
import org.thoughtcrime.securesms.connect.DcEventCenter;
import org.thoughtcrime.securesms.proxy.ProxySettingsActivity;
import org.thoughtcrime.securesms.relay.RelayListActivity;
import org.thoughtcrime.securesms.util.Prefs;
import org.thoughtcrime.securesms.util.ScreenLockUtil;
import org.thoughtcrime.securesms.util.StreamUtil;

public class AdvancedPreferenceFragment extends ListSummaryPreferenceFragment
    implements DcEventCenter.DcEventDelegate {
  private static final String TAG = "AdvancedPreferenceFragment";

  /** Recommended key rotation period (see `Config::KeyRotationPeriod` docs: 30-60 days). */
  private static final int FORWARD_SECRECY_ROTATION_DAYS = 60;
  /**
   * Default grace days when user has not set a custom value, and the low/high ends of the
   * allowed range. Must mirror the clamp enforced core-side by
   * `Config::KeyRotationGraceDays` (`clamp_key_rotation_grace_days` in config.rs) — core is the
   * source of truth and will silently re-clamp anything out of range, but keeping the UI in sync
   * avoids a confusing "I typed 5 but it shows 7" round-trip.
   */
  private static final int DEFAULT_GRACE_DAYS = 30;
  private static final int MIN_GRACE_DAYS = 7;
  private static final int MAX_GRACE_DAYS = 90;

  CheckBoxPreference selfReportingCheckbox;
  CheckBoxPreference multiDeviceCheckbox;
  CheckBoxPreference postQuantumCheckbox;
  CheckBoxPreference forwardSecrecyCheckbox;
  Preference keyRotationGraceButton;
  Preference rotateKeypairButton;
  private ActivityResultLauncher<Intent> screenLockLauncher;

  @Override
  public void onCreate(Bundle paramBundle) {
    super.onCreate(paramBundle);

    screenLockLauncher =
        registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
              if (result.getResultCode() == RESULT_OK) {
                openRelayListActivity();
              }
            });

    multiDeviceCheckbox = (CheckBoxPreference) this.findPreference("pref_bcc_self");
    if (multiDeviceCheckbox != null) {
      multiDeviceCheckbox.setOnPreferenceChangeListener(
          (preference, newValue) -> {
            boolean enabled = (Boolean) newValue;
            if (enabled) {
              dcContext.setConfigInt(CONFIG_BCC_SELF, 1);
              return true;
            } else {
              new AlertDialog.Builder(requireContext())
                  .setMessage(R.string.pref_multidevice_change_warn)
                  .setPositiveButton(
                      R.string.ok,
                      (dialogInterface, i) -> {
                        dcContext.setConfigInt(CONFIG_BCC_SELF, 0);
                        ((CheckBoxPreference) preference).setChecked(false);
                      })
                  .setNegativeButton(R.string.cancel, null)
                  .show();
              return false;
            }
          });
    }

    Preference submitDebugLog = this.findPreference("pref_view_log");
    if (submitDebugLog != null) {
      submitDebugLog.setOnPreferenceClickListener(new ViewLogListener());
    }

    Preference webxdcStore = this.findPreference(Prefs.WEBXDC_STORE_URL_PREF);
    if (webxdcStore != null) {
      webxdcStore.setOnPreferenceClickListener(new WebxdcStoreUrlListener());
    }
    updateWebxdcStoreSummary();

    selfReportingCheckbox = this.findPreference("pref_stats_sending");
    if (selfReportingCheckbox != null) {
      selfReportingCheckbox.setOnPreferenceChangeListener(
          (preference, newValue) -> {
            boolean enabled = (Boolean) newValue;
            if (enabled) {
              StatsSending.showStatsConfirmationDialog(
                  requireActivity(),
                  () -> {
                    ((CheckBoxPreference) preference).setChecked(true);
                  });
              return false;
            } else {
              dcContext.setConfigInt(CONFIG_STATS_SENDING, 0);
              return true;
            }
          });
    }

    postQuantumCheckbox = this.findPreference("pref_post_quantum_encryption");
    if (postQuantumCheckbox != null) {
      postQuantumCheckbox.setOnPreferenceChangeListener(
          (preference, newValue) -> {
            boolean enabled = (Boolean) newValue;
            dcContext.setConfigInt(CONFIG_KEY_GEN_MODE, enabled ? 1 : 0);
            updateRotateKeypairSummary();
            if (enabled) {
              // Offer immediate key update so hybrid subkey + PQ signing key exist now.
              new AlertDialog.Builder(requireContext())
                  .setTitle(R.string.pref_post_quantum_enable_apply_title)
                  .setMessage(R.string.pref_post_quantum_enable_apply_message)
                  .setPositiveButton(
                      R.string.ok,
                      (d, which) -> runRotateKeypairNow())
                  .setNegativeButton(R.string.pref_post_quantum_enable_apply_later, null)
                  .show();
            } else {
              // Turning PQ off: offer to drop PQ material from the published key.
              new AlertDialog.Builder(requireContext())
                  .setTitle(R.string.pref_rotate_keypair_now_confirm_title)
                  .setMessage(R.string.pref_rotate_keypair_now_confirm_message)
                  .setPositiveButton(
                      R.string.ok,
                      (d, which) -> runRotateKeypairNow())
                  .setNegativeButton(R.string.pref_post_quantum_enable_apply_later, null)
                  .show();
            }
            return true;
          });
    }

    rotateKeypairButton = this.findPreference("pref_rotate_keypair_now");
    if (rotateKeypairButton != null) {
      rotateKeypairButton.setOnPreferenceClickListener(new RotateKeypairListener());
    }

    forwardSecrecyCheckbox = this.findPreference("pref_forward_secrecy");
    if (forwardSecrecyCheckbox != null) {
      forwardSecrecyCheckbox.setOnPreferenceChangeListener(
          (preference, newValue) -> {
            boolean enabled = (Boolean) newValue;
            dcContext.setConfigInt(
                CONFIG_KEY_ROTATION_PERIOD, enabled ? FORWARD_SECRECY_ROTATION_DAYS : 0);
            return true;
          });
    }

    keyRotationGraceButton = this.findPreference("pref_key_rotation_grace");
    if (keyRotationGraceButton != null) {
      keyRotationGraceButton.setOnPreferenceClickListener(
          preference -> {
            showGraceDaysDialog();
            return true;
          });
      updateGraceDaysSummary();
    }

    Preference proxySettings = this.findPreference("proxy_settings_button");
    if (proxySettings != null) {
      proxySettings.setOnPreferenceClickListener(
          (preference) -> {
            startActivity(new Intent(requireActivity(), ProxySettingsActivity.class));
            return true;
          });
    }

    Preference relayListBtn = this.findPreference("pref_relay_list_button");
    if (relayListBtn != null) {
      relayListBtn.setOnPreferenceClickListener(
          ((preference) -> {
            boolean result =
                ScreenLockUtil.applyScreenLock(
                    requireActivity(),
                    getString(R.string.transports),
                    getString(R.string.enter_system_secret_to_continue),
                    screenLockLauncher);
            if (!result) {
              openRelayListActivity();
            }
            return true;
          }));
    }
  }

  @Override
  public void onCreatePreferences(@Nullable Bundle savedInstanceState, String rootKey) {
    addPreferencesFromResource(R.xml.preferences_advanced);
  }

  @Override
  public void onResume() {
    super.onResume();
    Objects.requireNonNull(
            ((ApplicationPreferencesActivity) requireActivity()).getSupportActionBar())
        .setTitle(R.string.menu_advanced);

    selfReportingCheckbox.setChecked(0 != dcContext.getConfigInt(CONFIG_STATS_SENDING));
    multiDeviceCheckbox.setChecked(0 != dcContext.getConfigInt(CONFIG_BCC_SELF));
    if (postQuantumCheckbox != null) {
      postQuantumCheckbox.setChecked(0 != dcContext.getConfigInt(CONFIG_KEY_GEN_MODE));
    }
    if (forwardSecrecyCheckbox != null) {
      forwardSecrecyCheckbox.setChecked(0 != dcContext.getConfigInt(CONFIG_KEY_ROTATION_PERIOD));
    }
    updateGraceDaysSummary();
    updateRotateKeypairSummary();
  }

  private void updateGraceDaysSummary() {
    if (keyRotationGraceButton == null) {
      return;
    }
    int days = dcContext.getConfigInt(CONFIG_KEY_ROTATION_GRACE_DAYS);
    if (days <= 0) {
      days = DEFAULT_GRACE_DAYS;
    }
    keyRotationGraceButton.setSummary(
        getString(R.string.pref_key_rotation_grace_explain) + "\n(" + days + " days)");
  }

  private void showGraceDaysDialog() {
    final EditText input = new EditText(requireContext());
    input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    int current = dcContext.getConfigInt(CONFIG_KEY_ROTATION_GRACE_DAYS);
    if (current <= 0) {
      current = DEFAULT_GRACE_DAYS;
    }
    input.setText(String.valueOf(current));
    input.setSelection(input.getText().length());

    new AlertDialog.Builder(requireContext())
        .setTitle(R.string.pref_key_rotation_grace_dialog_title)
        .setMessage(R.string.pref_key_rotation_grace_explain)
        .setView(input)
        .setPositiveButton(
            android.R.string.ok,
            (dialog, which) -> {
              try {
                int days = Integer.parseInt(input.getText().toString().trim());
                if (days < MIN_GRACE_DAYS) days = MIN_GRACE_DAYS;
                if (days > MAX_GRACE_DAYS) days = MAX_GRACE_DAYS;
                dcContext.setConfigInt(CONFIG_KEY_ROTATION_GRACE_DAYS, days);
                updateGraceDaysSummary();
              } catch (NumberFormatException e) {
                Toast.makeText(requireContext(), R.string.pref_rotate_keypair_now_failed, Toast.LENGTH_SHORT)
                    .show();
              }
            })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  /**
   * Summary under "Regenerate Keys Now" reflects both the *desired* mode
   * (key_gen_mode toggle) and the *actual* encryption subkey on the account.
   * Toggling PQ alone does not change keys — only rotate/regenerate does —
   * so without this the label stayed stuck on the old algorithm family.
   */
  private void updateRotateKeypairSummary() {
    if (rotateKeypairButton == null) {
      return;
    }
    boolean wantPq = 0 != dcContext.getConfigInt(CONFIG_KEY_GEN_MODE);
    String kind = dcContext.getSelfEncryptionKind();
    if (kind == null) {
      kind = "";
    }
    boolean havePq = "pq".equals(kind);

    if (wantPq && havePq) {
      rotateKeypairButton.setSummary(R.string.pref_rotate_keypair_now_explain_pq);
    } else if (!wantPq && !havePq) {
      rotateKeypairButton.setSummary(R.string.pref_rotate_keypair_now_explain_classic);
    } else if (wantPq && !havePq) {
      // Toggle on, published key still classic — user must regenerate.
      rotateKeypairButton.setSummary(R.string.pref_rotate_keypair_now_explain_pq_pending);
    } else {
      // Toggle off, but published key still has ML-KEM subkey.
      rotateKeypairButton.setSummary(R.string.pref_rotate_keypair_now_explain_classic_pending);
    }
  }

  protected File copyToCacheDir(Uri uri) throws IOException {
    try (InputStream inputStream = requireActivity().getContentResolver().openInputStream(uri)) {
      File file = File.createTempFile("tmp-keys-file", ".tmp", requireActivity().getCacheDir());
      try (OutputStream outputStream = new FileOutputStream(file)) {
        StreamUtil.copy(inputStream, outputStream);
      }
      return file;
    }
  }

  public static @NonNull String getVersion(@Nullable Context context) {
    try {
      if (context == null) return "";

      String app = context.getString(R.string.app_name);
      String version =
          context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;

      return String.format("%s %s", app, version);
    } catch (PackageManager.NameNotFoundException e) {
      Log.w(TAG, e);
      return context.getString(R.string.app_name);
    }
  }

  private void runRotateKeypairNow() {
    Context appContext = requireActivity().getApplicationContext();
    new Thread(
            () -> {
              boolean success = dcContext.rotateKeypairNow();
              if (!isAdded()) {
                return;
              }
              requireActivity()
                  .runOnUiThread(
                      () -> {
                        if (!isAdded()) {
                          return;
                        }
                        Toast.makeText(
                                appContext,
                                success
                                    ? R.string.pref_rotate_keypair_now_success
                                    : R.string.pref_rotate_keypair_now_failed,
                                Toast.LENGTH_SHORT)
                            .show();
                        updateRotateKeypairSummary();
                      });
            })
        .start();
  }

  private class RotateKeypairListener implements Preference.OnPreferenceClickListener {
    @Override
    public boolean onPreferenceClick(@NonNull Preference preference) {
      new AlertDialog.Builder(requireContext())
          .setTitle(R.string.pref_rotate_keypair_now_confirm_title)
          .setMessage(R.string.pref_rotate_keypair_now_confirm_message)
          .setPositiveButton(
              R.string.ok,
              (dialogInterface, i) -> runRotateKeypairNow())
          .setNegativeButton(R.string.cancel, null)
          .show();
      return true;
    }
  }

  private class ViewLogListener implements Preference.OnPreferenceClickListener {
    @Override
    public boolean onPreferenceClick(@NonNull Preference preference) {
      final Intent intent = new Intent(requireActivity(), LogViewActivity.class);
      startActivity(intent);
      return true;
    }
  }

  private class WebxdcStoreUrlListener implements Preference.OnPreferenceClickListener {
    @Override
    public boolean onPreferenceClick(@NonNull Preference preference) {
      View gl = View.inflate(requireActivity(), R.layout.single_line_input, null);
      EditText inputField = gl.findViewById(R.id.input_field);
      inputField.setHint(Prefs.DEFAULT_WEBXDC_STORE_URL);
      inputField.setText(Prefs.getWebxdcStoreUrl(requireActivity()));
      inputField.setSelection(inputField.getText().length());
      inputField.setInputType(TYPE_TEXT_VARIATION_URI);
      new AlertDialog.Builder(requireActivity())
          .setTitle(R.string.webxdc_store_url)
          .setMessage(R.string.webxdc_store_url_explain)
          .setView(gl)
          .setNegativeButton(android.R.string.cancel, null)
          .setPositiveButton(
              android.R.string.ok,
              (dlg, btn) -> {
                Prefs.setWebxdcStoreUrl(requireActivity(), inputField.getText().toString());
                updateWebxdcStoreSummary();
              })
          .show();
      return true;
    }
  }

  private void updateWebxdcStoreSummary() {
    Preference preference = this.findPreference(Prefs.WEBXDC_STORE_URL_PREF);
    if (preference != null) {
      preference.setSummary(Prefs.getWebxdcStoreUrl(requireActivity()));
    }
  }

  private void openRelayListActivity() {
    Intent intent = new Intent(requireActivity(), RelayListActivity.class);
    startActivity(intent);
  }
}
