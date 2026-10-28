package net.kdt.pojavlaunch.fragments;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.kdt.mcgui.ProgressLayout;
import com.kdt.mcgui.mcAccountSpinner;

import net.kdt.pojavlaunch.CustomControlsActivity;
import net.kdt.pojavlaunch.LauncherActivity;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.progresskeeper.ProgressListener;
import net.kdt.pojavlaunch.progresskeeper.TaskCountListener;
import net.kdt.pojavlaunch.value.MinecraftAccount;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Our Place Craft: the main screen of the launcher.
 * Nick, "НА СЕРВЕР", singleplayer, skin hint, memory slider, plus our own
 * first-start sign-in card and install/download progress card.
 */
public class MainMenuFragment extends Fragment {
    public static final String TAG = "MainMenuFragment";
    public static final String OPC_PROFILE_NAME = "OUR PLACE CRAFT";
    private static final Pattern NICK_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final int MIN_MEMORY_MB = 1024;
    private static final int MEMORY_STEP_MB = 256;

    /** Progress records shown on our card, in order of priority */
    private static final String[] PROGRESS_KEYS = {
            ProgressLayout.INSTALL_MODPACK,
            ProgressLayout.DOWNLOAD_MINECRAFT,
            ProgressLayout.UNPACK_RUNTIME,
            ProgressLayout.AUTHENTICATE_MICROSOFT,
            ProgressLayout.EXTRACT_COMPONENTS
    };

    private TextView mNickView;
    private TextView mMemoryValue;
    private SeekBar mMemoryBar;
    private FrameLayout mProgressOverlay;
    private ProgressBar mProgressBar;
    private TextView mProgressTitle;
    private TextView mProgressText;
    private FrameLayout mLoginOverlay;
    private TextView mLoginText;
    private EditText mLoginNick;
    private Button mLoginDone;
    private int mMaxMemory;
    private boolean mLoginDismissed = false;

    private final ProgressListener[] mProgressListeners = new ProgressListener[PROGRESS_KEYS.length];

    private final TaskCountListener mTaskCountListener = taskCount -> Tools.runOnUiThread(() -> {
        if (!isAdded()) return;
        mProgressOverlay.setVisibility(taskCount > 0 ? View.VISIBLE : View.GONE);
        if (taskCount == 0) {
            refreshState();
        }
    });

    public MainMenuFragment() {
        super(R.layout.fragment_launcher);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mNickView = view.findViewById(R.id.opc_nick);
        mMemoryValue = view.findViewById(R.id.opc_memory_value);
        mMemoryBar = view.findViewById(R.id.opc_memory);
        mProgressOverlay = view.findViewById(R.id.opc_progress_overlay);
        mProgressBar = view.findViewById(R.id.opc_progress_bar);
        mProgressTitle = view.findViewById(R.id.opc_progress_title);
        mProgressText = view.findViewById(R.id.opc_progress_text);
        mLoginOverlay = view.findViewById(R.id.opc_login_overlay);
        mLoginText = view.findViewById(R.id.opc_login_text);
        mLoginNick = view.findViewById(R.id.opc_login_nick);
        mLoginDone = view.findViewById(R.id.opc_login_done);

        Button playButton = view.findViewById(R.id.play_button);
        Button singleButton = view.findViewById(R.id.opc_single_button);
        Button skinButton = view.findViewById(R.id.opc_skin_button);
        ImageButton controlsButton = view.findViewById(R.id.opc_controls_button);
        Button msButton = view.findViewById(R.id.opc_ms_button);

        playButton.setOnClickListener(v -> launch(true));
        singleButton.setOnClickListener(v -> launch(false));
        skinButton.setOnClickListener(v -> new AlertDialog.Builder(requireContext())
                .setTitle(R.string.opc_skin_title)
                .setMessage(R.string.opc_skin_text)
                .setPositiveButton(R.string.opc_ok, null)
                .show());
        controlsButton.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), CustomControlsActivity.class)));
        mNickView.setOnClickListener(v -> showNickDialog());

        msButton.setOnClickListener(v ->
                Tools.swapFragment(requireActivity(), MicrosoftLoginFragment.class, MicrosoftLoginFragment.TAG, null));
        mLoginDone.setOnClickListener(v -> onLoginDone());

        setupMemory();
        for (int i = 0; i < PROGRESS_KEYS.length; i++) {
            final boolean isInstall = ProgressLayout.INSTALL_MODPACK.equals(PROGRESS_KEYS[i]);
            mProgressListeners[i] = new ProgressListener() {
                @Override
                public void onProgressStarted() {
                    Tools.runOnUiThread(() -> showProgress(isInstall, 0, null));
                }

                @Override
                public void onProgressUpdated(int progress, int resid, Object... va) {
                    Tools.runOnUiThread(() -> {
                        String text = null;
                        if (resid != -1 && isAdded()) {
                            try {
                                text = getString(resid, va);
                            } catch (Exception ignored) {
                            }
                        }
                        showProgress(isInstall, progress, text);
                    });
                }

                @Override
                public void onProgressEnded() {
                }
            };
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        for (int i = 0; i < PROGRESS_KEYS.length; i++) {
            ProgressKeeper.addListener(PROGRESS_KEYS[i], mProgressListeners[i]);
        }
        ProgressKeeper.addTaskCountListener(mTaskCountListener, true);
        refreshState();
        // Microsoft sign-in finishes in the background: refresh shortly after
        View root = getView();
        if (root != null) {
            root.postDelayed(this::refreshState, 1500);
            root.postDelayed(this::refreshState, 5000);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        for (int i = 0; i < PROGRESS_KEYS.length; i++) {
            ProgressKeeper.removeListener(PROGRESS_KEYS[i], mProgressListeners[i]);
        }
        ProgressKeeper.removeTaskCountListener(mTaskCountListener);
    }

    /* ---------------- state ---------------- */

    private void refreshState() {
        if (!isAdded()) return;
        MinecraftAccount account = getSelectedAccount();
        mNickView.setText(account != null ? account.username : getString(R.string.opc_no_nick));

        boolean online = Tools.hasOnlineProfile();
        mLoginText.setText(online ? R.string.opc_login_text_done : R.string.opc_login_text);
        mLoginNick.setEnabled(online);
        mLoginDone.setEnabled(online);
        if (online && mLoginNick.getText().length() == 0 && account != null) {
            mLoginNick.setText(account.username);
        }
        boolean needLogin = account == null || !online;
        if (!needLogin) mLoginDismissed = false;
        mLoginOverlay.setVisibility(needLogin && !mLoginDismissed ? View.VISIBLE : View.GONE);

        selectOpcProfile();
    }

    @Nullable
    private MinecraftAccount getSelectedAccount() {
        mcAccountSpinner spinner = getSpinner();
        return spinner == null ? null : spinner.getSelectedAccount();
    }

    @Nullable
    private mcAccountSpinner getSpinner() {
        if (getActivity() instanceof LauncherActivity) {
            return ((LauncherActivity) getActivity()).getAccountSpinner();
        }
        return null;
    }

    /** Make our modpack profile the selected one; install it if it is missing or incomplete. */
    private boolean selectOpcProfile() {
        if (!LauncherActivity.isOpcPackInstalled()) {
            if (ProgressKeeper.getTaskCount() == 0 && getActivity() instanceof LauncherActivity) {
                ((LauncherActivity) getActivity()).installBundledPackIfNeeded();
            }
            return false;
        }
        for (Map.Entry<String, MinecraftProfile> entry : LauncherProfiles.mainProfileJson.profiles.entrySet()) {
            MinecraftProfile profile = entry.getValue();
            if (profile != null && OPC_PROFILE_NAME.equals(profile.name)) {
                String current = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "");
                if (!entry.getKey().equals(current)) {
                    LauncherPreferences.DEFAULT_PREF.edit()
                            .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, entry.getKey())
                            .apply();
                }
                return true;
            }
        }
        return false;
    }

    /* ---------------- actions ---------------- */

    private void launch(boolean joinServer) {
        if (ProgressKeeper.getTaskCount() > 0) {
            Toast.makeText(requireContext(), R.string.opc_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        if (getSelectedAccount() == null || !Tools.hasOnlineProfile()) {
            mLoginDismissed = false;
            refreshState();
            return;
        }
        if (!selectOpcProfile()) {
            Toast.makeText(requireContext(), R.string.opc_not_installed, Toast.LENGTH_LONG).show();
            if (getActivity() instanceof LauncherActivity) ((LauncherActivity) getActivity()).reinstallBundledPack();
            return;
        }
        Tools.OPC_JOIN_SERVER = joinServer;
        ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
    }

    private void showNickDialog() {
        if (!Tools.hasOnlineProfile()) {
            mLoginDismissed = false;
            refreshState();
            return;
        }
        EditText input = new EditText(requireContext());
        input.setSingleLine(true);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(16)});
        MinecraftAccount account = getSelectedAccount();
        if (account != null) input.setText(account.username);
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.opc_nick_dialog_title)
                .setView(input)
                .setPositiveButton(R.string.opc_ok, (d, w) -> applyNick(input.getText().toString().trim()))
                .setNegativeButton(R.string.opc_cancel, null)
                .show();
    }

    private void onLoginDone() {
        if (!Tools.hasOnlineProfile()) {
            Toast.makeText(requireContext(), R.string.opc_login_need_ms, Toast.LENGTH_SHORT).show();
            return;
        }
        String nick = mLoginNick.getText().toString().trim();
        if (nick.isEmpty()) {
            mLoginDismissed = true;
            refreshState();
            return;
        }
        if (applyNick(nick)) {
            mLoginDismissed = true;
            refreshState();
        }
    }

    /** Select an existing account with this name, or create a local one. */
    @SuppressLint("ApplySharedPref")
    private boolean applyNick(String nick) {
        if (!NICK_PATTERN.matcher(nick).matches()) {
            Toast.makeText(requireContext(), R.string.opc_bad_nick, Toast.LENGTH_LONG).show();
            return false;
        }
        mcAccountSpinner spinner = getSpinner();
        if (spinner == null) return false;
        File accountFile = new File(Tools.DIR_ACCOUNT_NEW, nick + ".json");
        if (accountFile.exists()) {
            spinner.selectAccountByName(nick);
        } else {
            // Same path the original "local account" screen uses
            ExtraCore.setValue(ExtraConstants.MOJANG_LOGIN_TODO, new String[]{nick, ""});
        }
        View root = getView();
        if (root != null) root.postDelayed(this::refreshState, 300);
        return true;
    }

    /* ---------------- memory ---------------- */

    private void setupMemory() {
        int total = Tools.getTotalDeviceMemory(requireContext());
        // Leave room for Android itself
        mMaxMemory = Math.max(MIN_MEMORY_MB, Math.min(total - 1536, 16384));
        int steps = (mMaxMemory - MIN_MEMORY_MB) / MEMORY_STEP_MB;
        mMemoryBar.setMax(Math.max(steps, 1));
        int current = LauncherPreferences.DEFAULT_PREF.getInt("allocation", LauncherPreferences.PREF_RAM_ALLOCATION);
        current = Math.max(MIN_MEMORY_MB, Math.min(current, mMaxMemory));
        mMemoryBar.setProgress((current - MIN_MEMORY_MB) / MEMORY_STEP_MB);
        showMemory(current);
        mMemoryBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                showMemory(MIN_MEMORY_MB + progress * MEMORY_STEP_MB);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int value = MIN_MEMORY_MB + seekBar.getProgress() * MEMORY_STEP_MB;
                LauncherPreferences.DEFAULT_PREF.edit().putInt("allocation", value).apply();
                LauncherPreferences.PREF_RAM_ALLOCATION = value;
            }
        });
    }

    private void showMemory(int megabytes) {
        mMemoryValue.setText(String.format(Locale.US, getString(R.string.opc_memory_value), megabytes / 1024f));
    }

    /* ---------------- progress ---------------- */

    private void showProgress(boolean install, int progress, @Nullable String text) {
        if (!isAdded()) return;
        mProgressOverlay.setVisibility(View.VISIBLE);
        mProgressTitle.setText(install ? R.string.opc_install_title : R.string.opc_download_title);
        mProgressBar.setIndeterminate(progress <= 0);
        if (progress > 0) mProgressBar.setProgress(progress);
        if (text != null) mProgressText.setText(text);
    }
}
