package com.melody.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.ClipData;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.Toast;
import android.widget.ImageView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.text.Normalizer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(16, 17, 24);
    private static final int CARD = Color.rgb(32, 34, 43);
    private static final int WHITE = Color.rgb(248, 249, 255);
    private static final int MUTED = Color.rgb(175, 179, 191);
    private static final int ACCENT = Color.rgb(255, 139, 57);
    private static final String API = "https://api.audius.co/v1/tracks";
    private static final int PICK_AUDIO = 18;

    private final ExecutorService work = Executors.newFixedThreadPool(3);
    private final ExecutorService images = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Track> favorites = new LinkedHashMap<>();
    private final Map<String, Track> downloads = new LinkedHashMap<>();
    private final Map<String, Track> localTracks = new LinkedHashMap<>();
    private final Map<String, Track> recentTracks = new LinkedHashMap<>();
    private final Map<String, List<Track>> playlists = new LinkedHashMap<>();
    private final List<Track> visibleTracks = new ArrayList<>();
    private final List<Track> playQueue = new ArrayList<>();
    private final Map<String, Bitmap> artworkCache = new HashMap<>();

    private LinearLayout rows, root, player;
    private TextView sectionTitle, message, nowTitle, nowArtist, playButton, favoriteButton, elapsed, total, phoneAction;
    private SeekBar timeline;
    private EditText search;
    private MediaPlayer mediaPlayer;
    private BassBoost bassBoost;
    private Equalizer equalizer;
    private int bassLevel, eqPreset;
    private String language = "Telugu";
    private String selectedPlaylist;
    private boolean showingDownloads = false;
    private boolean showingLocal = false;
    private Dialog expanded;
    private ImageView miniArtwork, expandedArtwork;
    private TextView expandedTitle, expandedArtist, expandedPlay, expandedTime, expandedRepeat, expandedShuffle;
    private SeekBar expandedTimeline;
    private int repeatMode = 1; // 0 stops at the end, 1 continues the queue, 2 repeats this song.
    private boolean shuffleEnabled;
    private boolean showingRecent;
    private int consecutiveErrors;
    private Track current;
    private int currentIndex = -1;
    private int requestVersion = 0;
    private int playVersion = 0;
    private boolean showingFavorites = false;
    private boolean userSeeking = false;

    private static final class Track {
        final String id, title, artist, artwork, details, searchHints;
        final boolean downloadable;
        Track(String id, String title, String artist, String artwork, boolean downloadable) {
            this(id,title,artist,artwork,downloadable,"");
        }
        Track(String id, String title, String artist, String artwork, boolean downloadable, String details) {
            this(id,title,artist,artwork,downloadable,details,details);
        }
        Track(String id, String title, String artist, String artwork, boolean downloadable, String details, String searchHints) {
            this.id = id; this.title = title; this.artist = artist; this.artwork = artwork;
            this.downloadable = downloadable; this.details = details; this.searchHints = searchHints;
        }
        boolean isLocal() { return id.startsWith("local:"); }
        JSONObject json() {
            JSONObject value = new JSONObject();
            try { value.put("id", id); value.put("title", title); value.put("artist", artist); value.put("artwork", artwork); value.put("downloadable", downloadable); value.put("details", details); value.put("searchHints", searchHints); }
            catch (Exception ignored) { }
            return value;
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        loadFavorites();
        loadCollections();
        loadLocalSongs();
        loadRecent();
        bassLevel = getPreferences(MODE_PRIVATE).getInt("bass", 0);
        eqPreset = getPreferences(MODE_PRIVATE).getInt("eqPreset", 0);
        repeatMode = getPreferences(MODE_PRIVATE).getInt("repeat", 1);
        shuffleEnabled = getPreferences(MODE_PRIVATE).getBoolean("shuffle", false);
        drawScreen();
        showDiscover();
        main.postDelayed(this::updateProgress, 500);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private GradientDrawable background(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private GradientDrawable gradient(int start, int end, int radius) {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{start,end});
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
        header.setPadding(dp(22), dp(23), dp(22), dp(22));
        header.setBackground(gradient(Color.rgb(107, 51, 27), BG, 0));
        TextView brand = label("◖♫◗  Melody", 31, WHITE, true);
        header.addView(brand);
        TextView tagline = label("Your songs. Your sound. Telugu first.", 14, Color.rgb(246, 202, 168), false);
        LinearLayout.LayoutParams tagParams = new LinearLayout.LayoutParams(-1, -2);
        tagParams.topMargin = dp(4); header.addView(tagline, tagParams);
        root.addView(header);

        TextView feature = label("  ✦  Discover   ·   Playlists   ·   Music on your phone  ", 13, WHITE, true);
        feature.setGravity(Gravity.CENTER_VERTICAL);
        feature.setBackground(gradient(Color.rgb(104, 53, 29), Color.rgb(53, 44, 43), 16));
        LinearLayout.LayoutParams featureParams = new LinearLayout.LayoutParams(-1, dp(42));
        featureParams.setMargins(dp(22), dp(3), dp(22), dp(11));
        root.addView(feature, featureParams);

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setPadding(dp(22), dp(8), dp(22), dp(14));
        search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setTextColor(WHITE);
        search.setHintTextColor(MUTED);
        search.setHint("Song, film, singer, writer or artist");
        search.setPadding(dp(16), 0, dp(12), 0);
        search.setBackground(background(CARD, 14));
        searchRow.addView(search, new LinearLayout.LayoutParams(0, dp(50), 1));
        root.addView(searchRow);
        LinearLayout languages = new LinearLayout(this);
        languages.setPadding(dp(22), 0, dp(22), dp(10));
        for (String option : new String[]{"Telugu", "Hindi", "English", "All"}) {
            TextView chip = label(option, 13, option.equals(language) ? ACCENT : MUTED, true);
            chip.setPadding(dp(8), dp(8), dp(8), dp(8));
            languages.addView(chip);
            chip.setOnClickListener(v -> {
                language = option;
                for (int i = 0; i < languages.getChildCount(); i++) {
                    TextView item = (TextView) languages.getChildAt(i);
                    item.setTextColor(item == chip ? ACCENT : MUTED);
                }
                String query = search.getText().toString().trim();
                if (query.isEmpty()) showDiscover(); else loadTracks(query);
            });
        }
        root.addView(languages);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().trim();
                if (searchTask != null) main.removeCallbacks(searchTask);
                searchTask = query.isEmpty() ? () -> showDiscover() : () -> loadTracks(query);
                main.postDelayed(searchTask, query.isEmpty() ? 0 : 400);
            }
            public void afterTextChanged(Editable e) { }
        });

        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(dp(22), 0, dp(22), dp(12));
        TextView discover = label("Discover", 17, ACCENT, true);
        discover.setPadding(0, dp(8), dp(24), dp(8));
        discover.setOnClickListener(v -> {
            showingFavorites = false; showingDownloads = false; selectedPlaylist = null;
            discover.setTextColor(ACCENT);
            savedTab.setTextColor(MUTED);
            if (search.length() > 0) search.setText("");
            else showDiscover();
        });
        tabs.addView(discover);
        TextView saved = label("♥  Favorites", 17, MUTED, true);
        savedTab = saved;
        saved.setPadding(0, dp(8), 0, dp(8));
        saved.setOnClickListener(v -> {
            showingFavorites = true; showingDownloads = false; showingLocal = false; showingRecent = false; selectedPlaylist = null;
            phoneAction.setVisibility(View.GONE);
            requestVersion++;
            sectionTitle.setText("Your favorites");
            message.setText(favorites.isEmpty() ? "No favorites yet. Tap the heart on a song to save it." : "Saved on this phone");
            showTracks(new ArrayList<>(favorites.values()));
            discover.setTextColor(MUTED); saved.setTextColor(ACCENT);
        });
        tabs.addView(saved);
        TextView listTab = label("Playlists", 16, MUTED, true);
        listTab.setPadding(dp(12), dp(8), dp(12), dp(8));
        listTab.setOnClickListener(v -> showPlaylistPicker());
        tabs.addView(listTab);
        TextView offlineTab = label("Offline", 16, MUTED, true);
        offlineTab.setPadding(0, dp(8), 0, dp(8));
        offlineTab.setOnClickListener(v -> showDownloads());
        tabs.addView(offlineTab);
        TextView phoneTab = label("On phone", 16, MUTED, true);
        phoneTab.setPadding(dp(12), dp(8), dp(12), dp(8));
        phoneTab.setOnClickListener(v -> showLocalSongs());
        tabs.addView(phoneTab);
        TextView recentTab = label("Recent", 16, MUTED, true);
        recentTab.setPadding(dp(12), dp(8), dp(12), dp(8));
        recentTab.setOnClickListener(v -> showRecent());
        tabs.addView(recentTab);
        HorizontalScrollView tabStrip = new HorizontalScrollView(this);
        tabStrip.setHorizontalScrollBarEnabled(false);
        tabStrip.addView(tabs);
        root.addView(tabStrip);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = vertical();
        content.setPadding(dp(22), dp(10), dp(22), dp(16));
        sectionTitle = label("Discover", 25, WHITE, true);
        content.addView(sectionTitle);
        message = label("Finding music on Audius…", 13, MUTED, false);
        LinearLayout.LayoutParams info = new LinearLayout.LayoutParams(-1, -2);
        info.topMargin = dp(7); info.bottomMargin = dp(15);
        content.addView(message, info);
        phoneAction = button("＋  Add downloaded audio from your phone", Color.rgb(88, 53, 35));
        phoneAction.setTextSize(14);
        phoneAction.setOnClickListener(v -> pickLocalSongs());
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(-1, dp(48));
        addParams.bottomMargin = dp(14);
        content.addView(phoneAction, addParams);
        phoneAction.setVisibility(View.GONE);
        rows = vertical(); content.addView(rows);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        player = vertical();
        player.setPadding(dp(18), dp(15), dp(18), dp(14));
        player.setBackground(gradient(Color.rgb(61, 46, 42), CARD, 20));
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
        miniArtwork = new ImageView(this);
        miniArtwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        miniArtwork.setBackground(gradient(Color.rgb(236, 123, 55), Color.rgb(95, 45, 37), 11));
        miniArtwork.setClipToOutline(true);
        LinearLayout.LayoutParams coverParams = new LinearLayout.LayoutParams(dp(52), dp(52));
        coverParams.rightMargin = dp(10);
        titleRow.addView(miniArtwork, coverParams);
        miniArtwork.setOnClickListener(v -> expandPlayer());
        LinearLayout metadata = vertical(); metadata.addView(nowTitle); metadata.addView(nowArtist);
        metadata.setOnClickListener(v -> expandPlayer());
        metadata.setContentDescription("Open full player");
        titleRow.addView(metadata, new LinearLayout.LayoutParams(0, -2, 1));
        favoriteButton = button("♡", CARD);
        favoriteButton.setTextColor(ACCENT);
        favoriteButton.setOnClickListener(v -> toggleFavorite());
        titleRow.addView(favoriteButton);
        TextView addPlaylist = button("+", CARD);
        addPlaylist.setContentDescription("Add song to playlist");
        addPlaylist.setOnClickListener(v -> { if (current != null) choosePlaylist(current); });
        titleRow.addView(addPlaylist);
        TextView sound = button("♫", CARD);
        sound.setContentDescription("Sound adjustments");
        sound.setOnClickListener(v -> showSoundDialog());
        titleRow.addView(sound);
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
        LinearLayout extras = new LinearLayout(this); extras.setGravity(Gravity.CENTER);
        TextView back10 = label("↶ 10s", 14, ACCENT, true);
        back10.setGravity(Gravity.CENTER); back10.setMinHeight(dp(44));
        back10.setOnClickListener(v -> seekRelative(-10000));
        extras.addView(back10, new LinearLayout.LayoutParams(0, -2, 1));
        TextView expand = label("Expand  ⌃", 13, WHITE, true);
        expand.setGravity(Gravity.CENTER); expand.setMinHeight(dp(44));
        expand.setOnClickListener(v -> expandPlayer());
        extras.addView(expand, new LinearLayout.LayoutParams(0, -2, 1));
        TextView forward10 = label("10s ↷", 14, ACCENT, true);
        forward10.setGravity(Gravity.CENTER); forward10.setMinHeight(dp(44));
        forward10.setOnClickListener(v -> seekRelative(10000));
        extras.addView(forward10, new LinearLayout.LayoutParams(0, -2, 1));
        player.addView(extras);
    }

    private void seekRelative(int deltaMs) {
        if (mediaPlayer == null) return;
        try { mediaPlayer.seekTo(Math.max(0, Math.min(mediaPlayer.getDuration(), mediaPlayer.getCurrentPosition() + deltaMs))); }
        catch (IllegalStateException ignored) { }
    }

    private String repeatLabel() {
        return repeatMode == 2 ? "↻ One" : repeatMode == 1 ? "↻ Queue" : "↻ Off";
    }

    private void cycleRepeat() {
        repeatMode = (repeatMode + 1) % 3;
        getPreferences(MODE_PRIVATE).edit().putInt("repeat", repeatMode).apply();
        if (expandedRepeat != null) expandedRepeat.setText(repeatLabel());
    }

    private void toggleShuffle() {
        shuffleEnabled = !shuffleEnabled;
        getPreferences(MODE_PRIVATE).edit().putBoolean("shuffle", shuffleEnabled).apply();
        if (expandedShuffle != null) expandedShuffle.setText(shuffleEnabled ? "⇄ Shuffle on" : "⇄ Shuffle off");
        if (playQueue.isEmpty() || currentIndex < 0) return;
        // Keep the current song in place and only reorder the songs still to come.
        List<Track> remaining = new ArrayList<>(playQueue.subList(currentIndex + 1, playQueue.size()));
        if (shuffleEnabled) Collections.shuffle(remaining);
        else if (visibleTracks.containsAll(remaining))
            remaining.sort((a, b) -> Integer.compare(visibleTracks.indexOf(a), visibleTracks.indexOf(b)));
        playQueue.subList(currentIndex + 1, playQueue.size()).clear();
        playQueue.addAll(remaining);
    }

    private void expandPlayer() {
        if (current == null) return;
        if (expanded != null && expanded.isShowing()) return;
        expanded = new Dialog(this, android.R.style.Theme_Material_NoActionBar);
        ScrollView screen = new ScrollView(this);
        screen.setFillViewport(true);
        LinearLayout content = vertical();
        content.setPadding(dp(24), dp(24), dp(24), dp(28));
        content.setBackground(gradient(Color.rgb(80, 42, 27), BG, 0));
        screen.addView(content);
        TextView close = label("⌄  NOW PLAYING", 15, WHITE, true);
        close.setPadding(0, dp(4), 0, dp(22));
        close.setOnClickListener(v -> expanded.dismiss());
        content.addView(close);
        expandedArtwork = new ImageView(this);
        expandedArtwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        expandedArtwork.setClipToOutline(true);
        expandedArtwork.setBackground(gradient(Color.rgb(236, 123, 55), Color.rgb(82, 40, 34), 24));
        int side = Math.min(dp(350), getResources().getDisplayMetrics().widthPixels - dp(48));
        LinearLayout.LayoutParams artParams = new LinearLayout.LayoutParams(side, side);
        artParams.gravity = Gravity.CENTER_HORIZONTAL;
        artParams.bottomMargin = dp(28);
        content.addView(expandedArtwork, artParams);
        expandedTitle = label(current.title, 27, WHITE, true);
        expandedTitle.setMaxLines(2);
        content.addView(expandedTitle);
        expandedArtist = label(current.artist, 17, MUTED, false);
        LinearLayout.LayoutParams artistParams = new LinearLayout.LayoutParams(-1, -2);
        artistParams.topMargin = dp(6); artistParams.bottomMargin = dp(18);
        content.addView(expandedArtist, artistParams);
        expandedTimeline = new SeekBar(this);
        expandedTimeline.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        expandedTimeline.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        expandedTimeline.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar s) { userSeeking = true; }
            public void onProgressChanged(SeekBar s, int value, boolean fromUser) {
                if (fromUser && expandedTime != null) expandedTime.setText(clock(value) + " / " + total.getText());
            }
            public void onStopTrackingTouch(SeekBar s) {
                try { if (mediaPlayer != null) mediaPlayer.seekTo(s.getProgress()); }
                catch (IllegalStateException ignored) { }
                userSeeking = false;
            }
        });
        content.addView(expandedTimeline);
        expandedTime = label("0:00 / 0:00", 13, MUTED, false);
        content.addView(expandedTime);
        LinearLayout controls = new LinearLayout(this); controls.setGravity(Gravity.CENTER);
        TextView previous = button("|◀", CARD); previous.setOnClickListener(v -> skip(-1));
        TextView rewind = button("↶10", CARD); rewind.setOnClickListener(v -> seekRelative(-10000));
        expandedPlay = button("▶", ACCENT); expandedPlay.setTextColor(BG);
        expandedPlay.setOnClickListener(v -> togglePlayback());
        TextView forward = button("10↷", CARD); forward.setOnClickListener(v -> seekRelative(10000));
        TextView next = button("▶|", CARD); next.setOnClickListener(v -> skip(1));
        for (TextView control : new TextView[]{previous,rewind,expandedPlay,forward,next}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1);
            lp.setMargins(dp(3), 0, dp(3), 0);
            controls.addView(control, lp);
        }
        LinearLayout.LayoutParams controlsParams = new LinearLayout.LayoutParams(-1, -2);
        controlsParams.topMargin = dp(17); controlsParams.bottomMargin = dp(14);
        content.addView(controls, controlsParams);
        LinearLayout actions = new LinearLayout(this); actions.setGravity(Gravity.CENTER);
        expandedRepeat = button(repeatLabel(), CARD);
        expandedRepeat.setTextSize(14);
        expandedRepeat.setOnClickListener(v -> cycleRepeat());
        actions.addView(expandedRepeat, new LinearLayout.LayoutParams(0, dp(48), 1));
        expandedShuffle = button(shuffleEnabled ? "⇄ Shuffle on" : "⇄ Shuffle off", CARD);
        expandedShuffle.setTextSize(13);
        expandedShuffle.setOnClickListener(v -> toggleShuffle());
        actions.addView(expandedShuffle, new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView add = button("+ Playlist", CARD); add.setTextSize(14);
        add.setOnClickListener(v -> { if (current != null) choosePlaylist(current); });
        actions.addView(add, new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView download = button("↓ Download", CARD); download.setTextSize(14);
        download.setOnClickListener(v -> { if (current != null) downloadTrack(current, download); });
        actions.addView(download, new LinearLayout.LayoutParams(0, dp(48), 1));
        content.addView(actions);
        TextView source = label("Music provided by Audius. Downloads require artist permission.", 12, MUTED, false);
        source.setPadding(0, dp(17), 0, 0); content.addView(source);
        expanded.setContentView(screen);
        expanded.setOnDismissListener(v -> { expandedArtwork = null; expandedTitle = null; expandedArtist = null;
            expandedPlay = null; expandedTime = null; expandedRepeat = null; expandedShuffle = null;
            expandedTimeline = null; expanded = null; });
        Window window = expanded.getWindow();
        if (window != null) window.setLayout(-1, -1);
        expanded.show();
        if (expanded.getWindow() != null) expanded.getWindow().setLayout(-1, -1);
        updateExpanded();
    }

    private void updateExpanded() {
        if (expanded == null || !expanded.isShowing() || current == null) return;
        expandedTitle.setText(current.title);
        expandedArtist.setText(current.artist + (current.details.isEmpty() ? "" : "  •  " + current.details));
        expandedPlay.setText(playButton.getText());
        expandedTimeline.setMax(timeline.getMax());
        expandedTimeline.setProgress(timeline.getProgress());
        expandedTime.setText(elapsed.getText() + " / " + total.getText());
        expandedArtwork.setImageDrawable(null);
        showArtwork(current, expandedArtwork);
    }

    private String clock(int ms) {
        int seconds = Math.max(0, ms / 1000);
        return (seconds / 60) + ":" + String.format(java.util.Locale.US, "%02d", seconds % 60);
    }

    private void showDiscover() {
        showingFavorites = false; showingDownloads = false; showingLocal = false;
        showingRecent = false; selectedPlaylist = null;
        if (savedTab != null) savedTab.setTextColor(MUTED);
        int version = ++requestVersion;
        sectionTitle.setText("Discover");
        message.setText("Popular and new uploads on Audius • updated when you open Discover");
        message.setOnClickListener(null);
        phoneAction.setVisibility(View.VISIBLE);
        rows.removeAllViews();

        LinearLayout welcome = vertical();
        welcome.setPadding(dp(18), dp(17), dp(18), dp(17));
        welcome.setBackground(gradient(Color.rgb(138, 69, 33), Color.rgb(57, 43, 42), 18));
        welcome.addView(label("♪  Find your next song", 19, WHITE, true));
        TextView hint = label("Discover new uploads, or add Telugu songs already saved on your phone.", 13, WHITE, false);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
        hintParams.topMargin = dp(8);
        welcome.addView(hint, hintParams);
        TextView refresh = label("↻  Refresh new uploads", 13, WHITE, true);
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(-1, -2);
        refreshParams.topMargin = dp(14);
        welcome.addView(refresh, refreshParams);
        refresh.setOnClickListener(v -> showDiscover());
        LinearLayout.LayoutParams welcomeParams = new LinearLayout.LayoutParams(-1, -2);
        welcomeParams.bottomMargin = dp(20);
        rows.addView(welcome, welcomeParams);
        TextView loading = label("Loading music…", 15, MUTED, false);
        rows.addView(loading);

        String chosenLanguage = language;
        work.execute(() -> {
            LinkedHashMap<String, Track> popular = new LinkedHashMap<>();
            LinkedHashMap<String, Track> latest = new LinkedHashMap<>();
            LinkedHashMap<String, Track> downloadable = new LinkedHashMap<>();
            String term = chosenLanguage.equals("All") ? "" : URLEncoder.encode(chosenLanguage, "UTF-8");
            try { fetchTracks(chosenLanguage.equals("All") ? "/trending?time=week&limit=25" :
                    "/search?query=" + term + "&sort_method=popular&limit=25", popular); }
            catch (Exception ignored) { /* Other shelves may still load. */ }
            if (version != requestVersion) return;
            try { fetchTracks(chosenLanguage.equals("All") ? "/latest?limit=25" :
                    "/search?query=" + term + "&sort_method=recent&limit=25", latest); }
            catch (Exception ignored) { /* Other shelves may still load. */ }
            if (version != requestVersion) return;
            try {
                String downloadQuery = URLEncoder.encode(chosenLanguage.equals("All") ? "music" : chosenLanguage, "UTF-8");
                fetchTracks("/search?query=" + downloadQuery + "&sort_method=popular&only_downloadable=true&limit=25", downloadable);
            } catch (Exception ignored) { /* Older Audius hosts may not support the filter. */ }
            for (Track track : popular.values()) if (track.downloadable) downloadable.putIfAbsent(track.id, track);
            for (Track track : latest.values()) if (track.downloadable) downloadable.putIfAbsent(track.id, track);
            List<Track> popularList = new ArrayList<>(popular.values());
            List<Track> latestList = new ArrayList<>(latest.values());
            List<Track> downloadList = new ArrayList<>();
            for (Track track : downloadable.values()) if (track.downloadable) downloadList.add(track);
            main.post(() -> {
                if (version != requestVersion || isFinishing()) return;
                rows.removeView(loading);
                addDiscoverShelf(chosenLanguage.equals("All") ? "Trending on Audius" : "Popular " + chosenLanguage + " on Audius", popularList);
                addDiscoverShelf(chosenLanguage.equals("All") ? "New on Audius" : "New " + chosenLanguage + " uploads", latestList);
                addDiscoverShelf("Available to download", downloadList);
                if (downloadList.isEmpty()) {
                    TextView unavailable = label("No artist-enabled downloads found in these " + chosenLanguage + " results. Try All, or import an audio file you already have.", 13, MUTED, false);
                    LinearLayout.LayoutParams unavailableParams = new LinearLayout.LayoutParams(-1, -2);
                    unavailableParams.bottomMargin = dp(16);
                    rows.addView(unavailable, unavailableParams);
                }
                TextView note = label("Only songs whose artists enable downloads can be saved offline. You can also add audio files already on your phone.", 12, MUTED, false);
                rows.addView(note);
                if (popularList.isEmpty() && latestList.isEmpty()) {
                    TextView empty = label("Couldn't find tracks here. Check your internet, try another language, or add your audio files above.", 14, MUTED, false);
                    rows.addView(empty);
                    message.setText("Tap here to retry discovery");
                    message.setOnClickListener(v -> showDiscover());
                }
            });
        });
    }

    private void addDiscoverShelf(String title, List<Track> tracks) {
        if (tracks.isEmpty()) return;
        List<Track> shelf = new ArrayList<>(tracks.subList(0, Math.min(12, tracks.size())));
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView headingText = label(title, 19, WHITE, true);
        heading.addView(headingText, new LinearLayout.LayoutParams(0, -2, 1));
        TextView all = label("See all  ›", 13, ACCENT, true);
        all.setPadding(dp(8), dp(14), 0, dp(14));
        all.setOnClickListener(v -> {
            requestVersion++;
            sectionTitle.setText(title);
            message.setText("Songs provided by Audius • downloads require artist permission");
            message.setOnClickListener(null);
            phoneAction.setVisibility(View.GONE);
            showTracks(tracks);
        });
        heading.addView(all);
        rows.addView(heading);
        LinearLayout cards = new LinearLayout(this);
        for (int i = 0; i < shelf.size(); i++) {
            Track track = shelf.get(i);
            int index = i;
            LinearLayout card = vertical();
            card.setPadding(dp(8), dp(8), dp(8), dp(10));
            card.setBackground(background(CARD, 15));
            ImageView cover = new ImageView(this);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setClipToOutline(true);
            cover.setBackground(gradient(Color.rgb(232, 115, 44), Color.rgb(82, 43, 35), 12));
            card.addView(cover, new LinearLayout.LayoutParams(dp(150), dp(150)));
            showArtwork(track, cover);
            TextView song = label(track.title, 14, WHITE, true);
            song.setSingleLine(true); song.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams songParams = new LinearLayout.LayoutParams(-1, -2);
            songParams.topMargin = dp(7);
            card.addView(song, songParams);
            TextView artist = label(track.artist, 12, MUTED, false);
            artist.setSingleLine(true); artist.setEllipsize(android.text.TextUtils.TruncateAt.END);
            card.addView(artist);
            TextView download = label(track.downloadable ? "↓  Download" : "Stream only", 12,
                    track.downloadable ? ACCENT : MUTED, true);
            download.setPadding(0, dp(8), 0, dp(4));
            card.addView(download);
            download.setOnClickListener(v -> downloadTrack(track, download));
            card.setOnClickListener(v -> {
                visibleTracks.clear(); visibleTracks.addAll(shelf);
                play(track, index);
            });
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(dp(166), -2);
            cardParams.rightMargin = dp(10);
            cards.addView(card, cardParams);
        }
        HorizontalScrollView carousel = new HorizontalScrollView(this);
        carousel.setHorizontalScrollBarEnabled(false);
        carousel.addView(cards);
        LinearLayout.LayoutParams shelfParams = new LinearLayout.LayoutParams(-1, -2);
        shelfParams.bottomMargin = dp(21);
        rows.addView(carousel, shelfParams);
    }

    private void loadTracks(String query) {
        if (query.isEmpty()) { showDiscover(); return; }
        showingFavorites = false; showingDownloads = false; showingLocal = false; showingRecent = false; selectedPlaylist = null;
        phoneAction.setVisibility(View.GONE);
        if (savedTab != null) savedTab.setTextColor(MUTED);
        int version = ++requestVersion;
        sectionTitle.setText(query.isEmpty() ? language + " music" : "Search results");
        message.setText("Loading tracks…"); rows.removeAllViews();
        work.execute(() -> {
            try {
                LinkedHashMap<String, Track> found = new LinkedHashMap<>();
                List<String> terms = new ArrayList<>();
                if (query.isEmpty()) {
                    if (language.equals("All")) fetchTracks("/trending?time=week&limit=40", found);
                    else {
                        terms.add(language); terms.add(language + " songs");
                        if (language.equals("Telugu")) terms.add("తెలుగు");
                    }
                } else {
                    terms.add(query);
                    if (!language.equals("All") && !query.toLowerCase(Locale.ROOT).contains(language.toLowerCase(Locale.ROOT)))
                        terms.add(query + " " + language);
                    if (query.contains(" ")) terms.add(query.substring(0, query.lastIndexOf(' ')));
                    String compact = query.replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ").trim();
                    if (!compact.isEmpty() && !compact.equalsIgnoreCase(query) && !terms.contains(compact)) terms.add(compact);
                }
                Exception failure = null;
                for (String term : terms) {
                    if (version != requestVersion) return;
                    try { fetchTracks("/search?query=" + URLEncoder.encode(term, "UTF-8") + "&sort_method=relevant&limit=40", found); }
                    catch (Exception error) { failure = error; }
                }
                if (!query.isEmpty()) for (Track own : localTracks.values())
                    if (score(own, query) > 0) found.put(own.id, own);
                if (found.isEmpty() && failure != null) throw failure;
                List<Track> tracks = new ArrayList<>(found.values());
                if (!query.isEmpty()) {
                    tracks.removeIf(track -> score(track, query) <= 0);
                    // Preserve source order for ties, but put exact song names before remixes and loose matches.
                    Collections.sort(tracks, (a, b) -> Integer.compare(score(b, query), score(a, query)));
                }
                if (tracks.size() > 60) tracks = new ArrayList<>(tracks.subList(0, 60));
                List<Track> result = tracks;
                main.post(() -> {
                    if (version != requestVersion || showingFavorites || showingRecent || isFinishing()) return;
                    message.setText(result.isEmpty() ? "Not in this catalog. Tap here to add music you own on your phone."
                            : "Search title, singer and any film or writer names supplied by artists • long press a song for details");
                    message.setOnClickListener(result.isEmpty() ? v -> pickLocalSongs() : null);
                    showTracks(result);
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

    private int score(Track track, String query) {
        String title = normalize(track.title), artist = normalize(track.artist), hints = normalize(track.searchHints);
        String needle = normalize(query);
        int score = title.equals(needle) ? 1000 : title.startsWith(needle) ? 700 : title.contains(needle) ? 500 : 0;
        if (artist.equals(needle)) score += 550;
        else if (artist.contains(needle)) score += 180;
        if (hints.contains(needle)) score += 250;
        for (String word : needle.split("\\s+")) if (word.length() > 2 && title.contains(word)) score += 30;
        for (String word : needle.split("\\s+")) if (word.length() > 2 && hints.contains(word)) score += 10;
        if (!language.equals("All") && (title + " " + artist).toLowerCase(Locale.ROOT).contains(language.toLowerCase(Locale.ROOT))) score += 15;
        if (language.equals("Telugu") && title.matches(".*[\\u0C00-\\u0C7F].*")) score += 15;
        return score;
    }

    private String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private void fetchTracks(String endpoint, Map<String, Track> found) throws Exception {
        JSONArray data = readJson(API + endpoint + "&app_name=Melody").optJSONArray("data");
        if (data == null) throw new Exception("Missing tracks");
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.optJSONObject(i);
            if (item == null || item.optBoolean("is_stream_gated") || item.optBoolean("isStreamGated")) continue;
            if (item.has("is_streamable") && !item.optBoolean("is_streamable")) continue;
            if (item.has("isStreamable") && !item.optBoolean("isStreamable")) continue;
            String id = item.optString("id");
            if (id.isEmpty()) continue;
            JSONObject user = item.optJSONObject("user"), artwork = item.optJSONObject("artwork");
            String album = item.optString("album_name", item.optString("albumName", ""));
            String hints = album + " " + item.optString("tags", "") + " " + item.optString("description", "")
                    + " " + item.optString("genre", "") + " " + item.optString("mood", "");
            boolean canDownload = (item.optBoolean("downloadable") || item.optBoolean("is_downloadable"))
                    && item.isNull("download_conditions") && item.isNull("downloadConditions");
            found.putIfAbsent(id, new Track(id, item.optString("title", "Untitled"),
                    user == null ? "Unknown artist" : user.optString("name", "Unknown artist"),
                    artwork == null ? "" : artwork.optString("480x480", artwork.optString("_480x480", "")), canDownload,
                    album, hints));
        }
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
            artwork.setBackground(gradient(Color.rgb(230, 116, 49), Color.rgb(94, 46, 35), 10));
            artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
            artwork.setClipToOutline(true);
            row.addView(artwork, new LinearLayout.LayoutParams(dp(57), dp(57)));
            showArtwork(track, artwork);
            LinearLayout labels = vertical();
            labels.setPadding(dp(12), 0, dp(5), 0);
            TextView title = label(track.title, 15, WHITE, true);
            title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            TextView artist = label(track.artist, 13, MUTED, false);
            artist.setSingleLine(true); artist.setEllipsize(android.text.TextUtils.TruncateAt.END);
            labels.addView(title); labels.addView(artist);
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            TextView add = label("+", 23, ACCENT, true);
            add.setGravity(Gravity.CENTER);
            add.setContentDescription("Add " + track.title + " to playlist");
            row.addView(add, new LinearLayout.LayoutParams(dp(39), dp(48)));
            add.setOnClickListener(v -> choosePlaylist(track));
            TextView download = label(track.isLocal() || downloads.containsKey(track.id) ? "✓" : "↓", 23,
                    track.isLocal() || track.downloadable || downloads.containsKey(track.id) ? ACCENT : MUTED, true);
            download.setGravity(Gravity.CENTER);
            download.setContentDescription(track.isLocal() ? "On this phone" : track.downloadable ? "Download " + track.title :
                    "Download unavailable; artist has not enabled it");
            row.addView(download, new LinearLayout.LayoutParams(dp(39), dp(48)));
            download.setOnClickListener(v -> downloadTrack(track, download));
            TextView arrow = label("▶", 17, ACCENT, true);
            arrow.setPadding(dp(8), 0, dp(7), 0); row.addView(arrow);
            row.setOnClickListener(v -> play(track, index));
            row.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this).setTitle(track.title)
                        .setMessage(track.artist + (track.details.isEmpty() ? "" : "\n" + track.details) +
                                "\n" + (track.isLocal() ? "On your phone" : "On Audius") +
                                "\nDownload: " + (track.isLocal() ? "Already on phone" : track.downloadable ? "Artist enabled" : "Not enabled by artist"))
                        .setPositiveButton("OK", null).show();
                return true;
            });
            rows.addView(row, rowParams);
        }
    }

    private void showArtwork(Track track, ImageView view) {
        view.setImageResource(R.drawable.ic_melody);
        if (track.isLocal()) {
            String id = track.id;
            if (artworkCache.containsKey(id)) { view.setImageBitmap(artworkCache.get(id)); return; }
            view.setTag(id);
            images.execute(() -> {
                MediaMetadataRetriever metadata = new MediaMetadataRetriever();
                try {
                    metadata.setDataSource(this, Uri.parse(id.substring(6)));
                    byte[] art = metadata.getEmbeddedPicture();
                    if (art == null || art.length > 3_000_000) return;
                    Bitmap bitmap = BitmapFactory.decodeByteArray(art, 0, art.length);
                    if (bitmap != null) main.post(() -> {
                        artworkCache.put(id, bitmap);
                        if (!isFinishing() && id.equals(view.getTag())) view.setImageBitmap(bitmap);
                    });
                } catch (Exception ignored) { }
                finally { try { metadata.release(); } catch (Exception ignored) { } }
            });
        } else loadArtwork(track.artwork, view);
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
        if (shuffleEnabled && index >= 0 && index < playQueue.size()) {
            List<Track> remaining = new ArrayList<>(playQueue);
            remaining.remove(index);
            Collections.shuffle(remaining);
            playQueue.clear(); playQueue.add(track); playQueue.addAll(remaining);
            currentIndex = 0;
        }
        int version = ++playVersion;
        player.setVisibility(View.VISIBLE);
        nowTitle.setText(track.title); nowArtist.setText("Connecting • " + track.artist);
        showArtwork(track, miniArtwork);
        playButton.setText("…"); elapsed.setText("0:00"); total.setText("0:00");
        timeline.setProgress(0); timeline.setMax(1);
        updateExpanded();
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
                applyEffects(mp);
                consecutiveErrors = 0;
                mp.start(); playButton.setText("Ⅱ"); updateExpanded();
                rememberPlayed(track);
            });
            next.setOnCompletionListener(mp -> {
                if (version != playVersion) return;
                if (repeatMode == 2) {
                    try { mp.seekTo(0); mp.start(); playButton.setText("Ⅱ"); updateExpanded(); }
                    catch (IllegalStateException ignored) { }
                } else if (repeatMode == 1 || currentIndex < playQueue.size() - 1) skip(1);
                else { playButton.setText("▶"); updateExpanded(); }
            });
            next.setOnErrorListener((mp, what, extra) -> {
                if (version == playVersion) {
                    nowArtist.setText("Track unavailable • trying next song"); playButton.setText("▶");
                    if (++consecutiveErrors < playQueue.size()) main.post(() -> { if (version == playVersion) skip(1); });
                    else { nowArtist.setText("No playable songs in this list"); updateExpanded(); }
                }
                return true;
            });
            mediaPlayer = next;
            if (track.isLocal()) next.setDataSource(this, Uri.parse(track.id.substring(6)));
            else {
                File offline = offlineFile(track.id);
                next.setDataSource(offline.isFile() && offline.length() > 0 ? offline.getAbsolutePath() :
                        API + "/" + URLEncoder.encode(track.id, "UTF-8") + "/stream?app_name=Melody");
            }
            next.prepareAsync();
        } catch (Exception e) {
            nowArtist.setText("Can't play this track. Choose another."); playButton.setText("▶");
            if (++consecutiveErrors < playQueue.size()) main.post(() -> skip(1));
        }
    }

    private void stopPlayer() {
        playVersion++;
        if (bassBoost != null) { bassBoost.release(); bassBoost = null; }
        if (equalizer != null) { equalizer.release(); equalizer = null; }
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
            updateExpanded();
        } catch (IllegalStateException ignored) { }
    }

    private void skip(int direction) {
        if (playQueue.isEmpty()) return;
        int index = currentIndex < 0 ? 0 : (currentIndex + direction + playQueue.size()) % playQueue.size();
        Track next = playQueue.get(index);
        List<Track> snapshot = new ArrayList<>(playQueue);
        play(next, index);
        playQueue.clear(); playQueue.addAll(snapshot);
        currentIndex = index;
    }

    private void updateProgress() {
        if (mediaPlayer != null && !userSeeking) {
            try { int position = mediaPlayer.getCurrentPosition(); timeline.setProgress(position); elapsed.setText(clock(position));
                if (expanded != null && expanded.isShowing() && expandedTimeline != null) {
                    expandedTimeline.setMax(timeline.getMax()); expandedTimeline.setProgress(position);
                    expandedTime.setText(clock(position) + " / " + total.getText());
                }
            }
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

    private Track savedTrack(JSONObject item) {
        return new Track(item.optString("id"), item.optString("title"), item.optString("artist"),
                item.optString("artwork"), item.optBoolean("downloadable"), item.optString("details"),
                item.optString("searchHints", item.optString("details")));
    }

    private void loadLocalSongs() {
        try {
            JSONArray data = new JSONArray(getPreferences(MODE_PRIVATE).getString("local_tracks", "[]"));
            for (int i = 0; i < data.length(); i++) {
                Track track = savedTrack(data.getJSONObject(i));
                if (track.isLocal()) localTracks.put(track.id, track);
            }
        } catch (Exception ignored) { }
    }

    private void loadRecent() {
        try {
            JSONArray data = new JSONArray(getPreferences(MODE_PRIVATE).getString("recent", "[]"));
            for (int i = 0; i < data.length(); i++) {
                Track track = savedTrack(data.getJSONObject(i));
                if (!track.id.isEmpty()) recentTracks.put(track.id, track);
            }
        } catch (Exception ignored) { }
    }

    private void rememberPlayed(Track track) {
        recentTracks.remove(track.id);
        LinkedHashMap<String, Track> updated = new LinkedHashMap<>();
        updated.put(track.id, track);
        updated.putAll(recentTracks);
        recentTracks.clear();
        JSONArray data = new JSONArray();
        for (Track recent : updated.values()) {
            if (recentTracks.size() >= 50) break;
            recentTracks.put(recent.id, recent);
            data.put(recent.json());
        }
        getPreferences(MODE_PRIVATE).edit().putString("recent", data.toString()).apply();
        if (showingRecent) showRecent();
    }

    private void showRecent() {
        showingRecent = true; showingLocal = false; showingFavorites = false;
        showingDownloads = false; selectedPlaylist = null; requestVersion++;
        phoneAction.setVisibility(View.GONE);
        sectionTitle.setText("Recently played");
        message.setText(recentTracks.isEmpty() ? "Songs you play will appear here."
                : "Saved on this phone • long press a song to remove it");
        savedTab.setTextColor(MUTED);
        showTracks(new ArrayList<>(recentTracks.values()));
        for (int i = 0; i < rows.getChildCount(); i++) {
            Track track = visibleTracks.get(i);
            rows.getChildAt(i).setOnLongClickListener(v -> {
                new AlertDialog.Builder(this).setMessage("Remove " + track.title + " from recent songs?")
                        .setNegativeButton("Cancel", null).setPositiveButton("Remove", (dialog, which) -> {
                            recentTracks.remove(track.id);
                            JSONArray data = new JSONArray();
                            for (Track recent : recentTracks.values()) data.put(recent.json());
                            getPreferences(MODE_PRIVATE).edit().putString("recent", data.toString()).apply();
                            showRecent();
                        }).show();
                return true;
            });
        }
    }

    private void saveLocalSongs() {
        JSONArray data = new JSONArray();
        for (Track track : localTracks.values()) data.put(track.json());
        getPreferences(MODE_PRIVATE).edit().putString("local_tracks", data.toString()).apply();
    }

    private void showLocalSongs() {
        showingLocal = true; showingDownloads = false; showingFavorites = false; showingRecent = false; selectedPlaylist = null; requestVersion++;
        phoneAction.setVisibility(View.VISIBLE);
        sectionTitle.setText("Music on your phone");
        message.setText(localTracks.isEmpty() ? "Add audio you own, including old Telugu film songs."
                : "Your files stay on your phone • search can use embedded film, singer and composer tags");
        savedTab.setTextColor(MUTED);
        showTracks(new ArrayList<>(localTracks.values()));
        for (int i = 0; i < rows.getChildCount(); i++) {
            Track track = visibleTracks.get(i);
            rows.getChildAt(i).setOnLongClickListener(v -> {
                new AlertDialog.Builder(this).setMessage("Remove " + track.title + " from Melody? The original audio file stays on your phone.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Remove", (dialog, which) -> {
                            localTracks.remove(track.id); saveLocalSongs(); showLocalSongs();
                        }).show();
                return true;
            });
        }
    }

    private void pickLocalSongs() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_AUDIO);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_AUDIO || resultCode != RESULT_OK || data == null) return;
        List<Uri> selected = new ArrayList<>();
        if (data.getData() != null) selected.add(data.getData());
        ClipData clip = data.getClipData();
        if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) selected.add(clip.getItemAt(i).getUri());
        for (Uri uri : selected) {
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (SecurityException ignored) { }
            work.execute(() -> {
                MediaMetadataRetriever metadata = new MediaMetadataRetriever();
                try {
                    metadata.setDataSource(this, uri);
                    String title = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                    String artist = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                    String album = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
                    String composer = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER);
                    String author = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR);
                    String genre = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE);
                    if (title == null || title.trim().isEmpty()) title = "Audio on phone";
                    if (artist == null || artist.trim().isEmpty()) artist = "Unknown singer";
                    String details = album == null ? "" : album;
                    String hints = details + " " + (composer == null ? "" : composer) + " " +
                            (author == null ? "" : author) + " " + (genre == null ? "" : genre);
                    Track track = new Track("local:" + uri, title, artist, "", false, details, hints);
                    main.post(() -> {
                        localTracks.put(track.id, track); saveLocalSongs();
                        if (showingLocal) showLocalSongs();
                    });
                } catch (Exception failure) { main.post(() -> toast("Couldn't read an audio file")); }
                finally { try { metadata.release(); } catch (Exception ignored) { } }
            });
        }
        showLocalSongs();
    }

    private void loadCollections() {
        try {
            JSONArray data = new JSONArray(getPreferences(MODE_PRIVATE).getString("downloads", "[]"));
            for (int i = 0; i < data.length(); i++) {
                Track track = savedTrack(data.getJSONObject(i));
                if (offlineFile(track.id).isFile()) downloads.put(track.id, track);
            }
            JSONObject lists = new JSONObject(getPreferences(MODE_PRIVATE).getString("playlists", "{}"));
            java.util.Iterator<String> names = lists.keys();
            while (names.hasNext()) {
                String name = names.next();
                JSONArray items = lists.getJSONArray(name);
                List<Track> tracks = new ArrayList<>();
                for (int i = 0; i < items.length(); i++) tracks.add(savedTrack(items.getJSONObject(i)));
                playlists.put(name, tracks);
            }
        } catch (Exception ignored) { }
    }

    private void saveCollections() {
        JSONArray offline = new JSONArray();
        for (Track track : downloads.values()) offline.put(track.json());
        JSONObject lists = new JSONObject();
        try {
            for (Map.Entry<String, List<Track>> entry : playlists.entrySet()) {
                JSONArray tracks = new JSONArray();
                for (Track track : entry.getValue()) tracks.put(track.json());
                lists.put(entry.getKey(), tracks);
            }
        } catch (Exception ignored) { }
        getPreferences(MODE_PRIVATE).edit().putString("downloads", offline.toString())
                .putString("playlists", lists.toString()).apply();
    }

    private void createPlaylist(Track addTrack) {
        EditText input = new EditText(this);
        input.setSingleLine(true); input.setHint("Playlist name");
        new AlertDialog.Builder(this).setTitle("New playlist").setView(input)
                .setNegativeButton("Cancel", null).setPositiveButton("Create", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty() || name.length() > 45) { toast("Enter a name up to 45 characters"); return; }
                    if (playlists.containsKey(name)) { toast("That playlist already exists"); return; }
                    List<Track> tracks = new ArrayList<>();
                    if (addTrack != null) tracks.add(addTrack);
                    playlists.put(name, tracks); saveCollections();
                    toast("Playlist created");
                    if (addTrack == null) showPlaylist(name);
                }).show();
    }

    private void choosePlaylist(Track track) {
        List<String> choices = new ArrayList<>(playlists.keySet());
        choices.add("+ Create new playlist");
        new AlertDialog.Builder(this).setTitle("Add to playlist")
                .setItems(choices.toArray(new String[0]), (dialog, which) -> {
                    if (which == choices.size() - 1) { createPlaylist(track); return; }
                    String name = choices.get(which);
                    List<Track> tracks = playlists.get(name);
                    for (Track existing : tracks) if (existing.id.equals(track.id)) { toast("Already in " + name); return; }
                    tracks.add(track); saveCollections(); toast("Added to " + name);
                    if (name.equals(selectedPlaylist)) showPlaylist(name);
                }).show();
    }

    private void showPlaylistPicker() {
        List<String> choices = new ArrayList<>(playlists.keySet());
        choices.add("+ Create new playlist");
        new AlertDialog.Builder(this).setTitle("Your playlists")
                .setItems(choices.toArray(new String[0]), (dialog, which) -> {
                    if (which == choices.size() - 1) createPlaylist(null);
                    else showPlaylist(choices.get(which));
                }).show();
    }

    private void showPlaylist(String name) {
        selectedPlaylist = name; showingFavorites = false; showingDownloads = false; showingLocal = false; showingRecent = false; requestVersion++;
        phoneAction.setVisibility(View.GONE);
        sectionTitle.setText(name);
        message.setText("Saved on this phone • long press a song to remove it");
        showTracks(new ArrayList<>(playlists.get(name)));
        savedTab.setTextColor(MUTED);
        for (int i = 0; i < rows.getChildCount(); i++) {
            final Track track = visibleTracks.get(i);
            rows.getChildAt(i).setOnLongClickListener(v -> {
                new AlertDialog.Builder(this).setMessage("Remove " + track.title + " from " + name + "?")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Remove", (d, w) -> {
                            List<Track> tracks = playlists.get(name);
                            tracks.removeIf(t -> t.id.equals(track.id)); saveCollections(); showPlaylist(name);
                        }).show();
                return true;
            });
        }
    }

    private File offlineFile(String id) {
        // Never use a server supplied title or ID as a path component.
        return new File(getFilesDir(), "song_" + Integer.toHexString(id.hashCode()) + ".mp3");
    }

    private void showDownloads() {
        showingDownloads = true; showingFavorites = false; showingLocal = false; showingRecent = false; selectedPlaylist = null; requestVersion++;
        phoneAction.setVisibility(View.GONE);
        sectionTitle.setText("Offline songs");
        message.setText(downloads.isEmpty() ? "Songs with artist enabled downloads appear here." :
                "Stored privately on this phone • long press to delete");
        showTracks(new ArrayList<>(downloads.values()));
        savedTab.setTextColor(MUTED);
        for (int i = 0; i < rows.getChildCount(); i++) {
            Track track = visibleTracks.get(i);
            rows.getChildAt(i).setOnLongClickListener(v -> {
                new AlertDialog.Builder(this).setMessage("Delete offline copy of " + track.title + "?")
                        .setNegativeButton("Cancel", null).setPositiveButton("Delete", (d, w) -> {
                            offlineFile(track.id).delete(); downloads.remove(track.id); saveCollections(); showDownloads();
                        }).show();
                return true;
            });
        }
    }

    private void downloadTrack(Track track, TextView button) {
        if (track.isLocal()) { toast("This song is already on your phone"); return; }
        if (downloads.containsKey(track.id)) { toast("Already saved offline"); return; }
        if (!track.downloadable) { toast("This artist has not enabled downloads for the song"); return; }
        button.setText("…");
        work.execute(() -> {
            File output = offlineFile(track.id), temp = new File(output.getPath() + ".part");
            HttpURLConnection connection = null;
            try {
                String url = API + "/" + URLEncoder.encode(track.id, "UTF-8") + "/download?app_name=Melody&original=false";
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(12000); connection.setReadTimeout(25000);
                if (connection.getResponseCode() != 200) throw new Exception("Download unavailable");
                if (!connection.getURL().getProtocol().equals("https")) throw new Exception("Insecure redirect");
                String type = connection.getContentType();
                if (type != null && (type.contains("json") || type.contains("html"))) throw new Exception("No audio available");
                long length = 0;
                try (InputStream input = connection.getInputStream(); FileOutputStream file = new FileOutputStream(temp)) {
                    byte[] buffer = new byte[32768]; int n;
                    while ((n = input.read(buffer)) != -1) {
                        length += n;
                        if (length > 100_000_000L) throw new Exception("Song exceeds 100 MB limit");
                        file.write(buffer, 0, n);
                    }
                }
                if (length < 1024 || !temp.renameTo(output)) throw new Exception("Incomplete download");
                main.post(() -> {
                    downloads.put(track.id, track); saveCollections(); button.setText("✓"); toast("Saved for offline listening");
                    if (showingDownloads) showDownloads();
                });
            } catch (Exception error) {
                temp.delete();
                main.post(() -> { button.setText("↓"); toast("Download unavailable for this song"); });
            } finally { if (connection != null) connection.disconnect(); }
        });
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }

    private void applyEffects(MediaPlayer mp) {
        try {
            bassBoost = new BassBoost(0, mp.getAudioSessionId());
            bassBoost.setStrength((short) bassLevel);
            bassBoost.setEnabled(bassLevel > 0);
        } catch (Exception unavailable) { bassBoost = null; }
        try {
            equalizer = new Equalizer(0, mp.getAudioSessionId());
            if (eqPreset > 0 && eqPreset <= equalizer.getNumberOfPresets())
                equalizer.usePreset((short) (eqPreset - 1));
            equalizer.setEnabled(eqPreset > 0);
        } catch (Exception unavailable) { equalizer = null; }
    }

    private void showSoundDialog() {
        LinearLayout body = vertical(); body.setPadding(dp(22), dp(12), dp(22), dp(8));
        body.addView(label("Bass boost", 16, Color.DKGRAY, true));
        SeekBar bass = new SeekBar(this); bass.setMax(1000); bass.setProgress(bassLevel);
        bass.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar s) { }
            public void onProgressChanged(SeekBar s, int value, boolean fromUser) {
                if (!fromUser) return;
                bassLevel = value;
                try { if (bassBoost != null) { bassBoost.setStrength((short) value); bassBoost.setEnabled(value > 0); } }
                catch (Exception ignored) { }
                getPreferences(MODE_PRIVATE).edit().putInt("bass", value).apply();
            }
            public void onStopTrackingTouch(SeekBar s) { }
        });
        body.addView(bass);
        body.addView(label("Equalizer presets", 16, Color.DKGRAY, true));
        List<String> names = new ArrayList<>(); names.add("Normal");
        if (equalizer != null) {
            for (short i = 0; i < equalizer.getNumberOfPresets(); i++)
                names.add(equalizer.getPresetName(i));
        }
        android.widget.Spinner presets = new android.widget.Spinner(this);
        presets.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        presets.setSelection(Math.min(eqPreset, names.size() - 1));
        body.addView(presets);
        new AlertDialog.Builder(this).setTitle("Sound adjustments").setView(body)
                .setPositiveButton("Done", (dialog, which) -> {
                    eqPreset = presets.getSelectedItemPosition();
                    try { if (equalizer != null) {
                        if (eqPreset > 0) equalizer.usePreset((short) (eqPreset - 1));
                        equalizer.setEnabled(eqPreset > 0);
                    } } catch (Exception ignored) { }
                    getPreferences(MODE_PRIVATE).edit().putInt("eqPreset", eqPreset).apply();
                }).show();
    }

    private void loadFavorites() {
        try {
            JSONArray data = new JSONArray(getPreferences(MODE_PRIVATE).getString("favorites", "[]"));
            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.getJSONObject(i);
                Track track = savedTrack(item);
                favorites.put(track.id, track);
            }
        } catch (Exception ignored) { favorites.clear(); }
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (expanded != null) expanded.dismiss();
        stopPlayer();
        work.shutdownNow();
        images.shutdownNow();
        super.onDestroy();
    }
}
