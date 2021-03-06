package pan.alexander.tordnscrypt.itpd_fragment;
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


import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.IntentFilter;
import android.os.Bundle;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import android.text.Spanned;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import pan.alexander.tordnscrypt.MainActivity;
import pan.alexander.tordnscrypt.R;
import pan.alexander.tordnscrypt.utils.RootExecService;

import static pan.alexander.tordnscrypt.TopFragment.ITPDVersion;
import static pan.alexander.tordnscrypt.TopFragment.TOP_BROADCAST;
import static pan.alexander.tordnscrypt.utils.RootExecService.LOG_TAG;


/**
 * A simple {@link Fragment} subclass.
 */
public class ITPDRunFragment extends Fragment implements ITPDFragmentView, View.OnClickListener {

    private Button btnITPDStart;
    private TextView tvITPDStatus;
    private ProgressBar pbITPD;
    private TextView tvITPDLog;
    private TextView tvITPDinfoLog;

    private ITPDFragmentPresenter presenter;
    private ITPDFragmentReceiver receiver;

    public ITPDRunFragment() {
    }

    @SuppressLint("SetTextI18n")
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        View view = inflater.inflate(R.layout.fragment_itpd_run, container, false);

        btnITPDStart = view.findViewById(R.id.btnITPDStart);

        //Not required for a portrait orientation, so return
        if (btnITPDStart == null) {
            return view;
        }

        btnITPDStart.setOnClickListener(this);

        pbITPD = view.findViewById(R.id.pbITPD);

        tvITPDLog = view.findViewById(R.id.tvITPDLog);
        tvITPDLog.setMovementMethod(ScrollingMovementMethod.getInstance());

        tvITPDinfoLog = view.findViewById(R.id.tvITPDinfoLog);
        tvITPDinfoLog.setMovementMethod(ScrollingMovementMethod.getInstance());

        tvITPDStatus = view.findViewById(R.id.tvI2PDStatus);

        setITPDLogViewText();

        return view;
    }

    @Override
    public void onStart() {
        super.onStart();

        //MainFragment do this job for portrait orientation, so return
        if (btnITPDStart == null) {
            return;
        }

        presenter = new ITPDFragmentPresenter(this);

        receiver = new ITPDFragmentReceiver(this, presenter);

        if (getActivity() != null) {
            IntentFilter intentFilterBckgIntSer = new IntentFilter(RootExecService.COMMAND_RESULT);
            IntentFilter intentFilterTopFrg = new IntentFilter(TOP_BROADCAST);

            getActivity().registerReceiver(receiver, intentFilterBckgIntSer);
            getActivity().registerReceiver(receiver, intentFilterTopFrg);

            presenter.onStart(getActivity());
        }

    }

    @Override
    public void onStop() {
        super.onStop();

        try {
            if (getActivity() != null && receiver != null) {
                getActivity().unregisterReceiver(receiver);
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "ITPDRunFragment onStop exception " + e.getMessage() + " " + e.getCause());
        }

        if (presenter != null) {
            presenter.onStop();
        }

    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.btnITPDStart) {
            presenter.startButtonOnClick(getActivity());
        }
    }

    @Override
    public void setITPDStatus(int resourceText, int resourceColor) {
        tvITPDStatus.setText(resourceText);
        tvITPDStatus.setTextColor(getResources().getColor(resourceColor));
    }

    @Override
    public void setITPDStartButtonEnabled(boolean enabled) {
        if (btnITPDStart.isEnabled() && !enabled) {
            btnITPDStart.setEnabled(false);
        } else if (!btnITPDStart.isEnabled() && enabled) {
            btnITPDStart.setEnabled(true);
        }
    }

    @Override
    public void setStartButtonText(int textId) {
        btnITPDStart.setText(textId);
    }

    @Override
    public void setITPDProgressBarIndeterminate(boolean indeterminate) {
        if (!pbITPD.isIndeterminate() && indeterminate) {
            pbITPD.setIndeterminate(true);
        } else if (pbITPD.isIndeterminate() && !indeterminate){
            pbITPD.setIndeterminate(false);
        }
    }

    @Override
    @SuppressLint("SetTextI18n")
    public void setITPDLogViewText() {
        tvITPDLog.setText(getText(R.string.tvITPDDefaultLog) + " " + ITPDVersion);
    }

    @Override
    public void setITPDLogViewText(Spanned text) {
        tvITPDLog.setText(text);
    }

    @Override
    @SuppressLint("SetTextI18n")
    public void setITPDInfoLogText() {
        tvITPDinfoLog.setText("");
    }

    @Override
    public void setITPDInfoLogText(Spanned text) {
        tvITPDinfoLog.setText(text);
    }

    @Override
    public Activity getFragmentActivity() {
        return getActivity();
    }

    @Override
    public FragmentManager getFragmentFragmentManager() {
        return getFragmentManager();
    }

    public ITPDFragmentPresenterCallbacks getPresenter() {
        if (presenter == null && getActivity() instanceof MainActivity && ((MainActivity)getActivity()).getMainFragment() != null) {
            presenter = ((MainActivity)getActivity()).getMainFragment().getITPDFragmentPresenter();
        }

        return presenter;
    }
}
