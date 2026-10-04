package com.tunlian.app;

import android.Manifest;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.tencent.rtmp.ui.TXCloudVideoView;
import com.tencent.trtc.TRTCCloud;
import com.tencent.trtc.TRTCCloudDef;
import com.tencent.trtc.TRTCCloudListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 【2026-10-04】原生音视频会议 v2（对照腾讯会议风格重做 UI）
 * 默认进房静音（防啸叫）；聊天/举手/签到/休息/全体静音走 TRTC 自定义消息与网页端互通；
 * 云录制支持回放列表：下载 / 转写 / 豚链纪要（混元大模型）。
 */
public class MeetingActivity extends AppCompatActivity {

    public static final String EXTRA_ROOM_ID = "room_id";
    public static final int RESULT_FALLBACK = 99;

    private static final String TAG = "MeetingNative";
    private static final int REQ_PERM = 2001;

    private String base = "https://22.heitun.link";
    private int roomId = 0;
    private String selfId = "";
    private String selfName = "";
    private String userSig = "";
    private int sdkAppId = 0;
    private boolean isHost = false;

    private boolean micOn = false;      /* 进房默认静音，防啸叫 */
    private boolean camOn = true;
    private boolean speakerOn = true;
    private boolean inRoom = false;
    private boolean recording = false;

    private TRTCCloud trtc;
    private FrameLayout videoStage;
    private FrameLayout localBox;
    private TXCloudVideoView localView;
    private TextView tvHint;
    private TextView tvMicLabel;
    private TextView tvCamLabel;
    private EditText etChat;
    private LinearLayout chatList;
    private ScrollView chatPanel;
    private AlertDialog loadingDialog;
    private AlertDialog moreDialog;
    private Handler handler;

    private String activeVideoUserId = "";
    private final Map<String, TXCloudVideoView> remoteViews = new LinkedHashMap<>();
    private final List<String> memberSummary = new ArrayList<>();
    private final List<String> memberUids = new ArrayList<>();
    private int checkinCount = 0;

    private final Runnable heartbeatTask = new Runnable() {
        @Override
        public void run() {
            if (!inRoom) return;
            sendHeartbeat();
            handler.postDelayed(this, 5000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_meeting);

        handler = new Handler(Looper.getMainLooper());
        base = trimSlash(getString(R.string.site_url));
        roomId = getIntent().getIntExtra(EXTRA_ROOM_ID, 0);
        if (roomId <= 0) {
            fallback("房间号不正确");
            return;
        }

        videoStage = findViewById(R.id.videoStage);
        localBox = findViewById(R.id.localBox);
        localView = findViewById(R.id.localView);
        tvHint = findViewById(R.id.tvHint);
        tvMicLabel = findViewById(R.id.tvMic);
        tvCamLabel = findViewById(R.id.tvCam);
        etChat = findViewById(R.id.etChat);
        chatList = findViewById(R.id.chatList);
        chatPanel = findViewById(R.id.chatPanel);

        TextView tvRoom = findViewById(R.id.tvRoom);
        tvRoom.setText("TUN" + roomId);

        findViewById(R.id.btnForward).setOnClickListener(v -> shareInvite());
        findViewById(R.id.btnLeave).setOnClickListener(v -> leaveRoom());
        findViewById(R.id.btnMic).setOnClickListener(v -> toggleMic());
        findViewById(R.id.btnCam).setOnClickListener(v -> toggleCam());
        findViewById(R.id.btnShare).setOnClickListener(v ->
                Toast.makeText(this, "屏幕共享开发中，下一版上线", Toast.LENGTH_SHORT).show());
        findViewById(R.id.btnMembers).setOnClickListener(v -> showMembers());
        findViewById(R.id.btnMore).setOnClickListener(v -> showMorePanel());
        findViewById(R.id.btnEnd).setOnClickListener(v -> confirmEndMeeting());
        findViewById(R.id.btnSend).setOnClickListener(v -> sendChatText());
        findViewById(R.id.btnImg).setOnClickListener(v ->
                Toast.makeText(this, "图片聊天即将支持，先发文字吧", Toast.LENGTH_SHORT).show());
        etChat.setOnEditorActionListener((v, actionId, event) -> {
            sendChatText();
            return true;
        });
        localBox.setOnClickListener(v -> toggleCam());

        tvHint.setText("正在进入房间…");
        if (hasAvPermission()) {
            joinRoom();
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
            }, REQ_PERM);
        }
    }

    private boolean hasAvPermission() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                fallback("需要麦克风权限才能开会");
                return;
            }
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                camOn = false;
            }
            joinRoom();
        }
    }

    /* ==================== 进房 ==================== */

    private void joinRoom() {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("roomId", String.valueOf(roomId));
        http("GET", "/index.php/index/meeting/native_join.html", q, (resp, err) -> {
            if (resp == null) {
                fallback("网络异常，改开网页版会议");
                return;
            }
            if (resp.optInt("code", 1) != 0) {
                fallback(resp.optString("msg", "无法进入会议"));
                return;
            }
            JSONObject d = resp.optJSONObject("data");
            if (d == null) {
                fallback("入会信息为空");
                return;
            }
            sdkAppId = d.optInt("sdkAppId", 0);
            selfId = d.optString("userId", "");
            selfName = d.optString("name", "");
            userSig = d.optString("userSig", "");
            isHost = d.optInt("isHost", 0) == 1;
            if (sdkAppId <= 0 || selfId.isEmpty() || userSig.isEmpty() || "null".equals(userSig)) {
                fallback("入会信息不完整");
                return;
            }
            enterTrtcRoom();
        });
    }

    private void enterTrtcRoom() {
        trtc = TRTCCloud.sharedInstance(getApplicationContext());
        trtc.setListener(trtcListener);

        TRTCCloudDef.TRTCParams params = new TRTCCloudDef.TRTCParams();
        params.sdkAppId = sdkAppId;
        params.userId = selfId;
        params.userSig = userSig;
        params.roomId = roomId;
        params.role = TRTCCloudDef.TRTCRoleAnchor;
        trtc.setDefaultStreamRecvMode(true, true);
        trtc.setAudioRoute(TRTCCloudDef.TRTC_AUDIO_ROUTE_SPEAKER);
        trtc.enterRoom(params, TRTCCloudDef.TRTC_APP_SCENE_VIDEOCALL);

        inRoom = true;
        trtc.startLocalAudio(TRTCCloudDef.TRTC_AUDIO_QUALITY_DEFAULT);
        trtc.muteLocalAudio(true);          /* 默认静音防啸叫 */
        if (camOn) {
            trtc.startLocalPreview(true, localView);
        } else {
            trtc.muteLocalVideo(true);
            localBox.setVisibility(View.GONE);
        }
        tvMicLabel.setText("解除");
        handler.post(heartbeatTask);
        Toast.makeText(this, "已默认静音，说话请点底部麦克风（防啸叫）", Toast.LENGTH_LONG).show();
    }

    private final TRTCCloudListener trtcListener = new TRTCCloudListener() {

        @Override
        public void onEnterRoom(long result) {
            runOnUiThread(() -> {
                if (result > 0) {
                    tvHint.setText("");
                } else {
                    tvHint.setText("进房失败(" + result + ")，请退出重试");
                }
            });
        }

        @Override
        public void onUserVideoAvailable(String userId, boolean available) {
            runOnUiThread(() -> {
                if (available) {
                    addRemoteView(userId);
                } else {
                    removeRemoteView(userId);
                }
            });
        }

        @Override
        public void onRemoteUserLeaveRoom(String userId, int reason) {
            runOnUiThread(() -> removeRemoteView(userId));
        }

        @Override
        public void onError(int errCode, String errMsg, Bundle extraInfo) {
            Log.w(TAG, "trtc error " + errCode + " " + errMsg);
            runOnUiThread(() -> tvHint.setText("音视频异常(" + errCode + ")"));
        }

        @Override
        public void onRecvCustomCmdMsg(String userId, int cmdId, int seq, byte[] message) {
            try {
                JSONObject d = new JSONObject(new String(message, StandardCharsets.UTF_8));
                handleCustomMessage(d);
            } catch (Exception e) {
                Log.w(TAG, "bad custom msg: " + e.getMessage());
            }
        }
    };

    /* ==================== 自定义消息（与网页端互通，cmdId 固定 1） ==================== */

    private void sendCustom(JSONObject d) {
        if (trtc == null || !inRoom) return;
        try {
            trtc.sendCustomCmdMsg(1, d.toString().getBytes(StandardCharsets.UTF_8), true, false);
        } catch (Exception e) {
            Log.w(TAG, "sendCustom failed: " + e.getMessage());
        }
    }

    private void handleCustomMessage(JSONObject d) {
        String t = d.optString("t", "");
        String name = d.optString("name", "会议成员");
        runOnUiThread(() -> {
            switch (t) {
                case "text":
                    appendChat(name, d.optString("text", ""));
                    break;
                case "react": {
                    String k = d.optString("k", "");
                    Toast.makeText(MeetingActivity.this,
                            name + "：" + ("hand".equals(k) ? "举手" : k), Toast.LENGTH_SHORT).show();
                    break;
                }
                case "checkin": {
                    Toast.makeText(this, name + " 发起了签到", Toast.LENGTH_SHORT).show();
                    JSONObject ack = new JSONObject();
                    try {
                        ack.put("t", "checkin_ack");
                        sendCustom(ack);
                    } catch (Exception ignored) {
                    }
                    break;
                }
                case "checkin_ack":
                    checkinCount++;
                    break;
                case "break":
                    Toast.makeText(this, "主持人让大家休息 " + d.optInt("min", 5) + " 分钟",
                            Toast.LENGTH_SHORT).show();
                    break;
                case "host_mute":
                    if (d.optInt("on", 1) == 1 && micOn) {
                        micOn = false;
                        if (trtc != null) trtc.muteLocalAudio(true);
                        tvMicLabel.setText("解除");
                        Toast.makeText(this, "主持人已全体静音", Toast.LENGTH_SHORT).show();
                    }
                    break;
                default:
                    break;
            }
        });
    }

    /* ==================== 画面 ==================== */

    private void addRemoteView(String userId) {
        if (remoteViews.containsKey(userId)) {
            trtc.startRemoteView(userId, TRTCCloudDef.TRTC_VIDEO_STREAM_TYPE_BIG, remoteViews.get(userId));
            return;
        }
        TXCloudVideoView v = new TXCloudVideoView(this);
        v.setBackgroundColor(Color.parseColor("#000000"));
        v.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        videoStage.addView(v, 0);
        remoteViews.put(userId, v);
        trtc.startRemoteView(userId, TRTCCloudDef.TRTC_VIDEO_STREAM_TYPE_BIG, v);
        setActiveVideo(userId);
        tvHint.setText("");
    }

    private void removeRemoteView(String userId) {
        TXCloudVideoView v = remoteViews.remove(userId);
        if (v == null) return;
        trtc.stopRemoteView(userId, TRTCCloudDef.TRTC_VIDEO_STREAM_TYPE_BIG);
        videoStage.removeView(v);
        if (activeVideoUserId.equals(userId)) {
            String next = null;
            for (String k : remoteViews.keySet()) next = k;
            if (next != null) setActiveVideo(next);
        }
    }

    private void setActiveVideo(String userId) {
        activeVideoUserId = userId;
        for (Map.Entry<String, TXCloudVideoView> e : remoteViews.entrySet()) {
            e.getValue().setVisibility(e.getKey().equals(userId) ? View.VISIBLE : View.GONE);
        }
    }
    /* ==================== 控制条 / 聊天 / 成员 ==================== */

    private void toggleMic() {
        micOn = !micOn;
        if (trtc != null) trtc.muteLocalAudio(!micOn);
        tvMicLabel.setText(micOn ? "静音" : "解除");
        Toast.makeText(this, micOn ? "麦克风已打开" : "已静音", Toast.LENGTH_SHORT).show();
        sendHeartbeat();
    }

    private void toggleCam() {
        if (!camOn && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_PERM);
            return;
        }
        camOn = !camOn;
        if (camOn) {
            if (trtc != null) {
                trtc.muteLocalVideo(false);
                trtc.startLocalPreview(true, localView);
            }
            localBox.setVisibility(View.VISIBLE);
            tvCamLabel.setText("视频");
        } else {
            if (trtc != null) {
                trtc.muteLocalVideo(true);
                trtc.stopLocalPreview();
            }
            localBox.setVisibility(View.GONE);
            tvCamLabel.setText("开视频");
        }
        sendHeartbeat();
    }

    private void toggleSpeaker() {
        speakerOn = !speakerOn;
        if (trtc != null) {
            trtc.setAudioRoute(speakerOn
                    ? TRTCCloudDef.TRTC_AUDIO_ROUTE_SPEAKER
                    : TRTCCloudDef.TRTC_AUDIO_ROUTE_EARPIECE);
        }
        Toast.makeText(this, speakerOn ? "已切换到扬声器" : "已切换到听筒", Toast.LENGTH_SHORT).show();
    }

    private void sendChatText() {
        String text = etChat.getText().toString().trim();
        if (text.isEmpty()) return;
        etChat.setText("");
        JSONObject d = new JSONObject();
        try {
            d.put("t", "text");
            d.put("text", text);
            d.put("name", selfName);
        } catch (Exception ignored) {
        }
        appendChat(selfName.isEmpty() ? "我" : selfName + "（我）", text);
        sendCustom(d);
    }

    private void appendChat(String name, String text) {
        TextView tv = new TextView(this);
        tv.setText(name + "：" + text);
        tv.setTextColor(Color.parseColor("#E8EAED"));
        tv.setTextSize(14);
        tv.setPadding(0, 6, 0, 6);
        chatList.addView(tv);
        chatPanel.post(() -> chatPanel.fullScroll(View.FOCUS_DOWN));
        if (chatPanel.getVisibility() != View.VISIBLE) {
            chatPanel.setVisibility(View.VISIBLE);
            handler.postDelayed(() -> {
                if (etChat.getText().toString().trim().isEmpty()) {
                    chatPanel.setVisibility(View.GONE);
                }
            }, 6000);
        }
    }

    private void showMembers() {
        if (memberSummary.isEmpty()) {
            sendHeartbeat();
            Toast.makeText(this, "正在获取成员…", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("会议成员 (" + memberSummary.size() + ")　点名字可切换画面")
                .setItems(memberSummary.toArray(new String[0]), (d, w) -> {
                    if (w >= 0 && w < memberUids.size()) {
                        String uid = memberUids.get(w);
                        if (remoteViews.containsKey(uid)) {
                            setActiveVideo(uid);
                        } else {
                            Toast.makeText(this, "该成员未开摄像头", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setPositiveButton("关闭", null)
                .show();
    }
    /* ==================== 更多面板 ==================== */

    private void showMorePanel() {
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        int dpi = (int) getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#1C1E22"));
        root.setPadding(pad, pad, pad, pad);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(4);
        String[][] items = {
                {"+", "邀请", "invite"},
                {"💬", "聊天", "chat"},
                {"🛡", "主持人工具", "host"},
                {"🔇", "断开音频", "mute"},
                {"▣", "浮窗显示", "none"},
                {"✍", "签到", "checkin"},
                {"☕", "休息一下", "break"},
                {"⏺", "云录制", "rec"},
                {"☰", "云录制回放", "replay"},
                {"📝", "开启字幕", "none"},
                {"📋", "豚链纪要", "ai"},
        };
        for (String[] it : items) {
            boolean enabled = !"none".equals(it[2]);
            if ("host".equals(it[2]) && !isHost) enabled = false;
            View cell = buildMoreCell(it[0], it[1], enabled, dpi);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = getResources().getDisplayMetrics().widthPixels / 4 - pad / 2;
            lp.height = 86 * dpi;
            cell.setLayoutParams(lp);
            cell.setOnClickListener(v -> {
                if (moreDialog != null) moreDialog.dismiss();
                handleMoreAction(it[2]);
            });
            grid.addView(cell);
        }
        root.addView(grid);
        LinearLayout reactRow = new LinearLayout(this);
        reactRow.setOrientation(LinearLayout.HORIZONTAL);
        reactRow.setGravity(Gravity.CENTER);
        reactRow.setPadding(0, pad, 0, 0);
        String[] reacts = {"✋", "👏", "👍", "🌹", "😍", "😡", "💪"};
        for (String r : reacts) {
            Button b = new Button(this);
            b.setText(r);
            b.setTextSize(16);
            b.setTextColor(Color.WHITE);
            b.setBackgroundResource(R.drawable.bg_btn_dark);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 44 * dpi, 1);
            lp.setMargins(4, 0, 4, 0);
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> {
                if (moreDialog != null) moreDialog.dismiss();
                JSONObject d = new JSONObject();
                try {
                    d.put("t", "react");
                    d.put("k", "✋".equals(r) ? "hand" : r);
                    d.put("name", selfName);
                } catch (Exception ignored) {
                }
                sendCustom(d);
                Toast.makeText(this, "已发送 " + r, Toast.LENGTH_SHORT).show();
            });
            reactRow.addView(b);
        }
        root.addView(reactRow);

        Button cancel = new Button(this);
        cancel.setText("取消");
        cancel.setTextColor(Color.WHITE);
        cancel.setTextSize(15);
        cancel.setBackgroundResource(R.drawable.bg_btn_dark);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 44 * dpi);
        clp.topMargin = pad;
        cancel.setLayoutParams(clp);
        cancel.setOnClickListener(v -> {
            if (moreDialog != null) moreDialog.dismiss();
        });
        root.addView(cancel);

        moreDialog = new AlertDialog.Builder(this).setView(root).create();
        moreDialog.show();
    }

    private View buildMoreCell(String icon, String label, boolean enabled, int dpi) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        TextView ic = new TextView(this);
        ic.setText(icon);
        ic.setTextSize(22);
        ic.setGravity(Gravity.CENTER);
        ic.setBackgroundResource(R.drawable.bg_btn_dark);
        ic.setLayoutParams(new LinearLayout.LayoutParams(48 * dpi, 44 * dpi));
        if (!enabled) ic.setAlpha(0.4f);
        TextView lb = new TextView(this);
        lb.setText(label);
        lb.setTextSize(11);
        lb.setTextColor(enabled ? Color.parseColor("#C9CDD4") : Color.parseColor("#5A6068"));
        lb.setPadding(0, 6, 0, 0);
        cell.addView(ic);
        cell.addView(lb);
        return cell;
    }
    private void handleMoreAction(String action) {
        switch (action) {
            case "invite":
                shareInvite();
                break;
            case "chat":
                chatPanel.setVisibility(chatPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                break;
            case "host":
                showHostTools();
                break;
            case "mute":
                toggleMic();
                break;
            case "checkin": {
                checkinCount = 0;
                JSONObject d = new JSONObject();
                try {
                    d.put("t", "checkin");
                    d.put("name", selfName);
                } catch (Exception ignored) {
                }
                sendCustom(d);
                Toast.makeText(this, "已发起签到", Toast.LENGTH_SHORT).show();
                handler.postDelayed(() -> Toast.makeText(this,
                        "共收到 " + checkinCount + " 人签到", Toast.LENGTH_LONG).show(), 10000);
                break;
            }
            case "break": {
                JSONObject d = new JSONObject();
                try {
                    d.put("t", "break");
                    d.put("min", 5);
                    d.put("name", selfName);
                } catch (Exception ignored) {
                }
                sendCustom(d);
                Toast.makeText(this, "已通知大家休息 5 分钟", Toast.LENGTH_SHORT).show();
                break;
            }
            case "rec":
                toggleRecord();
                break;
            case "replay":
            case "ai":
                showReplayList();
                break;
            default:
                break;
        }
    }

    private void showHostTools() {
        new AlertDialog.Builder(this)
                .setTitle("主持人工具")
                .setItems(new String[]{"全体静音", "解除全体静音", "切换扬声器/听筒"}, (d, w) -> {
                    if (w == 0 || w == 1) {
                        JSONObject m = new JSONObject();
                        try {
                            m.put("t", "host_mute");
                            m.put("on", w == 0 ? 1 : 0);
                            m.put("name", selfName);
                        } catch (Exception ignored) {
                        }
                        sendCustom(m);
                        if (w == 0 && micOn) {
                            micOn = false;
                            if (trtc != null) trtc.muteLocalAudio(true);
                            tvMicLabel.setText("解除");
                        }
                        Toast.makeText(this, w == 0 ? "已全体静音" : "已解除全体静音", Toast.LENGTH_SHORT).show();
                    } else {
                        toggleSpeaker();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void shareInvite() {
        try {
            String text = "【豚链】邀请你加入会议 TUN" + roomId
                    + "，点击加入：" + base + "/meeting/" + roomId + ".html";
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, text);
            startActivity(Intent.createChooser(send, "邀请加入会议"));
        } catch (Exception e) {
            Toast.makeText(this, "分享失败", Toast.LENGTH_SHORT).show();
        }
    }
    /* ==================== 云录制 ==================== */

    private void toggleRecord() {
        if (!isHost) {
            Toast.makeText(this, "只有主持人可以开启云录制", Toast.LENGTH_SHORT).show();
            return;
        }
        showLoading(recording ? "正在停止录制…" : "正在开始录制…");
        Map<String, String> p = new LinkedHashMap<>();
        p.put("roomId", String.valueOf(roomId));
        String path = recording ? "/index.php/index/cloud_record/stop.html"
                : "/index.php/index/cloud_record/start.html";
        http("POST", path, p, (resp, err) -> {
            hideLoading();
            if (resp == null) {
                Toast.makeText(this, "网络异常", Toast.LENGTH_SHORT).show();
                return;
            }
            if (resp.optInt("code", 1) == 0) {
                recording = !recording;
                Toast.makeText(this, recording ? "已开始云录制"
                        : "已停止录制，可在回放里下载/生成纪要", Toast.LENGTH_LONG).show();
                if (!recording) showReplayList();
            } else {
                Toast.makeText(this, resp.optString("msg", "操作失败"), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showReplayList() {
        showLoading("正在获取回放列表…");
        http("GET", "/index.php/index/cloud_record/list.html", null, (resp, err) -> {
            hideLoading();
            if (resp == null || resp.optInt("code", 1) != 0) {
                Toast.makeText(this, "获取回放列表失败", Toast.LENGTH_SHORT).show();
                return;
            }
            JSONArray arr = resp.optJSONObject("data").optJSONArray("list");
            if (arr == null || arr.length() == 0) {
                Toast.makeText(this, "还没有云录制记录", Toast.LENGTH_SHORT).show();
                return;
            }
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(16, 8, 16, 16);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject r = arr.optJSONObject(i);
                if (r != null) root.addView(buildReplayRow(r));
            }
            ScrollView sv = new ScrollView(this);
            sv.addView(root);
            new AlertDialog.Builder(this)
                    .setTitle("云录制回放")
                    .setView(sv)
                    .setNegativeButton("关闭", null)
                    .show();
        });
    }

    private View buildReplayRow(JSONObject r) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, 12, 0, 12);
        TextView title = new TextView(this);
        title.setText(r.optString("timeText", "") + " · " + r.optString("statusText", "")
                + " · " + r.optString("durationText", "-"));
        title.setTextColor(Color.parseColor("#E8EAED"));
        title.setTextSize(14);
        row.addView(title);
        if (r.optInt("hasText", 0) == 1 || r.optInt("hasSummary", 0) == 1) {
            TextView st = new TextView(this);
            String s = (r.optInt("hasSummary", 0) == 1 ? "已有纪要 " : "")
                    + (r.optInt("hasText", 0) == 1 ? "已有转写" : "");
            st.setText(s.trim());
            st.setTextColor(Color.parseColor("#7BC47F"));
            st.setTextSize(12);
            row.addView(st);
        }
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, 8, 0, 0);
        int id = r.optInt("id", 0);
        btnRow.addView(smallBtn("下载", v -> downloadRecord(id)));
        btnRow.addView(smallBtn("转写", v -> transcribeRecord(id)));
        btnRow.addView(smallBtn("豚链纪要", v -> summaryRecord(id)));
        row.addView(btnRow);
        return row;
    }

    private Button smallBtn(String label, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(13);
        b.setTextColor(Color.WHITE);
        b.setBackgroundResource(R.drawable.bg_btn_dark);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                (int) (40 * getResources().getDisplayMetrics().density), 1);
        lp.setMargins(4, 0, 4, 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(onClick);
        return b;
    }
    private void downloadRecord(int id) {
        showLoading("正在生成下载地址…");
        Map<String, String> q = new LinkedHashMap<>();
        q.put("id", String.valueOf(id));
        http("GET", "/index.php/index/cloud_record/download.html", q, (resp, err) -> {
            hideLoading();
            if (resp == null || resp.optInt("code", 1) != 0) {
                Toast.makeText(this, resp == null ? "网络异常" : resp.optString("msg", "获取下载地址失败"),
                        Toast.LENGTH_LONG).show();
                return;
            }
            JSONObject d = resp.optJSONObject("data");
            String url = d == null ? "" : d.optString("url", "");
            String name = d == null || d.optString("fileName", "").isEmpty()
                    ? ("tunlian_rec_" + id + ".mp4") : d.optString("fileName");
            if (url.isEmpty()) {
                Toast.makeText(this, "下载地址为空", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.setTitle(name);
                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                dm.enqueue(req);
                Toast.makeText(this, "已开始下载到手机「下载」目录", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "下载失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void transcribeRecord(int id) {
        showLoading("转写中，录音越长耗时越久，请勿关闭…");
        Map<String, String> q = new LinkedHashMap<>();
        q.put("id", String.valueOf(id));
        http("GET", "/index.php/index/cloud_record/transcribe.html", q, (resp, err) -> {
            hideLoading();
            if (resp == null || resp.optInt("code", 1) != 0) {
                Toast.makeText(this, resp == null ? "网络异常" : resp.optString("msg", "转写失败"),
                        Toast.LENGTH_LONG).show();
                return;
            }
            showTextDialog("转写结果", resp.optJSONObject("data").optString("text", ""));
        });
    }

    private void summaryRecord(int id) {
        showLoading("豚链纪要生成中（先转写再总结），请稍候…");
        Map<String, String> q = new LinkedHashMap<>();
        q.put("id", String.valueOf(id));
        http("GET", "/index.php/index/cloud_record/summary.html", q, (resp, err) -> {
            hideLoading();
            if (resp == null || resp.optInt("code", 1) != 0) {
                Toast.makeText(this, resp == null ? "网络异常" : resp.optString("msg", "纪要生成失败"),
                        Toast.LENGTH_LONG).show();
                return;
            }
            showTextDialog("豚链纪要", resp.optJSONObject("data").optString("summary", ""));
        });
    }

    private void showTextDialog(String title, String text) {
        if (text == null || text.trim().isEmpty()) text = "（空）";
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#E8EAED"));
        tv.setTextSize(14);
        tv.setPadding(24, 16, 24, 16);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
    }
    /* ==================== 离开 / 结束 / 心跳 / 收尾 ==================== */

    private void leaveRoom() {
        finish();
    }

    private void confirmEndMeeting() {
        if (!isHost) {
            Toast.makeText(this, "只有主持人可以结束会议", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("结束会议")
                .setMessage("结束后所有人都将退出房间，确定吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("结束", (d, w) -> {
                    Map<String, String> p = new LinkedHashMap<>();
                    p.put("roomId", String.valueOf(roomId));
                    http("POST", "/index.php/index/meeting/end.html", p, (resp, err) -> finish());
                })
                .show();
    }

    private void sendHeartbeat() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("roomId", String.valueOf(roomId));
        p.put("mic", micOn ? "1" : "0");
        p.put("cam", camOn ? "1" : "0");
        http("POST", "/index.php/index/meeting/heartbeat.html", p, (resp, err) -> {
            if (resp == null || resp.optInt("code", 1) != 0) return;
            JSONObject d = resp.optJSONObject("data");
            if (d == null) return;
            if (d.optInt("ended", 0) == 1) {
                Toast.makeText(MeetingActivity.this, "主持人已结束会议", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            JSONArray arr = d.optJSONArray("members");
            if (arr == null) return;
            memberSummary.clear();
            memberUids.clear();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject m = arr.optJSONObject(i);
                if (m == null) continue;
                String name = m.optString("name", "");
                if (name.isEmpty()) name = "用户" + m.optInt("uid", 0);
                boolean me = m.optInt("uid", 0) == safeInt(selfId);
                memberSummary.add(name + (me ? "（我）" : "")
                        + "  " + (m.optInt("mic", 1) == 1 ? "🎤" : "🔇")
                        + (m.optInt("cam", 1) == 1 ? " 📷" : ""));
                memberUids.add(String.valueOf(m.optInt("uid", 0)));
            }
        });
    }

    private static int safeInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return -1;
        }
    }

    private void fallback(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        Intent i = new Intent();
        i.putExtra(EXTRA_ROOM_ID, roomId);
        setResult(RESULT_FALLBACK, i);
        finish();
    }

    private void showLoading(String msg) {
        hideLoading();
        loadingDialog = new AlertDialog.Builder(this)
                .setMessage(msg)
                .setCancelable(false)
                .show();
    }

    private void hideLoading() {
        if (loadingDialog != null && loadingDialog.isShowing()) {
            loadingDialog.dismiss();
        }
        loadingDialog = null;
    }

    private void exitAll() {
        inRoom = false;
        handler.removeCallbacks(heartbeatTask);
        Map<String, String> p = new LinkedHashMap<>();
        p.put("roomId", String.valueOf(roomId));
        http("POST", "/index.php/index/meeting/native_leave.html", p, null);
        if (trtc != null) {
            trtc.stopLocalPreview();
            trtc.stopLocalAudio();
            trtc.exitRoom();
            trtc.setListener(null);
            TRTCCloud.destroySharedInstance();
            trtc = null;
        }
    }

    @Override
    protected void onDestroy() {
        exitAll();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        new AlertDialog.Builder(this)
                .setTitle("离开会议")
                .setMessage("离开后其他人仍可继续开会，确定离开吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("离开", (d, w) -> finish())
                .show();
    }
    /* ==================== HTTP ==================== */

    private interface HttpCallback {
        void onDone(JSONObject resp, String err);
    }

    private void http(final String method, final String path,
                      final Map<String, String> params, final HttpCallback cb) {
        new Thread(() -> {
            JSONObject result = null;
            try {
                StringBuilder body = new StringBuilder();
                if (params != null) {
                    for (Map.Entry<String, String> e : params.entrySet()) {
                        if (body.length() > 0) body.append('&');
                        body.append(URLEncoder.encode(e.getKey(), "UTF-8"))
                                .append('=')
                                .append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), "UTF-8"));
                    }
                }
                String url = base + path;
                if ("GET".equals(method) && body.length() > 0) {
                    url += "?" + body;
                }
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod(method);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(120000);
                String cookie = CookieManager.getInstance().getCookie(base);
                if (cookie != null && !cookie.isEmpty()) {
                    conn.setRequestProperty("Cookie", cookie);
                }
                conn.setRequestProperty("User-Agent", "TunLianApp/Android");
                conn.setRequestProperty("Accept", "application/json");
                if (!"GET".equals(method)) {
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                    OutputStream os = conn.getOutputStream();
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    os.close();
                }
                int code = conn.getResponseCode();
                BufferedReader br = new BufferedReader(new InputStreamReader(
                        code >= 400 ? conn.getErrorStream() : conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                try {
                    result = new JSONObject(sb.toString());
                } catch (Exception je) {
                    Log.w(TAG, "non-json from " + path);
                }
                conn.disconnect();
            } catch (Exception e) {
                Log.w(TAG, "http " + path + " failed: " + e.getMessage());
            }
            final JSONObject r = result;
            if (cb != null) {
                handler.post(() -> cb.onDone(r, null));
            }
        }).start();
    }

    private static String trimSlash(String s) {
        if (s == null) return "";
        String t = s.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }
}