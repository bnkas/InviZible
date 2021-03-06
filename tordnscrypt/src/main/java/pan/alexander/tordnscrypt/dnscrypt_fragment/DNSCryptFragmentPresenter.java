package pan.alexander.tordnscrypt.dnscrypt_fragment;

/*
    This file is part of InviZible Pro.

    InviZible Pro is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    InviZible Pro is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with InviZible Pro.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2019-2020 by Garmatin Oleksandr invizible.soft@gmail.com
*/

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.text.Html;
import android.util.Log;
import android.widget.Toast;

import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceManager;

import java.util.LinkedList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import pan.alexander.tordnscrypt.MainActivity;
import pan.alexander.tordnscrypt.R;
import pan.alexander.tordnscrypt.dialogs.NotificationDialogFragment;
import pan.alexander.tordnscrypt.dialogs.NotificationHelper;
import pan.alexander.tordnscrypt.iptables.ModulesIptablesRules;
import pan.alexander.tordnscrypt.modules.ModulesAux;
import pan.alexander.tordnscrypt.modules.ModulesKiller;
import pan.alexander.tordnscrypt.modules.ModulesRunner;
import pan.alexander.tordnscrypt.modules.ModulesStatus;
import pan.alexander.tordnscrypt.settings.PathVars;
import pan.alexander.tordnscrypt.utils.OwnFileReader;
import pan.alexander.tordnscrypt.utils.PrefManager;
import pan.alexander.tordnscrypt.utils.enums.ModuleState;
import pan.alexander.tordnscrypt.vpn.Rule;
import pan.alexander.tordnscrypt.vpn.Util;
import pan.alexander.tordnscrypt.vpn.service.ServiceVPN;
import pan.alexander.tordnscrypt.vpn.service.ServiceVPNHandler;
import pan.alexander.tordnscrypt.vpn.service.ServiceVPNHelper;

import static pan.alexander.tordnscrypt.TopFragment.appVersion;
import static pan.alexander.tordnscrypt.utils.RootExecService.LOG_TAG;
import static pan.alexander.tordnscrypt.utils.enums.ModuleState.RESTARTING;
import static pan.alexander.tordnscrypt.utils.enums.ModuleState.RUNNING;
import static pan.alexander.tordnscrypt.utils.enums.ModuleState.STARTING;
import static pan.alexander.tordnscrypt.utils.enums.ModuleState.STOPPED;
import static pan.alexander.tordnscrypt.utils.enums.ModuleState.STOPPING;
import static pan.alexander.tordnscrypt.utils.enums.OperationMode.ROOT_MODE;
import static pan.alexander.tordnscrypt.utils.enums.OperationMode.VPN_MODE;

public class DNSCryptFragmentPresenter implements DNSCryptFragmentPresenterCallbacks {
    private boolean bound;

    private int displayLogPeriod = -1;

    private DNSCryptFragmentView view;
    private Timer timer = null;
    private volatile OwnFileReader logFile;
    private ModulesStatus modulesStatus;
    private ModuleState fixedModuleState;
    private ServiceConnection serviceConnection;
    private ServiceVPN serviceVPN;
    private volatile LinkedList<DNSQueryLogRecord> savedDNSQueryRawRecords;
    private volatile DNSQueryLogRecords dnsQueryLogRecords;
    private boolean torTethering;

    public DNSCryptFragmentPresenter(DNSCryptFragmentView view) {
        this.view = view;
    }

    public void onStart(Context context) {
        if (context == null || view == null) {
            return;
        }

        PathVars pathVars = PathVars.getInstance(context);
        String appDataDir = pathVars.getAppDataDir();

        modulesStatus = ModulesStatus.getInstance();

        savedDNSQueryRawRecords = new LinkedList<>();

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        torTethering = sharedPreferences.getBoolean("pref_common_tor_tethering", false);

        logFile = new OwnFileReader(context, appDataDir + "/logs/DnsCrypt.log");

        if (isDNSCryptInstalled(context)) {
            setDNSCryptInstalled(true);

            if (modulesStatus.getDnsCryptState() == STOPPING) {
                setDnsCryptStopping();

                displayLog(1000);
            } else if (isSavedDNSStatusRunning(context) || modulesStatus.getDnsCryptState() == RUNNING) {
                setDnsCryptRunning();

                if (modulesStatus.getDnsCryptState() != RESTARTING) {
                    modulesStatus.setDnsCryptState(RUNNING);
                }

                displayLog(1000);

            } else {
                setDnsCryptStopped();
                modulesStatus.setDnsCryptState(STOPPED);
            }

        } else {
            setDNSCryptInstalled(false);
        }

        dnsQueryLogRecords = new DNSQueryLogRecords(pathVars.getDNSCryptFallbackRes());
    }

    public void onStop(Context context) {

        if (new PrefManager(context).getBoolPref("DNSCryptSystemDNSAllowed")) {
            if (modulesStatus.getMode() == ROOT_MODE) {
                ModulesIptablesRules.denySystemDNS(context);
            } else if (modulesStatus.getMode() == VPN_MODE) {
                new PrefManager(context).setBoolPref("DNSCryptSystemDNSAllowed", false);
                ServiceVPNHelper.reload("DNSCrypt Deny system DNS", context);
            }
        }

        stopDisplayLog();
        unbindVPNService(context);
        view = null;
    }

    @Override
    public boolean isDNSCryptInstalled(Context context) {
        if (context == null) {
            return false;
        }

        return new PrefManager(context).getBoolPref("DNSCrypt Installed");
    }

    @Override
    public boolean isSavedDNSStatusRunning(Context context) {
        return new PrefManager(context).getBoolPref("DNSCrypt Running");
    }

    @Override
    public void saveDNSStatusRunning(Context context, boolean running) {
        new PrefManager(context).setBoolPref("DNSCrypt Running", running);
    }

    @Override
    public void displayLog(int period) {

        if (period == displayLogPeriod) {
            return;
        }

        displayLogPeriod = period;

        if (timer != null) {
            timer.purge();
            timer.cancel();
        }

        timer = new Timer();

        timer.schedule(new TimerTask() {
            int loop = 0;
            String previousLastLines = "";

            @Override
            public void run() {
                try {
                    if (view == null || view.getFragmentActivity() == null || logFile == null) {
                        return;
                    }

                    final String lastLines = logFile.readLastLines();

                    if (++loop > 120) {
                        loop = 0;
                        displayLog(10000);
                    }

                    final boolean displayed = displayDnsResponses(lastLines);

                    if (view == null || view.getFragmentActivity() == null || logFile == null) {
                        return;
                    }

                    view.getFragmentActivity().runOnUiThread(() -> {

                        if (view == null || view.getFragmentActivity() == null || lastLines == null || lastLines.isEmpty()) {
                            return;
                        }

                        if (!previousLastLines.contentEquals(lastLines)) {

                            dnsCryptStartedSuccessfully(lastLines);

                            dnsCryptStartedWithError(view.getFragmentActivity(), lastLines);

                            if (!displayed) {
                                view.setDNSCryptLogViewText(Html.fromHtml(lastLines));
                            }

                            previousLastLines = lastLines;
                        }

                        refreshDNSCryptState(view.getFragmentActivity());

                    });

                } catch (Exception e) {
                    Log.e(LOG_TAG, "DNSCryptFragmentPresenter timer run() exception " + e.getMessage() + " " + e.getCause());
                }
            }

        }, 1000, period);

    }

    @Override
    public void stopDisplayLog() {
        if (timer != null) {
            timer.purge();
            timer.cancel();
            timer = null;

            displayLogPeriod = -1;
        }
    }

    private void setDnsCryptStarting() {
        if (view == null) {
            return;
        }

        view.setDNSCryptStatus(R.string.tvDNSStarting, R.color.textModuleStatusColorStarting);
    }

    @Override
    public void setDnsCryptRunning() {
        if (view == null) {
            return;
        }

        view.setDNSCryptStatus(R.string.tvDNSRunning, R.color.textModuleStatusColorRunning);
        view.setStartButtonText(R.string.btnDNSCryptStop);

        if (new PrefManager(view.getFragmentActivity()).getBoolPref("DNSCryptSystemDNSAllowed")) {
            if (modulesStatus.getMode() == ROOT_MODE) {
                ModulesIptablesRules.denySystemDNS(view.getFragmentActivity());
            } else if (modulesStatus.getMode() == VPN_MODE) {
                new PrefManager(view.getFragmentActivity()).setBoolPref("DNSCryptSystemDNSAllowed", false);
                ServiceVPNHelper.reload("DNSCrypt Deny system DNS", view.getFragmentActivity());
            }

        }
    }

    private void setDnsCryptStopping() {
        if (view == null) {
            return;
        }

        view.setDNSCryptStatus(R.string.tvDNSStopping, R.color.textModuleStatusColorStopping);
    }

    @Override
    public void setDnsCryptStopped() {
        if (view == null) {
            return;
        }

        view.setDNSCryptStatus(R.string.tvDNSStop, R.color.textModuleStatusColorStopped);
        view.setStartButtonText(R.string.btnDNSCryptStart);
        view.setDNSCryptLogViewText();
    }

    @Override
    public void setDnsCryptInstalling() {
        if (view == null) {
            return;
        }

        view.setDNSCryptStatus(R.string.tvDNSInstalling, R.color.textModuleStatusColorInstalling);
    }

    @Override
    public void setDnsCryptInstalled() {
        if (view == null) {
            return;
        }

        view.setDNSCryptStatus(R.string.tvDNSInstalled, R.color.textModuleStatusColorInstalled);
    }

    @Override
    public void setDNSCryptStartButtonEnabled(boolean enabled) {
        if (view == null) {
            return;
        }

        view.setDNSCryptStartButtonEnabled(enabled);
    }

    @Override
    public void setDNSCryptProgressBarIndeterminate(boolean indeterminate) {
        if (view == null) {
            return;
        }

        view.setDNSCryptProgressBarIndeterminate(indeterminate);
    }

    private void setDNSCryptInstalled(boolean installed) {
        if (view == null) {
            return;
        }

        if (installed) {
            view.setDNSCryptStartButtonEnabled(true);
        } else {
            view.setDNSCryptStatus(R.string.tvDNSNotInstalled, R.color.textModuleStatusColorAlert);
        }
    }

    @Override
    public void setDnsCryptSomethingWrong() {
        if (view == null) {
            return;
        }

        view.setDNSCryptStatus(R.string.wrong, R.color.textModuleStatusColorAlert);
    }

    private void dnsCryptStartedSuccessfully(String lines) {

        if (view == null || modulesStatus == null) {
            return;
        }

        if ((modulesStatus.getDnsCryptState() == STARTING
                || modulesStatus.getDnsCryptState() == RUNNING)
                && lines.contains("lowest initial latency")) {

            if (!modulesStatus.isUseModulesWithRoot()) {
                view.setDNSCryptProgressBarIndeterminate(false);
            }

            setDnsCryptRunning();
        }
    }

    private void dnsCryptStartedWithError(Context context, String lastLines) {

        if (context == null || view == null) {
            return;
        }

        FragmentManager fragmentManager = view.getFragmentFragmentManager();

        if ((lastLines.contains("connect: connection refused")
                || lastLines.contains("ERROR"))
                && !lastLines.contains(" OK ")) {
            Log.e(LOG_TAG, "DNSCrypt Error: " + lastLines);

            if (fragmentManager != null) {
                NotificationHelper notificationHelper = NotificationHelper.setHelperMessage(
                        context, context.getText(R.string.helper_dnscrypt_no_internet).toString(), "helper_dnscrypt_no_internet");
                if (notificationHelper != null) {
                    notificationHelper.show(fragmentManager, NotificationHelper.TAG_HELPER);
                }
            }

        } else if (lastLines.contains("[CRITICAL]") && lastLines.contains("[FATAL]")) {

            if (fragmentManager != null) {
                NotificationHelper notificationHelper = NotificationHelper.setHelperMessage(
                        context, context.getText(R.string.helper_dnscrypt_no_internet).toString(), "helper_dnscrypt_no_internet");
                if (notificationHelper != null) {
                    notificationHelper.show(fragmentManager, NotificationHelper.TAG_HELPER);
                }
            }

            Log.e(LOG_TAG, "DNSCrypt FATAL Error: " + lastLines);

            stopDNSCrypt(context);
        }
    }

    private boolean displayDnsResponses(String savedLines) {

        if (view == null || modulesStatus == null) {
            return false;
        }

        if (modulesStatus.getMode() != VPN_MODE) {
            if (!savedDNSQueryRawRecords.isEmpty() && view != null && view.getFragmentActivity() != null) {
                savedDNSQueryRawRecords.clear();
                view.getFragmentActivity().runOnUiThread(() -> {
                    if (view != null && view.getFragmentActivity() != null && logFile != null) {
                        view.setDNSCryptLogViewText(Html.fromHtml(logFile.readLastLines()));
                    }
                });
                return true;
            } else {
                return false;
            }

        } else if (view != null && view.getFragmentActivity() != null && modulesStatus.getMode() == VPN_MODE && !bound) {
            bindToVPNService(view.getFragmentActivity());
            return false;
        }

        if (modulesStatus.getDnsCryptState() == RESTARTING) {
            clearDnsQueryRecords();
            savedDNSQueryRawRecords.clear();
            return false;
        }

        lockDnsQueryRawRecordsListForRead(true);

        LinkedList<DNSQueryLogRecord> dnsQueryRawRecords = getDnsQueryRawRecords();

        if (dnsQueryRawRecords.equals(savedDNSQueryRawRecords) || dnsQueryRawRecords.isEmpty()) {
            lockDnsQueryRawRecordsListForRead(false);
            return false;
        }

        savedDNSQueryRawRecords = new LinkedList<>(dnsQueryRawRecords);

        lockDnsQueryRawRecordsListForRead(false);

        LinkedList<DNSQueryLogRecord> dnsQueryLogRecords = dnsQueryRawRecordsToLogRecords(savedDNSQueryRawRecords);

        DNSQueryLogRecord record;
        StringBuilder lines = new StringBuilder();

        lines.append(savedLines);

        lines.append("<br />");

        for (int i = 0; i < dnsQueryLogRecords.size(); i++) {
            record = dnsQueryLogRecords.get(i);

            if (appVersion.startsWith("g") && record.getBlockedByIpv6()) {
                continue;
            }

            if (record.getBlocked()) {
                if (!record.getAName().isEmpty()) {
                    lines.append("<font color=#f08080>").append(record.getAName().toLowerCase());

                    if (record.getBlockedByIpv6()) {
                        lines.append(" ipv6");
                    }

                    lines.append("</font>");
                } else {
                    lines.append("<font color=#f08080>").append(record.getQName().toLowerCase()).append("</font>");
                }
            } else {

                if (record.getUid() != -1000 && !record.getIp().isEmpty()) {
                    lines.append("<font color=#E7AD42>");
                } else {
                    lines.append("<font color=#009688>");
                }

                if (record.getUid() != -1000) {
                    if (view != null && view.getFragmentActivity() != null) {

                        String appName = "";

                        List<Rule> appList = ServiceVPNHandler.getAppsList();

                        if (appList != null) {
                            for (Rule rule : appList) {
                                if (rule.uid == record.getUid()) {
                                    appName = rule.appName;
                                    break;
                                }
                            }
                        }

                        if (appName.isEmpty() || record.getUid() == 1000) {
                            appName = view.getFragmentActivity().getPackageManager().getNameForUid(record.getUid());
                        }

                        if (appName != null && !appName.isEmpty()) {
                            lines.append("<b>").append(appName).append("</b>").append(" -> ");
                        } else if (appName == null && !torTethering) {
                            lines.append("<b>").append("Unknown system traffic").append("</b>").append(" -> ");
                        }
                    }
                }

                if (!record.getAName().isEmpty()) {
                    lines.append(record.getAName().toLowerCase());
                }

                if (!record.getCName().isEmpty()) {
                    lines.append(" -> ").append(record.getCName().toLowerCase());
                }

                if (!record.getIp().isEmpty()) {
                    if (record.getUid() == -1000) {
                        lines.append(" -> ");
                    }
                    lines.append(record.getIp());
                }

                lines.append("</font>");
            }

            if (i < dnsQueryLogRecords.size() - 1) {
                lines.append("<br />");
            }
        }

        if (view != null && view.getFragmentActivity() != null) {
            view.getFragmentActivity().runOnUiThread(() -> {
                if (view != null && view.getFragmentActivity() != null) {
                    view.setDNSCryptLogViewText(Html.fromHtml(lines.toString()));
                } else {
                    savedDNSQueryRawRecords.clear();
                }
            });
        }

        return true;
    }

    private LinkedList<DNSQueryLogRecord> getDnsQueryRawRecords() {
        if (serviceVPN != null) {
            return serviceVPN.getDnsQueryRawRecords();
        }
        return new LinkedList<>();
    }

    private void clearDnsQueryRecords() {
        if (serviceVPN != null) {
            serviceVPN.clearDnsQueryRawRecords();
        }
    }

    private void lockDnsQueryRawRecordsListForRead(boolean lock) {
        if (serviceVPN != null) {
            serviceVPN.lockDnsQueryRawRecordsListForRead(lock);
        }
    }

    private LinkedList<DNSQueryLogRecord> dnsQueryRawRecordsToLogRecords(LinkedList<DNSQueryLogRecord> dnsQueryRawRecords) {
        return dnsQueryLogRecords.convertRecords(dnsQueryRawRecords);
    }

    @Override
    public void refreshDNSCryptState(Context context) {

        if (context == null || modulesStatus == null || view == null) {
            return;
        }

        ModuleState currentModuleState = modulesStatus.getDnsCryptState();

        if (currentModuleState.equals(fixedModuleState) && currentModuleState != STOPPED) {
            return;
        }

        if (currentModuleState == STARTING) {

            displayLog(1000);

        } else if (currentModuleState == RUNNING && view.getFragmentActivity() != null) {

            ServiceVPNHelper.prepareVPNServiceIfRequired(view.getFragmentActivity(), modulesStatus);

            view.setDNSCryptStartButtonEnabled(true);

            saveDNSStatusRunning(context, true);

            view.setStartButtonText(R.string.btnDNSCryptStop);

            displayLog(5000);

            if (modulesStatus.getMode() == VPN_MODE && !bound) {
                bindToVPNService(context);
            }

        } else if (currentModuleState == STOPPED) {

            stopDisplayLog();

            if (isSavedDNSStatusRunning(context)) {
                setDNSCryptStoppedBySystem(context);
            } else {
                setDnsCryptStopped();
            }

            view.setDNSCryptProgressBarIndeterminate(false);

            saveDNSStatusRunning(context, false);

            view.setDNSCryptStartButtonEnabled(true);
        }

        fixedModuleState = currentModuleState;
    }

    private void setDNSCryptStoppedBySystem(Context context) {
        if (view == null) {
            return;
        }

        setDnsCryptStopped();

        FragmentManager fragmentManager = view.getFragmentFragmentManager();

        if (context != null && modulesStatus != null) {

            modulesStatus.setDnsCryptState(STOPPED);

            ModulesAux.requestModulesStatusUpdate(context);

            if (fragmentManager != null) {
                DialogFragment notification = NotificationDialogFragment.newInstance(R.string.helper_dnscrypt_stopped);
                notification.show(fragmentManager, "NotificationDialogFragment");
            }

            Log.e(LOG_TAG, context.getText(R.string.helper_dnscrypt_stopped).toString());
        }

    }

    private void runDNSCrypt(Context context) {
        if (context == null || view == null) {
            return;
        }

        if (Util.isPrivateDns(context)) {
            FragmentManager fragmentManager = view.getFragmentFragmentManager();
            if (fragmentManager != null) {
                NotificationHelper notificationHelper = NotificationHelper.setHelperMessage(
                        context, context.getText(R.string.helper_dnscrypt_private_dns).toString(), "helper_dnscrypt_private_dns");
                if (notificationHelper != null) {
                    notificationHelper.show(fragmentManager, NotificationHelper.TAG_HELPER);
                }
            }
        }

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);

        if (!sharedPreferences.getBoolean("ignore_system_dns", false)) {
            new PrefManager(context).setBoolPref("DNSCryptSystemDNSAllowed", true);
        }

        ModulesRunner.runDNSCrypt(context);
    }

    private void stopDNSCrypt(Context context) {
        if (context == null) {
            return;
        }

        if (modulesStatus.getMode() == VPN_MODE) {
            clearDnsQueryRecords();
        }

        ModulesKiller.stopDNSCrypt(context);
    }

    private void bindToVPNService(Context context) {
        serviceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                serviceVPN = ((ServiceVPN.VPNBinder) service).getService();
                bound = true;
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                bound = false;
            }
        };

        if (context != null) {
            Intent intent = new Intent(context, ServiceVPN.class);
            context.bindService(intent, serviceConnection, 0);
        }
    }

    private void unbindVPNService(Context context) {
        if (bound && serviceConnection != null && context != null) {
            context.unbindService(serviceConnection);
            bound = false;
        }
    }

    public void startButtonOnClick(Context context) {
        if (context == null || view == null || modulesStatus == null) {
            return;
        }

        if (((MainActivity) context).childLockActive) {
            Toast.makeText(context, context.getText(R.string.action_mode_dialog_locked), Toast.LENGTH_LONG).show();
            return;
        }


        view.setDNSCryptStartButtonEnabled(false);


        if (new PrefManager(context).getBoolPref("Tor Running")
                && !new PrefManager(context).getBoolPref("DNSCrypt Running")) {

            if (modulesStatus.isContextUIDUpdateRequested()) {
                Toast.makeText(context, R.string.please_wait, Toast.LENGTH_SHORT).show();
                view.setDNSCryptStartButtonEnabled(true);
                return;
            }

            setDnsCryptStarting();

            runDNSCrypt(context);

            displayLog(1000);
        } else if (!new PrefManager(context).getBoolPref("Tor Running")
                && !new PrefManager(context).getBoolPref("DNSCrypt Running")) {

            if (modulesStatus.isContextUIDUpdateRequested()) {
                Toast.makeText(context, R.string.please_wait, Toast.LENGTH_SHORT).show();
                view.setDNSCryptStartButtonEnabled(true);
                return;
            }

            setDnsCryptStarting();

            runDNSCrypt(context);

            displayLog(1000);
        } else if (!new PrefManager(context).getBoolPref("Tor Running")
                && new PrefManager(context).getBoolPref("DNSCrypt Running")) {
            setDnsCryptStopping();
            stopDNSCrypt(context);
        } else if (new PrefManager(context).getBoolPref("Tor Running")
                && new PrefManager(context).getBoolPref("DNSCrypt Running")) {

            setDnsCryptStopping();
            stopDNSCrypt(context);
        }

        view.setDNSCryptProgressBarIndeterminate(true);
    }

}
