package com.tunlian.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.widget.Button;
import android.widget.GridLayout;
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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 【2026-10-04】原生音视频会议
 *
 * 为什么要有这个页面：
 * 网页会议跑在 WebView 里，用的是浏览器 WebRTC，回声消除时灵时不灵，
 * 多人同时开麦 + 外放就会尖叫。原生 TRTC SDK 走手机硬件 AEC，啸叫根治。
 *
 * 进房参数（SDKAppID / UserSig / 昵称 / 是否主持人）由服务端接口下发：
 *   GET /index.php/index/meeting/native_join.html?roomId=xxx
 * 成员在线状态靠心跳维护：
 *   POST /index.php/index/meeting/heartbeat.html
 */
public class MeetingActivity extends AppCompatActivity {

    public static final String EXTRA_ROOM_ID = "room_id";
    /** 进不去原生房间（未登录等）时回传给壳，让网页版兜底 */
    public static final int RESULT_FALLBACK = 99;

    private static final String TAG = "MeetingNative";
    private static final int REQ_PERM = 2001;

    private String base = "https://22.heitun.link";
    private int roomId = 0;
    private String selfId = "";
    private String userSig = "";
    private int sdkAppId = 0;
    private boolean isHost = false;

    private boolean micOn = true;
    private boolean camOn = true;
    private boolean speakerOn = true;
    private boolean inRoom = false;
    private int seconds = 0;

    private TRTCCloud trtc;
    private TXCloudVideoView localView;
    private GridLayout gridVideo;
    private TextView tvTitle;
    private TextView tvTimer;
    private TextView tvHint;
    private Button btnMic, btnCam, btnSpeaker, btnRec, btnEnd;
    private Handler handler;

    /** 远端用户 userId -> 画面容器 */
    private final Map<String, TXCloudVideoView> remoteViews = new LinkedHashMap<>();
    private final List<String> memberSummary = new ArrayList<>();

    private final Runnable tickTask = new Runnable() {
        @Override
        public void run() {
            if (inRoom) {
                seconds++;
                tvTimer.setText(formatDuration(seconds));
                handler.postDelayed(this, 1000);
            }
        }
    };

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

        gridVideo = findViewById(R.id.gridVideo);
        tvTitle = findViewById(R.id.tvTitle);
        tvTimer = findViewById(R.id.tvTimer);
        tvHint = findViewById(R.id.tvHint);
        btnMic = findViewById(R.id.btnMic);
        btnCam = findViewById(R.id.btnCam);
        btnSpeaker = findViewById(R.id.btnSpeaker);
        btnRec = findViewById(R.id.btnRec);
        btnEnd = findViewById(R.id.btnEnd);
        Button btnMembers = findViewById(R.id.btnMembers);
        Button btnLeave = findViewById(R.id.btnLeave);
        Button btnBack = findViewById(R.id.btnBack);

        if (roomId <= 0) {
            fallback("房间号不正确");
            return;
        }
        tvTitle.setText("会议 " + roomId);
        tvTimer.setText("00:00");
        tvHint.setText("正在进入房间…");

        btnMic.setOnClickListener(v -> toggleMic());
        btnCam.setOnClickListener(v -> toggleCam());
        btnSpeaker.setOnClickListener(v -> toggleSpeaker());
        btnMembers.setOnClickListener(v -> showMembers());
        btnRec.setOnClickListener(v -> toggleRecord());
        btnLeave.setOnClickListener(v -> leaveRoom());
        btnEnd.setOnClickListener(v -> confirmEndMeeting());
        btnBack.setOnClickListener(v -> onBackPressed());

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
            boolean cam = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
            if (!cam) {
                camOn = false;      // 没摄像头权限就纯音频入会
            }
            joinRoom();
        }
    }

    /* ==================== 进房 ==================== */

    private void joinRoom() {
        tvHint.setText("正在获取入会信息…");
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
            userSig = d.optString("userSig", "");
            isHost = d.optInt("isHost", 0) == 1;
            if (sdkAppId <= 0 || selfId.isEmpty() || userSig.isEmpty()) {
                fallback("入会信息不完整");
                return;
            }
            btnEnd.setVisibility(isHost ? View.VISIBLE : View.GONE);
            btnRec.setVisibility(isHost ? View.VISIBLE : View.GONE);
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
        trtc.enterRoom(params, TRTCCloudDef.TRTC_APP_SCENE_VIDEOCALL);

        trtc.setDefaultStreamRecvMode(true, true);
        trtc.setAudioRoute(TRTCCloudDef.TRTC_AUDIO_ROUTE_SPEAKER);

        inRoom = true;
        seconds = 0;
        tvHint.setText("");
        addLocalView();
        trtc.startLocalAudio(TRTCCloudDef.TRTC_AUDIO_QUALITY_DEFAULT);
        if (camOn) {
            trtc.startLocalPreview(true, localView);
        } else {
            trtc.muteLocalVideo(true);
            localView.setVisibility(View.GONE);
        }
        handler.post(tickTask);
        handler.post(heartbeatTask);
    }

    private final TRTCCloudListener trtcListener = new TRTCCloudListener() {

        @Override
        public void onEnterRoom(long result) {
            Log.i(TAG, "onEnterRoom result=" + result);
            runOnUiThread(() -> {
                if (result > 0) {
                    tvHint.setText("");
                } else {
                    tvHint.setText("进房失败(" + result + ")，请重试");
                }
            });
        }

        @Override
        public void onExitRoom(int reason) {
            Log.i(TAG, "onExitRoom reason=" + reason);
        }

        @Override
        public void onRemoteUserEnterRoom(String userId) {
            Log.i(TAG, "remote enter " + userId);
            runOnUiThread(() -> tvHint.setText(""));
        }

        @Override
        public void onRemoteUserLeaveRoom(String userId, int reason) {
            Log.i(TAG, "remote leave " + userId);
            runOnUiThread(() -> removeRemoteView(userId));
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
        public void onUserAudioAvailable(String userId, boolean available) {
            Log.i(TAG, "audio " + userId + " " + available);
        }

        @Override
        public void onError(int errCode, String errMsg, Bundle extraInfo) {
            Log.w(TAG, "trtc error " + errCode + " " + errMsg);
            runOnUiThread(() -> tvHint.setText("音视频异常(" + errCode + ")"));
        }
    };

    /* ==================== 画面 ==================== */

    private void addLocalView() {
        if (localView != null) return;
        localView = new TXCloudVideoView(this);
        localView.setBackgroundColor(Color.parseColor("#1b2430"));
        localView.setLayoutParams(cellParams(0));
        gridVideo.addView(localView);
    }

    private void addRemoteView(String userId) {
        if (remoteViews.containsKey(userId)) {
            trtc.startRemoteView(userId, TRTCCloudDef.TRTC_VIDEO_STREAM_TYPE_BIG, remoteViews.get(userId));
            return;
        }
        TXCloudVideoView v = new TXCloudVideoView(this);
        v.setBackgroundColor(Color.parseColor("#1b2430"));
        v.setLayoutParams(cellParams(gridVideo.getChildCount()));
        gridVideo.addView(v);
        remoteViews.put(userId, v);
        trtc.startRemoteView(userId, TRTCCloudDef.TRTC_VIDEO_STREAM_TYPE_BIG, v);
        tvHint.setText("");
    }

    private void removeRemoteView(String userId) {
        TXCloudVideoView v = remoteViews.remove(userId);
        if (v == null) return;
        trtc.stopRemoteView(userId, TRTCCloudDef.TRTC_VIDEO_STREAM_TYPE_BIG);
        gridVideo.removeView(v);
    }

    /** 两列宫格：每格宽度 = 屏幕一半 */
    private ViewGroup.LayoutParams cellParams(int index) {
        int w = getResources().getDisplayMetrics().widthPixels;
        int cellW = w / 2;
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = cellW;
        lp.height = (int) (cellW * 0.75f);
        lp.columnSpec = GridLayout.spec(index % 2);
        lp.rowSpec = GridLayout.spec(index / 2);
        lp.setMargins(2, 2, 2, 2);
        return lp;
    }

    /* ==================== 控制条 ==================== */

    private void toggleMic() {
        micOn = !micOn;
        trtc.muteLocalAudio(!micOn);
        btnMic.setText(micOn ? "静音" : "解除静音");
        btnMic.setSelected(!micOn);
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
            trtc.muteLocalVideo(false);
            if (localView == null) addLocalView();
            localView.setVisibility(View.VISIBLE);
            trtc.startLocalPreview(true, localView);
            btnCam.setText("关摄像头");
        } else {
            trtc.muteLocalVideo(true);
            trtc.stopLocalPreview();
            if (localView != null) localView.setVisibility(View.GONE);
            btnCam.setText("开摄像头");
        }
        btnCam.setSelected(!camOn);
        sendHeartbeat();
    }

    private void toggleSpeaker() {
        speakerOn = !speakerOn;
        trtc.setAudioRoute(speakerOn
                ? TRTCCloudDef.TRTC_AUDIO_ROUTE_SPEAKER
                : TRTCCloudDef.TRTC_AUDIO_ROUTE_EARPIECE);
        btnSpeaker.setText(speakerOn ? "扬声器" : "听筒");
        btnSpeaker.setSelected(!speakerOn);
    }

    private void showMembers() {
        if (memberSummary.isEmpty()) {
            Toast.makeText(this, "正在获取成员…", Toast.LENGTH_SHORT).show();
            sendHeartbeat();
            return;
        }
        String[] items = memberSummary.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("会议成员 (" + items.length + ")")
                .setItems(items, null)
                .setPositiveButton("关闭", null)
                .show();
    }

    /* ==================== 云录制（仅主持人） ==================== */

    private boolean recording = false;

    private void toggleRecord() {
        if (!isHost) {
            Toast.makeText(this, "只有主持人可以开启云录制", Toast.LENGTH_SHORT).show();
            return;
        }
        btnRec.setEnabled(false);
        Map<String, String> p = new LinkedHashMap<>();
        p.put("roomId", String.valueOf(roomId));
        String path = recording ? "/index.php/index/cloud_record/stop.html"
                : "/index.php/index/cloud_record/start.html";
        http("POST", path, p, (resp, err) -> {
            btnRec.setEnabled(true);
            if (resp == null) {
                Toast.makeText(this, "网络异常", Toast.LENGTH_SHORT).show();
                return;
            }
            if (resp.optInt("code", 1) == 0) {
                recording = !recording;
                btnRec.setText(recording ? "停止录制" : "云录制");
                btnRec.setSelected(recording);
                Toast.makeText(this, recording ? "已开始云录制" : "已停止云录制", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, resp.optString("msg", "操作失败"), Toast.LENGTH_SHORT).show();
            }
        });
    }

    /* ==================== 离开 / 结束 ==================== */

    private void leaveRoom() {
        finish();
    }

    private void confirmEndMeeting() {
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

    /* ==================== 心跳：上报在线状态 + 拉成员列表 ==================== */

    private void sendHeartbeat() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("roomId", String.valueOf(roomId));
        p.put("mic", micOn ? "1" : "0");
        p.put("cam", camOn ? "1" : "0");
        http("POST", "/index.php/index/meeting/heartbeat.html", p, (resp, err) -> {
            if (resp == null || resp.optInt("code", 1) != 0) return;
            JSONObject d = resp.optJSONObject("data");
            if (d == null) return;
            /* 主持人结束了会议：所有人自动退出（房间保留，凭会议号还能再开） */
            if (d.optInt("ended", 0) == 1) {
                Toast.makeText(MeetingActivity.this, "主持人已结束会议", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            JSONArray arr = d.optJSONArray("members");
            if (arr == null) return;
            memberSummary.clear();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject m = arr.optJSONObject(i);
                if (m == null) continue;
                String name = m.optString("name", "");
                if (name.isEmpty()) name = "用户" + m.optInt("uid", 0);
                String line = name
                        + (m.optInt("uid", 0) == safeInt(selfId) ? "（我）" : "")
                        + "  " + (m.optInt("mic", 1) == 1 ? "🎤已开麦" : "🔇已静音");
                memberSummary.add(line);
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

    /* ==================== 收尾 ==================== */

    /** 原生进不去时回退到网页版会议 */
    private void fallback(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        Intent i = new Intent();
        i.putExtra(EXTRA_ROOM_ID, roomId);
        setResult(RESULT_FALLBACK, i);
        finish();
    }

    private void exitAll() {
        inRoom = false;
        handler.removeCallbacks(tickTask);
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
        if (inRoom) {
            new AlertDialog.Builder(this)
                    .setTitle("离开会议")
                    .setMessage("离开后其他人仍可继续开会，确定离开吗？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("离开", (d, w) -> finish())
                    .show();
        } else {
            super.onBackPressed();
        }
    }

    /* ==================== HTTP ==================== */

    private interface HttpCallback {
        void onDone(JSONObject resp, String err);
    }

    /** 带站点 Cookie 的请求（APP 的登录态在 WebView Cookie 里） */
    private void http(final String method, final String path,
                      final Map<String, String> params, final HttpCallback cb) {
        new Thread(() -> {
            JSONObject result = null;
            String error = null;
            try {
                StringBuilder body = new StringBuilder();
                if (params != null && !params.isEmpty()) {
                    Iterator<Map.Entry<String, String>> it = params.entrySet().iterator();
                    while (it.hasNext()) {
                        Map.Entry<String, String> e = it.next();
                        body.append(URLEncoder.encode(e.getKey(), "UTF-8"))
                                .append('=')
                                .append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), "UTF-8"));
                        if (it.hasNext()) body.append('&');
                    }
                }
                String url = base + path;
                if ("GET".equals(method) && body.length() > 0) {
                    url += "?" + body;
                }
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod(method);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(12000);
                conn.setInstanceFollowRedirects(true);
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
                    os.write(body.toString().getBytes("UTF-8"));
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
                String text = sb.toString();
                try {
                    result = new JSONObject(text);
                } catch (Exception je) {
                    error = "返回内容不是 JSON";
                }
                conn.disconnect();
            } catch (Exception e) {
                error = e.getMessage();
                Log.w(TAG, "http " + path + " failed: " + e.getMessage());
            }
            final JSONObject r = result;
            final String err = error;
            if (cb != null) {
                handler.post(() -> cb.onDone(r, err));
            }
        }).start();
    }

    private static String trimSlash(String s) {
        if (s == null) return "";
        String t = s.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }

    private static String formatDuration(int sec) {
        return String.format(Locale.getDefault(), "%02d:%02d", sec / 60, sec % 60);
    }
}
