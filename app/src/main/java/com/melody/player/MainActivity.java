package com.melody.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;
import android.widget.ImageView;
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
    private final Map<String, Track> downloads = new LinkedHashMap<>();
    private final Map<String, List<Track>> playlists = new LinkedHashMap<>();
    private final List<Track> visibleTracks = new ArrayList<>();
    private final List<Track> playQueue = new ArrayList<>();
    private final Map<String, Bitmap> artworkCache = new HashMap<>();

    private LinearLayout rows, root, player;
    private TextView sectionTitle, message, nowTitle, nowArtist, playButton, favoriteButton, elapsed, total;
    private SeekBar timeline;
    private EditText search;
    private MediaPlayer mediaPlayer;
    private BassBoost bassBoost;
    private Equalizer equalizer;
    private int bassLevel, eqPreset;
    private String language = "Telugu";
    private String selectedPlaylist;
    private boolean showingDownloads = false;
    private Track current;
    private int currentIndex = -1;
    private int requestVersion = 0;
    private int playVersion = 0;
    private boolean showingFavorites = false;
    private boolean userSeeking = false;

    private static final class Track {
        final String id, title, artist, artwork;
        final boolean downloadable;
        Track(String id, String title, String artist, String artwork, boolean downloadable) {
            this.id = id; this.title = title; this.artist = artist; this.artwork = artwork;
            this.downloadable = downloadable;
        }
        JSONObject json() {
            JSONObject value = new JSONObject();
            try { value.put("id", id); value.put("title", title); value.put("artist", artist); value.put("artwork", artwork); value.put("downloadable", downloadable); }
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
        bassLevel = getPreferences(MODE_PRIVATE).getInt("bass", 0);
        eqPreset = getPreferences(MODE_PRIVATE).getInt("eqPreset", 0);
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
        TextView tagline = label("Telugu first • Hindi & English • No ads", 14, MUTED, false);
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
                loadTracks(search.getText().toString().trim());
            });
        }
        root.addView(languages);
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
            showingFavorites = false; showingDownloads = false; selectedPlaylist = null;
            discover.setTextColor(ACCENT);
            savedTab.setTextColor(MUTED);
            loadTracks(search.getText().toString().trim());
        });
        tabs.addView(discover);
        TextView saved = label("♥  Favorites", 17, MUTED, true);
        savedTab = saved;
        saved.setPadding(0, dp(8), 0, dp(8));
        saved.setOnClickListener(v -> {
            showingFavorites = true; showingDownloads = false; selectedPlaylist = null;
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
    }

    private String clock(int ms) {
        int seconds = Math.max(0, ms / 1000);
        return (seconds / 60) + ":" + String.format(java.util.Locale.US, "%02d", seconds % 60);
    }

    private void loadTracks(String query) {
        showingFavorites = false; showingDownloads = false; selectedPlaylist = null;
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
                }
                Exception failure = null;
                for (String term : terms) {
                    if (version != requestVersion) return;
                    try { fetchTracks("/search?query=" + URLEncoder.encode(term, "UTF-8") + "&sort_method=relevant&limit=40", found); }
                    catch (Exception error) { failure = error; }
                }
                if (found.isEmpty() && failure != null) throw failure;
                List<Track> tracks = new ArrayList<>(found.values());
                if (!query.isEmpty()) {
                    // Preserve source order for ties, but put exact song names before remixes and loose matches.
                    Collections.sort(tracks, (a, b) -> Integer.compare(score(b, query), score(a, query)));
                }
                if (tracks.size() > 60) tracks = new ArrayList<>(tracks.subList(0, 60));
                List<Track> result = tracks;
                main.post(() -> {
                    if (version != requestVersion || showingFavorites || isFinishing()) return;
                    message.setText(result.isEmpty() ? "No songs found on Audius. Try artist name or another spelling." : "Songs available on Audius • exact titles first");
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
        String title = track.title.toLowerCase(Locale.ROOT), artist = track.artist.toLowerCase(Locale.ROOT);
        String needle = query.toLowerCase(Locale.ROOT).trim();
        int score = title.equals(needle) ? 1000 : title.startsWith(needle) ? 700 : title.contains(needle) ? 500 : 0;
        if (artist.equals(needle)) score += 550;
        else if (artist.contains(needle)) score += 180;
        for (String word : needle.split("\\s+")) if (word.length() > 2 && title.contains(word)) score += 30;
        if (!language.equals("All") && (title + " " + artist).toLowerCase(Locale.ROOT).contains(language.toLowerCase(Locale.ROOT))) score += 15;
        if (language.equals("Telugu") && title.matches(".*[\\u0C00-\\u0C7F].*")) score += 15;
        return score;
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
            boolean canDownload = (item.optBoolean("downloadable") || item.optBoolean("is_downloadable"))
                    && item.isNull("download_conditions") && item.isNull("downloadConditions");
            found.putIfAbsent(id, new Track(id, item.optString("title", "Untitled"),
                    user == null ? "Unknown artist" : user.optString("name", "Unknown artist"),
                    artwork == null ? "" : artwork.optString("480x480", artwork.optString("_480x480", "")), canDownload));
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
            TextView add = label("+", 23, ACCENT, true);
            add.setGravity(Gravity.CENTER);
            add.setContentDescription("Add " + track.title + " to playlist");
            row.addView(add, new LinearLayout.LayoutParams(dp(39), dp(48)));
            add.setOnClickListener(v -> choosePlaylist(track));
            if (track.downloadable || downloads.containsKey(track.id)) {
                TextView download = label(downloads.containsKey(track.id) ? "✓" : "↓", 23, ACCENT, true);
                download.setGravity(Gravity.CENTER);
                download.setContentDescription(downloads.containsKey(track.id) ? "Saved offline" : "Download " + track.title);
                row.addView(download, new LinearLayout.LayoutParams(dp(39), dp(48)));
                download.setOnClickListener(v -> downloadTrack(track, download));
            }
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
                applyEffects(mp);
                mp.start(); playButton.setText("Ⅱ");
            });
            next.setOnCompletionListener(mp -> { if (version == playVersion) skip(1); });
            next.setOnErrorListener((mp, what, extra) -> {
                if (version == playVersion) { nowArtist.setText("Can't play this track. Choose another."); playButton.setText("▶"); }
                return true;
            });
            mediaPlayer = next;
            File offline = offlineFile(track.id);
            next.setDataSource(offline.isFile() && offline.length() > 0 ? offline.getAbsolutePath() :
                    API + "/" + URLEncoder.encode(track.id, "UTF-8") + "/stream?app_name=Melody");
            next.prepareAsync();
        } catch (Exception e) {
            nowArtist.setText("Can't play this track. Choose another."); playButton.setText("▶");
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

    private Track savedTrack(JSONObject item) {
        return new Track(item.optString("id"), item.optString("title"), item.optString("artist"),
                item.optString("artwork"), item.optBoolean("downloadable"));
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
        selectedPlaylist = name; showingFavorites = false; showingDownloads = false; requestVersion++;
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
        showingDownloads = true; showingFavorites = false; selectedPlaylist = null; requestVersion++;
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
        if (downloads.containsKey(track.id)) { toast("Already saved offline"); return; }
        if (!track.downloadable) { toast("Artist did not enable downloads"); return; }
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
        stopPlayer();
        work.shutdownNow();
        images.shutdownNow();
        super.onDestroy();
    }
}
