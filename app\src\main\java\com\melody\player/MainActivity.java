package com.melody.player;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(14, 17, 28);
    private static final int CARD = Color.rgb(29, 34, 49);
    private static final int WHITE = Color.rgb(248, 249, 255);
    private static final int MUTED = Color.rgb(166, 173, 193);
    private static final int ACCENT = Color.rgb(161, 118, 255);
    private static final String API = "https://api.audius.co/v1/tracks";

    private final ExecutorService work = Executors.newFixedThreadPool(3);
    private final ExecutorService images = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Track> favorites = new LinkedHashMap<>();
    private final List<Track> visibleTracks = new ArrayList<>();
    private final List<Track> playQueue = new ArrayList<>();
    private final Map<String, Bitmap> artworkCache = new HashMap<>();

    private LinearLayout rows, root, player;
    private TextView sectionTitle, message, nowTitle, nowArtist, playButton, favoriteButton, elapsed, total;
    private SeekBar timeline;
    private EditText search;
    private MediaPlayer mediaPlayer;
    private Track current;
    private int currentIndex = -1;
    private int requestVersion = 0;
    private int playVersion = 0;
    private boolean showingFavorites = false;
    private boolean userSeeking = false;

    private static final class Track {
        final String id, title, artist, artwork;
        Track(String id, String title, String artist, String artwork) {
            this.id = id; this.title = title; this.artist = artist; this.artwork = artwork;
        }
        JSONObject json() {
            JSONObject value = new JSONObject();
            try { value.put("id", id); value.put("title", title); value.put("artist", artist); value.put("artwork", artwork); }
            catch (Exception ignored) { }
            return value;
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        loadFavorites();
        drawScreen();
        loadTracks("");
        main.postDelayed(this::updateProgress, 500);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private GradientDrawable background(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView button(String text, int color) {
        TextView view = label(text, 18, WHITE, true);
        view.setGravity(Gravity.CENTER);
        view.setBackground(background(color, 24));
        view.setMinWidth(dp(48));
        view.setMinHeight(dp(48));
        return view;
    }

    private void drawScreen() {
        root = vertical(); root.setBackgroundColor(BG);
        setContentView(root);

        LinearLayout header = vertical();
        header.setPadding(dp(22), dp(20), dp(22), dp(12));
        TextView brand = label("♫  Melody", 32, WHITE, true);
        header.addView(brand);
        TextView tagline = label("Your music, your moment. No ads.", 14, MUTED, false);
        LinearLayout.LayoutParams tagParams = new LinearLayout.LayoutParams(-1, -2);
        tagParams.topMargin = dp(4); header.addView(tagline, tagParams);
        root.addView(header);

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setPadding(dp(22), dp(8), dp(22), dp(14));
        search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setTextColor(WHITE);
        search.setHintTextColor(MUTED);
        search.setHint("Search songs or artists");
        search.setPadding(dp(16), 0, dp(12), 0);
        search.setBackground(background(CARD, 14));
        searchRow.addView(search, new LinearLayout.LayoutParams(0, dp(50), 1));
        root.addView(searchRow);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().trim();
                if (searchTask != null) main.removeCallbacks(searchTask);
                searchTask = () -> loadTracks(query);
                main.postDelayed(searchTask, query.isEmpty() ? 0 : 400);
            }
            public void afterTextChanged(Editable e) { }
        });

        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(dp(22), 0, dp(22), dp(12));
        TextView discover = label("Discover", 17, ACCENT, true);
        discover.setPadding(0, dp(8), dp(24), dp(8));
        discover.setOnClickListener(v -> {
            showingFavorites = false;
            discover.setTextColor(ACCENT);
            savedTab.setTextColor(MUTED);
            loadTracks(search.getText().toString().trim());
        });
        tabs.addView(discover);
        TextView saved = label("♥  Favorites", 17, MUTED, true);
        savedTab = saved;
        saved.setPadding(0, dp(8), 0, dp(8));
        saved.setOnClickListener(v -> {
            showingFavorites = true;
            requestVersion++;
            sectionTitle.setText("Your favorites");
            message.setText(favorites.isEmpty() ? "No favorites yet. Tap the heart on a song to save it." : "Saved on this phone");
            showTracks(new ArrayList<>(favorites.values()));
            discover.setTextColor(MUTED); saved.setTextColor(ACCENT);
        });
        tabs.addView(saved);
        root.addView(tabs);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = vertical();
        content.setPadding(dp(22), dp(10), dp(22), dp(16));
        sectionTitle = label("Trending now", 23, WHITE, true);
        content.addView(sectionTitle);
        message = label("Loading tracks…", 13, MUTED, false);
        LinearLayout.LayoutParams info = new LinearLayout.LayoutParams(-1, -2);
        info.topMargin = dp(7); info.bottomMargin = dp(15);
        content.addView(message, info);
        rows = vertical(); content.addView(rows);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        player = vertical();
        player.setPadding(dp(18), dp(15), dp(18), dp(14));
        player.setBackground(background(CARD, 20));
        LinearLayout.LayoutParams playerParams = new LinearLayout.LayoutParams(-1, -2);
        playerParams.setMargins(dp(12), 0, dp(12), dp(12));
        root.addView(player, playerParams);
        drawPlayer();
        player.setVisibility(View.GONE);
    }

    private Runnable searchTask;
    private TextView savedTab;

    private void drawPlayer() {
        nowTitle = label("", 17, WHITE, true); nowTitle.setSingleLine(true);
        nowArtist = label("", 13, MUTED, false); nowArtist.setSingleLine(true);
        LinearLayout titleRow = new LinearLayout(this); titleRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout metadata = vertical(); metadata.addView(nowTitle); metadata.addView(nowArtist);
        titleRow.addView(metadata, new LinearLayout.LayoutParams(0, -2, 1));
        favoriteButton = button("♡", CARD);
        favoriteButton.setTextColor(ACCENT);
        favoriteButton.setOnClickListener(v -> toggleFavorite());
        titleRow.addView(favoriteButton);
        player.addView(titleRow);

        timeline = new SeekBar(this);
        timeline.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        timeline.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        timeline.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar s) { userSeeking = true; }
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) { if (fromUser) elapsed.setText(clock(p)); }
            public void onStopTrackingTouch(SeekBar s) {
                if (mediaPlayer != null) {
                    try { mediaPlayer.seekTo(s.getProgress()); } catch (IllegalStateException ignored) { }
                }
                userSeeking = false;
            }
        });
        player.addView(timeline);
        LinearLayout timeRow = new LinearLayout(this);
        elapsed = label("0:00", 12, MUTED, false); total = label("0:00", 12, MUTED, false);
        timeRow.addView(elapsed, new LinearLayout.LayoutParams(0, -2, 1)); timeRow.addView(total);
        player.addView(timeRow);

        LinearLayout controls = new LinearLayout(this); controls.setGravity(Gravity.CENTER);
        TextView previous = button("|◀", CARD);
        previous.setOnClickListener(v -> skip(-1));
        playButton = button("▶", ACCENT); playButton.setTextColor(BG);
        playButton.setOnClickListener(v -> togglePlayback());
        TextView next = button("▶|", CARD);
        next.setOnClickListener(v -> skip(1));
        controls.addView(previous);
        LinearLayout.LayoutParams middle = new LinearLayout.LayoutParams(dp(64), dp(56));
        middle.setMargins(dp(22), 0, dp(22), 0);
        controls.addView(playButton, middle);
        controls.addView(next);
        player.addView(controls);
    }

    private String clock(int ms) {
        int seconds = Math.max(0, ms / 1000);
        return (seconds / 60) + ":" + String.format(java.util.Locale.US, "%02d", seconds % 60);
    }

    private void loadTracks(String query) {
        showingFavorites = false;
        if (savedTab != null) savedTab.setTextColor(MUTED);
        int version = ++requestVersion;
        sectionTitle.setText(query.isEmpty() ? "Trending now" : "Search results");
        message.setText("Loading tracks…"); rows.removeAllViews();
        work.execute(() -> {
            try {
                String endpoint = query.isEmpty() ? "/trending?time=week&limit=30" :
                        "/search?query=" + URLEncoder.encode(query, "UTF-8") + "&limit=30";
                JSONObject response = readJson(API + endpoint + "&app_name=Melody");
                JSONArray data = response.optJSONArray("data");
                if (data == null) throw new Exception("Missing tracks in response");
                List<Track> tracks = new ArrayList<>();
                for (int i = 0; i < data.length(); i++) {
                    JSONObject item = data.optJSONObject(i);
                    if (item == null || item.optBoolean("is_stream_gated") || item.optBoolean("isStreamGated")) continue;
                    if (item.has("is_streamable") && !item.optBoolean("is_streamable")) continue;
                    if (item.has("isStreamable") && !item.optBoolean("isStreamable")) continue;
                    String id = item.optString("id");
                    if (id.isEmpty()) continue;
                    JSONObject user = item.optJSONObject("user");
                    JSONObject artwork = item.optJSONObject("artwork");
                    tracks.add(new Track(id, item.optString("title", "Untitled"),
                            user == null ? "Unknown artist" : user.optString("name", "Unknown artist"),
                            artwork == null ? "" : artwork.optString("480x480", artwork.optString("_480x480", ""))));
                }
                main.post(() -> {
                    if (version != requestVersion || showingFavorites || isFinishing()) return;
                    message.setText(tracks.isEmpty() ? "No tracks found. Try another search." : "Music from Audius • No ads in Melody");
                    showTracks(tracks);
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (version != requestVersion || isFinishing()) return;
                    message.setText("Couldn't load songs. Check your internet and tap to retry.");
                    message.setOnClickListener(v -> loadTracks(search.getText().toString().trim()));
                });
            }
        });
    }

    private JSONObject readJson(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(12000); connection.setReadTimeout(12000);
        connection.setRequestProperty("Accept", "application/json");
        try {
            if (connection.getResponseCode() != 200) throw new Exception("HTTP " + connection.getResponseCode());
            try (InputStream stream = connection.getInputStream()) {
                byte[] bytes = new byte[8192];
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                int n;
                while ((n = stream.read(bytes)) != -1) {
                    buffer.write(bytes, 0, n);
                    if (buffer.size() > 2_000_000) throw new Exception("Response too large");
                }
                return new JSONObject(buffer.toString("UTF-8"));
            }
        } finally { connection.disconnect(); }
    }

    private void showTracks(List<Track> tracks) {
        visibleTracks.clear(); visibleTracks.addAll(tracks);
        rows.removeAllViews();
        for (int i = 0; i < tracks.size(); i++) {
            final int index = i;
            Track track = tracks.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(9), dp(9), dp(9), dp(9));
            row.setBackground(background(CARD, 14));
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, dp(78));
            rowParams.bottomMargin = dp(9);
            ImageView artwork = new ImageView(this);
            artwork.setBackground(background(Color.rgb(73, 52, 105), 10));
            artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
            artwork.setClipToOutline(true);
            row.addView(artwork, new LinearLayout.LayoutParams(dp(57), dp(57)));
            loadArtwork(track.artwork, artwork);
            LinearLayout labels = vertical();
            labels.setPadding(dp(12), 0, dp(5), 0);
            TextView title = label(track.title, 15, WHITE, true);
            title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            TextView artist = label(track.artist, 13, MUTED, false);
            artist.setSingleLine(true); artist.setEllipsize(android.text.TextUtils.TruncateAt.END);
            labels.addView(title); labels.addView(artist);
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            TextView arrow = label("▶", 17, ACCENT, true);
            arrow.setPadding(dp(8), 0, dp(7), 0); row.addView(arrow);
            row.setOnClickListener(v -> play(track, index));
            rows.addView(row, rowParams);
        }
    }

    private void loadArtwork(String url, ImageView view) {
        if (!url.startsWith("https://")) return;
        if (artworkCache.containsKey(url)) { view.setImageBitmap(artworkCache.get(url)); return; }
        view.setTag(url);
        images.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(6000); connection.setReadTimeout(6000);
                try (InputStream input = connection.getInputStream()) {
                    Bitmap image = BitmapFactory.decodeStream(input);
                    if (image != null) main.post(() -> {
                        artworkCache.put(url, image);
                        if (!isFinishing() && url.equals(view.getTag())) view.setImageBitmap(image);
                    });
                }
            } catch (Exception ignored) { }
            finally { if (connection != null) connection.disconnect(); }
        });
    }

    private void play(Track track, int index) {
        stopPlayer();
        current = track; currentIndex = index;
        playQueue.clear(); playQueue.addAll(visibleTracks);
        int version = ++playVersion;
        player.setVisibility(View.VISIBLE);
        nowTitle.setText(track.title); nowArtist.setText("Connecting • " + track.artist);
        playButton.setText("…"); elapsed.setText("0:00"); total.setText("0:00");
        timeline.setProgress(0); timeline.setMax(1);
        updateFavoriteButton();
        try {
            MediaPlayer next = new MediaPlayer();
            next.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            next.setOnPreparedListener(mp -> {
                if (version != playVersion) return;
                nowArtist.setText(track.artist);
                timeline.setMax(Math.max(1, mp.getDuration()));
                total.setText(clock(mp.getDuration()));
                mp.start(); playButton.setText("Ⅱ");
            });
            next.setOnCompletionListener(mp -> { if (version == playVersion) skip(1); });
            next.setOnErrorListener((mp, what, extra) -> {
                if (version == playVersion) { nowArtist.setText("Can't play this track. Choose another."); playButton.setText("▶"); }
                return true;
            });
            mediaPlayer = next;
            next.setDataSource(API + "/" + URLEncoder.encode(track.id, "UTF-8") + "/stream?app_name=Melody");
            next.prepareAsync();
        } catch (Exception e) {
            nowArtist.setText("Can't play this track. Choose another."); playButton.setText("▶");
        }
    }

    private void stopPlayer() {
        playVersion++;
        if (mediaPlayer != null) {
            try { mediaPlayer.reset(); mediaPlayer.release(); } catch (Exception ignored) { }
            mediaPlayer = null;
        }
    }

    private void togglePlayback() {
        if (mediaPlayer == null) return;
        try {
            if (mediaPlayer.isPlaying()) { mediaPlayer.pause(); playButton.setText("▶"); }
            else { mediaPlayer.start(); playButton.setText("Ⅱ"); }
        } catch (IllegalStateException ignored) { }
    }

    private void skip(int direction) {
        if (playQueue.isEmpty()) return;
        int index = currentIndex < 0 ? 0 : (currentIndex + direction + playQueue.size()) % playQueue.size();
        Track next = playQueue.get(index);
        List<Track> snapshot = new ArrayList<>(playQueue);
        play(next, index);
        playQueue.clear(); playQueue.addAll(snapshot);
    }

    private void updateProgress() {
        if (mediaPlayer != null && !userSeeking) {
            try { int position = mediaPlayer.getCurrentPosition(); timeline.setProgress(position); elapsed.setText(clock(position)); }
            catch (IllegalStateException ignored) { }
        }
        main.postDelayed(this::updateProgress, 500);
    }

    private void toggleFavorite() {
        if (current == null) return;
        if (favorites.containsKey(current.id)) favorites.remove(current.id);
        else favorites.put(current.id, current);
        JSONArray data = new JSONArray();
        for (Track track : favorites.values()) data.put(track.json());
        getPreferences(MODE_PRIVATE).edit().putString("favorites", data.toString()).apply();
        updateFavoriteButton();
        if (showingFavorites) {
            message.setText(favorites.isEmpty() ? "No favorites yet. Tap the heart on a song to save it." : "Saved on this phone");
            showTracks(new ArrayList<>(favorites.values()));
        }
    }

    private void updateFavoriteButton() {
        favoriteButton.setText(current != null && favorites.containsKey(current.id) ? "♥" : "♡");
    }

    private void loadFavorites() {
        try {
            JSONArray data = new JSONArray(getPreferences(MODE_PRIVATE).getString("favorites", "[]"));
            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.getJSONObject(i);
                Track track = new Track(item.getString("id"), item.optString("title"),
                        item.optString("artist"), item.optString("artwork"));
                favorites.put(track.id, track);
            }
        } catch (Exception ignored) { favorites.clear(); }
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        stopPlayer();
        work.shutdownNow();
        images.shutdownNow();
        super.onDestroy();
    }
}
