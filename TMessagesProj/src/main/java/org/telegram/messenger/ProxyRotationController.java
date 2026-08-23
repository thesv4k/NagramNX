package org.telegram.messenger;

import android.content.SharedPreferences;
import android.os.SystemClock;

import org.telegram.tgnet.ConnectionsManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ProxyRotationController implements NotificationCenter.NotificationCenterDelegate {
    private final static ProxyRotationController INSTANCE = new ProxyRotationController();

    public final static int DEFAULT_TIMEOUT_INDEX = 1;
    public final static List<Integer> ROTATION_TIMEOUTS = Arrays.asList(
            5, 10, 15, 30, 60
    );

    private boolean isCurrentlyChecking;
    private boolean timerScheduled;

    private final Runnable checkProxyAndSwitchRunnable = new Runnable() {
        @Override
        public void run() {
            timerScheduled = false;
            if (!SharedConfig.isProxyEnabled() || !SharedConfig.proxyRotationEnabled || SharedConfig.proxyList.size() <= 1) {
                return;
            }

            int state = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
            if (state == ConnectionsManager.ConnectionStateConnected) {
                return;
            }

            // Current proxy is failing to connect within timeout -> mark current proxy unavailable
            if (SharedConfig.currentProxy != null) {
                SharedConfig.currentProxy.available = false;
            }

            isCurrentlyChecking = true;
            int currentAccount = UserConfig.selectedAccount;
            boolean startedCheck = false;
            long now = SystemClock.elapsedRealtime();

            for (int i = 0; i < SharedConfig.proxyList.size(); i++) {
                final SharedConfig.ProxyInfo proxyInfo = SharedConfig.proxyList.get(i);
                if (proxyInfo.checking || (now - proxyInfo.availableCheckTime < 10 * 1000 && proxyInfo != SharedConfig.currentProxy)) {
                    continue;
                }
                startedCheck = true;
                proxyInfo.checking = true;
                proxyInfo.proxyCheckPingId = ConnectionsManager.getInstance(currentAccount).checkProxy(proxyInfo.address, proxyInfo.port, proxyInfo.username, proxyInfo.password, proxyInfo.secret, time -> AndroidUtilities.runOnUIThread(() -> {
                    proxyInfo.availableCheckTime = SystemClock.elapsedRealtime();
                    proxyInfo.checking = false;
                    if (time == -1) {
                        proxyInfo.available = false;
                        proxyInfo.ping = 0;
                    } else {
                        proxyInfo.ping = time;
                        proxyInfo.available = true;
                    }
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyCheckDone, proxyInfo);
                }));
            }

            if (!startedCheck) {
                isCurrentlyChecking = false;
                switchToAvailable();
            }
        }
    };

    public static void init() {
        INSTANCE.initInternal();
    }

    @SuppressWarnings("ComparatorCombinators")
    private void switchToAvailable() {
        isCurrentlyChecking = false;

        if (!SharedConfig.isProxyEnabled() || !SharedConfig.proxyRotationEnabled || SharedConfig.proxyList.isEmpty()) {
            return;
        }

        List<SharedConfig.ProxyInfo> candidates = new ArrayList<>();
        for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
            if (info != SharedConfig.currentProxy && !info.checking) {
                candidates.add(info);
            }
        }

        if (candidates.isEmpty()) {
            return;
        }

        Collections.sort(candidates, (o1, o2) -> {
            if (o1.available != o2.available) {
                return o1.available ? -1 : 1;
            }
            if (o1.ping > 0 && o2.ping > 0) {
                return Long.compare(o1.ping, o2.ping);
            }
            return 0;
        });

        SharedConfig.ProxyInfo target = candidates.get(0);

        SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
        editor.putString("proxy_ip", target.address);
        editor.putString("proxy_pass", target.password);
        editor.putString("proxy_user", target.username);
        editor.putInt("proxy_port", target.port);
        editor.putString("proxy_secret", target.secret);
        editor.putBoolean("proxy_enabled", true);

        if (!target.secret.isEmpty()) {
            editor.putBoolean("proxy_enabled_calls", false);
        }
        editor.apply();

        SharedConfig.currentProxy = target;
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyChangedByRotation);
        ConnectionsManager.setProxySettings(true, SharedConfig.currentProxy.address, SharedConfig.currentProxy.port, SharedConfig.currentProxy.username, SharedConfig.currentProxy.password, SharedConfig.currentProxy.secret);

        // Schedule next check in case the newly chosen proxy also fails to connect
        scheduleRotationCheck();
    }

    private void scheduleRotationCheck() {
        if (!SharedConfig.isProxyEnabled() || !SharedConfig.proxyRotationEnabled || SharedConfig.proxyList.size() <= 1) {
            return;
        }
        AndroidUtilities.cancelRunOnUIThread(checkProxyAndSwitchRunnable);
        int timeoutIdx = Math.max(0, Math.min(SharedConfig.proxyRotationTimeout, ROTATION_TIMEOUTS.size() - 1));
        long delay = ROTATION_TIMEOUTS.get(timeoutIdx) * 1000L;
        timerScheduled = true;
        AndroidUtilities.runOnUIThread(checkProxyAndSwitchRunnable, delay);
    }

    private void initInternal() {
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            NotificationCenter.getInstance(i).addObserver(this, NotificationCenter.didUpdateConnectionState);
        }
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxyCheckDone);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxySettingsChanged);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.proxyCheckDone) {
            if (!SharedConfig.isProxyEnabled() || !SharedConfig.proxyRotationEnabled || SharedConfig.proxyList.size() <= 1 || !isCurrentlyChecking) {
                return;
            }
            switchToAvailable();
        } else if (id == NotificationCenter.proxySettingsChanged) {
            if (SharedConfig.isProxyEnabled() && SharedConfig.proxyRotationEnabled) {
                scheduleRotationCheck();
            } else {
                AndroidUtilities.cancelRunOnUIThread(checkProxyAndSwitchRunnable);
                timerScheduled = false;
            }
        } else if (id == NotificationCenter.didUpdateConnectionState && account == UserConfig.selectedAccount) {
            if (!SharedConfig.isProxyEnabled() || !SharedConfig.proxyRotationEnabled || SharedConfig.proxyList.size() <= 1) {
                AndroidUtilities.cancelRunOnUIThread(checkProxyAndSwitchRunnable);
                timerScheduled = false;
                return;
            }

            int state = ConnectionsManager.getInstance(account).getConnectionState();

            if (state == ConnectionsManager.ConnectionStateConnected) {
                AndroidUtilities.cancelRunOnUIThread(checkProxyAndSwitchRunnable);
                timerScheduled = false;
            } else if (state == ConnectionsManager.ConnectionStateConnectingToProxy || state == ConnectionsManager.ConnectionStateConnecting || state == ConnectionsManager.ConnectionStateUpdating) {
                if (!timerScheduled) {
                    scheduleRotationCheck();
                }
            }
        }
    }
}
