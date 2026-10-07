package com.tunlian.app;

import android.Manifest;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.util.Rational;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.tencent.rtmp.ui.TXCloudVideoView;
import com.tencent.trtc.TRTCCloud;
import com.tencent.trtc.TRTCCloudDef;
import com.tencent.trtc.TRTCCloudListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
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
    private static final int REQ_IMG = 2002;
    private static final int REQ_IMG_PERM = 2003;

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
    private boolean subOn = false;          /* 【2026-10-05】实时字幕开关 */
    private volatile boolean subRunning = false;
    private boolean sharing = false;        /* 【2026-10-06】屏幕共享中 */

    /* 【2026-10-07】全屏聊天页 / 头像宫格 / 转写存档 */
    private AlertDialog chatDialog;
    private LinearLayout chatMsgList;
    private ScrollView chatScroll;
    private EditText chatInput;
    private final List<JSONObject> chatData = new ArrayList<>();
    private final Map<String, String> avatarByName = new LinkedHashMap<>();
    private final Map<String, Bitmap> avatarCache = new LinkedHashMap<>();
    private final List<JSONObject> memberObjs = new ArrayList<>();
    private GridLayout avatarGrid;
    private final Map<String, Integer> voiceVol = new LinkedHashMap<>();
    private AlertDialog pageDialog;

    private TRTCCloud trtc;
    private FrameLayout videoStage;
    private FrameLayout localBox;
    private TXCloudVideoView localView;
    private TextView tvHint;
    private TextView tvMicLabel;
    private TextView tvCamLabel;
    private LinearLayout rootLayout;
    private TextView tvSubtitle;
    private AudioRecord audioRecord;
    private Thread subThread;
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
        rootLayout = findViewById(R.id.rootLayout);
        tvSubtitle = findViewById(R.id.tvSubtitle);
        setupAvatarGrid();

        TextView tvRoom = findViewById(R.id.tvRoom);
        tvRoom.setText("TUN" + roomId);

        findViewById(R.id.btnForward).setOnClickListener(v -> shareInvite());
        findViewById(R.id.btnLeave).setOnClickListener(v -> leaveRoom());
        findViewById(R.id.btnMic).setOnClickListener(v -> toggleMic());
        findViewById(R.id.btnCam).setOnClickListener(v -> toggleCam());
        findViewById(R.id.btnShare).setOnClickListener(v -> toggleScreenShare());
        findViewById(R.id.btnMembers).setOnClickListener(v -> showMembers());
        findViewById(R.id.btnMore).setOnClickListener(v -> showMorePanel());
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
        trtc.enableAudioVolumeEvaluation(300);   /* 音量回调：谁在说话排谁前面 */
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
        public void onUserVoiceVolume(java.util.ArrayList<TRTCCloudDef.TRTCVolumeInfo> userVolumes, int totalVolume) {
            runOnUiThread(() -> {
                if (userVolumes == null) return;
                for (TRTCCloudDef.TRTCVolumeInfo info : userVolumes) {
                    if (info == null) continue;
                    String uid = (info.userId == null || info.userId.isEmpty()) ? selfId : info.userId;
                    voiceVol.put(uid, info.volume);
                }
                updateAvatarGrid();
            });
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
                case "sub":
                    showSubtitle(name, d.optString("txt", ""));
                    break;
                case "img":
                    appendChatImage(name, d.optString("url", ""));
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
        updateAvatarGrid();
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
        updateAvatarGrid();
    }

    private void setActiveVideo(String userId) {
        activeVideoUserId = userId;
        for (Map.Entry<String, TXCloudVideoView> e : remoteViews.entrySet()) {
            e.getValue().setVisibility(e.getKey().equals(userId) ? View.VISIBLE : View.GONE);
        }
        updateAvatarGrid();
    }

    /* ==================== 头像宫格（没人开视频时替代黑屏） ==================== */

    private void setupAvatarGrid() {
        avatarGrid = new GridLayout(this);
        avatarGrid.setColumnCount(3);
        avatarGrid.setVisibility(View.GONE);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        videoStage.addView(avatarGrid, glp);
    }

    /** 重建头像宫格：说话的人排最前（近 3 秒内有音量），其余按成员顺序 */
    private void updateAvatarGrid() {
        if (avatarGrid == null) return;
        boolean anyVideo = !remoteViews.isEmpty() || camOn;
        if (anyVideo) {
            avatarGrid.setVisibility(View.GONE);
            return;
        }
        avatarGrid.setVisibility(View.VISIBLE);
        avatarGrid.removeAllViews();
        if (memberObjs.isEmpty()) {
            TextView wait = new TextView(this);
            wait.setText("等待成员加入…");
            wait.setTextColor(Color.parseColor("#9aa7b5"));
            wait.setTextSize(14);
            avatarGrid.addView(wait);
            return;
        }
        int dpi = (int) getResources().getDisplayMetrics().density;
        /* 按“最近在说话”排序：volume 大的靠前 */
        List<JSONObject> sorted = new ArrayList<>(memberObjs);
        java.util.Collections.sort(sorted, (a, b) -> {
            int va = voiceVol.containsKey(String.valueOf(a.optInt("uid", 0)))
                    ? voiceVol.get(String.valueOf(a.optInt("uid", 0))) : 0;
            int vb = voiceVol.containsKey(String.valueOf(b.optInt("uid", 0)))
                    ? voiceVol.get(String.valueOf(b.optInt("uid", 0))) : 0;
            return vb - va;
        });
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int cellW = Math.min(screenW / 3, 130 * dpi);
        int cellH = 150 * dpi;
        for (JSONObject mem : sorted) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(cellW, cellH);
            clp.setMargins(6 * dpi, 10 * dpi, 6 * dpi, 0);
            cell.setLayoutParams(clp);
            View av = buildAvatarView(mem.optString("name", ""),
                    mem.optString("icon", ""), 72 * dpi);
            cell.addView(av);
            TextView nm = new TextView(this);
            String n = mem.optString("name", "");
            nm.setText(n.isEmpty() ? "用户" + mem.optInt("uid", 0) : n);
            nm.setTextColor(Color.WHITE);
            nm.setTextSize(13);
            nm.setMaxLines(1);
            nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
            nm.setGravity(Gravity.CENTER);
            nm.setPadding(2 * dpi, 6 * dpi, 2 * dpi, 0);
            cell.addView(nm, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView mic = new TextView(this);
            mic.setText(mem.optInt("mic", 1) == 1 ? "🎤" : "🔇");
            mic.setTextSize(11);
            mic.setGravity(Gravity.CENTER);
            cell.addView(mic);
            final String uid = String.valueOf(mem.optInt("uid", 0));
            final boolean hasCam = mem.optInt("cam", 0) == 1;
            if (hasCam) {
                cell.setOnClickListener(v -> {
                    if (remoteViews.containsKey(uid)) setActiveVideo(uid);
                });
            }
            avatarGrid.addView(cell);
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
        if (chatInput == null) return;
        String text = chatInput.getText().toString().trim();
        if (text.isEmpty()) return;
        chatInput.setText("");
        JSONObject d = new JSONObject();
        try {
            d.put("t", "text");
            d.put("text", text);
            d.put("name", selfName);
        } catch (Exception ignored) {
        }
        appendChat(selfName, text, "", true);
        sendCustom(d);
    }

    /** 收到/发出一条消息：先存进 chatData，聊天页开着就渲染 */
    private void appendChat(String name, String text) {
        appendChat(name, text, "", name != null && name.equals(selfName));
    }

    private void appendChat(String name, String text, String imgUrl, boolean mine) {
        JSONObject m = new JSONObject();
        try {
            m.put("name", name == null || name.isEmpty() ? "会议成员" : name);
            m.put("text", text == null ? "" : text);
            m.put("url", imgUrl == null ? "" : imgUrl);
            m.put("mine", mine);
            m.put("icon", avatarByName.containsKey(name) ? avatarByName.get(name) : "");
        } catch (Exception ignored) {
        }
        chatData.add(m);
        addChatBubble(m);
    }

    /* ==================== 全屏聊天页 ==================== */

    private void openChatPage() {
        if (chatDialog != null && chatDialog.isShowing()) return;
        int dpi = (int) getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#17181A"));

        /* 顶栏 */
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(16 * dpi, 12 * dpi, 16 * dpi, 12 * dpi);
        TextView title = new TextView(this);
        title.setText("聊天");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tp.leftMargin = 12 * dpi;
        title.setLayoutParams(tp);
        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(Color.parseColor("#C9CDD4"));
        close.setTextSize(18);
        close.setPadding(12 * dpi, 4 * dpi, 4 * dpi, 4 * dpi);
        close.setOnClickListener(v -> {
            if (chatDialog != null) chatDialog.dismiss();
        });
        bar.addView(title);
        bar.addView(close);
        root.addView(bar);

        /* 消息列表 */
        chatMsgList = new LinearLayout(this);
        chatMsgList.setOrientation(LinearLayout.VERTICAL);
        chatMsgList.setPadding(10 * dpi, 6 * dpi, 10 * dpi, 16 * dpi);
        chatScroll = new ScrollView(this);
        chatScroll.addView(chatMsgList);
        root.addView(chatScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        /* 输入行（图片按钮移到这里） */
        LinearLayout inRow = new LinearLayout(this);
        inRow.setOrientation(LinearLayout.HORIZONTAL);
        inRow.setGravity(Gravity.CENTER_VERTICAL);
        inRow.setPadding(10 * dpi, 8 * dpi, 10 * dpi, 10 * dpi);
        Button imgBtn = new Button(this);
        imgBtn.setText("🖼");
        imgBtn.setTextSize(16);
        imgBtn.setBackgroundResource(R.drawable.bg_btn_dark);
        imgBtn.setOnClickListener(v -> pickChatImage());
        inRow.addView(imgBtn, new LinearLayout.LayoutParams(40 * dpi, 40 * dpi));
        chatInput = new EditText(this);
        chatInput.setHint("说点什么...");
        chatInput.setHintTextColor(Color.parseColor("#8A8F99"));
        chatInput.setTextColor(Color.WHITE);
        chatInput.setTextSize(14);
        chatInput.setMaxLines(1);
        chatInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        chatInput.setBackgroundResource(R.drawable.bg_input);
        chatInput.setPadding(14 * dpi, 0, 14 * dpi, 0);
        LinearLayout.LayoutParams etp = new LinearLayout.LayoutParams(0, 40 * dpi, 1);
        etp.leftMargin = 8 * dpi;
        chatInput.setLayoutParams(etp);
        chatInput.setOnEditorActionListener((v, actionId, event) -> {
            sendChatText();
            return true;
        });
        inRow.addView(chatInput);
        Button send = new Button(this);
        send.setText("发送");
        send.setTextColor(Color.WHITE);
        send.setTextSize(14);
        send.setBackgroundResource(R.drawable.bg_btn_blue);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, 40 * dpi);
        sp.leftMargin = 8 * dpi;
        send.setLayoutParams(sp);
        send.setOnClickListener(v -> sendChatText());
        inRow.addView(send);
        root.addView(inRow);

        chatDialog = new AlertDialog.Builder(this).setView(root).create();
        chatDialog.show();
        if (chatDialog.getWindow() != null) {
            chatDialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            chatDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        /* 重画历史消息 */
        chatMsgList.removeAllViews();
        for (JSONObject m : chatData) addChatBubble(m);
    }

    /** 渲染一条消息：头像 + 名字 + 气泡（文字或图片），自己的靠右 */
    private void addChatBubble(JSONObject m) {
        if (chatMsgList == null || m == null) return;
        int dpi = (int) getResources().getDisplayMetrics().density;
        boolean mine = m.optBoolean("mine", false);
        String name = m.optString("name", "会议成员");

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 5 * dpi, 0, 5 * dpi);
        row.setGravity(Gravity.TOP);
        if (mine) row.setGravity(Gravity.END);

        View av = buildAvatarView(name, m.optString("icon", ""), 36 * dpi);
        LinearLayout.LayoutParams avp = new LinearLayout.LayoutParams(36 * dpi, 36 * dpi);
        if (!mine) avp.rightMargin = 8 * dpi; else avp.leftMargin = 8 * dpi;
        av.setLayoutParams(avp);
        if (mine) {
            row.addView(buildBubble(m, dpi));
            row.addView(av);
        } else {
            row.addView(av);
            row.addView(buildBubble(m, dpi));
        }
        chatMsgList.addView(row);
        chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
    }

    private View buildBubble(JSONObject m, int dpi) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colp.leftMargin = 2 * dpi;
        colp.rightMargin = 2 * dpi;
        col.setLayoutParams(colp);
        TextView nm = new TextView(this);
        nm.setText(m.optString("name", ""));
        nm.setTextSize(12);
        nm.setTextColor(Color.parseColor("#8A93A0"));
        nm.setPadding(4 * dpi, 0, 4 * dpi, 2 * dpi);
        nm.setGravity(m.optBoolean("mine", false) ? Gravity.END : Gravity.START);
        col.addView(nm);

        String url = m.optString("url", "");
        GradientDrawable bubble = new GradientDrawable();
        bubble.setCornerRadius(10 * dpi);
        bubble.setColor(Color.parseColor(m.optBoolean("mine", false) ? "#1F4E79" : "#232830"));
        if (!url.isEmpty()) {
            FrameLayout imgBox = new FrameLayout(this);
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setAdjustViewBounds(true);
            iv.setBackground(bubble);
            iv.setPadding(4 * dpi, 4 * dpi, 4 * dpi, 4 * dpi);
            int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.55f);
            FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(w, w);
            iv.setLayoutParams(ilp);
            final String full = url.startsWith("http") ? url : base + url;
            iv.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(full)));
                } catch (Exception e) {
                    Toast.makeText(this, "打不开图片", Toast.LENGTH_SHORT).show();
                }
            });
            loadBitmapInto(full, iv, w);
            imgBox.addView(iv);
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.gravity = m.optBoolean("mine", false) ? Gravity.END : Gravity.START;
            imgBox.setLayoutParams(blp);
            col.addView(imgBox);
        } else {
            TextView tv = new TextView(this);
            String txt = m.optString("text", "");
            tv.setText(txt);
            tv.setTextSize(15);
            tv.setTextColor(Color.parseColor("#E8EAED"));
            tv.setBackground(bubble);
            tv.setPadding(10 * dpi, 7 * dpi, 10 * dpi, 7 * dpi);
            LinearLayout.LayoutParams tvp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tvp.gravity = m.optBoolean("mine", false) ? Gravity.END : Gravity.START;
            tvp.width = Math.min(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.62f), 1 + 14 * txt.length() * dpi);
            tv.setLayoutParams(tvp);
            col.addView(tv);
        }
        return col;
    }

    /** 头像：有图用圆头像，没有用首字彩色圆标 */
    private View buildAvatarView(String name, String iconUrl, int sizePx) {
        FrameLayout box = new FrameLayout(this);
        TextView letter = new TextView(this);
        String n = name == null || name.isEmpty() ? "?" : name.trim();
        letter.setText(n.isEmpty() ? "?" : n.substring(0, 1));
        letter.setTextColor(Color.WHITE);
        letter.setTextSize(14);
        letter.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        int[] colors = {0xFF2C6BED, 0xFF7B52CC, 0xFF1F9D6E, 0xFFC25E1E, 0xFF3B7EA1, 0xFF8A4B60};
        bg.setColor(colors[Math.abs(n.hashCode()) % colors.length]);
        letter.setBackground(bg);
        box.addView(letter, new FrameLayout.LayoutParams(sizePx, sizePx));
        if (iconUrl != null && !iconUrl.isEmpty()) {
            final ImageView iv = new ImageView(this);
            iv.setVisibility(View.GONE);
            box.addView(iv, new FrameLayout.LayoutParams(sizePx, sizePx));
            loadBitmapInto(iconUrl, iv, sizePx, letter);
        }
        return box;
    }

    /** 简易图片加载（线程 + 内存缓存），加载完圆形裁切 */
    private void loadBitmapInto(final String url, final ImageView iv, final int sizePx, final View... fallback) {
        Bitmap c = avatarCache.get(url);
        if (c != null) {
            iv.setImageBitmap(rounded(c, sizePx));
            iv.setVisibility(View.VISIBLE);
            for (View f : fallback) f.setVisibility(View.GONE);
            return;
        }
        new Thread(() -> {
            Bitmap bm = null;
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(15000);
                InputStream is = conn.getInputStream();
                bm = BitmapFactory.decodeStream(is);
                is.close();
                conn.disconnect();
            } catch (Exception e) {
                Log.w(TAG, "img load failed: " + e.getMessage());
            }
            if (bm != null) avatarCache.put(url, bm);
            final Bitmap fbm = bm;
            handler.post(() -> {
                if (fbm == null) return;
                iv.setImageBitmap(rounded(fbm, sizePx));
                iv.setVisibility(View.VISIBLE);
                for (View f : fallback) f.setVisibility(View.GONE);
            });
        }).start();
    }

    private android.graphics.drawable.Drawable rounded(Bitmap src, int sizePx) {
        android.graphics.drawable.Drawable d =
                androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(getResources(), src);
        if (d instanceof android.graphics.drawable.BitmapDrawable) {
            ((android.graphics.drawable.BitmapDrawable) d).setAntiAlias(true);
        }
        if (d instanceof androidx.core.graphics.drawable.RoundedBitmapDrawable) {
            ((androidx.core.graphics.drawable.RoundedBitmapDrawable) d).setCircular(true);
        }
        return d;
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
        int pad = (int) (14 * getResources().getDisplayMetrics().density);
        int dpi = (int) getResources().getDisplayMetrics().density;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        /* 格子宽度按“减去左右内边距后的可用宽度”算，否则 4 列会挤到屏幕外，最后一列文字被裁 */
        int cellW = (screenW - 2 * pad) / 4;

        /* 全屏深色面板：盖住整个会议画面（含顶栏和控制条） */
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#EE15171A"));
        root.setPadding(pad, pad, pad, pad);
        root.setClickable(true);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(4);
        /* 图标统一用白色线描矢量图（res/drawable/ic_more_*.xml），黑白风格 */
        Object[][] items = {
                {R.drawable.ic_more_invite, "邀请", "invite"},
                {R.drawable.ic_more_chat, "聊天", "chat"},
                {R.drawable.ic_more_host, "主持人", "host"},
                {R.drawable.ic_more_mute, "断开音频", "mute"},
                {R.drawable.ic_more_float, "浮窗显示", "pip"},
                {R.drawable.ic_more_checkin, "签到", "checkin"},
                {R.drawable.ic_more_break, "休息一下", "break"},
                {R.drawable.ic_more_rec, "云录制", "rec"},
                {R.drawable.ic_more_replay, "录制回放", "replay"},
                {R.drawable.ic_more_sub, "开启字幕", "sub"},
                {R.drawable.ic_more_ai, "豚链纪要", "ai"},
                {R.drawable.ic_more_note, "转写记录", "note"},
                {R.drawable.ic_more_end, "结束会议", "end"},
        };
        for (Object[] it : items) {
            boolean enabled = !"none".equals(it[2]);
            if ("host".equals(it[2]) && !isHost) enabled = false;
            if ("end".equals(it[2]) && !isHost) enabled = false;
            View cell = buildMoreCell((Integer) it[0], (String) it[1], enabled, dpi);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = cellW;
            lp.height = 96 * dpi;
            cell.setLayoutParams(lp);
            cell.setOnClickListener(v -> {
                if (moreDialog != null) moreDialog.dismiss();
                handleMoreAction((String) it[2]);
            });
            grid.addView(cell);
        }
        root.addView(grid);

        /* 中部留白，把互动区压到底部（对齐腾讯会议布局） */
        View spacer = new View(this);
        LinearLayout.LayoutParams splp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        spacer.setLayoutParams(splp);
        root.addView(spacer);

        /* 举手：宽按钮 */
        Button hand = new Button(this);
        hand.setText("✋ 举手");
        hand.setTextColor(Color.WHITE);
        hand.setTextSize(15);
        hand.setAllCaps(false);
        hand.setBackgroundResource(R.drawable.bg_btn_dark);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 48 * dpi);
        hand.setLayoutParams(hlp);
        hand.setOnClickListener(v -> {
            if (moreDialog != null) moreDialog.dismiss();
            JSONObject d = new JSONObject();
            try {
                d.put("t", "react");
                d.put("k", "hand");
                d.put("name", selfName);
            } catch (Exception ignored) {
            }
            sendCustom(d);
            Toast.makeText(this, "已举手", Toast.LENGTH_SHORT).show();
        });
        root.addView(hand);

        /* 表情行 */
        LinearLayout reactRow = new LinearLayout(this);
        reactRow.setOrientation(LinearLayout.HORIZONTAL);
        reactRow.setGravity(Gravity.CENTER);
        reactRow.setPadding(0, pad, 0, 0);
        String[] reacts = {"👏", "👍", "🌹", "😍", "😡", "💪"};
        for (String r : reacts) {
            TextView b = new TextView(this);
            b.setText(r);
            b.setTextSize(24);
            b.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 44 * dpi, 1);
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> {
                if (moreDialog != null) moreDialog.dismiss();
                JSONObject d = new JSONObject();
                try {
                    d.put("t", "react");
                    d.put("k", r);
                    d.put("name", selfName);
                } catch (Exception ignored) {
                }
                sendCustom(d);
                Toast.makeText(this, "已发送 " + r, Toast.LENGTH_SHORT).show();
            });
            reactRow.addView(b);
        }
        root.addView(reactRow);

        /* 取消：纯文字 */
        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setTextColor(Color.parseColor("#8A9099"));
        cancel.setTextSize(14);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(0, pad, 0, (int) (8 * getResources().getDisplayMetrics().density));
        cancel.setOnClickListener(v -> {
            if (moreDialog != null) moreDialog.dismiss();
        });
        root.addView(cancel);

        moreDialog = new AlertDialog.Builder(this).setView(root).create();
        moreDialog.show();
        /* 铺满全屏：默认 AlertDialog 只包内容，这里强制 MATCH_PARENT */
        if (moreDialog.getWindow() != null) {
            moreDialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            moreDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
    }

    private View buildMoreCell(int iconRes, String label, boolean enabled, int dpi) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        ImageView ic = new ImageView(this);
        ic.setImageResource(iconRes);
        int ip = 11 * dpi;
        ic.setPadding(ip, ip, ip, ip);
        ic.setBackgroundResource(R.drawable.bg_btn_dark);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(48 * dpi, 48 * dpi);
        ic.setLayoutParams(ilp);
        if (!enabled) ic.setAlpha(0.35f);
        TextView lb = new TextView(this);
        lb.setText(label);
        lb.setTextSize(12);
        lb.setGravity(Gravity.CENTER);
        lb.setTextColor(enabled ? Color.parseColor("#C9CDD4") : Color.parseColor("#5A6068"));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = 8 * dpi;
        lb.setLayoutParams(llp);
        cell.addView(ic);
        cell.addView(lb);
        return cell;
    }
    /* ==================== 屏幕共享（TRTC 屏幕采集，走主流，其他人自动看到） ==================== */

    private void toggleScreenShare() {
        if (trtc == null || !inRoom) {
            Toast.makeText(this, "进会后才能共享屏幕", Toast.LENGTH_SHORT).show();
            return;
        }
        if (sharing) {
            stopScreenShare();
        } else {
            startScreenShare();
        }
    }

    private void startScreenShare() {
        TRTCCloudDef.TRTCVideoEncParam enc = new TRTCCloudDef.TRTCVideoEncParam();
        enc.videoResolution = TRTCCloudDef.TRTC_VIDEO_RESOLUTION_1280_720;
        enc.videoResolutionMode = TRTCCloudDef.TRTC_VIDEO_RESOLUTION_MODE_PORTRAIT;
        enc.videoFps = 10;
        enc.videoBitrate = 1600;
        enc.enableAdjustRes = false;

        String err = "";
        boolean ok = false;
        try {
            /* Android 屏幕共享占用主流，先停掉摄像头预览，避免两路主流打架 */
            trtc.stopLocalPreview();
            /* 新版 SDK：startScreenCapture(encParams) */
            Method m = TRTCCloud.class.getMethod("startScreenCapture", TRTCCloudDef.TRTCVideoEncParam.class);
            m.invoke(trtc, enc);
            ok = true;
        } catch (NoSuchMethodException e) {
            try {
                /* 旧版 SDK：startScreenCapture(streamType, encParams, screenShareParams) */
                Class<?> pCls = Class.forName("com.tencent.trtc.TRTCCloudDef$TRTCScreenShareParams");
                Method m2 = TRTCCloud.class.getMethod("startScreenCapture", int.class,
                        TRTCCloudDef.TRTCVideoEncParam.class, pCls);
                m2.invoke(trtc, TRTCCloudDef.TRTC_VIDEO_STREAM_TYPE_BIG, enc, pCls.newInstance());
                ok = true;
            } catch (Exception e2) {
                err = String.valueOf(e2.getMessage());
            }
        } catch (Exception e) {
            err = String.valueOf(e.getMessage());
        }
        if (!ok) {
            Toast.makeText(this, "屏幕共享启动失败：" + err, Toast.LENGTH_SHORT).show();
            if (camOn) trtc.startLocalPreview(true, localView);
            return;
        }
        sharing = true;
        Toast.makeText(this, "已开始共享屏幕，其他人可看到", Toast.LENGTH_SHORT).show();
    }

    private void stopScreenShare() {
        try {
            Method m = TRTCCloud.class.getMethod("stopScreenCapture");
            m.invoke(trtc);
        } catch (Exception ignored) {
        }
        if (camOn && trtc != null) {
            trtc.startLocalPreview(true, localView);
        }
        sharing = false;
        Toast.makeText(this, "已停止屏幕共享", Toast.LENGTH_SHORT).show();
    }

    /* ==================== 图片聊天 ==================== */

    private void pickChatImage() {
        String perm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{perm}, REQ_IMG_PERM);
            return;
        }
        openImagePicker();
    }

    private void openImagePicker() {
        Intent it = new Intent(Intent.ACTION_GET_CONTENT);
        it.setType("image/*");
        try {
            startActivityForResult(Intent.createChooser(it, "选择图片"), REQ_IMG);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开图库", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_IMG && resultCode == RESULT_OK && data != null && data.getData() != null) {
            uploadChatImage(data.getData());
        }
    }

    private void uploadChatImage(Uri uri) {
        showLoading("图片发送中…");
        new Thread(() -> {
            String payload = null;
            try {
                Bitmap bm = MediaStore.Images.Media.getBitmap(getContentResolver(), uri);
                int max = 1080;
                int w = bm.getWidth();
                int h = bm.getHeight();
                float scale = Math.min(1f, (float) max / Math.max(w, h));
                if (scale < 1f) {
                    bm = Bitmap.createScaledBitmap(bm, Math.round(w * scale), Math.round(h * scale), true);
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                bm.compress(Bitmap.CompressFormat.JPEG, 80, bos);
                payload = "data:image/jpeg;base64,"
                        + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
            } catch (Exception e) {
                Log.w(TAG, "read image failed: " + e.getMessage());
            }
            final String b64 = payload;
            handler.post(() -> {
                if (b64 == null) {
                    hideLoading();
                    Toast.makeText(this, "读取图片失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                Map<String, String> p = new LinkedHashMap<>();
                p.put("dir", "chat");
                p.put("from", "base64");
                p.put("module", "meeting");
                p.put("Orientation", "1");
                p.put("imgBase64", b64);
                http("POST", "/index.php/index/attachment/upload.html", p, (resp, err) -> {
                    hideLoading();
                    if (resp == null || resp.optInt("code", 0) != 1) {
                        Toast.makeText(this, resp == null ? "图片上传失败"
                                : resp.optString("info", "图片上传失败"), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String url = resp.optString("url", "");
                    if (url.isEmpty()) url = resp.optString("path", "");
                    if (url.isEmpty()) {
                        Toast.makeText(this, "图片上传失败", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    appendChatImage(selfName.isEmpty() ? "我" : selfName, url);
                    JSONObject d = new JSONObject();
                    try {
                        d.put("t", "img");
                        d.put("name", selfName);
                        d.put("url", url);
                    } catch (Exception ignored) {
                    }
                    sendCustom(d);
                });
            });
        }).start();
    }

    /** 图片消息：统一进 chatData，由全屏聊天页渲染 */
    private void appendChatImage(String name, String url) {
        appendChat(name, "", url, name != null && name.equals(selfName));
    }

    /* ==================== 浮窗显示（系统画中画，无需悬浮窗权限） ==================== */

    private void enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || !getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            /* 不支持画中画就退到后台，音视频照常进行，不会挂断 */
            moveTaskToBack(true);
            Toast.makeText(this, "已缩到后台，会议继续进行", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            PictureInPictureParams p = new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(9, 16))
                    .build();
            enterPictureInPictureMode(p);
        } catch (Exception e) {
            Toast.makeText(this, "浮窗启动失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPip, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPip, newConfig);
        if (rootLayout == null) return;
        if (isInPip) {
            /* 浮窗里只保留第 1 个子视图（视频区），顶栏/输入栏/控制条全部隐藏 */
            for (int i = 0; i < rootLayout.getChildCount(); i++) {
                rootLayout.getChildAt(i).setVisibility(i == 1 ? View.VISIBLE : View.GONE);
            }
        } else {
            for (int i = 0; i < rootLayout.getChildCount(); i++) {
                rootLayout.getChildAt(i).setVisibility(View.VISIBLE);
            }
            tvSubtitle.setVisibility(subOn ? View.VISIBLE : View.GONE);
        }
    }

    /* ==================== 实时字幕（本地麦克风分片识别 + 广播给全员） ==================== */

    private void toggleSubtitle() {
        if (subOn) {
            stopSubtitle();
        } else {
            startSubtitle();
        }
    }

    private void startSubtitle() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "没有录音权限，无法开启字幕", Toast.LENGTH_SHORT).show();
            return;
        }
        final int rate = 16000;
        int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            Toast.makeText(this, "字幕启动失败：音频参数不支持", Toast.LENGTH_SHORT).show();
            return;
        }
        final int bufSize = Math.max(min, rate * 2);
        AudioRecord rec = null;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
        } catch (Exception e) {
            rec = null;
        }
        if (rec == null || rec.getState() != AudioRecord.STATE_INITIALIZED) {
            if (rec != null) {
                rec.release();
                rec = null;
            }
            try {
                rec = new AudioRecord(MediaRecorder.AudioSource.MIC, rate,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
            } catch (Exception e) {
                rec = null;
            }
        }
        if (rec == null || rec.getState() != AudioRecord.STATE_INITIALIZED) {
            Toast.makeText(this, "字幕启动失败：麦克风被占用", Toast.LENGTH_SHORT).show();
            return;
        }
        audioRecord = rec;
        audioRecord.startRecording();
        subOn = true;
        subRunning = true;
        tvSubtitle.setText("");
        tvSubtitle.setVisibility(View.VISIBLE);

        final int chunkBytes = rate * 2 * 4;      /* 每 4 秒识别一次 */
        final int readBytes = rate;               /* 每次读 0.5 秒 */
        subThread = new Thread(() -> {
            byte[] acc = new byte[chunkBytes];
            int filled = 0;
            byte[] tmp = new byte[readBytes];
            while (subRunning && audioRecord != null) {
                int n;
                try {
                    n = audioRecord.read(tmp, 0, tmp.length);
                } catch (Exception e) {
                    break;
                }
                if (n <= 0) continue;
                int take = Math.min(n, chunkBytes - filled);
                System.arraycopy(tmp, 0, acc, filled, take);
                filled += take;
                if (filled >= chunkBytes) {
                    final byte[] wav = pcmToWav(acc, rate);
                    filled = 0;
                    asrChunk(wav);
                }
            }
        }, "subtitle-recorder");
        subThread.start();
        Toast.makeText(this, "字幕已开启（识别你的讲话，全会议室可见）", Toast.LENGTH_SHORT).show();
    }

    private void stopSubtitle() {
        stopSubtitleSilent();
        if (tvSubtitle != null) tvSubtitle.setVisibility(View.GONE);
        Toast.makeText(this, "字幕已关闭", Toast.LENGTH_SHORT).show();
    }

    /** 静默停止（离开会议 / 退后台时用，不弹提示） */
    private void stopSubtitleSilent() {
        subRunning = false;
        subOn = false;
        if (subThread != null) {
            subThread.interrupt();
            subThread = null;
        }
        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (Exception ignored) {
            }
            audioRecord.release();
            audioRecord = null;
        }
    }

    /** 把一个 4 秒的音频分片送服务器识别，识别结果本地显示并广播给会议室其他人 */
    private void asrChunk(byte[] wav) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("audio", Base64.encodeToString(wav, Base64.NO_WRAP));
        http("POST", "/index.php/index/asr/live.html", p, (resp, err) -> {
            if (resp == null || resp.optInt("code", 1) != 0) return;
            JSONObject data = resp.optJSONObject("data");
            if (data == null) return;
            String txt = data.optString("text", "").trim();
            if (txt.isEmpty()) return;
            showSubtitle(selfName.isEmpty() ? "我" : selfName, txt);
            /* 【2026-10-07】同步存档到服务器转写记录，供「转写记录」页随时查看 */
            Map<String, String> sp = new LinkedHashMap<>();
            sp.put("roomId", String.valueOf(roomId));
            sp.put("text", txt);
            http("POST", "/index.php/index/meeting/subsave.html", sp, null);
            JSONObject d = new JSONObject();
            try {
                d.put("t", "sub");
                d.put("name", selfName);
                d.put("txt", txt);
            } catch (Exception ignored) {
            }
            sendCustom(d);
        });
    }

    /** 字幕：同时推进聊天室（可随时回看文字）和底部字幕条 */
    private void showSubtitle(String name, String txt) {
        if (txt == null || txt.isEmpty()) return;
        appendChat(name, "🎙 " + txt);
        if (tvSubtitle != null) {
            tvSubtitle.setVisibility(View.VISIBLE);
            tvSubtitle.setText(name + "：" + txt);
        }
    }

    /** 裸 PCM(16bit 单声道) 封装成 WAV，云端识别只认带头的音频 */
    private static byte[] pcmToWav(byte[] pcm, int sampleRate) {
        int total = pcm.length;
        byte[] wav = new byte[44 + total];
        writeAscii(wav, 0, "RIFF");
        writeInt(wav, 4, 36 + total);
        writeAscii(wav, 8, "WAVE");
        writeAscii(wav, 12, "fmt ");
        writeInt(wav, 16, 16);
        writeShort(wav, 20, (short) 1);
        writeShort(wav, 22, (short) 1);
        writeInt(wav, 24, sampleRate);
        writeInt(wav, 28, sampleRate * 2);
        writeShort(wav, 32, (short) 2);
        writeShort(wav, 34, (short) 16);
        writeAscii(wav, 36, "data");
        writeInt(wav, 40, total);
        System.arraycopy(pcm, 0, wav, 44, total);
        return wav;
    }

    private static void writeAscii(byte[] b, int off, String s) {
        for (int i = 0; i < s.length(); i++) b[off + i] = (byte) s.charAt(i);
    }

    private static void writeInt(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xff);
        b[off + 1] = (byte) ((v >> 8) & 0xff);
        b[off + 2] = (byte) ((v >> 16) & 0xff);
        b[off + 3] = (byte) ((v >> 24) & 0xff);
    }

    private static void writeShort(byte[] b, int off, short v) {
        b[off] = (byte) (v & 0xff);
        b[off + 1] = (byte) ((v >> 8) & 0xff);
    }

    private void handleMoreAction(String action) {
        switch (action) {
            case "invite":
                shareInvite();
                break;
            case "chat":
                openChatPage();
                break;
            case "host":
                showHostTools();
                break;
            case "mute":
                toggleMic();
                break;
            case "pip":
                enterPip();
                break;
            case "sub":
                toggleSubtitle();
                break;
            case "note":
                openTranscriptPage();
                break;
            case "end":
                confirmEndMeeting();
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
    /* ==================== 通用全屏页面壳 / 转写记录 ==================== */

    /** 全屏深色页面：顶栏标题+关闭，返回弹窗句柄方便页面跳转 */
    private AlertDialog showPage(String title, View content) {
        int dpi = (int) getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#17181A"));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(16 * dpi, 12 * dpi, 16 * dpi, 12 * dpi);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(17);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tp.leftMargin = 12 * dpi;
        t.setLayoutParams(tp);
        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(Color.parseColor("#C9CDD4"));
        close.setTextSize(18);
        close.setPadding(12 * dpi, 4 * dpi, 4 * dpi, 4 * dpi);
        close.setOnClickListener(v -> {
            if (pageDialog != null) pageDialog.dismiss();
        });
        bar.addView(t);
        bar.addView(close);
        root.addView(bar);
        content.setPadding(content.getPaddingLeft(), 4 * dpi,
                content.getPaddingRight(), 12 * dpi);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        if (pageDialog != null && pageDialog.isShowing()) pageDialog.dismiss();
        pageDialog = new AlertDialog.Builder(this).setView(root).create();
        pageDialog.show();
        if (pageDialog.getWindow() != null) {
            pageDialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            pageDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        return pageDialog;
    }

    /** 转写记录：先列出有存档的会议（含当前会议），点进去看逐句内容 */
    private void openTranscriptPage() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(14, 6, 14, 6);

        /* 当前会议置顶 */
        LinearLayout cur = new LinearLayout(this);
        cur.setOrientation(LinearLayout.VERTICAL);
        cur.setPadding(12, 14, 12, 14);
        GradientDrawable curBg = new GradientDrawable();
        curBg.setCornerRadius(12);
        curBg.setColor(Color.parseColor("#232830"));
        cur.setBackground(curBg);
        TextView ct = new TextView(this);
        ct.setText("当前会议 TUN" + roomId);
        ct.setTextColor(Color.WHITE);
        ct.setTextSize(15);
        cur.addView(ct);
        TextView cs = new TextView(this);
        cs.setText("查看本场会议的实时转写内容 →");
        cs.setTextColor(Color.parseColor("#7BC47F"));
        cs.setTextSize(12);
        cs.setPadding(0, 6, 0, 0);
        cur.addView(cs);
        cur.setOnClickListener(v -> openTranscriptDetail(roomId, "当前会议 TUN" + roomId));
        LinearLayout.LayoutParams curp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        curp.bottomMargin = 12;
        list.addView(cur, curp);

        TextView hisTitle = new TextView(this);
        hisTitle.setText("历史会议转写");
        hisTitle.setTextColor(Color.parseColor("#8A93A0"));
        hisTitle.setTextSize(13);
        hisTitle.setPadding(4, 4, 4, 10);
        list.addView(hisTitle);

        showPage("转写记录", list);
        showLoading("获取转写记录…");
        http("GET", "/index.php/index/meeting/subhistory.html", null, (resp, err) -> {
            hideLoading();
            if (resp == null || resp.optInt("code", 1) != 0) {
                addPageLine(list, resp == null ? "网络异常，稍后重试" : "暂无历史转写记录");
                return;
            }
            JSONArray arr = resp.optJSONObject("data").optJSONArray("list");
            if (arr == null || arr.length() == 0) {
                addPageLine(list, "还没有历史转写。开会时开启「字幕」，讲话会自动存档到这里。");
                return;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject r = arr.optJSONObject(i);
                if (r == null) continue;
                final int rid = r.optInt("roomId", 0);
                String label = r.optString("timeText", "") + " 的会议 · "
                        + r.optInt("count", 0) + " 条";
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(12, 14, 12, 14);
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(12);
                bg.setColor(Color.parseColor("#1C2026"));
                row.setBackground(bg);
                TextView tv = new TextView(this);
                tv.setText(label);
                tv.setTextColor(Color.parseColor("#E8EAED"));
                tv.setTextSize(14);
                row.addView(tv);
                row.setOnClickListener(v -> openTranscriptDetail(rid, label));
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rp.bottomMargin = 10;
                list.addView(row, rp);
            }
        });
    }

    private void addPageLine(LinearLayout list, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#8A93A0"));
        tv.setTextSize(13);
        tv.setPadding(4, 10, 4, 10);
        list.addView(tv);
    }

    /** 某场会议的转写详情：逐句 姓名 + 时间 + 内容 */
    private void openTranscriptDetail(int rid, String title) {
        showLoading("获取转写内容…");
        Map<String, String> q = new LinkedHashMap<>();
        q.put("roomId", String.valueOf(rid));
        http("GET", "/index.php/index/meeting/sublist.html", q, (resp, err) -> {
            hideLoading();
            LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            list.setPadding(14, 6, 14, 6);
            if (resp == null || resp.optInt("code", 1) != 0) {
                addPageLine(list, resp == null ? "网络异常，稍后重试"
                        : resp.optString("msg", "获取失败"));
                showPage(title, list);
                return;
            }
            JSONArray arr = resp.optJSONObject("data").optJSONArray("list");
            if (arr == null || arr.length() == 0) {
                addPageLine(list, "本场还没有转写内容。开启「字幕」后讲话即自动记录。");
                showPage(title, list);
                return;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject r = arr.optJSONObject(i);
                if (r == null) continue;
                LinearLayout block = new LinearLayout(this);
                block.setOrientation(LinearLayout.VERTICAL);
                block.setPadding(12, 12, 12, 12);
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(10);
                bg.setColor(Color.parseColor("#1C2026"));
                block.setBackground(bg);
                TextView who = new TextView(this);
                who.setText(r.optString("name", "成员") + "   " + r.optString("timeText", ""));
                who.setTextColor(Color.parseColor("#8A93A0"));
                who.setTextSize(12);
                block.addView(who);
                TextView txt = new TextView(this);
                txt.setText(r.optString("text", ""));
                txt.setTextColor(Color.parseColor("#E8EAED"));
                txt.setTextSize(15);
                txt.setPadding(0, 6, 0, 0);
                block.addView(txt);
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                bp.bottomMargin = 10;
                list.addView(block, bp);
            }
            showPage(title, list);
        });
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
            /* 【2026-10-07】升级为全屏录制回放页，保存在云端随时复盘 */
            showPage("录制回放", sv);
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
            memberObjs.clear();
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
                memberObjs.add(m);
                if (!name.isEmpty() && m.optString("icon", "").isEmpty()) {
                    avatarByName.put(name, "");
                } else if (!name.isEmpty()) {
                    avatarByName.put(name, m.optString("icon", ""));
                }
            }
            updateAvatarGrid();
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
        stopSubtitleSilent();
        if (sharing && trtc != null) {
            try {
                Method m = TRTCCloud.class.getMethod("stopScreenCapture");
                m.invoke(trtc);
            } catch (Exception ignored) {
            }
            sharing = false;
        }
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