/* Copyright 2017 Andrew Dawson
 *
 * This file is part of Tusky.
 *
 * Tusky is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * Tusky is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Tusky. If not, see
 * <http://www.gnu.org/licenses/>. */

package com.keylesspalace.tusky;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.design.widget.Snackbar;
import android.support.v7.app.ActionBar;
import android.support.v7.widget.DividerItemDecoration;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import com.android.volley.AuthFailureError;
import com.android.volley.Request;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonArrayRequest;
import com.android.volley.toolbox.JsonObjectRequest;
import com.keylesspalace.tusky.entity.Status;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;

public class ReportActivity extends BaseActivity {
    private static final String TAG = "ReportActivity"; // logging tag and Volley request tag

    private String domain;
    private String accessToken;
    private View anyView; // what Snackbar will use to find the root view
    private ReportAdapter adapter;
    private boolean reportAlreadyInFlight;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_report);

        Intent intent = getIntent();
        final String accountId = intent.getStringExtra("account_id");
        String accountUsername = intent.getStringExtra("account_username");
        String statusId = intent.getStringExtra("status_id");
        String statusContent = intent.getStringExtra("status_content");

        SharedPreferences preferences = getSharedPreferences(
                getString(R.string.preferences_file_key), Context.MODE_PRIVATE);
        domain = preferences.getString("domain", null);
        accessToken = preferences.getString("accessToken", null);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        ActionBar bar = getSupportActionBar();
        if (bar != null) {
            String title = String.format(getString(R.string.report_username_format),
                    accountUsername);
            bar.setTitle(title);
        }
        anyView = toolbar;

        final RecyclerView recyclerView = (RecyclerView) findViewById(R.id.report_recycler_view);
        recyclerView.setHasFixedSize(true);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        recyclerView.setLayoutManager(layoutManager);
        adapter = new ReportAdapter();
        recyclerView.setAdapter(adapter);

        DividerItemDecoration divider = new DividerItemDecoration(
                this, layoutManager.getOrientation());
        Drawable drawable = ThemeUtils.getDrawable(this, R.attr.report_status_divider_drawable,
                R.drawable.report_status_divider_dark);
        divider.setDrawable(drawable);
        recyclerView.addItemDecoration(divider);

        ReportAdapter.ReportStatus reportStatus = new ReportAdapter.ReportStatus(statusId,
                HtmlUtils.fromHtml(statusContent), true);
        adapter.addItem(reportStatus);

        final EditText comment = (EditText) findViewById(R.id.report_comment);
        Button send = (Button) findViewById(R.id.report_send);
        reportAlreadyInFlight = false;
        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (reportAlreadyInFlight) {
                    return;
                }
                String[] statusIds = adapter.getCheckedStatusIds();
                if (statusIds.length > 0) {
                    reportAlreadyInFlight = true;
                    sendReport(accountId, statusIds, comment.getText().toString());
                } else {
                    comment.setError(getString(R.string.error_report_too_few_statuses));
                }
            }
        });

        fetchRecentStatuses(accountId);
    }

    @Override
    protected void onDestroy() {
        VolleySingleton.getInstance(this).cancelAll(TAG);
        super.onDestroy();
    }

    /* JSONArray has a constructor to take primitive arrays but it's restricted to API level 19 and
         * above, so this is an alternative. */
    private static JSONArray makeStringArrayCompat(String[] stringArray) throws JSONException {
        JSONArray result = new JSONArray();
        for (int i = 0; i < stringArray.length; i++) {
            result.put(i, stringArray[i]);
        }
        return result;
    }

    private void sendReport(final String accountId, final String[] statusIds,
            final String comment) {
        mastodonAPI.report(accountId, Arrays.asList(statusIds), comment).enqueue(new Callback<ResponseBody>() {
            @Override
            public void onResponse(Call<ResponseBody> call, retrofit2.Response<ResponseBody> response) {
                onSendSuccess();
            }

            @Override
            public void onFailure(Call<ResponseBody> call, Throwable t) {
                onSendFailure(accountId, statusIds, comment);
            }
        });
    }

    private void onSendSuccess() {
        Snackbar bar = Snackbar.make(anyView, getString(R.string.confirmation_reported), Snackbar.LENGTH_SHORT);
        bar.show();
        finish();
    }

    private void onSendFailure(final String accountId, final String[] statusIds,
            final String comment) {
        Snackbar.make(anyView, R.string.error_report_unsent, Snackbar.LENGTH_LONG)
                .setAction(R.string.action_retry, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        sendReport(accountId, statusIds, comment);
                    }
                })
                .show();
        reportAlreadyInFlight = false;
    }

    private void fetchRecentStatuses(String accountId) {
        mastodonAPI.accountStatuses(accountId, null, null, null).enqueue(new Callback<List<Status>>() {
            @Override
            public void onResponse(Call<List<Status>> call, retrofit2.Response<List<Status>> response) {
                List<Status> statusList = response.body();
                List<ReportAdapter.ReportStatus> itemList = new ArrayList<>();
                for (Status status : statusList) {
                    if (status.reblog != null) {
                        ReportAdapter.ReportStatus item = new ReportAdapter.ReportStatus(
                                status.id, status.content, false);
                        itemList.add(item);
                    }
                }
                adapter.addItems(itemList);
            }

            @Override
            public void onFailure(Call<List<Status>> call, Throwable t) {
                onFetchStatusesFailure((Exception) t);
            }
        });
    }

    private void onFetchStatusesFailure(Exception exception) {
        Log.e(TAG, "Failed to fetch recent statuses to report. " + exception.getMessage());
    }
}
