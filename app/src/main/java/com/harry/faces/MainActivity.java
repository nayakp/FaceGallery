package com.harry.faces;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.DecelerateInterpolator;
import android.widget.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@android.annotation.SuppressLint("ExifInterface")
public final class MainActivity extends Activity {
    private static final long SINGLE_GROUP_ID = Long.MIN_VALUE;
    private static final int PHOTO_PERMISSION_REQUEST = 42;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 43;
    private static final int EXPORT_BACKUP_REQUEST = 44;
    private static final int IMPORT_BACKUP_REQUEST = 45;
    private static final int DELETE_PHOTO_REQUEST = 46;
    private static final int DELETE_PERMISSION_REQUEST = 47;
    private static final int PHOTO_LOCATION_PERMISSION_REQUEST = 48;
    private static final int DELETE_FACE_PHOTOS_REQUEST = 49;
    private static final int NAV_HOME = 1;
    private static final int NAV_SEARCH = 2;
    private static final int NAV_ALBUMS = 3;
    private static final int DUPLICATE_PAGE_SIZE = 50;
    // Placeholder until the public Play Store listing is available. Keep the
    // redirect in one place so it can be replaced without changing the UI.
    private static final String PLAY_STORE_URL =
            "https://play.google.com/store/apps/details?id=com.harry.faces";
    private static final String ISSUES_URL = "https://github.com/nayakp/FaceGallery/issues";
    private static final int FACE_THUMB_CORNER_DP = 16;

    private final int BG = Color.rgb(247, 248, 252);
    private final int INK = Color.rgb(25, 32, 49);
    private final int BLUE = Color.rgb(48, 87, 213);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService images = Executors.newFixedThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())));
    private final ExecutorService data = Executors.newSingleThreadExecutor();
    private final ExecutorService fullImages = Executors.newSingleThreadExecutor();
    private final android.util.LruCache<String, Bitmap> photoCache =
            new android.util.LruCache<>(adaptiveCacheBytes()) {
                @Override protected int sizeOf(String key, Bitmap bitmap) {
                    return bitmap.getAllocationByteCount();
                }
            };
    private final android.util.LruCache<Long, Bitmap> faceCache =
            new android.util.LruCache<>(adaptiveFaceCacheBytes()) {
                @Override protected int sizeOf(Long key, Bitmap bitmap) {
                    return bitmap.getAllocationByteCount();
                }
            };
    private final android.util.LruCache<String, Bitmap> pagerPreviewCache =
            new android.util.LruCache<>(adaptivePagerCacheBytes()) {
                @Override protected int sizeOf(String key, Bitmap bitmap) {
                    return bitmap.getAllocationByteCount();
                }
            };
    private final Set<String> pagerPreviewLoads =
            Collections.synchronizedSet(new HashSet<>());

    private Store store;
    private LinearLayout root;
    private Switch settingsScanToggle;
    private TextView settingsScanInfo;
    private Switch settingsReevaluationToggle;
    private TextView settingsReevaluationInfo;
    private EditText searchInput;
    private PeopleAdapter peopleAdapter;
    private AlbumPickerAdapter albumPickerAdapter;
    private DeleteFacePickerAdapter deleteFacePickerAdapter;
    private TextView peopleEmpty;
    private TextView albumSelectedCount;
    private LinearLayout albumSelectedStrip;
    private HorizontalScrollView albumSelectedScroller;
    private TextView deleteFaceSelectedCount;
    private LinearLayout deleteFaceSelectedStrip;
    private HorizontalScrollView deleteFaceSelectedScroller;
    private GridView grid;
    private Bitmap fullBitmap;
    private PhotoPager photoPager;
    private View photoTopControls;
    private View photoBottomControls;
    private View homeFloatingBar;
    private View scanActivityDot;
    private Dialog activeDialog;
    private Dialog faceDuplicateListDialog;
    private int homeNavigationInsetBottom;

    private long personId = -1;
    private long singleCoverPersonId = -1;
    private String photoUri;
    private String pendingDeleteUri;
    private String pendingInfoUri;
    private int gridPosition;
    private int homePosition;
    private int singlesPosition;
    private boolean singlesScreen;
    private boolean personFromSingles;
    private boolean pendingScanAfterPermission;
    private boolean pendingReevaluationAfterNotification;
    private boolean visible;
    private boolean lastScanRunning;
    private boolean settingsOpen;
    private boolean helpSupportOpen;
    private boolean settingsClosing;
    private boolean syncingScanToggle;
    private boolean syncingReevaluationToggle;
    private boolean photoControlsVisible = true;
    private boolean searchOpen;
    private boolean albumsOpen;
    private boolean albumPickerOpen;
    private boolean albumPickerFromDetail;
    private boolean albumShowUnnamed;
    private boolean deleteFacePickerOpen;
    private boolean deleteFaceShowUnnamed;
    private boolean returnToDeleteFacePicker;
    private String searchQuery = "";
    private String albumFaceQuery = "";
    private String deleteFaceQuery = "";
    private long activeAlbumId = -1;
    private long returnAlbumId = -1;
    private float settingsTouchDownX;
    private float settingsTouchDownY;
    private int faceThumbDp = 116;
    private int albumThumbDp = 176;
    private int photoThumbPx;
    private long loadedPersonPhotoId = -1;
    private long loadedAlbumPhotoId = -1;
    private int photoListGeneration;
    private int fullPhotoGeneration;

    private List<Store.Person> allPeople = new ArrayList<>();
    private List<Store.Person> displayedPeople = new ArrayList<>();
    private List<Store.Person> albumPickerPeople = new ArrayList<>();
    private List<Store.Person> deleteFacePickerPeople = new ArrayList<>();
    private List<Store.Album> faceAlbums = new ArrayList<>();
    private List<String> photoList = new ArrayList<>();
    private final LinkedHashSet<Long> albumDraftSelection = new LinkedHashSet<>();
    private final LinkedHashSet<Long> deleteFaceSelection = new LinkedHashSet<>();
    private final HashSet<Long> faceDuplicateQueueLoading = new HashSet<>();
    private final ArrayList<String> pendingBulkDeleteUris = new ArrayList<>();

    private final ScanState.Listener scanListener = snapshot -> handler.post(() -> {
        boolean finished = lastScanRunning && !snapshot.running();
        lastScanRunning = snapshot.running();
        updateStatus(snapshot);
        if (finished && visible && personId < 0 && photoUri == null) {
            rememberHomePosition();
            loadedAlbumPhotoId = -1;
            loadPeopleThenRender();
        }
    });

    private static int adaptiveCacheBytes() {
        long available = Runtime.getRuntime().maxMemory() / 20L;
        return (int) Math.max(6L * 1024 * 1024, Math.min(12L * 1024 * 1024, available));
    }

    private static int adaptiveFaceCacheBytes() {
        long available = Runtime.getRuntime().maxMemory() / 32L;
        return (int) Math.max(6L * 1024 * 1024, Math.min(12L * 1024 * 1024, available));
    }

    private static int adaptivePagerCacheBytes() {
        long available = Runtime.getRuntime().maxMemory() / 3L;
        return (int) Math.max(64L * 1024 * 1024, Math.min(160L * 1024 * 1024, available));
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = Store.get(getApplicationContext());
        if (state != null) {
            personId = state.getLong("person", -1);
            photoUri = state.getString("photo");
            pendingDeleteUri = state.getString("pending_delete_uri");
            pendingInfoUri = state.getString("pending_info_uri");
            photoControlsVisible = state.getBoolean("photo_controls_visible", true);
            gridPosition = state.getInt("grid", 0);
            homePosition = state.getInt("home", 0);
            singlesPosition = state.getInt("singles_position", 0);
            singlesScreen = state.getBoolean("singles_screen", false);
            personFromSingles = state.getBoolean("person_from_singles", false);
            settingsOpen = state.getBoolean("settings_open", false);
            helpSupportOpen = state.getBoolean("help_support_open", false);
            searchOpen = state.getBoolean("search_open", false);
            searchQuery = state.getString("search_query", "");
            albumsOpen = state.getBoolean("albums_open", false);
            albumPickerOpen = state.getBoolean("album_picker_open", false);
            albumPickerFromDetail = state.getBoolean("album_picker_from_detail", false);
            albumShowUnnamed = state.getBoolean("album_show_unnamed", false);
            albumFaceQuery = state.getString("album_face_query", "");
            deleteFacePickerOpen = state.getBoolean("delete_face_picker_open", false);
            deleteFaceShowUnnamed = state.getBoolean("delete_face_show_unnamed", false);
            returnToDeleteFacePicker = state.getBoolean("return_to_delete_face_picker", false);
            deleteFaceQuery = state.getString("delete_face_query", "");
            activeAlbumId = state.getLong("active_album", -1);
            returnAlbumId = state.getLong("return_album", -1);
            long[] selected = state.getLongArray("album_draft_selection");
            if (selected != null) for (long id : selected) albumDraftSelection.add(id);
            long[] deleteSelected = state.getLongArray("delete_face_selection");
            if (deleteSelected != null) for (long id : deleteSelected) deleteFaceSelection.add(id);
            ArrayList<String> pendingDeletes = state.getStringArrayList("pending_bulk_delete_uris");
            if (pendingDeletes != null) pendingBulkDeleteUris.addAll(pendingDeletes);
        }
        lastScanRunning = ScanState.current().running();
        applyNormalWindowLayout();
        showLaunchScreen();
        loadPeopleThenRender();
    }

    @Override protected void onStart() {
        super.onStart();
        visible = true;
        ScanState.addListener(scanListener);
    }

    @Override protected void onStop() {
        visible = false;
        ScanState.removeListener(scanListener);
        super.onStop();
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        int firstVisible = grid == null ? -1 : grid.getFirstVisiblePosition();
        if (grid != null && personId < 0 && !albumsOpen && !albumPickerOpen
                && !deleteFacePickerOpen) rememberHomePosition();
        else if (firstVisible >= 0) gridPosition = firstVisible;
        super.onConfigurationChanged(newConfig);
        photoThumbPx = 0;
        render();
        if (firstVisible >= 0 && grid != null) grid.setSelection(firstVisible);
        updateActiveDialogBounds();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            pagerPreviewCache.evictAll();
            pagerPreviewLoads.clear();
            return;
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            photoCache.trimToSize(Math.max(1, adaptiveCacheBytes() / 2));
            faceCache.trimToSize(Math.max(1, adaptiveFaceCacheBytes() / 2));
            pagerPreviewCache.trimToSize(Math.max(1, adaptivePagerCacheBytes() / 2));
        }
    }

    @Override protected void onDestroy() {
        ScanState.removeListener(scanListener);
        images.shutdownNow();
        data.shutdownNow();
        fullImages.shutdownNow();
        releaseFullBitmap();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void showLaunchScreen() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(BG);
        TextView title = text("FaceGallery", 24);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));
        ProgressBar progress = new ProgressBar(this);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(40), dp(40));
        progressParams.setMargins(0, dp(22), 0, dp(12));
        root.addView(progress, progressParams);
        TextView message = text("Loading your gallery\u2026", 14);
        message.setTextColor(Color.GRAY);
        message.setGravity(Gravity.CENTER);
        root.addView(message, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
    }

    private void loadPeopleThenRender() {
        data.execute(() -> {
            try {
                List<Store.Person> loaded = store.people();
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    allPeople = loaded;
                    render();
                });
            } catch (RuntimeException error) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    allPeople = new ArrayList<>();
                    render();
                    toast("The face index could not be loaded: " + errorMessage(error));
                });
            }
        });
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putLong("person", personId);
        state.putString("photo", photoUri);
        state.putString("pending_delete_uri", pendingDeleteUri);
        state.putString("pending_info_uri", pendingInfoUri);
        state.putBoolean("photo_controls_visible", photoControlsVisible);
        state.putInt("grid", gridPosition);
        state.putInt("home", homePosition);
        state.putInt("singles_position", singlesPosition);
        state.putBoolean("singles_screen", singlesScreen);
        state.putBoolean("person_from_singles", personFromSingles);
        state.putBoolean("settings_open", settingsOpen);
        state.putBoolean("help_support_open", helpSupportOpen);
        state.putBoolean("search_open", searchOpen);
        state.putString("search_query", searchQuery);
        state.putBoolean("albums_open", albumsOpen);
        state.putBoolean("album_picker_open", albumPickerOpen);
        state.putBoolean("album_picker_from_detail", albumPickerFromDetail);
        state.putBoolean("album_show_unnamed", albumShowUnnamed);
        state.putString("album_face_query", albumFaceQuery);
        state.putBoolean("delete_face_picker_open", deleteFacePickerOpen);
        state.putBoolean("delete_face_show_unnamed", deleteFaceShowUnnamed);
        state.putBoolean("return_to_delete_face_picker", returnToDeleteFacePicker);
        state.putString("delete_face_query", deleteFaceQuery);
        state.putLong("active_album", activeAlbumId);
        state.putLong("return_album", returnAlbumId);
        long[] selected = new long[albumDraftSelection.size()];
        int selectedIndex = 0;
        for (long id : albumDraftSelection) selected[selectedIndex++] = id;
        state.putLongArray("album_draft_selection", selected);
        long[] deleteSelected = new long[deleteFaceSelection.size()];
        int deleteIndex = 0;
        for (long id : deleteFaceSelection) deleteSelected[deleteIndex++] = id;
        state.putLongArray("delete_face_selection", deleteSelected);
        state.putStringArrayList("pending_bulk_delete_uris", pendingBulkDeleteUris);
        super.onSaveInstanceState(state);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + .5f);
    }

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
    }

    private int usableWindowWidthPx() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowMetrics metrics = getWindowManager().getCurrentWindowMetrics();
            android.graphics.Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            return Math.max(1, metrics.getBounds().width() - insets.left - insets.right);
        }
        WindowInsets insets = getWindow().getDecorView().getRootWindowInsets();
        int left = insets == null ? 0 : insets.getSystemWindowInsetLeft();
        int right = insets == null ? 0 : insets.getSystemWindowInsetRight();
        return Math.max(1, getResources().getDisplayMetrics().widthPixels - left - right);
    }

    private int gridWidthDp() {
        return Math.max(1, Math.round(usableWindowWidthPx()
                / getResources().getDisplayMetrics().density) - 8);
    }

    private int gridWidthPx() {
        return Math.max(1, usableWindowWidthPx() - dp(8));
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(INK);
        return view;
    }

    private Button button(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setStateListAnimator(null);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
        styleButton(button, Color.rgb(232, 237, 252), BLUE);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    /** Flat pill with ripple; inset keeps neighbouring buttons visually apart. */
    private void styleButton(Button button, int fill, int textColor) {
        button.setTextColor(textColor);
        button.setBackground(new android.graphics.drawable.InsetDrawable(
                new android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf((textColor & 0xFFFFFF) | 0x33000000),
                        rounded(fill, 22), null), dp(3)));
        button.setPadding(dp(18), 0, dp(18), 0);
    }

    private GradientDrawable rounded(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private String personName() {
        for (Store.Person person : allPeople) if (person.id() == personId) return person.title();
        return "Person " + personId;
    }

    private void render() {
        if (scanActivityDot != null) scanActivityDot.clearAnimation();
        scanActivityDot = null;
        allPeople = store.people();
        if (helpSupportOpen) {
            renderHelpSupport();
            return;
        }
        if (settingsOpen) {
            renderSettings(false);
            return;
        }
        if (deleteFacePickerOpen) {
            renderDeleteFacePicker();
            return;
        }
        if (albumPickerOpen) {
            renderAlbumPicker();
            return;
        }
        if (albumsOpen) {
            renderAlbums();
            return;
        }
        if (photoUri != null) {
            showFull();
            return;
        }
        grid = null;
        peopleAdapter = null;
        albumPickerAdapter = null;
        peopleEmpty = null;
        searchInput = null;
        homeFloatingBar = null;
        homeNavigationInsetBottom = 0;
        if (personId < 0 && !singlesScreen) applyHomeWindowLayout();
        else applyNormalWindowLayout();
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(4), dp(8), dp(4), 0);
        setContentView(root);

        FrameLayout toolbar = new FrameLayout(this);
        LinearLayout navigation = new LinearLayout(this);
        navigation.setGravity(Gravity.CENTER_VERTICAL);
        if (personId < 0 && photoUri == null) {
            TextView menu = text("☰", 27);
            menu.setGravity(Gravity.CENTER);
            menu.setBackgroundColor(Color.TRANSPARENT);
            menu.setContentDescription("Open settings");
            menu.setClickable(true);
            menu.setFocusable(true);
            menu.setOnClickListener(view -> openSettings());
            navigation.addView(menu, new LinearLayout.LayoutParams(dp(52), dp(52)));
        }
        if (personId >= 0 || singlesScreen) {
            VectorIcon back = new VectorIcon(this, R.drawable.ic_back);
            back.setLineColor(INK);
            back.setContentDescription("Back");
            back.setBackgroundColor(Color.TRANSPARENT);
            back.setClickable(true);
            back.setFocusable(true);
            back.setOnClickListener(view -> onBackPressed());
            navigation.addView(back, new LinearLayout.LayoutParams(dp(48), dp(52)));
        }
        toolbar.addView(navigation, new FrameLayout.LayoutParams(-2, dp(56), Gravity.START | Gravity.CENTER_VERTICAL));

        String heading = personId >= 0 ? personName() : singlesScreen ? "Single Face Pics" : "FaceGallery";
        TextView title = text(heading, 21);
        title.setTypeface(null, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(-1, dp(56), Gravity.CENTER);
        int titleMargin = dp(singlesScreen && personId < 0 ? 104 : 56);
        titleParams.setMargins(titleMargin, 0, titleMargin, 0);
        toolbar.addView(title, titleParams);

        if (personId >= 0) {
            TextView personSettings = text("\u2699", 25);
            personSettings.setGravity(Gravity.CENTER);
            personSettings.setBackgroundColor(Color.TRANSPARENT);
            personSettings.setContentDescription("Face settings");
            personSettings.setClickable(true);
            personSettings.setFocusable(true);
            personSettings.setOnClickListener(this::showPersonSettings);
            toolbar.addView(personSettings, new FrameLayout.LayoutParams(
                    dp(52), dp(56), Gravity.END | Gravity.CENTER_VERTICAL));
        }

        scanActivityDot = new View(this);
        scanActivityDot.setBackground(rounded(Color.rgb(35, 190, 82), 5));
        scanActivityDot.setContentDescription("Intensive face processing in progress");
        scanActivityDot.setVisibility(View.GONE);
        FrameLayout.LayoutParams scanDotParams = new FrameLayout.LayoutParams(
                dp(9), dp(9), Gravity.END | Gravity.CENTER_VERTICAL);
        scanDotParams.setMargins(0, 0, dp(personId >= 0 ? 50 : 16), 0);
        toolbar.addView(scanActivityDot, scanDotParams);
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(56)));

        updateStatus(ScanState.current());

        if (personId < 0) {
            if (!singlesScreen && searchOpen) showHomeSearch();
            showPeople();
        }
        else showPhotos();

    }

    private void showHomeSearch() {
        searchInput = new EditText(this);
        searchInput.setHint("Search faces");
        searchInput.setSingleLine(true);
        searchInput.setText(searchQuery);
        searchInput.setSelection(searchInput.length());
        searchInput.setTextSize(16);
        searchInput.setTextColor(INK);
        searchInput.setHintTextColor(Color.GRAY);
        searchInput.setPadding(dp(16), 0, dp(16), 0);
        GradientDrawable background = rounded(Color.WHITE, 18);
        background.setStroke(dp(1), Color.rgb(218, 222, 232));
        searchInput.setBackground(background);
        searchInput.setContentDescription("Search faces by name");
        searchInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable text) {
                searchQuery = text.toString();
                applyPeopleSearch();
            }
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(52));
        params.setMargins(dp(10), dp(4), dp(10), dp(8));
        root.addView(searchInput, params);
        searchInput.requestFocus();
        handler.post(() -> {
            if (searchInput == null || !searchInput.isAttachedToWindow()) return;
            ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void showPersonSettings(View anchor) {
        final long currentPersonId = personId;
        PopupMenu menu = new PopupMenu(this, anchor);
        boolean preparing=faceDuplicateQueueLoading.contains(currentPersonId);
        android.view.MenuItem check=menu.getMenu().add(0,1,0,
                preparing?"Checking possible duplicates…":"Check possible duplicates");
        check.setEnabled(!preparing);
        if(!preparing&&store.hasQueuedDuplicatesForPerson(currentPersonId))
            menu.getMenu().add(0,2,1,"Review duplicates");
        menu.setOnMenuItemClickListener(item -> {
            if(item.getItemId()==1)prepareFaceDuplicateReview(currentPersonId);
            else if(item.getItemId()==2)showFaceDuplicateQueue(currentPersonId);
            return true;
        });
        menu.show();
    }

    private void showPeople() {
        applyPeopleSearch();

        if (singlesScreen) {
            TextView subtitle = text("FACES FOUND IN ONE PHOTO", 12);
            subtitle.setTextColor(BLUE);
            subtitle.setPadding(dp(5), dp(12), 0, dp(14));
            root.addView(subtitle);
        }

        FrameLayout peopleArea = new FrameLayout(this);
        grid = new GridView(this);
        int gridWidthDp = gridWidthDp();
        int gridSidePaddingDp = 8;
        int availableDp = Math.max(1, gridWidthDp - gridSidePaddingDp * 2);
        int targetThumbDp = 120;
        int gapDp = 8;
        int minimumColumns = availableDp >= 280 ? 3 : availableDp >= 184 ? 2 : 1;
        int columns = Math.max(minimumColumns, Math.round((availableDp + gapDp)
                / (float) (targetThumbDp + gapDp)));
        int fittedThumbDp = (availableDp - gapDp * (columns - 1)) / columns;
        faceThumbDp = fittedThumbDp;
        int columnWidthDp = faceThumbDp;
        int groupWidthDp = columns * columnWidthDp + (columns - 1) * gapDp;
        int unusedWidthDp = Math.max(0, gridWidthDp - groupWidthDp);
        int leftPaddingDp = unusedWidthDp / 2;
        int rightPaddingDp = unusedWidthDp - leftPaddingDp;
        grid.setNumColumns(columns);
        // Adapt the column count to the current window width, then divide all
        // remaining space between equal thumbnails and equal gaps. The root's
        // 4dp inset plus this grid's 8dp inset produces a 12dp screen margin.
        grid.setColumnWidth(dp(columnWidthDp));
        grid.setStretchMode(GridView.NO_STRETCH);
        grid.setGravity(Gravity.CENTER_HORIZONTAL);
        int bottomScrollInset = singlesScreen ? 0 : dp(84) + homeNavigationInsetBottom;
        grid.setPadding(dp(leftPaddingDp), 0, dp(rightPaddingDp), bottomScrollInset);
        grid.setClipToPadding(false);
        grid.setHorizontalSpacing(dp(gapDp));
        grid.setVerticalSpacing(dp(6));
        peopleAdapter = new PeopleAdapter();
        grid.setAdapter(peopleAdapter);
        peopleEmpty = text(peopleEmptyMessage(), 19);
        peopleEmpty.setGravity(Gravity.CENTER);
        peopleArea.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        peopleArea.addView(peopleEmpty, new FrameLayout.LayoutParams(-1, -1));
        grid.setEmptyView(peopleEmpty);
        root.addView(peopleArea, new LinearLayout.LayoutParams(-1, 0, 1));
        grid.setSelection(!searchQuery.trim().isEmpty() ? 0
                : singlesScreen ? singlesPosition : homePosition);
        grid.setOnItemClickListener((parent, view, position, id) -> {
            rememberHomePosition();
            Store.Person selected = displayedPeople.get(position);
            if (selected.id() == SINGLE_GROUP_ID) {
                searchOpen = false;
                searchQuery = "";
                hideKeyboard();
                singlesScreen = true;
                singlesPosition = 0;
                render();
                return;
            }
            personId = selected.id();
            personFromSingles = singlesScreen;
            gridPosition = 0;
            render();
        });

        if (!fullAccess()) {
            Button access = button(anyAccess() ? "Allow access to all photos" : "Choose photo access",
                    this::requestPhotos);
            access.setContentDescription("Photo access");
            root.addView(access);
        }
        if (!singlesScreen) addFloatingBar(peopleArea, searchOpen ? NAV_SEARCH : NAV_HOME);
    }

    private void applyPeopleSearch() {
        List<Store.Person> singletons = new ArrayList<>();
        List<Store.Person> frequent = new ArrayList<>();
        for (Store.Person person : allPeople) {
            if (person.count() == 1) singletons.add(person);
            else frequent.add(person);
        }
        singleCoverPersonId = singletons.isEmpty() ? -1 : singletons.get(0).id();
        ArrayList<Store.Person> candidates = new ArrayList<>(singlesScreen ? singletons : frequent);
        if (!singlesScreen && !singletons.isEmpty()) {
            candidates.add(new Store.Person(SINGLE_GROUP_ID, "Single Face Pics", null, singletons.size()));
        }
        String query = searchQuery.trim().toLowerCase(Locale.ROOT);
        if (!query.isEmpty()) candidates.removeIf(person ->
                !person.title().toLowerCase(Locale.ROOT).contains(query));
        displayedPeople = candidates;
        if (peopleAdapter != null) peopleAdapter.notifyDataSetChanged();
        if (peopleEmpty != null) peopleEmpty.setText(peopleEmptyMessage());
        if (grid != null && !query.isEmpty()) grid.setSelection(0);
    }

    private String peopleEmptyMessage() {
        String query = searchQuery.trim();
        if (!query.isEmpty()) return "No faces match \"" + query + "\".";
        return singlesScreen ? "No single-photo faces yet."
                : "Your memories, together.\n\nOpen Settings to start face recognition.\n"
                + "Everything is processed on this phone.";
    }

    private void addFloatingBar(FrameLayout peopleArea, int activeItem) {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(8), 0, dp(8), 0);
        GradientDrawable background = rounded(Color.WHITE, 32);
        background.setStroke(dp(1), Color.rgb(218, 222, 232));
        bar.setBackground(background);
        bar.setElevation(dp(10));

        VectorIcon settings = new VectorIcon(this, R.drawable.ic_settings);
        settings.setLineColor(INK);
        settings.setContentDescription("Settings");
        settings.setOnClickListener(view -> openSettings());
        VectorIcon home = new VectorIcon(this, R.drawable.ic_home);
        home.setLineColor(activeItem == NAV_HOME ? BLUE : INK);
        home.setContentDescription("Home");
        home.setOnClickListener(view -> goHome());
        VectorIcon search = new VectorIcon(this, R.drawable.ic_search);
        search.setLineColor(activeItem == NAV_SEARCH ? BLUE : INK);
        search.setContentDescription(activeItem == NAV_SEARCH ? "Close face search" : "Search faces");
        search.setOnClickListener(view -> {
            if (albumsOpen) openHomeSearch();
            else toggleHomeSearch();
        });
        VectorIcon albums = new VectorIcon(this, R.drawable.ic_albums);
        albums.setLineColor(activeItem == NAV_ALBUMS ? BLUE : INK);
        albums.setContentDescription("Face albums");
        albums.setOnClickListener(view -> openAlbums());
        bar.addView(settings, new LinearLayout.LayoutParams(dp(52), dp(56)));
        bar.addView(new Space(this), new LinearLayout.LayoutParams(dp(8), dp(1)));
        bar.addView(home, new LinearLayout.LayoutParams(dp(52), dp(56)));
        bar.addView(new Space(this), new LinearLayout.LayoutParams(dp(8), dp(1)));
        bar.addView(search, new LinearLayout.LayoutParams(dp(52), dp(56)));
        bar.addView(new Space(this), new LinearLayout.LayoutParams(dp(8), dp(1)));
        bar.addView(albums, new LinearLayout.LayoutParams(dp(52), dp(56)));

        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
                dp(260), dp(58), Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        barParams.setMargins(0, 0, 0, dp(10) + homeNavigationInsetBottom);
        peopleArea.addView(bar, barParams);
        homeFloatingBar = bar;
        updateHomeBottomInset(homeNavigationInsetBottom);
    }

    private void goHome() {
        personId = -1;
        photoUri = null;
        singlesScreen = false;
        personFromSingles = false;
        homePosition = 0;
        searchOpen = false;
        searchQuery = "";
        albumsOpen = false;
        albumPickerOpen = false;
        activeAlbumId = -1;
        returnAlbumId = -1;
        hideKeyboard();
        render();
    }

    private void openAlbums() {
        rememberHomePosition();
        searchOpen = false;
        searchQuery = "";
        hideKeyboard();
        albumsOpen = true;
        albumPickerOpen = false;
        activeAlbumId = -1;
        render();
    }

    private void openHomeSearch() {
        albumsOpen = false;
        albumPickerOpen = false;
        activeAlbumId = -1;
        searchOpen = true;
        searchQuery = "";
        render();
    }

    private void toggleHomeSearch() {
        searchOpen = !searchOpen;
        if (!searchOpen) {
            searchQuery = "";
            hideKeyboard();
        }
        render();
    }

    private void hideKeyboard() {
        View current = getCurrentFocus();
        if (current != null) ((android.view.inputmethod.InputMethodManager)
                getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(current.getWindowToken(), 0);
    }

    private void renderAlbums() {
        grid = null;
        peopleAdapter = null;
        albumPickerAdapter = null;
        peopleEmpty = null;
        searchInput = null;
        homeFloatingBar = null;
        homeNavigationInsetBottom = 0;
        applyHomeWindowLayout();

        Store.Album found = activeAlbumId < 0 ? null : store.album(activeAlbumId);
        if (activeAlbumId >= 0 && found == null) activeAlbumId = -1;
        final Store.Album currentAlbum = activeAlbumId < 0 ? null : found;

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(4), dp(8), dp(4), 0);
        setContentView(root);

        FrameLayout header = new FrameLayout(this);
        VectorIcon back = new VectorIcon(this, R.drawable.ic_back);
        back.setLineColor(INK);
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setContentDescription(currentAlbum == null ? "Back to faces" : "Back to albums");
        back.setClickable(true);
        back.setFocusable(true);
        back.setOnClickListener(view -> {
            if (activeAlbumId >= 0) {
                activeAlbumId = -1;
                render();
            } else goHome();
        });
        header.addView(back, new FrameLayout.LayoutParams(dp(52), dp(56),
                Gravity.START | Gravity.CENTER_VERTICAL));

        TextView title = text(currentAlbum == null ? "Face Albums" : currentAlbum.name(), 21);
        title.setTypeface(null, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(-1, dp(56), Gravity.CENTER);
        titleParams.setMargins(dp(58), 0, dp(58), 0);
        header.addView(title, titleParams);

        TextView add = text("+", 30);
        add.setGravity(Gravity.CENTER);
        add.setBackgroundColor(Color.TRANSPARENT);
        add.setContentDescription(currentAlbum == null ? "Create face album" : "Add faces to album");
        add.setClickable(true);
        add.setFocusable(true);
        add.setOnClickListener(view -> {
            if (currentAlbum == null) promptCreateAlbum();
            else startAlbumPicker(currentAlbum.id(), true);
        });
        header.addView(add, new FrameLayout.LayoutParams(dp(52), dp(56),
                Gravity.END | Gravity.CENTER_VERTICAL));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(56)));

        FrameLayout area = new FrameLayout(this);
        GridView albumGrid = new GridView(this);
        grid = albumGrid;
        albumGrid.setStretchMode(GridView.NO_STRETCH);
        albumGrid.setGravity(Gravity.CENTER_HORIZONTAL);
        albumGrid.setClipToPadding(false);
        int bottomInset = dp(84) + homeNavigationInsetBottom;
        TextView empty = text("", 18);
        empty.setGravity(Gravity.CENTER);
        empty.setTextColor(Color.DKGRAY);

        if (currentAlbum == null) {
            configureAlbumGrid(albumGrid, bottomInset);
            faceAlbums = store.albums();
            albumGrid.setAdapter(new AlbumAdapter());
            empty.setText("No face albums yet.\n\nTap + to create an album.");
            albumGrid.setOnItemClickListener((parent, view, position, id) -> {
                Store.Album album = faceAlbums.get(position);
                if (album.faceCount() == 0) startAlbumPicker(album.id(), false);
                else {
                    activeAlbumId = album.id();
                    gridPosition = 0;
                    render();
                }
            });
        } else {
            configureAlbumPhotoGrid(albumGrid, bottomInset);
            long requestedAlbum = currentAlbum.id();
            boolean reusePhotos = loadedAlbumPhotoId == requestedAlbum;
            if (!reusePhotos) photoList = new ArrayList<>();
            PhotosAdapter adapter = new PhotosAdapter();
            albumGrid.setAdapter(adapter);
            albumGrid.setSelection(gridPosition);
            empty.setText(reusePhotos ? "This album has no photos.\n\nTap + to add faces."
                    : "Loading album photos\u2026");
            albumGrid.setOnItemClickListener((parent, view, position, id) -> {
                gridPosition = albumGrid.getFirstVisiblePosition();
                returnAlbumId = requestedAlbum;
                albumsOpen = false;
                personId = -1;
                photoUri = photoList.get(position);
                photoControlsVisible = true;
                personFromSingles = false;
                singlesScreen = false;
                render();
            });
            if (!reusePhotos) {
                int generation = ++photoListGeneration;
                data.execute(() -> {
                    List<String> loaded = albumPhotosByDate(requestedAlbum);
                    runOnUiThread(() -> {
                        if (isDestroyed() || generation != photoListGeneration || !albumsOpen
                                || albumPickerOpen || activeAlbumId != requestedAlbum) return;
                        photoList = loaded;
                        loadedAlbumPhotoId = requestedAlbum;
                        loadedPersonPhotoId = -1;
                        empty.setText("This album has no photos.\n\nTap + to add faces.");
                        adapter.notifyDataSetChanged();
                        albumGrid.setSelection(gridPosition);
                    });
                });
            }
        }
        area.addView(albumGrid, new FrameLayout.LayoutParams(-1, -1));
        area.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        albumGrid.setEmptyView(empty);
        root.addView(area, new LinearLayout.LayoutParams(-1, 0, 1));
        addFloatingBar(area, NAV_ALBUMS);
        updateStatus(ScanState.current());
    }

    private void configureAlbumGrid(GridView albumGrid, int bottomInset) {
        int gridWidthDp = gridWidthDp();
        int sidePaddingDp = 8;
        int availableDp = Math.max(1, gridWidthDp - sidePaddingDp * 2);
        int gapDp = 12;
        int targetDp = 176;
        int columns = Math.max(2, Math.round((availableDp + gapDp) / (float) (targetDp + gapDp)));
        albumThumbDp = (availableDp - gapDp * (columns - 1)) / columns;
        int groupWidthDp = columns * albumThumbDp + (columns - 1) * gapDp;
        int unusedDp = Math.max(0, gridWidthDp - groupWidthDp);
        albumGrid.setNumColumns(columns);
        albumGrid.setColumnWidth(dp(albumThumbDp));
        albumGrid.setHorizontalSpacing(dp(gapDp));
        albumGrid.setVerticalSpacing(dp(12));
        albumGrid.setPadding(dp(unusedDp / 2), dp(4), dp(unusedDp - unusedDp / 2), bottomInset);
    }

    private void configureAlbumFaceGrid(GridView faceGrid, int bottomInset) {
        int gridWidthDp = gridWidthDp();
        int sidePaddingDp = 8;
        int availableDp = Math.max(1, gridWidthDp - sidePaddingDp * 2);
        int gapDp = 8;
        int targetDp = 120;
        int minimumColumns = availableDp >= 280 ? 3 : availableDp >= 184 ? 2 : 1;
        int columns = Math.max(minimumColumns,
                Math.round((availableDp + gapDp) / (float) (targetDp + gapDp)));
        faceThumbDp = (availableDp - gapDp * (columns - 1)) / columns;
        int groupWidthDp = columns * faceThumbDp + (columns - 1) * gapDp;
        int unusedDp = Math.max(0, gridWidthDp - groupWidthDp);
        faceGrid.setNumColumns(columns);
        faceGrid.setColumnWidth(dp(faceThumbDp));
        faceGrid.setHorizontalSpacing(dp(gapDp));
        faceGrid.setVerticalSpacing(dp(8));
        faceGrid.setPadding(dp(unusedDp / 2), dp(4), dp(unusedDp - unusedDp / 2), bottomInset);
    }

    private void configureAlbumPhotoGrid(GridView photoGrid, int bottomInset) {
        configurePhotoGrid(photoGrid, bottomInset);
    }

    private void configurePhotoGrid(GridView photoGrid, int bottomInset) {
        int gridWidthPx = gridWidthPx();
        int preferredGapPx = Math.max(1, dp(3));
        int preferredThumbPx = dp(120);
        int columns = Math.max(3, (gridWidthPx + preferredGapPx)
                / Math.max(1, preferredThumbPx + preferredGapPx));
        int thumbPx = Math.max(1,
                (gridWidthPx - preferredGapPx * (columns + 1)) / columns);
        int remainder = gridWidthPx - thumbPx * columns;
        while (remainder % (columns + 1) != 0 && thumbPx > 1) {
            thumbPx--;
            remainder += columns;
        }
        int gapPx = Math.max(1, remainder / (columns + 1));
        photoThumbPx = thumbPx;
        photoGrid.setNumColumns(columns);
        photoGrid.setColumnWidth(photoThumbPx);
        photoGrid.setStretchMode(GridView.NO_STRETCH);
        photoGrid.setGravity(Gravity.CENTER_HORIZONTAL);
        photoGrid.setHorizontalSpacing(gapPx);
        photoGrid.setVerticalSpacing(gapPx);
        photoGrid.setPadding(gapPx, gapPx, gapPx, bottomInset);
        photoGrid.setClipToPadding(false);
    }

    /**
     * Builds one chronological stream for the whole album.  The database query
     * intentionally returns the union of every selected person's photos; this
     * final sort must therefore never use person/face order as a tie breaker.
     */
    private List<String> albumPhotosByDate(long albumId) {
        List<String> photos = store.albumPhotos(albumId);
        if (photos.size() < 2) return photos;

        HashMap<Long, Long> timestamps = new HashMap<>();
        ArrayList<Long> ids = new ArrayList<>(photos.size());
        for (String value : photos) {
            try {
                ids.add(ContentUris.parseId(Uri.parse(value)));
            } catch (RuntimeException ignored) {
            }
        }

        if (anyAccess()) {
            final int batchSize = 400;
            String[] projection = new String[]{MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.DATE_MODIFIED};
            for (int start = 0; start < ids.size(); start += batchSize) {
                int end = Math.min(start + batchSize, ids.size());
                StringBuilder selection = new StringBuilder(MediaStore.Images.Media._ID).append(" IN (");
                String[] arguments = new String[end - start];
                for (int index = start; index < end; index++) {
                    if (index > start) selection.append(',');
                    selection.append('?');
                    arguments[index - start] = Long.toString(ids.get(index));
                }
                selection.append(')');
                try (Cursor cursor = getContentResolver().query(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection,
                        selection.toString(), arguments, null)) {
                    if (cursor == null) continue;
                    while (cursor.moveToNext()) {
                        long taken = cursor.getLong(1);
                        long added = cursor.getLong(2) * 1000L;
                        long modified = cursor.getLong(3) * 1000L;
                        timestamps.put(cursor.getLong(0), taken > 0 ? taken
                                : added > 0 ? added : modified);
                    }
                } catch (RuntimeException ignored) {
                    // Media ids still provide a global newest-first fallback if a
                    // row becomes unavailable while the album is opening.
                }
            }
        }
        photos.sort((first, second) -> {
            int byDate = Long.compare(albumPhotoTimestamp(second, timestamps),
                    albumPhotoTimestamp(first, timestamps));
            if (byDate != 0) return byDate;
            int byMediaId = Long.compare(albumPhotoMediaId(second), albumPhotoMediaId(first));
            return byMediaId != 0 ? byMediaId : second.compareTo(first);
        });
        return photos;
    }

    private long albumPhotoTimestamp(String value, Map<Long, Long> timestamps) {
        return timestamps.getOrDefault(albumPhotoMediaId(value), 0L);
    }

    private long albumPhotoMediaId(String value) {
        try {
            return ContentUris.parseId(Uri.parse(value));
        } catch (RuntimeException ignored) {
            return -1L;
        }
    }

    private void promptCreateAlbum() {
        EditText input = new EditText(this);
        input.setHint("Family, Friends, Office\u2026");
        input.setSingleLine(true);
        input.setSelectAllOnFocus(false);
        int inset = dp(20);
        FrameLayout wrapper = new FrameLayout(this);
        wrapper.setPadding(inset, 0, inset, 0);
        wrapper.addView(input, new FrameLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Create face album")
                .setView(wrapper)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        input.setError("Enter an album name");
                        return;
                    }
                    store.createAlbum(name);
                    dialog.dismiss();
                    render();
                }));
        dialog.show();
    }

    private void startAlbumPicker(long albumId, boolean fromDetail) {
        activeAlbumId = albumId;
        albumPickerFromDetail = fromDetail;
        albumPickerOpen = true;
        albumShowUnnamed = false;
        albumFaceQuery = "";
        albumDraftSelection.clear();
        albumDraftSelection.addAll(store.albumPersonIds(albumId));
        hideKeyboard();
        render();
    }

    private void cancelAlbumPicker() {
        hideKeyboard();
        albumPickerOpen = false;
        albumFaceQuery = "";
        albumShowUnnamed = false;
        albumDraftSelection.clear();
        if (!albumPickerFromDetail) activeAlbumId = -1;
        render();
    }

    private void saveAlbumPicker() {
        store.setAlbumPeople(activeAlbumId, albumDraftSelection);
        loadedAlbumPhotoId = -1;
        hideKeyboard();
        albumPickerOpen = false;
        albumFaceQuery = "";
        albumShowUnnamed = false;
        albumDraftSelection.clear();
        albumsOpen = true;
        render();
    }

    private void renderAlbumPicker() {
        Store.Album album = store.album(activeAlbumId);
        if (album == null) {
            albumPickerOpen = false;
            activeAlbumId = -1;
            render();
            return;
        }
        applyNormalWindowLayout();
        grid = null;
        peopleAdapter = null;
        albumPickerAdapter = null;
        albumSelectedCount = null;
        albumSelectedStrip = null;
        albumSelectedScroller = null;
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(4), dp(8), dp(4), 0);
        root.setFocusableInTouchMode(true);
        setContentView(root);
        boolean landscape = isLandscape();
        int headerHeight = landscape ? 48 : 56;

        FrameLayout header = new FrameLayout(this);
        VectorIcon back = new VectorIcon(this, R.drawable.ic_back);
        back.setLineColor(INK);
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setContentDescription("Cancel adding faces");
        back.setClickable(true);
        back.setFocusable(true);
        back.setOnClickListener(view -> cancelAlbumPicker());
        header.addView(back, new FrameLayout.LayoutParams(dp(52), dp(headerHeight),
                Gravity.START | Gravity.CENTER_VERTICAL));
        TextView title = text("Add Faces", 21);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(-1, dp(headerHeight), Gravity.CENTER);
        titleParams.setMargins(dp(56), 0, dp(landscape ? 76 : 56), 0);
        header.addView(title, titleParams);
        if (landscape) {
            TextView add = text("Add", 16);
            add.setTextColor(BLUE);
            add.setTypeface(null, Typeface.BOLD);
            add.setGravity(Gravity.CENTER);
            add.setContentDescription("Add selected faces");
            add.setClickable(true);
            add.setFocusable(true);
            add.setOnClickListener(view -> saveAlbumPicker());
            header.addView(add, new FrameLayout.LayoutParams(dp(72), dp(headerHeight),
                    Gravity.END | Gravity.CENTER_VERTICAL));
        }
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(headerHeight)));

        albumSelectedCount = text("", 15);
        albumSelectedCount.setTypeface(null, Typeface.BOLD);
        albumSelectedCount.setPadding(dp(14), dp(landscape ? 3 : 8),
                dp(14), dp(landscape ? 3 : 8));
        root.addView(albumSelectedCount, new LinearLayout.LayoutParams(-1, -2));

        EditText pickerSearch = new EditText(this);
        pickerSearch.setHint("Search named faces");
        pickerSearch.setSingleLine(true);
        pickerSearch.setText(albumFaceQuery);
        pickerSearch.setTextSize(16);
        pickerSearch.setTextColor(INK);
        pickerSearch.setHintTextColor(Color.GRAY);
        pickerSearch.setPadding(dp(16), 0, dp(16), 0);
        GradientDrawable searchBackground = rounded(Color.WHITE, 18);
        searchBackground.setStroke(dp(1), Color.rgb(218, 222, 232));
        pickerSearch.setBackground(searchBackground);
        Button unnamed = button(albumShowUnnamed ? "Hide unnamed faces" : "Show unnamed faces", () -> {
            albumShowUnnamed = !albumShowUnnamed;
            renderAlbumPicker();
        });
        unnamed.setContentDescription("Show or hide unnamed faces");
        if (landscape) {
            LinearLayout searchRow = new LinearLayout(this);
            searchRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(0, dp(42), 1);
            searchParams.setMargins(dp(10), 0, dp(5), dp(4));
            searchRow.addView(pickerSearch, searchParams);
            LinearLayout.LayoutParams unnamedParams = new LinearLayout.LayoutParams(dp(176), dp(42));
            unnamedParams.setMargins(dp(5), 0, dp(10), dp(4));
            searchRow.addView(unnamed, unnamedParams);
            root.addView(searchRow, new LinearLayout.LayoutParams(-1, dp(46)));
        } else {
            LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, dp(52));
            searchParams.setMargins(dp(10), 0, dp(10), dp(6));
            root.addView(pickerSearch, searchParams);
        }

        albumSelectedScroller = new HorizontalScrollView(this);
        albumSelectedScroller.setHorizontalScrollBarEnabled(false);
        albumSelectedScroller.setFillViewport(false);
        albumSelectedStrip = new LinearLayout(this);
        albumSelectedStrip.setOrientation(LinearLayout.HORIZONTAL);
        albumSelectedStrip.setPadding(dp(10), dp(2), dp(10), dp(4));
        albumSelectedScroller.addView(albumSelectedStrip,
                new HorizontalScrollView.LayoutParams(-2, dp(landscape ? 52 : 76)));
        root.addView(albumSelectedScroller,
                new LinearLayout.LayoutParams(-1, dp(landscape ? 56 : 80)));
        if (!landscape) {
            LinearLayout.LayoutParams unnamedParams = new LinearLayout.LayoutParams(-1, dp(48));
            unnamedParams.setMargins(dp(10), 0, dp(10), dp(4));
            root.addView(unnamed, unnamedParams);
        }

        GridView pickerGrid = new GridView(this);
        grid = pickerGrid;
        pickerGrid.setStretchMode(GridView.NO_STRETCH);
        pickerGrid.setGravity(Gravity.CENTER_HORIZONTAL);
        pickerGrid.setClipToPadding(true);
        configureAlbumFaceGrid(pickerGrid, dp(8));
        refreshAlbumPickerFaces();
        albumPickerAdapter = new AlbumPickerAdapter();
        pickerGrid.setAdapter(albumPickerAdapter);
        TextView empty = text("No faces match this search.", 17);
        empty.setGravity(Gravity.CENTER);
        FrameLayout pickerArea = new FrameLayout(this);
        pickerArea.addView(pickerGrid, new FrameLayout.LayoutParams(-1, -1));
        pickerArea.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        pickerGrid.setEmptyView(empty);
        root.addView(pickerArea, new LinearLayout.LayoutParams(-1, 0, 1));
        pickerGrid.setOnItemClickListener((parent, view, position, id) -> {
            long personId = albumPickerPeople.get(position).id();
            if (!albumDraftSelection.remove(personId)) albumDraftSelection.add(personId);
            updateAlbumSelectionUi();
            albumPickerAdapter.notifyDataSetChanged();
        });

        if (!landscape) {
            LinearLayout actions = new LinearLayout(this);
            actions.setPadding(dp(10), dp(6), dp(10), dp(10));
            Button cancel = button("Cancel", this::cancelAlbumPicker);
            Button addFaces = button("Add", this::saveAlbumPicker);
            styleButton(addFaces, BLUE, Color.WHITE);
            actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(52), 1));
            actions.addView(new Space(this), new LinearLayout.LayoutParams(dp(10), 1));
            actions.addView(addFaces, new LinearLayout.LayoutParams(0, dp(52), 1));
            root.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        }

        pickerSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable text) {
                albumFaceQuery = text.toString();
                refreshAlbumPickerFaces();
                if (albumPickerAdapter != null) albumPickerAdapter.notifyDataSetChanged();
            }
        });
        updateAlbumSelectionUi();
        root.requestFocus();
    }

    private void refreshAlbumPickerFaces() {
        String query = albumFaceQuery.trim().toLowerCase(Locale.ROOT);
        ArrayList<Store.Person> filtered = new ArrayList<>();
        for (Store.Person person : allPeople) {
            if (!albumShowUnnamed && person.name().isEmpty()) continue;
            if (!query.isEmpty() && !person.title().toLowerCase(Locale.ROOT).contains(query)) continue;
            filtered.add(person);
        }
        albumPickerPeople = filtered;
    }

    private void updateAlbumSelectionUi() {
        if (albumSelectedCount == null || albumSelectedStrip == null) return;
        boolean landscape = isLandscape();
        int imageSize = landscape ? 44 : 58;
        int itemWidth = landscape ? 52 : 66;
        int itemHeight = landscape ? 50 : 70;
        int count = albumDraftSelection.size();
        albumSelectedCount.setText(count + (count == 1 ? " face selected" : " faces selected"));
        albumSelectedStrip.removeAllViews();
        Map<Long, Store.Person> peopleById = new LinkedHashMap<>();
        for (Store.Person person : allPeople) peopleById.put(person.id(), person);
        for (long id : albumDraftSelection) {
            Store.Person person = peopleById.get(id);
            if (person == null) continue;
            FrameLayout item = new FrameLayout(this);
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackground(rounded(Color.rgb(227, 232, 246), 12));
            image.setClipToOutline(true);
            FrameLayout.LayoutParams imageParams = new FrameLayout.LayoutParams(
                    dp(imageSize), dp(imageSize),
                    Gravity.START | Gravity.BOTTOM);
            item.addView(image, imageParams);
            bindFaceThumb(image, id);
            TextView remove = text("\u00d7", 17);
            remove.setTextColor(INK);
            remove.setGravity(Gravity.CENTER);
            GradientDrawable removeBackground = rounded(Color.WHITE, 12);
            removeBackground.setStroke(dp(1), Color.rgb(200, 204, 214));
            remove.setBackground(removeBackground);
            remove.setContentDescription("Remove " + person.title());
            remove.setClickable(true);
            remove.setFocusable(true);
            remove.setOnClickListener(view -> {
                albumDraftSelection.remove(id);
                updateAlbumSelectionUi();
                if (albumPickerAdapter != null) albumPickerAdapter.notifyDataSetChanged();
            });
            FrameLayout.LayoutParams removeParams = new FrameLayout.LayoutParams(dp(24), dp(24),
                    Gravity.END | Gravity.TOP);
            item.addView(remove, removeParams);
            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(
                    dp(itemWidth), dp(itemHeight));
            itemParams.setMargins(0, 0, dp(6), 0);
            albumSelectedStrip.addView(item, itemParams);
        }
        if (albumSelectedScroller != null) {
            albumSelectedScroller.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
            ViewGroup.LayoutParams params = albumSelectedScroller.getLayoutParams();
            if (params != null) {
                params.height = count == 0 ? 0 : dp(landscape ? 56 : 80);
                albumSelectedScroller.setLayoutParams(params);
            }
        }
    }

    private void startDeleteFacePicker() {
        settingsOpen = false;
        helpSupportOpen = false;
        deleteFacePickerOpen = true;
        deleteFaceShowUnnamed = false;
        returnToDeleteFacePicker = false;
        deleteFaceQuery = "";
        deleteFaceSelection.clear();
        hideKeyboard();
        render();
    }

    private void cancelDeleteFacePicker() {
        hideKeyboard();
        deleteFacePickerOpen = false;
        deleteFaceShowUnnamed = false;
        returnToDeleteFacePicker = false;
        deleteFaceQuery = "";
        deleteFaceSelection.clear();
        settingsOpen = true;
        render();
    }

    private void renderDeleteFacePicker() {
        applyNormalWindowLayout();
        grid = null;
        peopleAdapter = null;
        deleteFacePickerAdapter = null;
        deleteFaceSelectedCount = null;
        deleteFaceSelectedStrip = null;
        deleteFaceSelectedScroller = null;
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(4), dp(8), dp(4), 0);
        root.setFocusableInTouchMode(true);
        setContentView(root);
        boolean landscape = isLandscape();
        int headerHeight = landscape ? 48 : 56;

        FrameLayout header = new FrameLayout(this);
        VectorIcon back = new VectorIcon(this, R.drawable.ic_back);
        back.setLineColor(INK);
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setContentDescription("Cancel deleting face photos");
        back.setClickable(true);
        back.setFocusable(true);
        back.setOnClickListener(view -> cancelDeleteFacePicker());
        header.addView(back, new FrameLayout.LayoutParams(dp(52), dp(headerHeight),
                Gravity.START | Gravity.CENTER_VERTICAL));
        TextView title = text("Delete Face Photos", 20);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(-1, dp(headerHeight), Gravity.CENTER);
        titleParams.setMargins(dp(56), 0, dp(landscape ? 86 : 56), 0);
        header.addView(title, titleParams);
        if (landscape) {
            TextView deleteAction = text("Delete", 16);
            deleteAction.setTextColor(Color.rgb(190, 40, 40));
            deleteAction.setTypeface(null, Typeface.BOLD);
            deleteAction.setGravity(Gravity.CENTER);
            deleteAction.setContentDescription("Delete selected face photos");
            deleteAction.setClickable(true);
            deleteAction.setFocusable(true);
            deleteAction.setOnClickListener(view -> confirmDeleteSelectedFacePhotos());
            header.addView(deleteAction, new FrameLayout.LayoutParams(dp(82), dp(headerHeight),
                    Gravity.END | Gravity.CENTER_VERTICAL));
        }
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(headerHeight)));

        deleteFaceSelectedCount = text("", 15);
        deleteFaceSelectedCount.setTypeface(null, Typeface.BOLD);
        deleteFaceSelectedCount.setPadding(dp(14), dp(landscape ? 3 : 8),
                dp(14), dp(landscape ? 3 : 8));
        root.addView(deleteFaceSelectedCount, new LinearLayout.LayoutParams(-1, -2));

        EditText pickerSearch = new EditText(this);
        pickerSearch.setHint("Search named faces");
        pickerSearch.setSingleLine(true);
        pickerSearch.setText(deleteFaceQuery);
        pickerSearch.setTextSize(16);
        pickerSearch.setTextColor(INK);
        pickerSearch.setHintTextColor(Color.GRAY);
        pickerSearch.setPadding(dp(16), 0, dp(16), 0);
        GradientDrawable searchBackground = rounded(Color.WHITE, 18);
        searchBackground.setStroke(dp(1), Color.rgb(218, 222, 232));
        pickerSearch.setBackground(searchBackground);
        Button unnamed = button(deleteFaceShowUnnamed ? "Hide unnamed faces" : "Show unnamed faces", () -> {
            deleteFaceShowUnnamed = !deleteFaceShowUnnamed;
            renderDeleteFacePicker();
        });
        unnamed.setContentDescription("Show or hide unnamed faces");
        if (landscape) {
            LinearLayout searchRow = new LinearLayout(this);
            searchRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(0, dp(42), 1);
            searchParams.setMargins(dp(10), 0, dp(5), dp(4));
            searchRow.addView(pickerSearch, searchParams);
            LinearLayout.LayoutParams unnamedParams = new LinearLayout.LayoutParams(dp(176), dp(42));
            unnamedParams.setMargins(dp(5), 0, dp(10), dp(4));
            searchRow.addView(unnamed, unnamedParams);
            root.addView(searchRow, new LinearLayout.LayoutParams(-1, dp(46)));
        } else {
            LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, dp(52));
            searchParams.setMargins(dp(10), 0, dp(10), dp(6));
            root.addView(pickerSearch, searchParams);
        }

        deleteFaceSelectedScroller = new HorizontalScrollView(this);
        deleteFaceSelectedScroller.setHorizontalScrollBarEnabled(false);
        deleteFaceSelectedScroller.setFillViewport(false);
        deleteFaceSelectedStrip = new LinearLayout(this);
        deleteFaceSelectedStrip.setOrientation(LinearLayout.HORIZONTAL);
        deleteFaceSelectedStrip.setPadding(dp(10), dp(2), dp(10), dp(4));
        deleteFaceSelectedScroller.addView(deleteFaceSelectedStrip,
                new HorizontalScrollView.LayoutParams(-2, dp(landscape ? 52 : 76)));
        root.addView(deleteFaceSelectedScroller,
                new LinearLayout.LayoutParams(-1, dp(landscape ? 56 : 80)));
        if (!landscape) {
            LinearLayout.LayoutParams unnamedParams = new LinearLayout.LayoutParams(-1, dp(48));
            unnamedParams.setMargins(dp(10), 0, dp(10), dp(4));
            root.addView(unnamed, unnamedParams);
        }

        GridView pickerGrid = new GridView(this);
        grid = pickerGrid;
        pickerGrid.setStretchMode(GridView.NO_STRETCH);
        pickerGrid.setGravity(Gravity.CENTER_HORIZONTAL);
        pickerGrid.setClipToPadding(true);
        configureAlbumFaceGrid(pickerGrid, dp(8));
        refreshDeleteFacePickerFaces();
        deleteFacePickerAdapter = new DeleteFacePickerAdapter();
        pickerGrid.setAdapter(deleteFacePickerAdapter);
        TextView empty = text("No faces match this search.", 17);
        empty.setGravity(Gravity.CENTER);
        FrameLayout pickerArea = new FrameLayout(this);
        pickerArea.addView(pickerGrid, new FrameLayout.LayoutParams(-1, -1));
        pickerArea.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        pickerGrid.setEmptyView(empty);
        root.addView(pickerArea, new LinearLayout.LayoutParams(-1, 0, 1));
        pickerGrid.setOnItemClickListener((parent, view, position, id) -> {
            long selectedId = deleteFacePickerPeople.get(position).id();
            if (!deleteFaceSelection.remove(selectedId)) deleteFaceSelection.add(selectedId);
            updateDeleteFaceSelectionUi();
            deleteFacePickerAdapter.notifyDataSetChanged();
        });
        pickerGrid.setOnItemLongClickListener((parent, view, position, id) -> {
            hideKeyboard();
            personId = deleteFacePickerPeople.get(position).id();
            gridPosition = 0;
            deleteFacePickerOpen = false;
            returnToDeleteFacePicker = true;
            render();
            return true;
        });

        if (!landscape) {
            LinearLayout actions = new LinearLayout(this);
            actions.setPadding(dp(10), dp(6), dp(10), dp(10));
            Button cancel = button("Cancel", this::cancelDeleteFacePicker);
            Button delete = button("Delete", this::confirmDeleteSelectedFacePhotos);
            styleButton(delete, Color.rgb(190, 40, 40), Color.WHITE);
            actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(52), 1));
            actions.addView(new Space(this), new LinearLayout.LayoutParams(dp(10), 1));
            actions.addView(delete, new LinearLayout.LayoutParams(0, dp(52), 1));
            root.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        }

        pickerSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable text) {
                deleteFaceQuery = text.toString();
                refreshDeleteFacePickerFaces();
                if (deleteFacePickerAdapter != null) deleteFacePickerAdapter.notifyDataSetChanged();
            }
        });
        updateDeleteFaceSelectionUi();
        root.requestFocus();
    }

    private void refreshDeleteFacePickerFaces() {
        String query = deleteFaceQuery.trim().toLowerCase(Locale.ROOT);
        ArrayList<Store.Person> filtered = new ArrayList<>();
        for (Store.Person person : allPeople) {
            if (!deleteFaceShowUnnamed && person.name().isEmpty()) continue;
            if (!query.isEmpty() && !person.title().toLowerCase(Locale.ROOT).contains(query)) continue;
            filtered.add(person);
        }
        deleteFacePickerPeople = filtered;
    }

    private void updateDeleteFaceSelectionUi() {
        if (deleteFaceSelectedCount == null || deleteFaceSelectedStrip == null) return;
        boolean landscape = isLandscape();
        int imageSize = landscape ? 44 : 58;
        int itemWidth = landscape ? 52 : 66;
        int itemHeight = landscape ? 50 : 70;
        int count = deleteFaceSelection.size();
        deleteFaceSelectedCount.setText(count + (count == 1 ? " face selected" : " faces selected")
                + "  \u2022  Hold a face to review its photos");
        deleteFaceSelectedStrip.removeAllViews();
        Map<Long, Store.Person> peopleById = new LinkedHashMap<>();
        for (Store.Person person : allPeople) peopleById.put(person.id(), person);
        for (long id : deleteFaceSelection) {
            Store.Person person = peopleById.get(id);
            if (person == null) continue;
            FrameLayout item = new FrameLayout(this);
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackground(rounded(Color.rgb(227, 232, 246), 12));
            image.setClipToOutline(true);
            item.addView(image, new FrameLayout.LayoutParams(dp(imageSize), dp(imageSize),
                    Gravity.START | Gravity.BOTTOM));
            bindFaceThumb(image, id);
            TextView remove = text("\u00d7", 17);
            remove.setTextColor(INK);
            remove.setGravity(Gravity.CENTER);
            GradientDrawable removeBackground = rounded(Color.WHITE, 12);
            removeBackground.setStroke(dp(1), Color.rgb(200, 204, 214));
            remove.setBackground(removeBackground);
            remove.setContentDescription("Remove " + person.title());
            remove.setClickable(true);
            remove.setFocusable(true);
            remove.setOnClickListener(view -> {
                deleteFaceSelection.remove(id);
                updateDeleteFaceSelectionUi();
                if (deleteFacePickerAdapter != null) deleteFacePickerAdapter.notifyDataSetChanged();
            });
            item.addView(remove, new FrameLayout.LayoutParams(dp(24), dp(24),
                    Gravity.END | Gravity.TOP));
            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(
                    dp(itemWidth), dp(itemHeight));
            itemParams.setMargins(0, 0, dp(6), 0);
            deleteFaceSelectedStrip.addView(item, itemParams);
        }
        deleteFaceSelectedScroller.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
        ViewGroup.LayoutParams params = deleteFaceSelectedScroller.getLayoutParams();
        if (params != null) {
            params.height = count == 0 ? 0 : dp(landscape ? 56 : 80);
            deleteFaceSelectedScroller.setLayoutParams(params);
        }
    }

    private void confirmDeleteSelectedFacePhotos() {
        if (deleteFaceSelection.isEmpty()) {
            toast("Select at least one face.");
            return;
        }
        if (ScanState.current().running()) {
            toast("Pause face recognition before deleting photos.");
            return;
        }
        List<String> targets = store.photos(deleteFaceSelection);
        if (targets.isEmpty()) {
            toast("No photos were found for the selected faces.");
            return;
        }
        int faceCount = deleteFaceSelection.size();
        int photoCount = targets.size();
        new AlertDialog.Builder(this)
                .setTitle("Delete " + photoCount + (photoCount == 1 ? " photo?" : " photos?"))
                .setMessage("This will permanently delete every photo containing the "
                        + faceCount + (faceCount == 1 ? " selected face" : " selected faces")
                        + " from this phone. A photo may also contain other people. This cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete photos", (dialog, which) -> requestBulkPhotoDelete(targets))
                .show();
    }

    private void requestBulkPhotoDelete(List<String> targets) {
        pendingBulkDeleteUris.clear();
        pendingBulkDeleteUris.addAll(targets);
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                ArrayList<Uri> uris = new ArrayList<>(targets.size());
                for (String target : targets) uris.add(Uri.parse(target));
                PendingIntent request = MediaStore.createDeleteRequest(getContentResolver(), uris);
                startIntentSenderForResult(request.getIntentSender(), DELETE_FACE_PHOTOS_REQUEST,
                        null, 0, 0, 0);
                return;
            }
            ArrayList<String> deleted = new ArrayList<>();
            for (String target : targets) {
                if (getContentResolver().delete(Uri.parse(target), null, null) > 0) deleted.add(target);
            }
            pendingBulkDeleteUris.clear();
            pendingBulkDeleteUris.addAll(deleted);
            finishBulkPhotoDelete();
        } catch (Exception error) {
            pendingBulkDeleteUris.clear();
            toast("Photo deletion failed: " + errorMessage(error));
        }
    }

    private void finishBulkPhotoDelete() {
        int deleted = pendingBulkDeleteUris.size();
        store.removePhotos(pendingBulkDeleteUris);
        for (String uri : pendingBulkDeleteUris) photoCache.remove(uri);
        faceCache.evictAll();
        loadedPersonPhotoId = -1;
        loadedAlbumPhotoId = -1;
        photoListGeneration++;
        pendingBulkDeleteUris.clear();
        deleteFaceSelection.clear();
        deleteFaceQuery = "";
        deleteFacePickerOpen = false;
        deleteFaceShowUnnamed = false;
        returnToDeleteFacePicker = false;
        personId = -1;
        photoUri = null;
        settingsOpen = true;
        render();
        toast(deleted + (deleted == 1 ? " photo deleted." : " photos deleted."));
    }

    private void showPhotos() {
        long requestedPerson = personId;
        boolean reusePhotos = loadedPersonPhotoId == requestedPerson;
        if (!reusePhotos) photoList = new ArrayList<>();
        TextView count = text(reusePhotos ? photoList.size() + " photos" : "Loading photos\u2026", 13);
        count.setPadding(0, 0, 0, dp(12));
        root.addView(count);
        GridView photoGrid = new GridView(this);
        grid = photoGrid;
        configurePhotoGrid(photoGrid, 0);
        PhotosAdapter adapter = new PhotosAdapter();
        photoGrid.setAdapter(adapter);
        root.addView(photoGrid, new LinearLayout.LayoutParams(-1, 0, 1));
        photoGrid.setSelection(gridPosition);
        photoGrid.setOnItemClickListener((parent, view, position, id) -> {
            gridPosition = photoGrid.getFirstVisiblePosition();
            photoUri = photoList.get(position);
            photoControlsVisible = true;
            render();
        });
        photoGrid.setOnItemLongClickListener((parent, view, position, id) -> {
            correct(photoList.get(position));
            return true;
        });
        if (!reusePhotos) {
            int generation = ++photoListGeneration;
            data.execute(() -> {
                List<String> loaded = store.photos(requestedPerson);
                runOnUiThread(() -> {
                    if (isDestroyed() || generation != photoListGeneration
                            || personId != requestedPerson || photoUri != null) return;
                    photoList = loaded;
                    loadedPersonPhotoId = requestedPerson;
                    loadedAlbumPhotoId = -1;
                    count.setText(photoList.size() + " photos");
                    adapter.notifyDataSetChanged();
                    photoGrid.setSelection(gridPosition);
                });
            });
        }
    }

    private void rememberHomePosition() {
        if (grid == null || personId >= 0 || albumsOpen || albumPickerOpen || deleteFacePickerOpen) return;
        if (singlesScreen) singlesPosition = grid.getFirstVisiblePosition();
        else homePosition = grid.getFirstVisiblePosition();
    }

    private void openSettings() {
        rememberHomePosition();
        settingsOpen = true;
        renderSettings(true);
    }

    private void renderSettings(boolean animateIn) {
        settingsClosing = false;
        grid = null;
        settingsScanToggle = null;
        settingsScanInfo = null;
        settingsReevaluationToggle = null;
        settingsReevaluationInfo = null;
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(4), dp(8), dp(4), 0);
        if (animateIn) root.setTranslationX(-getResources().getDisplayMetrics().widthPixels);
        setContentView(root);

        FrameLayout header = new FrameLayout(this);
        VectorIcon back = new VectorIcon(this, R.drawable.ic_back);
        back.setLineColor(INK);
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setContentDescription("Back from Settings");
        back.setClickable(true);
        back.setFocusable(true);
        back.setOnClickListener(view -> closeSettings());
        header.addView(back, new FrameLayout.LayoutParams(dp(52), dp(56), Gravity.START | Gravity.CENTER_VERTICAL));

        TextView title = text("Settings", 21);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(-1, dp(56), Gravity.CENTER);
        titleParams.setMargins(dp(56), 0, dp(56), 0);
        header.addView(title, titleParams);
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(56)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(10), dp(8), dp(10), dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        LinearLayout scanRow = new LinearLayout(this);
        scanRow.setGravity(Gravity.CENTER_VERTICAL);
        scanRow.setPadding(dp(14), dp(14), dp(8), dp(14));
        LinearLayout scanText = new LinearLayout(this);
        scanText.setOrientation(LinearLayout.VERTICAL);
        TextView scanTitle = text("Run Face Recognition", 17);
        scanTitle.setTypeface(null, Typeface.BOLD);
        settingsScanInfo = text("", 13);
        settingsScanInfo.setTextColor(Color.DKGRAY);
        settingsScanInfo.setPadding(0, dp(4), dp(8), 0);
        scanText.addView(scanTitle, new LinearLayout.LayoutParams(-1, -2));
        scanText.addView(settingsScanInfo, new LinearLayout.LayoutParams(-1, -2));
        scanRow.addView(scanText, new LinearLayout.LayoutParams(0, -2, 1));

        settingsScanToggle = new Switch(this);
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{-android.R.attr.state_checked}
        };
        settingsScanToggle.setTrackTintList(new android.content.res.ColorStateList(states,
                new int[]{Color.rgb(38, 181, 84), Color.rgb(181, 185, 194)}));
        settingsScanToggle.setThumbTintList(new android.content.res.ColorStateList(states,
                new int[]{Color.WHITE, Color.WHITE}));
        settingsScanToggle.setContentDescription("Start or pause face recognition");
        settingsScanToggle.setOnCheckedChangeListener((view, checked) -> {
            if (syncingScanToggle) return;
            ScanState.Snapshot snapshot = ScanState.current();
            if (checked != (snapshot.running() && !snapshot.pauseRequested())) toggleScan();
        });
        scanRow.addView(settingsScanToggle, new LinearLayout.LayoutParams(dp(64), dp(48)));
        content.addView(scanRow, new LinearLayout.LayoutParams(-1, -2));
        addDivider(content);

        LinearLayout reevaluationRow=new LinearLayout(this);
        reevaluationRow.setGravity(Gravity.CENTER_VERTICAL);
        reevaluationRow.setPadding(dp(14),dp(14),dp(8),dp(14));
        LinearLayout reevaluationText=new LinearLayout(this);
        reevaluationText.setOrientation(LinearLayout.VERTICAL);
        TextView reevaluationTitle=text("Re-evaluate unnamed faces",17);
        reevaluationTitle.setTypeface(null,Typeface.BOLD);
        settingsReevaluationInfo=text("",13);settingsReevaluationInfo.setTextColor(Color.DKGRAY);
        settingsReevaluationInfo.setPadding(0,dp(4),dp(8),0);
        reevaluationText.addView(reevaluationTitle,new LinearLayout.LayoutParams(-1,-2));
        reevaluationText.addView(settingsReevaluationInfo,new LinearLayout.LayoutParams(-1,-2));
        reevaluationRow.addView(reevaluationText,new LinearLayout.LayoutParams(0,-2,1));
        settingsReevaluationToggle=new Switch(this);
        settingsReevaluationToggle.setTrackTintList(new android.content.res.ColorStateList(states,
                new int[]{Color.rgb(38,181,84),Color.rgb(181,185,194)}));
        settingsReevaluationToggle.setThumbTintList(new android.content.res.ColorStateList(states,
                new int[]{Color.WHITE,Color.WHITE}));
        settingsReevaluationToggle.setContentDescription("Start or pause unnamed face re-evaluation");
        settingsReevaluationToggle.setOnCheckedChangeListener((view,checked)->{
            if(syncingReevaluationToggle)return;
            ScanState.Snapshot snapshot=ScanState.current();
            boolean active=snapshot.running()&&!snapshot.pauseRequested()
                    &&snapshot.job()==ScanState.Job.REEVALUATE_UNNAMED;
            if(checked!=active)toggleUnnamedReevaluation();
        });
        reevaluationRow.addView(settingsReevaluationToggle,new LinearLayout.LayoutParams(dp(64),dp(48)));
        content.addView(reevaluationRow,new LinearLayout.LayoutParams(-1,-2));
        addDivider(content);

        addSettingsAction(content, "Name unnamed faces",
                "Name a face, merge it with another group, or move to the next face.", this::startNamingReview);
        addSettingsAction(content, "Review possible duplicates",
                "Compare likely matches and combine groups that show the same person.", this::startDuplicateReview);
        addSettingsAction(content, "Review skipped duplicates",
                "Revalidate skipped comparisons and review the ones that are still valid.",
                this::startSkippedDuplicateReview);
        addSettingsAction(content, "Delete face photos",
                "Select face groups and delete every photo containing those faces.",
                this::startDeleteFacePicker);
        addSettingsAction(content, "Export scan backup",
                "Save the face index, names, albums, thumbnails and recognition data.", this::exportBackup);
        addSettingsAction(content, "Import scan backup",
                "Restore a previously exported FaceGallery backup.", this::confirmImportBackup);
        addSettingsAction(content, "Model licenses",
                "Read the YuNet and InsightFace research model terms.", this::licenses);
        addSettingsAction(content, "Rate us",
                "Open the FaceGallery page in Google Play.", this::rateUs);
        addSettingsAction(content, "Help & Support",
                "Report a problem or ask a question on GitHub.", this::openHelpSupport);
        if (!fullAccess()) addSettingsAction(content, "Photo access",
                anyAccess() ? "Allow access to all photos." : "Choose which photos FaceGallery may scan.",
                this::requestPhotos);
        addSettingsAction(content, "About FaceGallery",
                "Privacy, background scanning and recognition information.", this::about);
        if (personId >= 0) {
            addSettingsAction(content, "Rename person", "Change this face group's name.", this::rename);
            addSettingsAction(content, "Merge with another person",
                    "Combine this group with another face group.", this::merge);
        }
        TextView version = text("Version " + appVersionName(), 13);
        version.setTextColor(Color.GRAY);
        version.setGravity(Gravity.CENTER);
        version.setPadding(0, dp(24), 0, dp(12));
        content.addView(version, new LinearLayout.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        updateStatus(ScanState.current());
        if (animateIn) root.animate().translationX(0).setDuration(230).start();
    }

    private void rateUs() {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_STORE_URL));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException error) {
            toast("Google Play could not be opened on this device.");
        }
    }

    private void openHelpSupport() {
        helpSupportOpen = true;
        renderHelpSupport();
    }

    private void renderHelpSupport() {
        applyNormalWindowLayout();
        grid = null;
        settingsScanToggle = null;
        settingsScanInfo = null;
        settingsReevaluationToggle = null;
        settingsReevaluationInfo = null;
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(4), dp(8), dp(4), 0);
        setContentView(root);

        FrameLayout header = new FrameLayout(this);
        VectorIcon back = new VectorIcon(this, R.drawable.ic_back);
        back.setLineColor(INK);
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setContentDescription("Back to Settings");
        back.setClickable(true);
        back.setFocusable(true);
        back.setOnClickListener(view -> closeHelpSupport());
        header.addView(back, new FrameLayout.LayoutParams(
                dp(52), dp(56), Gravity.START | Gravity.CENTER_VERTICAL));

        TextView title = text("Help & Support", 21);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(
                -1, dp(56), Gravity.CENTER);
        titleParams.setMargins(dp(56), 0, dp(56), 0);
        header.addView(title, titleParams);
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(56)));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(28), dp(24), dp(24));
        TextView heading = text("Need help?", 20);
        heading.setTypeface(null, Typeface.BOLD);
        content.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        TextView message = text("For help and support, open an issue on GitHub:", 16);
        message.setTextColor(Color.DKGRAY);
        message.setPadding(0, dp(12), 0, dp(10));
        content.addView(message, new LinearLayout.LayoutParams(-1, -2));
        TextView email = text("Open a GitHub issue", 17);
        email.setTextColor(BLUE);
        email.setTypeface(null, Typeface.BOLD);
        email.setGravity(Gravity.CENTER_VERTICAL);
        email.setContentDescription("Open a GitHub issue");
        email.setClickable(true);
        email.setFocusable(true);
        email.setOnClickListener(view -> reportIssue());
        content.addView(email, new LinearLayout.LayoutParams(-1, dp(48)));
        TextView diagnosticNote = text(
                "The issue draft includes basic app and device diagnostics. "
                        + "GitHub issues are public, so review or remove them before submitting.", 13);
        diagnosticNote.setTextColor(Color.GRAY);
        diagnosticNote.setPadding(0, dp(4), 0, 0);
        content.addView(diagnosticNote, new LinearLayout.LayoutParams(-1, -2));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void closeHelpSupport() {
        helpSupportOpen = false;
        renderSettings(false);
    }

    private void reportIssue() {
        String versionName = appVersionName();
        String title = "FaceGallery support - v" + versionName;
        String body = buildSupportEmailBody(versionName);
        Uri issueUri = Uri.parse(ISSUES_URL + "/new"
                + "?title=" + Uri.encode(title)
                + "&body=" + Uri.encode(body));
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, issueUri));
        } catch (ActivityNotFoundException error) {
            toast("No browser is available. Visit " + ISSUES_URL);
        }
    }

    private String appVersionName() {
        try {
            android.content.pm.PackageInfo info = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "unknown" : info.versionName;
        } catch (PackageManager.NameNotFoundException error) {
            return "unknown";
        }
    }

    private String buildSupportEmailBody(String versionName) {
        long versionCode = -1;
        try {
            android.content.pm.PackageInfo info = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            versionCode = info.getLongVersionCode();
        } catch (PackageManager.NameNotFoundException ignored) {
        }

        android.util.DisplayMetrics display = getResources().getDisplayMetrics();
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        ActivityManager activityManager =
                (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager != null) activityManager.getMemoryInfo(memory);
        long bytesPerMiB = 1024L * 1024L;
        String locale = getResources().getConfiguration().getLocales().isEmpty()
                ? "unknown"
                : getResources().getConfiguration().getLocales().get(0).toLanguageTag();
        String access = fullAccess() ? "Full"
                : anyAccess() ? "Limited" : "Not granted";
        ScanState.Snapshot scan = ScanState.current();
        String scanStatus = scan.pauseRequested() ? "Pausing"
                : scan.running() ? "Running" : "Stopped";
        String primaryAbi = Build.SUPPORTED_ABIS.length == 0
                ? "unknown" : Build.SUPPORTED_ABIS[0];
        int indexedImages = store.scannedPhotoCount();
        int accessibleImages = accessiblePhotoCount();
        String persistedProgress = accessibleImages >= 0
                ? indexedImages + " indexed / " + accessibleImages + " accessible images"
                : indexedImages + " indexed images (accessible total unavailable)";
        String sessionProgress = scan.total() > 0
                ? "Current scan session: " + scan.processed() + " / " + scan.total()
                        + " (" + scan.failed() + " unreadable)\n"
                : "";

        return "Please describe the issue or question above this line.\n\n"
                + "--- Diagnostic information ---\n"
                + "App version: " + versionName + " (" + versionCode + ")\n"
                + "Package: " + getPackageName() + "\n"
                + "Device: " + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "Android: " + Build.VERSION.RELEASE + " (SDK "
                + Build.VERSION.SDK_INT + ")\n"
                + "Locale: " + locale + "\n"
                + "Primary ABI: " + primaryAbi + "\n"
                + "Screen: " + display.widthPixels + " x " + display.heightPixels
                + " px, " + display.densityDpi + " dpi\n"
                + "Available device memory: " + (memory.availMem / bytesPerMiB) + " MiB\n"
                + "App maximum heap: "
                + (Runtime.getRuntime().maxMemory() / bytesPerMiB) + " MiB\n"
                + "Photo access: " + access + "\n"
                + "Scan status: " + scanStatus + "\n"
                + "Scan progress: " + persistedProgress + "\n"
                + sessionProgress
                + "Face groups: " + store.people().size() + "\n"
                + "------------------------------\n";
    }

    private int accessiblePhotoCount() {
        if (!anyAccess()) return -1;
        try (Cursor cursor = getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID}, null, null, null)) {
            return cursor == null ? -1 : cursor.getCount();
        } catch (RuntimeException error) {
            return -1;
        }
    }

    private void addSettingsAction(LinearLayout content, String label, String detail, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(14), dp(14), dp(14), dp(14));
        row.setClickable(true);
        row.setFocusable(true);
        android.util.TypedValue value = new android.util.TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true))
            row.setBackgroundResource(value.resourceId);
        TextView title = text(label, 16);
        title.setTypeface(null, Typeface.BOLD);
        TextView subtitle = text(detail, 13);
        subtitle.setTextColor(Color.DKGRAY);
        subtitle.setPadding(0, dp(4), 0, 0);
        row.addView(title, new LinearLayout.LayoutParams(-1, -2));
        row.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));
        row.setOnClickListener(view -> action.run());
        content.addView(row, new LinearLayout.LayoutParams(-1, -2));
        addDivider(content);
    }

    private void addDivider(LinearLayout content) {
        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(225, 228, 235));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1));
        params.setMargins(dp(14), 0, dp(14), 0);
        content.addView(divider, params);
    }

    private void closeSettings() {
        if (!settingsOpen || settingsClosing) return;
        settingsClosing = true;
        View page = root;
        page.animate().translationX(-getResources().getDisplayMetrics().widthPixels)
                .setDuration(210).withEndAction(() -> {
                    settingsOpen = false;
                    helpSupportOpen = false;
                    settingsClosing = false;
                    render();
                }).start();
    }

    private String scanActionLabel(ScanState.Snapshot snapshot) {
        if (snapshot.pauseRequested()) return "Pausing face recognition…";
        return snapshot.running() ? "Pause face recognition" : "Start / resume face recognition";
    }

    private String scanDetails(ScanState.Snapshot snapshot) {
        if (snapshot.job()==ScanState.Job.PHOTO_SCAN&&snapshot.total() > 0) {
            return "Scanned " + snapshot.processed() + " / " + snapshot.total() + " images"
                    + (snapshot.failed() > 0 ? " • " + snapshot.failed() + " unreadable" : "");
        }
        int saved = store == null ? 0 : store.scannedPhotoCount();
        return saved + (saved == 1 ? " image" : " images") + " saved in the scan index";
    }

    private void about() {
        new AlertDialog.Builder(this)
                .setTitle("FaceGallery • private gallery")
                .setMessage("YuNet detects faces; InsightFace antelopev2 ResNet-100 compares them. Processing stays on this phone and the app has no Internet permission. Android may include the scan database in its encrypted system backup when device backup is enabled. Manual export files remain wherever you save them.\n\n"
                        + "A foreground notification keeps user-started face processing running when the app is backgrounded or the screen is locked. Re-evaluating unnamed faces compares their saved embeddings with every named and unnamed group; it does not reopen the original photos. The green Home indicator means CPU-intensive processing is active. Pausing or interruption is safe.\n\n"
                        + "Recognition is imperfect, especially with side profiles, children and similar-looking people. Use Merge for duplicates; hold a photo to separate a wrong match.\n\n"
                        + "Research/demo only: the public InsightFace pretrained model is not licensed for commercial use. Model terms are always available from the main menu. Originals are never changed.\n\n"
                        + "Icons by Icons8 (icons8.com).")
                .setPositiveButton("Close", null)
                .show();
    }

    private void licenses() {
        StringBuilder text = new StringBuilder();
        for (String name : new String[]{"YUNET_LICENSE.txt", "INSIGHTFACE_MODEL_TERMS.txt"}) {
            try (java.io.InputStream input = getAssets().open(name);
                 java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                text.append(name).append("\n").append(output.toString("UTF-8")).append("\n\n");
            } catch (Exception error) {
                text.append("License unavailable: ").append(name).append("\n\n");
            }
        }
        new AlertDialog.Builder(this).setTitle("Model licenses & research terms").setMessage(text.toString())
                .setPositiveButton("Close", null).show();
    }

    private void exportBackup() {
        if (ScanState.current().running()) {
            toast("Pause scanning before exporting a backup.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Export scan backup")
                .setMessage("The backup contains face thumbnails, recognition embeddings, names, face albums and photo links. Treat it as sensitive and keep it somewhere private. Original photos are not included.")
                .setPositiveButton("Choose location", (dialog, which) -> {
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/octet-stream")
                            .putExtra(Intent.EXTRA_TITLE, "FacesGallary-scan-backup.fgbackup");
                    startActivityForResult(intent, EXPORT_BACKUP_REQUEST);
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void confirmImportBackup() {
        if (ScanState.current().running()) {
            toast("Pause scanning before importing a backup.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Import scan backup?")
                .setMessage("Import replaces the current face index, groups, names and face albums. Existing photos are never changed. After import, run Resume once so exact matching photos on this phone can be reconnected without face recognition.")
                .setPositiveButton("Choose backup", (dialog, which) -> {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                    startActivityForResult(intent, IMPORT_BACKUP_REQUEST);
                })
                .setNegativeButton("Cancel", null).show();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == DELETE_PHOTO_REQUEST) {
            String deleted = pendingDeleteUri;
            if (resultCode == RESULT_OK && deleted != null) finishDeletedPhoto(deleted);
            else pendingDeleteUri = null;
            return;
        }
        if (requestCode == DELETE_FACE_PHOTOS_REQUEST) {
            if (resultCode == RESULT_OK && !pendingBulkDeleteUris.isEmpty()) finishBulkPhotoDelete();
            else pendingBulkDeleteUris.clear();
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == EXPORT_BACKUP_REQUEST) writeBackup(data.getData());
        else if (requestCode == IMPORT_BACKUP_REQUEST) readBackup(data.getData());
    }

    private void writeBackup(Uri destination) {
        AlertDialog progress = new AlertDialog.Builder(this).setTitle("Preparing backup")
                .setMessage("Adding portable photo fingerprintsâ€¦")
                .setCancelable(false).create();
        progress.show();
        images.execute(() -> {
            int unavailable = 0;
            try {
                List<String> missing = store.missingFingerprintUris();
                for (int index = 0; index < missing.size(); index++) {
                    String uri = missing.get(index);
                    try { store.setFingerprint(uri, Engine.fingerprint(getApplicationContext(), uri)); }
                    catch (IOException | RuntimeException error) { unavailable++; }
                    if (index % 25 == 0 || index + 1 == missing.size()) {
                        int complete = index + 1, total = missing.size();
                        handler.post(() -> progress.setMessage("Preparing photo fingerprints " + complete + " / " + total));
                    }
                }
                try (OutputStream output = getContentResolver().openOutputStream(destination, "wt")) {
                    if (output == null) throw new IOException("The selected location cannot be written");
                    store.exportBackup(output);
                }
                int missed = unavailable;
                handler.post(() -> {
                    if (isDestroyed()) return;
                    progress.dismiss();
                    String message = "Scan backup exported.";
                    if (missed > 0) message += " " + missed + " unavailable photos could not receive portable fingerprints.";
                    toast(message);
                });
            } catch (Exception error) {
                handler.post(() -> {
                    if (isDestroyed()) return;
                    progress.dismiss();
                    toast("Backup export failed: " + errorMessage(error));
                });
            }
        });
    }

    private void readBackup(Uri source) {
        AlertDialog progress = new AlertDialog.Builder(this).setTitle("Importing backup")
                .setMessage("Checking and restoring the face indexâ€¦")
                .setCancelable(false).create();
        progress.show();
        images.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(source)) {
                if (input == null) throw new IOException("The selected backup cannot be read");
                int restored = store.importBackup(input);
                handler.post(() -> {
                    if (isDestroyed()) return;
                    progress.dismiss();faceCache.evictAll();photoCache.evictAll();
                    personId = -1;photoUri = null;singlesScreen = false;personFromSingles = false;
                    render();
                    new AlertDialog.Builder(this).setTitle("Backup imported")
                            .setMessage(restored + " scanned image records were restored. Reconnect exact copies of your photos now; only new or changed photos will need face recognition.")
                            .setPositiveButton("Reconnect now", (dialog, which) -> toggleScan())
                            .setNegativeButton("Later", null).show();
                });
            } catch (Exception error) {
                handler.post(() -> {
                    if (isDestroyed()) return;
                    progress.dismiss();
                    toast("Backup import failed: " + errorMessage(error));
                });
            }
        });
    }

    private String errorMessage(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private void rename() {
        EditText input = new EditText(this);
        input.setSingleLine();
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(80)});
        for (Store.Person person : allPeople) if (person.id() == personId) input.setText(person.name());
        new AlertDialog.Builder(this).setTitle("Name this person")
                .setMessage("Leave empty to keep this person unnamed.").setView(input)
                .setPositiveButton("Save", (dialog, which) -> {
                    store.rename(personId, input.getText().toString());
                    faceCache.remove(personId);
                    render();
                }).setNegativeButton("Cancel", null).show();
    }

    private void merge() {
        if (ScanState.current().running()) {
            toast("Pause scanning before correcting groups.");
            return;
        }
        List<Store.Person> targets = new ArrayList<>();
        for (Store.Person person : allPeople) if (person.id() != personId) targets.add(person);
        if (targets.isEmpty()) {
            toast("No other groups yet.");
            return;
        }
        String[] names = targets.stream().map(person -> person.title() + " · " + person.count() + " photos")
                .toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle("Merge into…").setItems(names, (dialog, index) ->
                new AlertDialog.Builder(this).setTitle("Merge with " + targets.get(index).title() + "?")
                        .setMessage("The selected person's name will be kept. Photos stay unchanged.")
                        .setPositiveButton("Merge", (confirm, which) -> {
                            long oldId = personId;
                            store.merge(personId, targets.get(index).id());
                            personId = targets.get(index).id();
                            faceCache.remove(oldId);
                            faceCache.remove(personId);
                            render();
                        }).setNegativeButton("Cancel", null).show()).show();
    }

    private void startNamingReview() { startNamingReview(true); }

    private void startNamingReview(boolean notifyWhenUnavailable) {
        if (ScanState.current().running()) {
            if (notifyWhenUnavailable) toast("Pause scanning before naming or merging faces.");
            return;
        }
        loadNamingBatch(0, notifyWhenUnavailable, new HashSet<>());
    }

    private void loadNamingBatch(int reviewed, boolean notifyWhenUnavailable, Set<Long> skipped) {
        List<Store.Person> candidates = store.unnamedPeople(20, skipped);
        if (candidates.isEmpty()) {
            if (!skipped.isEmpty()) toast("All remaining unnamed faces were skipped in this session.");
            else if (reviewed > 0) toast("No more unnamed faces were found after " + reviewed + " reviews.");
            else if (notifyWhenUnavailable) toast("There are no unnamed faces to review.");
            return;
        }
        ArrayList<Long> ids = new ArrayList<>();
        for (Store.Person person : candidates) ids.add(person.id());
        showNamingFace(ids, 0, reviewed, skipped);
    }

    private void showNamingFace(List<Long> ids, int requestedIndex, int reviewed, Set<Long> skipped) {
        int index = requestedIndex;
        Store.Person current = null;
        while (index < ids.size()) {
            current = store.person(ids.get(index));
            if (current != null && current.name().isEmpty()) break;
            index++;
        }
        if (current == null || index >= ids.size()) {
            render();
            loadNamingBatch(reviewed, false, skipped);
            return;
        }
        final int currentIndex = index;
        final Store.Person face = current;
        final Dialog dialog = new Dialog(this);
        LinearLayout panel = dialogPanel();

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Name this face • " + (reviewed + 1), 20);
        title.setTypeface(null, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1));
        Button close = button("×", dialog::dismiss);
        close.setTextSize(25);close.setContentDescription("Close face naming");
        header.addView(close, new LinearLayout.LayoutParams(dp(52), dp(52)));
        panel.addView(header);

        ImageView portrait = facePortrait(face.id(), 184);
        LinearLayout.LayoutParams portraitParams = new LinearLayout.LayoutParams(dp(184), dp(184));
        portraitParams.gravity = Gravity.CENTER_HORIZONTAL;
        panel.addView(portrait, portraitParams);
        TextView photoCount = text(face.count() + (face.count() == 1 ? " photo" : " photos"), 13);
        photoCount.setTextColor(Color.GRAY);photoCount.setGravity(Gravity.CENTER);
        photoCount.setPadding(0, dp(7), 0, dp(10));
        panel.addView(photoCount, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout entry = new LinearLayout(this);
        entry.setGravity(Gravity.CENTER_VERTICAL);
        EditText name = new EditText(this);
        name.setHint("Enter name");name.setSingleLine();
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(80)});
        entry.addView(name, new LinearLayout.LayoutParams(0, dp(58), 1));

        List<Store.Person> targets = new ArrayList<>();
        for (Store.Person person : store.people()) if (person.id() != face.id()) targets.add(person);
        ArrayList<String> choices = new ArrayList<>();choices.add("Merge with…");
        for (Store.Person person : targets)
            choices.add(person.title() + " · " + person.count());
        Spinner mergeTarget = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, choices);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mergeTarget.setAdapter(adapter);mergeTarget.setContentDescription("Optional merge target");
        entry.addView(mergeTarget, new LinearLayout.LayoutParams(0, dp(58), 1));
        panel.addView(entry, new LinearLayout.LayoutParams(-1, -2));

        Button save = button("Save & next", () -> {
            String entered = name.getText().toString().trim();
            int selected = mergeTarget.getSelectedItemPosition();
            if (entered.isEmpty() && selected == 0) {
                name.setError("Enter a name or choose a face to merge with");
                return;
            }
            long resultId = face.id();
            if (selected > 0) {
                Store.Person target = targets.get(selected - 1);
                store.merge(face.id(), target.id());
                resultId = target.id();
                faceCache.remove(face.id());faceCache.remove(target.id());
            }
            if (!entered.isEmpty()) store.rename(resultId, entered);
            dialog.dismiss();
            render();
            continueNamingReview(ids, currentIndex + 1, reviewed + 1, skipped);
        });
        styleButton(save, BLUE, Color.WHITE);
        Button next = button("Next", () -> {
            skipped.add(face.id());
            dialog.dismiss();
            continueNamingReview(ids, currentIndex + 1, reviewed + 1, skipped);
        });
        LinearLayout actions = new LinearLayout(this);actions.setGravity(Gravity.CENTER);
        actions.addView(next, new LinearLayout.LayoutParams(0, dp(56), 1));
        actions.addView(save, new LinearLayout.LayoutParams(0, dp(56), 1));
        panel.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        showDialog(dialog, panel);
    }

    private void continueNamingReview(List<Long> ids, int nextIndex, int reviewed, Set<Long> skipped) {
        if (reviewed % 20 == 0) {
            toast("20 faces reviewed • refreshing the next unnamed faces…");
            loadNamingBatch(reviewed, false, skipped);
        } else if (nextIndex >= ids.size()) loadNamingBatch(reviewed, false, skipped);
        else showNamingFace(ids, nextIndex, reviewed, skipped);
    }

    private void startDuplicateReview() {
        if (ScanState.current().running()) {
            toast("Pause scanning before reviewing possible duplicates.");
            return;
        }
        loadDuplicateBatch(0, true);
    }

    private void loadDuplicateBatch(int reviewed, boolean announce) {
        if (announce) toast("Finding the most similar face groups…");
        images.execute(() -> {
            // Answers change group centroids and decision history. Re-rank every
            // 20 decisions, then continue until the user closes the review dialog.
            List<Store.SimilarPair> pairs = store.similarPairs(20);
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing()) return;
                if (pairs.isEmpty()) {
                    render();
                    toast(reviewed == 0 ? "No likely duplicate face groups were found."
                            : "No more likely duplicate face groups were found after " + reviewed + " reviews.");
                } else showDuplicatePair(pairs, 0, reviewed);
            });
        });
    }

    private void showDuplicatePair(List<Store.SimilarPair> pairs, int requestedIndex,
                                   int reviewed) {
        int index = requestedIndex;
        Store.Person first = null, second = null;
        while (index < pairs.size()) {
            first = store.person(pairs.get(index).first().id());
            second = store.person(pairs.get(index).second().id());
            if (first != null && second != null && first.id() != second.id()) break;
            index++;
        }
        if (first == null || second == null || index >= pairs.size()) {
            render();loadDuplicateBatch(reviewed, false);return;
        }
        final int currentIndex = index;
        final Store.Person leftPerson = first, rightPerson = second;
        showDuplicateComparison(leftPerson,rightPerson,reviewed,
                ()->{
                    store.markNotSame(leftPerson.id(),rightPerson.id());
                    continueDuplicateReview(pairs,currentIndex+1,reviewed+1);
                },()->{
                    store.skipDuplicate(leftPerson.id(),rightPerson.id());
                    continueDuplicateReview(pairs,currentIndex+1,reviewed+1);
                },()->{
                    Store.Person keep=preferredMergeTarget(leftPerson,rightPerson);
                    Store.Person remove=keep.id()==leftPerson.id()?rightPerson:leftPerson;
                    store.merge(remove.id(),keep.id());faceCache.remove(remove.id());faceCache.remove(keep.id());
                    render();continueDuplicateReview(pairs,currentIndex+1,reviewed+1);
                });
    }

    private void continueDuplicateReview(List<Store.SimilarPair> pairs, int nextIndex,
                                         int reviewed) {
        if (reviewed % 20 == 0) {
            toast("20 reviews completed • recomputing the next suggestions…");
            loadDuplicateBatch(reviewed, false);
        } else if (nextIndex >= pairs.size()) loadDuplicateBatch(reviewed, false);
        else showDuplicatePair(pairs, nextIndex, reviewed);
    }

    private void prepareFaceDuplicateReview(long currentPersonId) {
        if(ScanState.current().running()){
            toast("Pause scanning before checking possible duplicates.");return;
        }
        Store.Person current=store.person(currentPersonId);
        if(current==null){toast("This face group is no longer available.");return;}
        if(faceDuplicateQueueLoading.contains(currentPersonId)){
            toast("Still processing possible duplicates for face "+current.title()+".");return;
        }
        boolean refreshing=store.hasQueuedDuplicatesForPerson(currentPersonId);
        String faceName=current.title();faceDuplicateQueueLoading.add(currentPersonId);
        toast((refreshing?"Refreshing":"Processing")
                +" the list of possible duplicates for face "+faceName+"…");
        images.execute(()->{
            List<Store.SimilarPair> matches=store.similarPairsForPerson(
                    currentPersonId,Integer.MAX_VALUE);
            store.replaceFaceDuplicateQueue(currentPersonId,matches);
            runOnUiThread(()->{
                faceDuplicateQueueLoading.remove(currentPersonId);
                if(isDestroyed()||isFinishing())return;
                if(store.person(currentPersonId)==null)
                    toast("This face group is no longer available.");
                else if(matches.isEmpty())
                    toast("No likely duplicates were found for face "+faceName+".");
                else toast(matches.size()+" duplicate "+(matches.size()==1?"face is":"faces are")
                            +" ready to be reviewed for face "+faceName+".");
            });
        });
    }

    private void showFaceDuplicateQueue(long currentPersonId){
        if(faceDuplicateQueueLoading.contains(currentPersonId)){
            toast("Possible duplicates are still being prepared for this face.");return;
        }
        if(ScanState.current().running()){
            toast("Pause scanning before reviewing possible duplicates.");return;
        }
        Store.Person current=store.person(currentPersonId);
        List<Store.Person> candidates=store.queuedDuplicatesForPerson(currentPersonId);
        if(current==null){toast("This face group is no longer available.");return;}
        if(candidates.isEmpty()){toast("There are no prepared duplicates for this face.");return;}

        Dialog dialog=new Dialog(this);LinearLayout panel=dialogPanel();
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text("Duplicates for "+current.title()+" ("+candidates.size()+")",20);
        title.setTypeface(null,Typeface.BOLD);
        title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        header.addView(title,new LinearLayout.LayoutParams(0,dp(52),1));
        Button close=button("×",dialog::dismiss);close.setTextSize(25);
        close.setContentDescription("Close duplicate list");
        header.addView(close,new LinearLayout.LayoutParams(dp(52),dp(52)));panel.addView(header);
        FaceDuplicateQueueController controller=new FaceDuplicateQueueController(
                dialog,currentPersonId,candidates);
        controller.titleView=title;
        LinearLayout pageActions=new LinearLayout(this);pageActions.setGravity(Gravity.CENTER_VERTICAL);
        Button allDifferent=button("All Different",
                ()->confirmAllDifferentQueued(controller));
        allDifferent.setContentDescription("Mark every face on this page as different");
        controller.allDifferentButton=allDifferent;
        pageActions.addView(allDifferent,new LinearLayout.LayoutParams(0,dp(52),1));
        Button combineAll=button("Combine all",
                ()->confirmCombineAllQueued(controller));
        combineAll.setContentDescription("Combine every duplicate face group on this page");
        controller.combineAllButton=combineAll;
        pageActions.addView(combineAll,new LinearLayout.LayoutParams(0,dp(52),1));
        LinearLayout.LayoutParams pageActionParams=new LinearLayout.LayoutParams(-1,dp(58));
        pageActionParams.setMargins(dp(8),0,dp(8),dp(6));panel.addView(pageActions,pageActionParams);
        DuplicatePageIndicator topIndicator=new DuplicatePageIndicator();
        controller.topIndicator=topIndicator;
        LinearLayout.LayoutParams indicatorParams=new LinearLayout.LayoutParams(-1,dp(10));
        indicatorParams.setMargins(dp(10),0,dp(10),0);panel.addView(topIndicator,indicatorParams);
        DuplicateCarousel carousel=new DuplicateCarousel(controller);controller.carousel=carousel;
        panel.addView(carousel,new LinearLayout.LayoutParams(-1,0,1));
        controller.updateControls();carousel.refreshPages(true);
        dialog.setOnDismissListener(ignored->{
            if(activeDialog==dialog)activeDialog=null;
            if(faceDuplicateListDialog==dialog)faceDuplicateListDialog=null;
            if(controller.changed&&!isDestroyed())render();
        });
        showFaceDuplicateListDialog(dialog,panel);
    }

    private final class DuplicatePageIndicator extends View{
        private final Paint track=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumb=new Paint(Paint.ANTI_ALIAS_FLAG);
        private float position;private int pages=1;
        DuplicatePageIndicator(){
            super(MainActivity.this);track.setColor(Color.rgb(220,224,232));thumb.setColor(BLUE);
        }
        void setPosition(int page,int pages){
            setScrollPosition(page,pages);
        }
        void setScrollPosition(float position,int pages){
            this.pages=Math.max(1,pages);
            this.position=Math.max(0,Math.min(position,this.pages-1));
            int page=Math.round(this.position);
            setContentDescription("Duplicate page "+(page+1)+" of "+this.pages);invalidate();
        }
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);float width=getWidth(),barHeight=dp(4),top=(getHeight()-barHeight)/2f;
            canvas.drawRoundRect(0,top,width,top+barHeight,barHeight/2,barHeight/2,track);
            float thumbWidth=pages==1?width:Math.max(dp(24),width/pages);
            float left=pages==1?0:(width-thumbWidth)*position/(pages-1f);
            canvas.drawRoundRect(left,top,left+thumbWidth,top+barHeight,
                    barHeight/2,barHeight/2,thumb);
        }
    }

    private final class FaceDuplicateQueueController{
        final Dialog dialog;final long currentPersonId;
        List<Store.Person> candidates;boolean changed;int pageIndex;
        Button allDifferentButton,combineAllButton;
        TextView titleView;DuplicateCarousel carousel;
        DuplicatePageIndicator topIndicator;
        FaceDuplicateQueueController(Dialog dialog,long currentPersonId,List<Store.Person> candidates){
            this.dialog=dialog;this.currentPersonId=currentPersonId;this.candidates=candidates;
        }
        int pageCount(){return Math.max(1,(candidates.size()+DUPLICATE_PAGE_SIZE-1)/DUPLICATE_PAGE_SIZE);}
        List<Store.Person> pageCandidates(int page){
            int start=Math.min(Math.max(0,page)*DUPLICATE_PAGE_SIZE,candidates.size());
            int end=Math.min(start+DUPLICATE_PAGE_SIZE,candidates.size());
            return new ArrayList<>(candidates.subList(start,end));
        }
        List<Store.Person> currentPageCandidates(){return pageCandidates(pageIndex);}
        void updateControls(){
            int count=currentPageCandidates().size();
            Store.Person current=store.person(currentPersonId);
            if(titleView!=null)titleView.setText("Duplicates for "
                    +(current==null?"face":current.title())+" ("+candidates.size()+")");
            if(allDifferentButton!=null){
                allDifferentButton.setText("All Different ("+count+")");
                allDifferentButton.setEnabled(count>0);
            }
            if(combineAllButton!=null){
                combineAllButton.setText("Combine all ("+count+")");
                combineAllButton.setEnabled(count>0);
            }
            if(topIndicator!=null)topIndicator.setPosition(pageIndex,pageCount());
        }
        void updateIndicatorPosition(float position){
            if(topIndicator!=null)topIndicator.setScrollPosition(position,pageCount());
        }
        boolean reload(){
            candidates=store.queuedDuplicatesForPerson(currentPersonId);
            pageIndex=Math.max(0,Math.min(pageIndex,pageCount()-1));
            updateControls();
            if(candidates.isEmpty()){
                if(dialog.isShowing())dialog.dismiss();
                return true;
            }
            if(carousel!=null)carousel.refreshPages(false);
            return false;
        }
        boolean reloadAfterPageDecision(){
            boolean hadNext=pageIndex+1<pageCount();
            candidates=store.queuedDuplicatesForPerson(currentPersonId);
            pageIndex=Math.max(0,Math.min(pageIndex,pageCount()-1));updateControls();
            if(candidates.isEmpty()){
                if(dialog.isShowing())dialog.dismiss();return true;
            }
            if(carousel!=null){carousel.refreshPages(true);carousel.animateEntrance(hadNext);}
            return false;
        }
    }

    private final class FaceDuplicatePageAdapter extends BaseAdapter{
        final FaceDuplicateQueueController controller;
        List<Store.Person> pageCandidates=Collections.emptyList();
        FaceDuplicatePageAdapter(FaceDuplicateQueueController controller){this.controller=controller;}
        void setPage(int page){pageCandidates=controller.pageCandidates(page);notifyDataSetChanged();}
        @Override public int getCount(){return pageCandidates.size();}
        @Override public Store.Person getItem(int position){return pageCandidates.get(position);}
        @Override public long getItemId(int position){return getItem(position).id();}
        @Override public View getView(int position,View reusable,ViewGroup parent){
            LinearLayout row=faceDuplicateQueueRow(controller,getItem(position));
            row.setLayoutParams(new AbsListView.LayoutParams(-1,dp(92)));return row;
        }
    }

    private final class DuplicateCarousel extends FrameLayout{
        final FaceDuplicateQueueController controller;
        final ListView[] pageViews=new ListView[3];
        final FaceDuplicatePageAdapter[] pageAdapters=new FaceDuplicatePageAdapter[3];
        final int touchSlop=ViewConfiguration.get(MainActivity.this).getScaledTouchSlop();
        VelocityTracker velocityTracker;float downX,downY,dragOffset;
        boolean dragging,animating;
        DuplicateCarousel(FaceDuplicateQueueController controller){
            super(MainActivity.this);this.controller=controller;setClipChildren(true);
            setContentDescription("Swipe left or right between duplicate pages");
            for(int i=0;i<3;i++){
                ListView page=new ListView(MainActivity.this);
                page.setDivider(new android.graphics.drawable.ColorDrawable(Color.rgb(225,228,235)));
                page.setDividerHeight(dp(1));page.setOverScrollMode(OVER_SCROLL_NEVER);
                FaceDuplicatePageAdapter adapter=new FaceDuplicatePageAdapter(controller);
                page.setAdapter(adapter);pageViews[i]=page;pageAdapters[i]=adapter;addView(page);
            }
        }
        void refreshPages(boolean resetCurrent){
            int first=pageViews[1].getFirstVisiblePosition(),top=0;
            View firstView=pageViews[1].getChildAt(0);
            if(firstView!=null)top=firstView.getTop();
            for(int i=0;i<3;i++){
                int page=controller.pageIndex+i-1;pageAdapters[i].setPage(page);
                pageViews[i].setVisibility(page>=0&&page<controller.pageCount()?VISIBLE:INVISIBLE);
                if(i!=1||resetCurrent){
                    ListView pageView=pageViews[i];pageView.setSelectionFromTop(0,0);
                    pageView.post(()->pageView.setSelectionFromTop(0,0));
                }
            }
            if(!resetCurrent&&pageAdapters[1].getCount()>0){
                int restore=Math.min(Math.max(0,first),pageAdapters[1].getCount()-1);
                final int restoreTop=top;pageViews[1].post(()->
                        pageViews[1].setSelectionFromTop(restore,restoreTop));
            }
            dragOffset=0;requestLayout();post(()->applyOffset(0));
        }
        void animateEntrance(boolean fromNext){
            post(()->{
                dragOffset=fromNext?dp(32):-dp(32);applyOffset(dragOffset);
                animateOffsetTo(0,0);
            });
        }
        @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom){
            int width=right-left,height=bottom-top;
            for(ListView page:pageViews)page.layout(0,0,width,height);
            applyOffset(dragOffset);
        }
        private void applyOffset(float offset){
            dragOffset=offset;float width=getWidth();if(width<=0)return;
            pageViews[0].setTranslationX(-width+offset);
            pageViews[1].setTranslationX(offset);
            pageViews[2].setTranslationX(width+offset);
            controller.updateIndicatorPosition(controller.pageIndex-offset/width);
        }
        private void beginTouch(MotionEvent event){
            downX=event.getX();downY=event.getY();dragging=false;
            if(velocityTracker!=null)velocityTracker.recycle();
            velocityTracker=VelocityTracker.obtain();velocityTracker.addMovement(event);
        }
        @Override public boolean onInterceptTouchEvent(MotionEvent event){
            if(animating)return true;
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN -> {beginTouch(event);return false;}
                case MotionEvent.ACTION_MOVE -> {
                    if(velocityTracker!=null)velocityTracker.addMovement(event);
                    float dx=event.getX()-downX,dy=event.getY()-downY;
                    if(Math.abs(dx)>touchSlop&&Math.abs(dx)>Math.abs(dy)*1.2f){
                        dragging=true;getParent().requestDisallowInterceptTouchEvent(true);return true;
                    }
                }
                case MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> releaseTracker();
            }
            return false;
        }
        @Override public boolean onTouchEvent(MotionEvent event){
            if(animating)return true;
            if(velocityTracker==null)velocityTracker=VelocityTracker.obtain();
            velocityTracker.addMovement(event);
            if(event.getActionMasked()==MotionEvent.ACTION_MOVE){
                float desired=event.getX()-downX;
                boolean beyondStart=desired>0&&controller.pageIndex==0;
                boolean beyondEnd=desired<0&&controller.pageIndex+1>=controller.pageCount();
                applyOffset((beyondStart||beyondEnd)?desired*.24f:desired);return true;
            }
            if(event.getActionMasked()==MotionEvent.ACTION_UP){
                velocityTracker.computeCurrentVelocity(1000);float velocity=velocityTracker.getXVelocity();
                int direction=0;float width=Math.max(1,getWidth());
                if(controller.pageIndex+1<controller.pageCount()
                        &&(dragOffset<-width*.18f||velocity<-900))direction=1;
                else if(controller.pageIndex>0
                        &&(dragOffset>width*.18f||velocity>900))direction=-1;
                releaseTracker();getParent().requestDisallowInterceptTouchEvent(false);
                animateOffsetTo(direction==1?-width:direction==-1?width:0,direction);return true;
            }
            if(event.getActionMasked()==MotionEvent.ACTION_CANCEL){
                releaseTracker();getParent().requestDisallowInterceptTouchEvent(false);
                animateOffsetTo(0,0);return true;
            }
            return true;
        }
        private void animateOffsetTo(float target,int direction){
            if(animating)return;animating=true;
            ValueAnimator animator=ValueAnimator.ofFloat(dragOffset,target);
            animator.setDuration(220);animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(value->applyOffset((float)value.getAnimatedValue()));
            animator.addListener(new android.animation.AnimatorListenerAdapter(){
                @Override public void onAnimationEnd(android.animation.Animator animation){
                    animating=false;
                    if(direction!=0){
                        controller.pageIndex=Math.max(0,Math.min(
                                controller.pageIndex+direction,controller.pageCount()-1));
                        controller.updateControls();refreshPages(true);
                    }else applyOffset(0);
                }
            });animator.start();
        }
        private void releaseTracker(){
            if(velocityTracker!=null){velocityTracker.recycle();velocityTracker=null;}
            dragging=false;
        }
    }

    private void confirmCombineAllQueued(FaceDuplicateQueueController controller){
        Store.Person current=store.person(controller.currentPersonId);
        List<Store.Person> candidates=controller.currentPageCandidates();
        if(current==null||candidates.isEmpty()){
            if(controller.reload())toast("All duplicates are resolved.");return;
        }
        String message="Combine all "+candidates.size()+" face groups on this page into "
                +current.title()+"? Use this only when every listed face is the same person. ";
        message+=current.name().isEmpty()
                ?"The face will adopt the first available name. This bulk action cannot be undone."
                :"The name "+current.name()+" will be kept. This bulk action cannot be undone.";
        new AlertDialog.Builder(this).setTitle("Combine this page?")
                .setMessage(message)
                .setPositiveButton("Combine all",(ignored,which)->combineAllQueued(
                        controller,current,candidates))
                .setNegativeButton("Cancel",null).show();
    }

    private void confirmAllDifferentQueued(FaceDuplicateQueueController controller){
        Store.Person current=store.person(controller.currentPersonId);
        List<Store.Person> candidates=controller.currentPageCandidates();
        if(current==null||candidates.isEmpty()){
            if(controller.reload())toast("All duplicates are resolved.");return;
        }
        new AlertDialog.Builder(this).setTitle("Mark this page as Different?")
                .setMessage("Mark all "+candidates.size()+" face groups on this page as different from "
                        +current.title()+"? These comparisons will not appear again in duplicate review.")
                .setPositiveButton("All Different",(ignored,which)->
                        markPageDifferent(controller,current,candidates))
                .setNegativeButton("Cancel",null).show();
    }

    private void markPageDifferent(FaceDuplicateQueueController controller,Store.Person current,
                                   List<Store.Person> candidates){
        ArrayList<Long> candidateIds=new ArrayList<>();
        for(Store.Person candidate:candidates)candidateIds.add(candidate.id());
        AlertDialog progress=duplicateBatchProgress("Marking faces Different",
                "Updating "+candidateIds.size()+" comparisons...");
        images.execute(()->{
            int changed=store.markAllNotSame(current.id(),candidateIds);
            runOnUiThread(()->{
                if(progress.isShowing())progress.dismiss();
                boolean resolved=controller.reloadAfterPageDecision();
                toast(resolved?"All duplicates are resolved.":changed+" "
                        +(changed==1?"comparison":"comparisons")+" marked Different.");
            });
        });
    }

    private void combineAllQueued(FaceDuplicateQueueController controller,Store.Person current,
                                  List<Store.Person> candidates){
        ArrayList<Long> candidateIds=new ArrayList<>();String inheritedName="";
        for(Store.Person candidate:candidates){
            candidateIds.add(candidate.id());
            if(inheritedName.isEmpty()&&!candidate.name().isEmpty())inheritedName=candidate.name();
        }
        LinearLayout busy=new LinearLayout(this);busy.setGravity(Gravity.CENTER_VERTICAL);
        busy.setPadding(dp(20),dp(12),dp(20),dp(12));
        busy.addView(new ProgressBar(this),new LinearLayout.LayoutParams(dp(42),dp(42)));
        TextView status=text("Combining "+candidateIds.size()+" face groupsâ€¦",15);
        LinearLayout.LayoutParams statusParams=new LinearLayout.LayoutParams(0,-2,1);
        statusParams.setMargins(dp(14),0,0,0);busy.addView(status,statusParams);
        AlertDialog progress=new AlertDialog.Builder(this).setTitle("Combining duplicates")
                .setView(busy).setCancelable(false).create();progress.show();
        final String nameToKeep=inheritedName;
        images.execute(()->{
            if(current.name().isEmpty()&&!nameToKeep.isEmpty())
                store.rename(current.id(),nameToKeep);
            int merged=store.mergeAll(candidateIds,current.id());
            runOnUiThread(()->{
                if(progress.isShowing())progress.dismiss();
                for(long id:candidateIds)faceCache.remove(id);faceCache.remove(current.id());
                loadedPersonPhotoId=-1;personId=current.id();controller.changed|=merged>0;
                boolean resolved=controller.reloadAfterPageDecision();
                toast(resolved?"All duplicates are resolved.":merged+" face "
                        +(merged==1?"group":"groups")+" combined.");
            });
        });
    }

    private AlertDialog duplicateBatchProgress(String title,String message){
        LinearLayout busy=new LinearLayout(this);busy.setGravity(Gravity.CENTER_VERTICAL);
        busy.setPadding(dp(20),dp(12),dp(20),dp(12));
        busy.addView(new ProgressBar(this),new LinearLayout.LayoutParams(dp(42),dp(42)));
        TextView status=text(message,15);
        LinearLayout.LayoutParams statusParams=new LinearLayout.LayoutParams(0,-2,1);
        statusParams.setMargins(dp(14),0,0,0);busy.addView(status,statusParams);
        AlertDialog progress=new AlertDialog.Builder(this).setTitle(title)
                .setView(busy).setCancelable(false).create();progress.show();return progress;
    }

    private LinearLayout faceDuplicateQueueRow(FaceDuplicateQueueController controller,
                                                Store.Person candidate){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2),dp(8),dp(2),dp(8));
        ImageView portrait=facePortrait(candidate.id(),68);
        portrait.setContentDescription("Possible duplicate "+candidate.title());
        row.addView(portrait,new LinearLayout.LayoutParams(dp(68),dp(68)));
        TextView name=text(candidate.title(),12);name.setTypeface(null,Typeface.BOLD);
        name.setGravity(Gravity.CENTER);name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameParams=new LinearLayout.LayoutParams(dp(72),-1);
        nameParams.setMargins(dp(5),0,dp(3),0);row.addView(name,nameParams);

        LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.addView(compactDuplicateButton("Different",()->handleQueuedDuplicate(
                controller,candidate.id(),0)),
                new LinearLayout.LayoutParams(0,dp(52),1));
        actions.addView(compactDuplicateButton("Skip",()->handleQueuedDuplicate(
                controller,candidate.id(),1)),
                new LinearLayout.LayoutParams(0,dp(52),1));
        actions.addView(compactDuplicateButton("Combine",()->handleQueuedDuplicate(
                controller,candidate.id(),2)),
                new LinearLayout.LayoutParams(0,dp(52),1));
        row.addView(actions,new LinearLayout.LayoutParams(0,-1,1));
        return row;
    }

    private Button compactDuplicateButton(String label,Runnable action){
        Button result=button(label,action);result.setTextSize(11);result.setMinWidth(0);
        result.setMinimumWidth(0);result.setPadding(0,0,0,0);return result;
    }

    private void handleQueuedDuplicate(FaceDuplicateQueueController controller,long candidateId,int decision){
        long currentPersonId=controller.currentPersonId;
        Store.Person current=store.person(currentPersonId),candidate=store.person(candidateId);
        if(current==null||candidate==null){
            store.removeQueuedDuplicate(currentPersonId,candidateId);
        }else if(decision==0){
            store.markNotSame(currentPersonId,candidateId);
        }else if(decision==1){
            store.skipDuplicate(currentPersonId,candidateId);
        }else{
            if(current.name().isEmpty()&&!candidate.name().isEmpty())
                store.rename(currentPersonId,candidate.name());
            store.merge(candidateId,currentPersonId);
            faceCache.remove(candidateId);faceCache.remove(currentPersonId);
            loadedPersonPhotoId=-1;personId=currentPersonId;controller.changed=true;
        }
        if(controller.reload())toast("All duplicates are resolved.");
    }

    private void showFaceDuplicateListDialog(Dialog dialog,View panel){
        dialog.setContentView(panel);dialog.setCanceledOnTouchOutside(false);dialog.show();
        activeDialog=dialog;faceDuplicateListDialog=dialog;
        Window window=dialog.getWindow();if(window==null)return;
        window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        updateActiveDialogBounds();
    }

    private void startSkippedDuplicateReview() {
        if (ScanState.current().running()) {
            toast("Pause scanning before reviewing skipped duplicates.");
            return;
        }
        loadSkippedDuplicateBatch(0,new HashSet<>());
    }

    private void loadSkippedDuplicateBatch(int reviewed, Set<String> reviewedThisSession) {
        Dialog progress=showSkippedRevalidationProgress();
        images.execute(()->{
            List<Store.SimilarPair> revalidated=store.revalidatedSkippedPairs();
            List<Store.SimilarPair> pairs=new ArrayList<>();
            for(Store.SimilarPair pair:revalidated){
                if(reviewedThisSession.contains(duplicatePairKey(pair.first().id(),pair.second().id())))continue;
                pairs.add(pair);if(pairs.size()==20)break;
            }
            runOnUiThread(()->{
                if(progress.isShowing())progress.dismiss();
                if(isDestroyed()||isFinishing())return;
                if(pairs.isEmpty()){
                    render();
                    toast(reviewed==0?"There are no valid skipped duplicate comparisons."
                            :"No more skipped duplicate comparisons remain.");
                }else showSkippedDuplicatePair(pairs,0,reviewed,reviewedThisSession);
            });
        });
    }

    private Dialog showSkippedRevalidationProgress() {
        Dialog dialog=new Dialog(this);dialog.setCancelable(false);
        LinearLayout panel=dialogPanel();
        TextView title=text("Review skipped duplicates",20);title.setTypeface(null,Typeface.BOLD);
        title.setPadding(0,dp(12),0,dp(12));panel.addView(title,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout status=new LinearLayout(this);status.setGravity(Gravity.CENTER_VERTICAL);
        ProgressBar spinner=new ProgressBar(this);spinner.setIndeterminate(true);
        status.addView(spinner,new LinearLayout.LayoutParams(dp(42),dp(42)));
        TextView message=text("Revalidating skipped items…",15);message.setPadding(dp(12),0,0,0);
        status.addView(message,new LinearLayout.LayoutParams(0,dp(56),1));
        panel.addView(status,new LinearLayout.LayoutParams(-1,-2));
        showDialog(dialog,panel);return dialog;
    }

    private void showSkippedDuplicatePair(List<Store.SimilarPair> pairs,int requestedIndex,
                                          int reviewed,Set<String> reviewedThisSession) {
        int index=requestedIndex;Store.Person first=null,second=null;
        while(index<pairs.size()){
            first=store.person(pairs.get(index).first().id());
            second=store.person(pairs.get(index).second().id());
            if(first!=null&&second!=null&&first.id()!=second.id())break;
            index++;
        }
        if(first==null||second==null||index>=pairs.size()){
            render();loadSkippedDuplicateBatch(reviewed,reviewedThisSession);return;
        }
        final int currentIndex=index;final Store.Person left=first,right=second;
        showDuplicateComparison(left,right,reviewed,
                ()->{
                    store.markNotSame(left.id(),right.id());
                    continueSkippedDuplicateReview(pairs,currentIndex+1,reviewed+1,reviewedThisSession);
                },()->{
                    store.skipDuplicate(left.id(),right.id());
                    reviewedThisSession.add(duplicatePairKey(left.id(),right.id()));
                    continueSkippedDuplicateReview(pairs,currentIndex+1,reviewed+1,reviewedThisSession);
                },()->{
                    Store.Person keep=preferredMergeTarget(left,right);
                    Store.Person remove=keep.id()==left.id()?right:left;
                    store.merge(remove.id(),keep.id());faceCache.remove(remove.id());faceCache.remove(keep.id());
                    render();continueSkippedDuplicateReview(
                            pairs,currentIndex+1,reviewed+1,reviewedThisSession);
                });
    }

    private void continueSkippedDuplicateReview(List<Store.SimilarPair> pairs,int nextIndex,
                                                int reviewed,Set<String> reviewedThisSession) {
        if(reviewed%20==0||nextIndex>=pairs.size())
            loadSkippedDuplicateBatch(reviewed,reviewedThisSession);
        else showSkippedDuplicatePair(pairs,nextIndex,reviewed,reviewedThisSession);
    }

    private void showDuplicateComparison(Store.Person leftPerson,Store.Person rightPerson,
                                         int reviewed,Runnable different,Runnable skip,Runnable combine) {
        final Dialog dialog=new Dialog(this);
        LinearLayout panel=dialogPanel();
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text("Review possible duplicates",20);title.setTypeface(null,Typeface.BOLD);
        header.addView(title,new LinearLayout.LayoutParams(0,dp(52),1));
        Button close=button("×",dialog::dismiss);close.setTextSize(25);
        close.setContentDescription("Close duplicate review");
        header.addView(close,new LinearLayout.LayoutParams(dp(52),dp(52)));panel.addView(header);
        TextView question=text("Are these the same person? • "+(reviewed+1),14);
        question.setTextColor(Color.DKGRAY);question.setGravity(Gravity.CENTER);
        question.setPadding(0,0,0,dp(12));panel.addView(question,new LinearLayout.LayoutParams(-1,-2));

        LinearLayout faces=new LinearLayout(this);faces.setGravity(Gravity.CENTER);
        faces.addView(duplicatePortrait(leftPerson),new LinearLayout.LayoutParams(0,-2,1));
        faces.addView(duplicatePortrait(rightPerson),new LinearLayout.LayoutParams(0,-2,1));
        panel.addView(faces,new LinearLayout.LayoutParams(-1,-2));
        TextView hint=text("Combine joins both groups. Original photos are never changed.",13);
        hint.setTextColor(Color.GRAY);hint.setGravity(Gravity.CENTER);hint.setPadding(0,dp(12),0,dp(8));
        panel.addView(hint,new LinearLayout.LayoutParams(-1,-2));

        LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.CENTER);
        actions.addView(button("Different",()->{dialog.dismiss();different.run();}),
                new LinearLayout.LayoutParams(0,dp(56),1));
        actions.addView(button("Skip",()->{dialog.dismiss();skip.run();}),
                new LinearLayout.LayoutParams(0,dp(56),1));
        actions.addView(button("Combine",()->{dialog.dismiss();combine.run();}),
                new LinearLayout.LayoutParams(0,dp(56),1));
        panel.addView(actions,new LinearLayout.LayoutParams(-1,-2));
        showDialog(dialog,panel);
    }

    private String duplicatePairKey(long first, long second) {
        return Math.min(first, second) + ":" + Math.max(first, second);
    }

    private Store.Person preferredMergeTarget(Store.Person first, Store.Person second) {
        if (first.name().isEmpty() != second.name().isEmpty()) return first.name().isEmpty() ? second : first;
        return first.count() >= second.count() ? first : second;
    }

    private LinearLayout duplicatePortrait(Store.Person person) {
        LinearLayout column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);column.setGravity(Gravity.CENTER);
        column.addView(facePortrait(person.id(),132),new LinearLayout.LayoutParams(dp(132),dp(132)));
        TextView label=text(person.title(),14);label.setTypeface(null,Typeface.BOLD);label.setGravity(Gravity.CENTER);
        label.setMaxLines(1);label.setEllipsize(android.text.TextUtils.TruncateAt.END);label.setPadding(dp(3),dp(7),dp(3),0);
        column.addView(label,new LinearLayout.LayoutParams(-1,-2));
        TextView count=text(person.count()+" photos",12);count.setTextColor(Color.GRAY);count.setGravity(Gravity.CENTER);
        column.addView(count,new LinearLayout.LayoutParams(-1,-2));return column;
    }

    private ImageView facePortrait(long id,int size) {
        ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackground(rounded(Color.rgb(227,232,246),FACE_THUMB_CORNER_DP));image.setClipToOutline(true);
        image.setContentDescription("Face portrait");
        Bitmap bitmap=faceCache.get(id);
        if(bitmap==null){byte[] bytes=store.thumb(id);if(bytes!=null){bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length);if(bitmap!=null)faceCache.put(id,bitmap);}}
        if(bitmap!=null)image.setImageBitmap(bitmap);else image.setImageResource(android.R.drawable.ic_menu_report_image);
        return image;
    }

    private LinearLayout dialogPanel() {
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18),dp(8),dp(18),dp(16));panel.setBackground(rounded(Color.WHITE,20));return panel;
    }

    private void showDialog(Dialog dialog,View panel) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.addView(panel, new ScrollView.LayoutParams(-1, -2));
        dialog.setContentView(scroll);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(ignored -> {
            if (activeDialog == dialog) activeDialog = null;
        });
        dialog.show();
        activeDialog = dialog;
        Window window=dialog.getWindow();if(window!=null)
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        updateActiveDialogBounds();
    }

    private void updateActiveDialogBounds() {
        if (activeDialog == null || !activeDialog.isShowing()) return;
        Window window = activeDialog.getWindow();
        if (window == null) return;
        boolean duplicateList=activeDialog==faceDuplicateListDialog;
        int width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(24),
                dp(duplicateList?560:520));
        int height = duplicateList
                ? Math.min(getResources().getDisplayMetrics().heightPixels-dp(36),dp(680))
                : isLandscape()
                ? Math.max(dp(180), getResources().getDisplayMetrics().heightPixels - dp(24))
                : ViewGroup.LayoutParams.WRAP_CONTENT;
        window.setLayout(width, height);
    }

    private void correct(String uri) {
        if (ScanState.current().running()) {
            toast("Pause scanning before correcting groups.");
            return;
        }
        new AlertDialog.Builder(this).setTitle("Wrong person?")
                .setMessage("Move this person's face in this photo into a new group. You can then merge that group into the correct person.")
                .setPositiveButton("Separate", (dialog, which) -> {
                    store.separate(personId, uri);
                    faceCache.evictAll();
                    render();
                }).setNegativeButton("Cancel", null).show();
    }

    private void showFull() {
        applyPhotoWindowLayout();

        FrameLayout viewer = new FrameLayout(this);
        viewer.setBackgroundColor(Color.BLACK);
        PhotoPager pager = new PhotoPager(this);
        photoPager = pager;
        pager.setBackgroundColor(Color.BLACK);
        pager.setContentDescription("Photo viewer");
        viewer.addView(pager, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        photoTopControls = top;
        top.setGravity(Gravity.CENTER_VERTICAL);
        VectorIcon back = new VectorIcon(this, R.drawable.ic_back);
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setContentDescription("Back to photos");
        back.setClickable(true);
        back.setFocusable(true);
        back.setOnClickListener(button -> onBackPressed());
        top.addView(back, new LinearLayout.LayoutParams(dp(56), dp(56)));
        Space topSpace = new Space(this);
        top.addView(topSpace, new LinearLayout.LayoutParams(0, dp(56), 1));
        MoreIcon more = new MoreIcon(this);
        more.setContentDescription("Photo menu");
        more.setOnClickListener(button -> showPhotoMenu(button));
        top.addView(more, new LinearLayout.LayoutParams(dp(56), dp(56)));
        viewer.addView(top, new FrameLayout.LayoutParams(-1, dp(64), Gravity.TOP));

        LinearLayout bottom = new LinearLayout(this);
        photoBottomControls = bottom;
        bottom.setGravity(Gravity.CENTER);
        VectorIcon delete = new VectorIcon(this, R.drawable.ic_delete);
        delete.setContentDescription("Delete photo");
        delete.setOnClickListener(button -> confirmDeletePhoto());
        bottom.addView(delete, new LinearLayout.LayoutParams(dp(72), dp(64)));
        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(-1, dp(72), Gravity.BOTTOM);
        viewer.addView(bottom, bottomParams);
        setContentView(viewer);
        setPhotoControlsVisible(photoControlsVisible, false);

        String key = photoUri;
        ensureViewerPhotoList(key, pager);
        pager.rebuildForUri(key);
        prefetchPagerWindow(key, pager);
        if (fullBitmap == null || fullBitmap.isRecycled()
                || !pager.setCurrentFullBitmap(key, fullBitmap)) loadFullViewerPhoto(key, pager);

    }

    private void ensureViewerPhotoList(String key, PhotoPager pager) {
        if (photoList.contains(key)) return;
        photoList = new ArrayList<>(Collections.singletonList(key));
        long requestedPerson = personId;
        long requestedAlbum = returnAlbumId;
        data.execute(() -> {
            List<String> loaded = requestedPerson >= 0 ? store.photos(requestedPerson)
                    : requestedAlbum >= 0 ? albumPhotosByDate(requestedAlbum)
                    : new ArrayList<>(Collections.singletonList(key));
            runOnUiThread(() -> {
                if (isDestroyed() || photoPager != pager || !key.equals(photoUri)
                        || !loaded.contains(key)) return;
                photoList = loaded;
                pager.rebuildForUri(key);
                prefetchPagerWindow(key, pager);
            });
        });
    }

    private void loadFullViewerPhoto(String key, PhotoPager pager) {
        int generation = ++fullPhotoGeneration;
        int screen = Math.max(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
        int decodeLimit = Math.max(3072, Math.min(4096, screen * 3));
        fullImages.execute(() -> {
            if (generation != fullPhotoGeneration) return;
            try {
                Bitmap bitmap = Engine.decode(getApplicationContext(), key, decodeLimit);
                runOnUiThread(() -> {
                    if (generation == fullPhotoGeneration && !isDestroyed()
                            && photoPager == pager && key.equals(photoUri)
                            && pager.setCurrentFullBitmap(key, bitmap)) {
                        recycleFullBitmapOnly();
                        fullBitmap = bitmap;
                    } else bitmap.recycle();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (generation == fullPhotoGeneration && key.equals(photoUri))
                        toast("Photo is unavailable. Check photo access or whether it was deleted.");
                });
            }
        });
    }

    private void onPagerPhotoSelected(String uri, PhotoPager pager) {
        if (uri.equals(photoUri) || photoPager != pager) return;
        photoUri = uri;
        recycleFullBitmapOnly();
        prefetchPagerWindow(uri, pager);
        loadFullViewerPhoto(uri, pager);
    }

    private void prefetchPagerWindow(String centerUri, PhotoPager pager) {
        int center = photoList.indexOf(centerUri);
        if (center < 0) return;
        requestPagerPreview(centerUri, pager);
        for (int distance = 1; distance <= 2; distance++) {
            int previous = center - distance;
            int next = center + distance;
            if (previous >= 0) requestPagerPreview(photoList.get(previous), pager);
            if (next < photoList.size()) requestPagerPreview(photoList.get(next), pager);
        }
    }

    private Bitmap pagerPreview(String uri) {
        Bitmap preview = pagerPreviewCache.get(uri);
        return preview != null ? preview : photoCache.get(uri);
    }

    private void requestPagerPreview(String uri, PhotoPager pager) {
        Bitmap cached = pagerPreview(uri);
        if (cached != null) {
            pager.onPreviewLoaded(uri, cached);
            if (pagerPreviewCache.get(uri) != null) return;
        }
        if (!pagerPreviewLoads.add(uri)) return;
        int target = 2560;
        images.execute(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = Engine.decode(getApplicationContext(), uri, target);
                pagerPreviewCache.put(uri, bitmap);
            } catch (IOException | RuntimeException ignored) {
            } finally {
                pagerPreviewLoads.remove(uri);
            }
            Bitmap loaded = bitmap;
            if (loaded != null) runOnUiThread(() -> {
                if (!isDestroyed() && photoPager == pager) pager.onPreviewLoaded(uri, loaded);
            });
        });
    }

    private void applyPhotoWindowLayout() {
        Window window = getWindow();
        View decor = window.getDecorView();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        WindowManager.LayoutParams attributes = window.getAttributes();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attributes);
        }
        decor.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(0, 0, 0, 0);
            return insets.consumeSystemWindowInsets();
        });
        decor.setPadding(0, 0, 0, 0);
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        decor.requestApplyInsets();
    }

    private void applyNormalWindowLayout() {
        Window window = getWindow();
        View decor = window.getDecorView();
        window.setStatusBarColor(BG);
        window.setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setNavigationBarContrastEnforced(true);
        }
        WindowManager.LayoutParams attributes = window.getAttributes();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
            window.setAttributes(attributes);
        }
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        decor.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        decor.requestApplyInsets();
    }

    private void applyHomeWindowLayout() {
        Window window = getWindow();
        View decor = window.getDecorView();
        window.setStatusBarColor(BG);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setNavigationBarContrastEnforced(false);
        }
        WindowManager.LayoutParams attributes = window.getAttributes();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
            window.setAttributes(attributes);
        }
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        decor.setOnApplyWindowInsetsListener((view, insets) -> {
            int navigationInset = insets.getSystemWindowInsetBottom();
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), 0);
            updateHomeBottomInset(navigationInset);
            return insets.consumeSystemWindowInsets();
        });
        decor.requestApplyInsets();
    }

    private void updateHomeBottomInset(int navigationInset) {
        homeNavigationInsetBottom = Math.max(0, navigationInset);
        if (grid != null && !singlesScreen) {
            grid.setPadding(grid.getPaddingLeft(), grid.getPaddingTop(), grid.getPaddingRight(),
                    dp(84) + homeNavigationInsetBottom);
        }
        if (homeFloatingBar != null) {
            ViewGroup.LayoutParams rawParams = homeFloatingBar.getLayoutParams();
            if (rawParams instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) rawParams;
                params.bottomMargin = dp(10) + homeNavigationInsetBottom;
                homeFloatingBar.setLayoutParams(params);
            }
        }
    }

    private void togglePhotoControls() {
        setPhotoControlsVisible(!photoControlsVisible, true);
    }

    private void setPhotoControlsVisible(boolean visible, boolean animate) {
        photoControlsVisible = visible;
        updatePhotoControlView(photoTopControls, visible, animate);
        updatePhotoControlView(photoBottomControls, visible, animate);
    }

    private void updatePhotoControlView(View control, boolean visible, boolean animate) {
        if (control == null) return;
        control.animate().cancel();
        if (visible) {
            control.setVisibility(View.VISIBLE);
            if (animate) {
                control.setAlpha(0f);
                control.animate().alpha(1f).setDuration(160).start();
            } else control.setAlpha(1f);
        } else if (animate) {
            control.animate().alpha(0f).setDuration(160)
                    .withEndAction(() -> {
                        if (!photoControlsVisible) control.setVisibility(View.GONE);
                    }).start();
        } else {
            control.setAlpha(0f);
            control.setVisibility(View.GONE);
        }
    }

    private void showPhotoMenu(View anchor) {
        long viewedPerson=personId;String viewedUri=photoUri;
        Store.Person face=viewedPerson>=0?store.person(viewedPerson):null;
        boolean canUntag=face!=null&&viewedUri!=null
                &&store.hasFaceInPhoto(viewedPerson,viewedUri);
        LinearLayout menu=new LinearLayout(this);menu.setOrientation(LinearLayout.VERTICAL);
        menu.setBackground(rounded(Color.WHITE,12));
        EyeIcon eye = new EyeIcon(this);
        eye.setLineColor(INK);
        LinearLayout info=photoMenuRow(eye,"Info");menu.addView(info,new LinearLayout.LayoutParams(-1,dp(56)));
        LinearLayout untag=null;
        if(canUntag){
            View divider=new View(this);divider.setBackgroundColor(Color.rgb(225,228,235));
            menu.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));
            ImageView removeIcon=new ImageView(this);
            removeIcon.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
            removeIcon.setColorFilter(INK);removeIcon.setPadding(dp(8),dp(8),dp(8),dp(8));
            untag=photoMenuRow(removeIcon,"This isn't "+face.title());
            menu.addView(untag,new LinearLayout.LayoutParams(-1,dp(56)));
        }
        int popupWidth=dp(canUntag?300:180),popupHeight=dp(canUntag?113:56);
        PopupWindow popup = new PopupWindow(menu,popupWidth,popupHeight,true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(8));
        info.setOnClickListener(view -> {
            popup.dismiss();
            showPhotoInfo(viewedUri);
        });
        if(untag!=null){
            String faceTitle=face.title();
            untag.setOnClickListener(view->{
                popup.dismiss();confirmUntagFaceFromPhoto(viewedPerson,viewedUri,faceTitle);
            });
        }
        popup.showAsDropDown(anchor,-popupWidth+dp(8),-dp(8));
    }

    private LinearLayout photoMenuRow(View icon,String labelText){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14),0,dp(14),0);row.setClickable(true);row.setFocusable(true);
        android.util.TypedValue value=new android.util.TypedValue();
        if(getTheme().resolveAttribute(android.R.attr.selectableItemBackground,value,true))
            row.setBackgroundResource(value.resourceId);
        row.addView(icon,new LinearLayout.LayoutParams(dp(36),dp(48)));
        TextView label=text(labelText,15);label.setTypeface(null,Typeface.BOLD);
        label.setGravity(Gravity.CENTER_VERTICAL);label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(label,new LinearLayout.LayoutParams(0,dp(52),1));return row;
    }

    private void confirmUntagFaceFromPhoto(long faceId,String uri,String faceTitle){
        if(ScanState.current().running()){
            toast("Pause face processing before removing a face tag.");return;
        }
        if(!store.hasFaceInPhoto(faceId,uri)){
            toast("This face is no longer tagged in the photo.");return;
        }
        new AlertDialog.Builder(this).setTitle(faceTitle+" isn't in this photo?")
                .setMessage("Remove this photo from "+faceTitle+"? The original photo will not be deleted. If no other face is tagged in it, the photo will be marked unscanned for the next face scan.")
                .setPositiveButton("Remove tag",(dialog,which)->
                        untagFaceFromViewer(faceId,uri,faceTitle))
                .setNegativeButton("Cancel",null).show();
    }

    private void untagFaceFromViewer(long faceId,String uri,String faceTitle){
        int oldIndex=Math.max(0,photoList.indexOf(uri));
        Store.UntagResult result=store.untagFaceFromPhoto(faceId,uri);
        if(result.removed()==0){toast("This face is no longer tagged in the photo.");return;}
        faceCache.evictAll();List<String> remaining=store.photos(faceId);
        loadedPersonPhotoId=faceId;photoList=remaining;
        if(faceId==personId&&uri.equals(photoUri)){
            releaseFullBitmap();
            if(remaining.isEmpty()){
                boolean returnToSingles=personFromSingles;
                photoUri=null;personId=-1;personFromSingles=false;
                if(!returnToSingles)singlesScreen=false;
            }else photoUri=remaining.get(Math.min(oldIndex,remaining.size()-1));
            render();
        }
        toast("Removed "+faceTitle+" from this photo."
                +(result.unscanned()?" The photo is now unscanned.":""));
    }

    private void showPhotoInfo(String uri) {
        if (uri == null) return;
        if (Build.VERSION.SDK_INT >= 29
                && checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            pendingInfoUri = uri;
            requestPermissions(new String[]{Manifest.permission.ACCESS_MEDIA_LOCATION},
                    PHOTO_LOCATION_PERMISSION_REQUEST);
            return;
        }
        loadPhotoInfo(uri);
    }

    private void loadPhotoInfo(String uri) {
        images.execute(() -> {
            String details;
            try { details = readPhotoInfo(uri); }
            catch (Exception error) { details = "Photo details are unavailable.\n\n" + errorMessage(error); }
            String message = details;
            runOnUiThread(() -> {
                if (isDestroyed() || !uri.equals(photoUri)) return;
                new AlertDialog.Builder(this).setTitle("Photo info").setMessage(message)
                        .setPositiveButton("Close", null).show();
            });
        });
    }

    private String readPhotoInfo(String uriText) throws IOException {
        Uri uri = Uri.parse(uriText);
        if (Build.VERSION.SDK_INT >= 29
                && checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            try { uri = MediaStore.setRequireOriginal(uri); }
            catch (IllegalArgumentException ignored) { /* Provider does not expose an original URI. */ }
        }
        ArrayList<String> projection = new ArrayList<>(Arrays.asList(
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.MIME_TYPE));
        if (Build.VERSION.SDK_INT >= 29) projection.add(MediaStore.Images.Media.RELATIVE_PATH);

        String name = null, mime = null, folder = null;
        long taken = 0, added = 0, modified = 0, bytes = 0;
        int width = 0, height = 0;
        try (Cursor cursor = getContentResolver().query(uri,
                projection.toArray(new String[0]), null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                name = cursorString(cursor, MediaStore.Images.Media.DISPLAY_NAME);
                taken = cursorLong(cursor, MediaStore.Images.Media.DATE_TAKEN);
                added = cursorLong(cursor, MediaStore.Images.Media.DATE_ADDED) * 1000L;
                modified = cursorLong(cursor, MediaStore.Images.Media.DATE_MODIFIED) * 1000L;
                width = (int) cursorLong(cursor, MediaStore.Images.Media.WIDTH);
                height = (int) cursorLong(cursor, MediaStore.Images.Media.HEIGHT);
                bytes = cursorLong(cursor, MediaStore.Images.Media.SIZE);
                mime = cursorString(cursor, MediaStore.Images.Media.MIME_TYPE);
                if (Build.VERSION.SDK_INT >= 29)
                    folder = cursorString(cursor, MediaStore.Images.Media.RELATIVE_PATH);
            }
        }

        String make = null, model = null, exposure = null, aperture = null;
        String iso = null, focalLength = null, exifTaken = null;
        float[] location = new float[2];
        boolean hasLocation = false;
        try (android.os.ParcelFileDescriptor descriptor =
                     getContentResolver().openFileDescriptor(uri, "r")) {
            if (descriptor != null) {
                ExifInterface exif = new ExifInterface(descriptor.getFileDescriptor());
                make = exif.getAttribute(ExifInterface.TAG_MAKE);
                model = exif.getAttribute(ExifInterface.TAG_MODEL);
                exposure = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME);
                aperture = exif.getAttribute(ExifInterface.TAG_F_NUMBER);
                iso = exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS);
                focalLength = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH);
                exifTaken = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
                hasLocation = exif.getLatLong(location);
            }
        } catch (RuntimeException ignored) {
            // Some providers expose the image but do not support seekable EXIF access.
        }

        StringBuilder details = new StringBuilder();
        appendInfo(details, "Name", name);
        if (taken > 0) appendInfo(details, "Taken", formatTime(taken));
        else appendInfo(details, "Taken", exifTaken);
        if (modified > 0) appendInfo(details, "Modified", formatTime(modified));
        else if (added > 0) appendInfo(details, "Added", formatTime(added));
        if (width > 0 && height > 0) appendInfo(details, "Dimensions", width + " × " + height);
        if (bytes > 0) appendInfo(details, "Size", formatBytes(bytes));
        appendInfo(details, "Type", mime);
        appendInfo(details, "Folder", folder);
        String camera = joinWords(make, model);
        appendInfo(details, "Camera", camera);
        appendInfo(details, "Exposure", exposure == null ? null : exposure + " sec");
        appendInfo(details, "Aperture", aperture == null ? null : "f/" + aperture);
        appendInfo(details, "ISO", iso);
        appendInfo(details, "Focal length", focalLength == null ? null : focalLength + " mm");
        if (hasLocation) appendInfo(details, "Location",
                String.format(Locale.US, "%.6f, %.6f", location[0], location[1]));
        if (details.length() == 0) details.append("No metadata is available for this photo.");
        return details.toString().trim();
    }

    private String cursorString(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? null : cursor.getString(index);
    }

    private long cursorLong(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? 0 : cursor.getLong(index);
    }

    private void appendInfo(StringBuilder details, String label, String value) {
        if (value == null || value.trim().isEmpty()) return;
        if (details.length() > 0) details.append('\n');
        details.append(label).append(": ").append(value.trim());
    }

    private String joinWords(String first, String second) {
        if (first == null || first.trim().isEmpty()) return second;
        if (second == null || second.trim().isEmpty()) return first;
        if (second.toLowerCase(Locale.ROOT).startsWith(first.toLowerCase(Locale.ROOT))) return second;
        return first.trim() + " " + second.trim();
    }

    private String formatTime(long time) {
        return java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,
                java.text.DateFormat.SHORT).format(new Date(time));
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes / 1024d;
        if (value < 1024) return String.format(Locale.US, "%.1f KB", value);
        value /= 1024d;
        if (value < 1024) return String.format(Locale.US, "%.1f MB", value);
        return String.format(Locale.US, "%.2f GB", value / 1024d);
    }

    private void sharePhoto() {
        if (photoUri == null) return;
        Intent share = new Intent(Intent.ACTION_SEND).setType("image/*")
                .putExtra(Intent.EXTRA_STREAM, Uri.parse(photoUri))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(share, "Share photo"));
    }

    private void confirmDeletePhoto() {
        if (photoUri == null) return;
        new AlertDialog.Builder(this).setTitle("Delete this photo?")
                .setMessage("This removes the original photo from this device. This action cannot be undone.")
                .setPositiveButton("Delete", (dialog, which) -> beginDeletePhoto(photoUri))
                .setNegativeButton("Cancel", null).show();
    }

    private void beginDeletePhoto(String uri) {
        pendingDeleteUri = uri;
        if (Build.VERSION.SDK_INT == 28
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    DELETE_PERMISSION_REQUEST);
            return;
        }
        deletePhotoNow(uri);
    }

    private void deletePhotoNow(String uriText) {
        try {
            Uri uri = Uri.parse(uriText);
            if (Build.VERSION.SDK_INT >= 30) {
                PendingIntent request = MediaStore.createDeleteRequest(getContentResolver(),
                        Collections.singletonList(uri));
                startIntentSenderForResult(request.getIntentSender(), DELETE_PHOTO_REQUEST,
                        null, 0, 0, 0);
            } else if (Build.VERSION.SDK_INT >= 29) {
                deletePhotoWithRecovery(uri, uriText);
            } else {
                int deleted = getContentResolver().delete(uri, null, null);
                if (deleted > 0) finishDeletedPhoto(uriText);
                else toast("The photo could not be deleted.");
            }
        } catch (Exception error) {
            pendingDeleteUri = null;
            toast("Delete failed: " + errorMessage(error));
        }
    }

    @android.annotation.TargetApi(29)
    private void deletePhotoWithRecovery(Uri uri, String uriText) throws IntentSender.SendIntentException {
        try {
            int deleted = getContentResolver().delete(uri, null, null);
            if (deleted > 0) finishDeletedPhoto(uriText);
            else toast("The photo could not be deleted.");
        } catch (android.app.RecoverableSecurityException recoverable) {
            startIntentSenderForResult(recoverable.getUserAction().getActionIntent().getIntentSender(),
                    DELETE_PHOTO_REQUEST, null, 0, 0, 0);
        }
    }

    private void finishDeletedPhoto(String deletedUri) {
        store.removePhoto(deletedUri);
        photoCache.remove(deletedUri);
        releaseFullBitmap();
        pendingDeleteUri = null;
        photoUri = null;
        loadedPersonPhotoId = -1;
        loadedAlbumPhotoId = -1;
        photoListGeneration++;
        if (personId < 0 && returnAlbumId >= 0) {
            albumsOpen = true;
            activeAlbumId = returnAlbumId;
            returnAlbumId = -1;
        }
        if (personId >= 0 && store.person(personId) == null) {
            personId = -1;
            personFromSingles = false;
            singlesScreen = false;
            if (returnToDeleteFacePicker) {
                returnToDeleteFacePicker = false;
                deleteFacePickerOpen = true;
            }
        }
        render();
        toast("Photo deleted.");
    }

    private void releaseFullBitmap() {
        recycleFullBitmapOnly();
        photoPager = null;
        photoTopControls = null;
        photoBottomControls = null;
    }

    private void recycleFullBitmapOnly() {
        fullPhotoGeneration++;
        if (fullBitmap != null && !fullBitmap.isRecycled()) fullBitmap.recycle();
        fullBitmap = null;
    }

    @Override public void onBackPressed() {
        if (helpSupportOpen) {
            closeHelpSupport();
        } else if (settingsOpen) {
            closeSettings();
        } else if (deleteFacePickerOpen) {
            cancelDeleteFacePicker();
        } else if (albumPickerOpen) {
            cancelAlbumPicker();
        } else if (albumsOpen) {
            if (activeAlbumId >= 0) {
                activeAlbumId = -1;
                render();
            } else goHome();
        } else if (searchOpen && personId < 0 && !singlesScreen) {
            searchOpen = false;
            searchQuery = "";
            hideKeyboard();
            render();
        } else if (photoUri != null) {
            releaseFullBitmap();
            photoUri = null;
            if (personId < 0 && returnAlbumId >= 0) {
                albumsOpen = true;
                activeAlbumId = returnAlbumId;
                returnAlbumId = -1;
            }
            render();
        } else if (personId >= 0) {
            personId = -1;
            if (returnToDeleteFacePicker) {
                returnToDeleteFacePicker = false;
                deleteFacePickerOpen = true;
            } else if (returnAlbumId >= 0) {
                albumsOpen = true;
                activeAlbumId = returnAlbumId;
                returnAlbumId = -1;
            } else if (!personFromSingles) singlesScreen = false;
            personFromSingles = false;
            render();
        } else if (singlesScreen) {
            singlesScreen = false;
            render();
        } else super.onBackPressed();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        boolean settingsSwipe = settingsOpen && !settingsClosing;
        boolean homeSwipe = !settingsOpen && !helpSupportOpen
                && !albumsOpen && !albumPickerOpen && !deleteFacePickerOpen
                && personId < 0 && !singlesScreen && photoUri == null;
        boolean albumSwipe = albumsOpen && !albumPickerOpen && !settingsOpen && !helpSupportOpen;
        if (settingsSwipe || homeSwipe || albumSwipe) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                settingsTouchDownX = event.getX();
                settingsTouchDownY = event.getY();
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                float horizontal = event.getX() - settingsTouchDownX;
                float vertical = event.getY() - settingsTouchDownY;
                boolean clearlyHorizontal = Math.abs(horizontal) > Math.abs(vertical) * 1.2f;
                if (settingsSwipe && horizontal < -dp(72) && clearlyHorizontal) {
                    if (helpSupportOpen) closeHelpSupport();
                    else closeSettings();
                    return true;
                }
                if (homeSwipe && clearlyHorizontal) {
                    if (horizontal > dp(72)) {
                        if (searchOpen) toggleHomeSearch();
                        else openSettings();
                        return true;
                    }
                    if (horizontal < -dp(72)) {
                        if (searchOpen) openAlbums();
                        else toggleHomeSearch();
                        return true;
                    }
                }
                if (albumSwipe && horizontal > dp(72) && clearlyHorizontal) {
                    openHomeSearch();
                    return true;
                }
            }
        }
        return super.dispatchTouchEvent(event);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private boolean fullAccess() {
        String permission = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean anyAccess() {
        return fullAccess() || (Build.VERSION.SDK_INT >= 34
                && checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                == PackageManager.PERMISSION_GRANTED);
    }

    private void requestPhotos() {
        if (Build.VERSION.SDK_INT >= 34) {
            requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED}, PHOTO_PERMISSION_REQUEST);
        } else {
            requestPermissions(new String[]{Build.VERSION.SDK_INT >= 33
                    ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE},
                    PHOTO_PERMISSION_REQUEST);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == PHOTO_PERMISSION_REQUEST) {
            render();
            if (pendingScanAfterPermission && anyAccess()) {
                pendingScanAfterPermission = false;
                requestNotificationThenStart();
            } else pendingScanAfterPermission = false;
        } else if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            boolean reevaluate=pendingReevaluationAfterNotification;
            pendingScanAfterPermission = false;
            pendingReevaluationAfterNotification=false;
            if(reevaluate)ScanService.reevaluateUnnamed(this);else ScanService.resume(this);
        } else if (requestCode == DELETE_PERMISSION_REQUEST) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED
                    && pendingDeleteUri != null) deletePhotoNow(pendingDeleteUri);
            else {
                pendingDeleteUri = null;
                toast("Storage permission is required to delete this photo.");
            }
        } else if (requestCode == PHOTO_LOCATION_PERMISSION_REQUEST) {
            String uri = pendingInfoUri;
            pendingInfoUri = null;
            if (uri != null && uri.equals(photoUri)) loadPhotoInfo(uri);
        }
    }

    private void toggleScan() {
        ScanState.Snapshot snapshot = ScanState.current();
        if (snapshot.pauseRequested()) {
            toast(snapshot.job()==ScanState.Job.REEVALUATE_UNNAMED
                    ?"The current face group is still finishing. Resume when pausing is complete."
                    :"The current photo is still finishing. Resume when pausing is complete.");
            return;
        }
        if (snapshot.running()) {
            if(snapshot.job()==ScanState.Job.PHOTO_SCAN)ScanService.pause(this);
            else toast("Pause unnamed-face re-evaluation before starting a photo scan.");
            return;
        }
        if (!anyAccess()) {
            pendingScanAfterPermission = true;
            requestPhotos();
            return;
        }
        requestNotificationThenStart();
    }

    private void requestNotificationThenStart() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            pendingScanAfterPermission = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_REQUEST);
        } else {
            pendingScanAfterPermission = false;
            ScanService.resume(this);
        }
    }

    private void toggleUnnamedReevaluation(){
        ScanState.Snapshot snapshot=ScanState.current();
        if(snapshot.pauseRequested()){
            toast("The current face group is still finishing. Start again when pausing is complete.");
            updateStatus(snapshot);return;
        }
        if(snapshot.running()){
            if(snapshot.job()==ScanState.Job.REEVALUATE_UNNAMED)ScanService.pause(this);
            else toast("Pause face recognition before re-evaluating unnamed faces.");
            updateStatus(snapshot);return;
        }
        if(Build.VERSION.SDK_INT>=33
                &&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                !=PackageManager.PERMISSION_GRANTED){
            pendingReevaluationAfterNotification=true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_REQUEST);
        }else{
            pendingReevaluationAfterNotification=false;
            ScanService.reevaluateUnnamed(this);
        }
    }

    private void updateStatus(ScanState.Snapshot snapshot) {
        updateScanActivityDot(snapshot.running() && !snapshot.pauseRequested());
        if (settingsScanInfo != null) {
            boolean photoJob=snapshot.job()==ScanState.Job.PHOTO_SCAN;
            String state = photoJob&&snapshot.pauseRequested() ? "Pausing"
                    : photoJob&&snapshot.running() ? "On" : "Off";
            settingsScanInfo.setText(String.format(Locale.getDefault(), "%s • %s",
                    state, scanDetails(snapshot)));
        }
        if (settingsScanToggle != null) {
            boolean photoJob=snapshot.job()==ScanState.Job.PHOTO_SCAN;
            syncingScanToggle = true;
            settingsScanToggle.setChecked(photoJob&&snapshot.running()&&!snapshot.pauseRequested());
            settingsScanToggle.setEnabled(!snapshot.pauseRequested()
                    &&(!snapshot.running()||photoJob));
            syncingScanToggle = false;
        }
        if(settingsReevaluationInfo!=null){
            String detail=snapshot.job()==ScanState.Job.REEVALUATE_UNNAMED
                    ?snapshot.message():"Compares unnamed groups with all existing groups using saved face data; original photos are not rescanned.";
            settingsReevaluationInfo.setText(detail);
        }
        if(settingsReevaluationToggle!=null){
            boolean reevaluation=snapshot.job()==ScanState.Job.REEVALUATE_UNNAMED;
            syncingReevaluationToggle=true;
            settingsReevaluationToggle.setChecked(
                    reevaluation&&snapshot.running()&&!snapshot.pauseRequested());
            settingsReevaluationToggle.setEnabled(!snapshot.pauseRequested()
                    &&(!snapshot.running()||reevaluation));
            syncingReevaluationToggle=false;
        }
    }

    private void updateScanActivityDot(boolean scanning) {
        if (scanActivityDot == null) return;
        if (!scanning) {
            scanActivityDot.clearAnimation();
            scanActivityDot.setAlpha(1f);
            scanActivityDot.setVisibility(View.GONE);
            return;
        }
        scanActivityDot.setVisibility(View.VISIBLE);
        if (scanActivityDot.getAnimation() != null) return;
        AlphaAnimation blink = new AlphaAnimation(1f, 0.28f);
        blink.setDuration(1100);
        blink.setRepeatMode(Animation.REVERSE);
        blink.setRepeatCount(Animation.INFINITE);
        scanActivityDot.startAnimation(blink);
    }

    private final class AlbumAdapter extends BaseAdapter {
        @Override public int getCount() { return faceAlbums.size(); }
        @Override public Object getItem(int position) { return faceAlbums.get(position); }
        @Override public long getItemId(int position) { return faceAlbums.get(position).id(); }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            AlbumHolder holder;
            LinearLayout cell;
            if (recycled instanceof LinearLayout && recycled.getTag() instanceof AlbumHolder) {
                cell = (LinearLayout) recycled;
                holder = (AlbumHolder) recycled.getTag();
            } else {
                cell = new LinearLayout(MainActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                FrameLayout coverFrame = new FrameLayout(MainActivity.this);
                coverFrame.setBackground(rounded(Color.rgb(232, 235, 243), 18));
                coverFrame.setClipToOutline(true);
                ImageView[] covers = new ImageView[4];
                int coverPixels = dp(albumThumbDp);
                int dividerPixels = dp(2);
                int miniPixels = (coverPixels - dividerPixels) / 2;
                int[] gravities = new int[]{
                        Gravity.START | Gravity.TOP, Gravity.END | Gravity.TOP,
                        Gravity.START | Gravity.BOTTOM, Gravity.END | Gravity.BOTTOM};
                for (int index = 0; index < covers.length; index++) {
                    ImageView cover = new ImageView(MainActivity.this);
                    cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    cover.setBackgroundColor(Color.rgb(224, 228, 238));
                    covers[index] = cover;
                    coverFrame.addView(cover, new FrameLayout.LayoutParams(
                            miniPixels, miniPixels, gravities[index]));
                }
                LinearLayout addPrompt = new LinearLayout(MainActivity.this);
                addPrompt.setOrientation(LinearLayout.VERTICAL);
                addPrompt.setGravity(Gravity.CENTER);
                TextView plus = text("+", 38);
                plus.setGravity(Gravity.CENTER);
                TextView addFaces = text("Add faces", 15);
                addFaces.setTypeface(null, Typeface.BOLD);
                addFaces.setGravity(Gravity.CENTER);
                addPrompt.addView(plus, new LinearLayout.LayoutParams(-1, dp(48)));
                addPrompt.addView(addFaces, new LinearLayout.LayoutParams(-1, -2));
                coverFrame.addView(addPrompt, new FrameLayout.LayoutParams(-1, -1));
                cell.addView(coverFrame,
                        new LinearLayout.LayoutParams(dp(albumThumbDp), dp(albumThumbDp)));
                TextView label = text("", 16);
                label.setTypeface(null, Typeface.BOLD);
                label.setGravity(Gravity.CENTER);
                label.setSingleLine(true);
                label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                label.setPadding(dp(3), dp(6), dp(3), 0);
                cell.addView(label, new LinearLayout.LayoutParams(-1, -2));
                TextView count = text("", 12);
                count.setTextColor(Color.GRAY);
                count.setGravity(Gravity.CENTER);
                cell.addView(count, new LinearLayout.LayoutParams(-1, -2));
                holder = new AlbumHolder(coverFrame, covers, addPrompt, label, count);
                cell.setTag(holder);
            }
            Store.Album album = faceAlbums.get(position);
            boolean empty = album.faceCount() == 0;
            holder.coverFrame.setContentDescription(album.name() + (empty ? ", empty album" :
                    ", " + album.faceCount() + " faces"));
            holder.label.setText(album.name());
            holder.count.setText(empty ? "Empty album" : album.faceCount() +
                    (album.faceCount() == 1 ? " face" : " faces"));
            holder.addPrompt.setVisibility(empty ? View.VISIBLE : View.GONE);
            List<Long> members = empty ? Collections.emptyList()
                    : new ArrayList<>(store.albumPersonIds(album.id()));
            for (int index = 0; index < holder.covers.length; index++) {
                ImageView cover = holder.covers[index];
                cover.setTag(null);
                cover.setImageDrawable(null);
                cover.setVisibility(empty ? View.GONE : View.VISIBLE);
                if (index < members.size()) bindFaceThumb(cover, members.get(index));
            }
            return cell;
        }
    }

    private final class AlbumPickerAdapter extends BaseAdapter {
        @Override public int getCount() { return albumPickerPeople.size(); }
        @Override public Object getItem(int position) { return albumPickerPeople.get(position); }
        @Override public long getItemId(int position) { return albumPickerPeople.get(position).id(); }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            FaceCellHolder holder;
            LinearLayout cell;
            if (recycled instanceof LinearLayout && recycled.getTag() instanceof FaceCellHolder
                    && ((FaceCellHolder) recycled.getTag()).tick != null) {
                cell = (LinearLayout) recycled;
                holder = (FaceCellHolder) recycled.getTag();
            } else {
                cell = new LinearLayout(MainActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                FrameLayout imageFrame = new FrameLayout(MainActivity.this);
                ImageView cover = new ImageView(MainActivity.this);
                cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                cover.setBackground(rounded(Color.rgb(227, 232, 246), FACE_THUMB_CORNER_DP));
                cover.setClipToOutline(true);
                imageFrame.addView(cover, new FrameLayout.LayoutParams(-1, -1));
                TextView tick = text("\u2713", 16);
                tick.setTextColor(Color.WHITE);
                tick.setTypeface(null, Typeface.BOLD);
                tick.setGravity(Gravity.CENTER);
                tick.setBackground(rounded(Color.rgb(35, 190, 82), 13));
                FrameLayout.LayoutParams tickParams = new FrameLayout.LayoutParams(dp(26), dp(26),
                        Gravity.END | Gravity.TOP);
                tickParams.setMargins(0, dp(4), dp(4), 0);
                imageFrame.addView(tick, tickParams);
                cell.addView(imageFrame,
                        new LinearLayout.LayoutParams(dp(faceThumbDp), dp(faceThumbDp)));
                TextView label = text("", 14);
                label.setGravity(Gravity.CENTER);
                label.setMaxLines(1);
                label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                label.setPadding(dp(2), dp(5), dp(2), dp(5));
                cell.addView(label, new LinearLayout.LayoutParams(-1, -2));
                holder = new FaceCellHolder(cover, label, tick);
                cell.setTag(holder);
            }
            Store.Person person = albumPickerPeople.get(position);
            boolean selected = albumDraftSelection.contains(person.id());
            holder.label.setText(person.title());
            holder.tick.setVisibility(selected ? View.VISIBLE : View.GONE);
            holder.cover.setContentDescription(person.title() + (selected ? ", selected" : ", not selected"));
            bindFaceThumb(holder.cover, person.id());
            return cell;
        }
    }

    private final class DeleteFacePickerAdapter extends BaseAdapter {
        @Override public int getCount() { return deleteFacePickerPeople.size(); }
        @Override public Object getItem(int position) { return deleteFacePickerPeople.get(position); }
        @Override public long getItemId(int position) { return deleteFacePickerPeople.get(position).id(); }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            FaceCellHolder holder;
            LinearLayout cell;
            if (recycled instanceof LinearLayout && recycled.getTag() instanceof FaceCellHolder
                    && ((FaceCellHolder) recycled.getTag()).tick != null) {
                cell = (LinearLayout) recycled;
                holder = (FaceCellHolder) recycled.getTag();
            } else {
                cell = new LinearLayout(MainActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                FrameLayout imageFrame = new FrameLayout(MainActivity.this);
                ImageView cover = new ImageView(MainActivity.this);
                cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                cover.setBackground(rounded(Color.rgb(227, 232, 246), FACE_THUMB_CORNER_DP));
                cover.setClipToOutline(true);
                imageFrame.addView(cover, new FrameLayout.LayoutParams(-1, -1));
                TextView tick = text("\u2713", 16);
                tick.setTextColor(Color.WHITE);
                tick.setTypeface(null, Typeface.BOLD);
                tick.setGravity(Gravity.CENTER);
                tick.setBackground(rounded(Color.rgb(190, 40, 40), 13));
                FrameLayout.LayoutParams tickParams = new FrameLayout.LayoutParams(dp(26), dp(26),
                        Gravity.END | Gravity.TOP);
                tickParams.setMargins(0, dp(4), dp(4), 0);
                imageFrame.addView(tick, tickParams);
                cell.addView(imageFrame,
                        new LinearLayout.LayoutParams(dp(faceThumbDp), dp(faceThumbDp)));
                TextView label = text("", 14);
                label.setGravity(Gravity.CENTER);
                label.setMaxLines(1);
                label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                label.setPadding(dp(2), dp(5), dp(2), dp(5));
                cell.addView(label, new LinearLayout.LayoutParams(-1, -2));
                holder = new FaceCellHolder(cover, label, tick);
                cell.setTag(holder);
            }
            Store.Person person = deleteFacePickerPeople.get(position);
            boolean selected = deleteFaceSelection.contains(person.id());
            holder.label.setText(person.title());
            holder.tick.setVisibility(selected ? View.VISIBLE : View.GONE);
            holder.cover.setContentDescription(person.title() + (selected ? ", selected for deletion"
                    : ", not selected; hold to review photos"));
            bindFaceThumb(holder.cover, person.id());
            return cell;
        }
    }

    private static final class AlbumHolder {
        final FrameLayout coverFrame;
        final ImageView[] covers;
        final LinearLayout addPrompt;
        final TextView label;
        final TextView count;
        AlbumHolder(FrameLayout coverFrame, ImageView[] covers, LinearLayout addPrompt,
                    TextView label, TextView count) {
            this.coverFrame = coverFrame;
            this.covers = covers;
            this.addPrompt = addPrompt;
            this.label = label;
            this.count = count;
        }
    }

    private static final class FaceCellHolder {
        final ImageView cover;
        final TextView label;
        final TextView tick;
        FaceCellHolder(ImageView cover, TextView label, TextView tick) {
            this.cover = cover;
            this.label = label;
            this.tick = tick;
        }
    }

    private final class PeopleAdapter extends BaseAdapter {
        @Override public int getCount() { return displayedPeople.size(); }
        @Override public Object getItem(int position) { return displayedPeople.get(position); }
        @Override public long getItemId(int position) { return displayedPeople.get(position).id(); }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            PersonHolder holder;
            LinearLayout cell;
            if (recycled instanceof LinearLayout && recycled.getTag() instanceof PersonHolder) {
                cell = (LinearLayout) recycled;
                holder = (PersonHolder) recycled.getTag();
            } else {
                cell = new LinearLayout(MainActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                cell.setPadding(0, dp(2), 0, dp(4));
                ImageView cover = new ImageView(MainActivity.this);
                cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                cover.setBackground(rounded(Color.rgb(227, 232, 246), FACE_THUMB_CORNER_DP));
                cover.setClipToOutline(true);
                cell.addView(cover, new LinearLayout.LayoutParams(dp(faceThumbDp), dp(faceThumbDp)));
                TextView label = text("", 14);
                label.setMaxLines(1);
                label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                label.setGravity(Gravity.CENTER);
                label.setPadding(0, dp(5), 0, 0);
                cell.addView(label, new LinearLayout.LayoutParams(-1, -2));
                TextView count = text("", 12);
                count.setTextColor(Color.GRAY);
                count.setGravity(Gravity.CENTER);
                cell.addView(count, new LinearLayout.LayoutParams(-1, -2));
                holder = new PersonHolder(cover, label, count);
                cell.setTag(holder);
            }

            Store.Person person = displayedPeople.get(position);
            boolean synthetic = person.id() == SINGLE_GROUP_ID;
            long thumbId = synthetic ? singleCoverPersonId : person.id();
            holder.boundId = thumbId;
            holder.cover.setContentDescription(person.title());
            holder.label.setText(person.title());
            holder.count.setText(synthetic ? person.count() + " faces" : person.count() + " photos");
            holder.cover.setImageDrawable(null);
            Bitmap cached = faceCache.get(thumbId);
            if (cached != null) holder.cover.setImageBitmap(cached);
            else if (thumbId >= 0) loadFaceThumb(holder, thumbId);
            return cell;
        }
    }

    private void loadFaceThumb(PersonHolder holder, long id) {
        images.execute(() -> {
            byte[] bytes = store.thumb(id);
            if (bytes == null || holder.boundId != id) return;
            Bitmap bitmap = decodeFaceThumbnail(bytes);
            if (bitmap == null) return;
            faceCache.put(id, bitmap);
            runOnUiThread(() -> {
                if (!isDestroyed() && holder.boundId == id) holder.cover.setImageBitmap(bitmap);
            });
        });
    }

    private void bindFaceThumb(ImageView view, long id) {
        view.setTag(id);
        view.setImageDrawable(null);
        Bitmap cached = faceCache.get(id);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        images.execute(() -> {
            byte[] bytes = store.thumb(id);
            if (bytes == null || !Long.valueOf(id).equals(view.getTag())) return;
            Bitmap bitmap = decodeFaceThumbnail(bytes);
            if (bitmap == null) return;
            faceCache.put(id, bitmap);
            runOnUiThread(() -> {
                if (!isDestroyed() && Long.valueOf(id).equals(view.getTag())) {
                    view.setImageBitmap(bitmap);
                }
            });
        });
    }

    private Bitmap decodeFaceThumbnail(byte[] bytes) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    private static final class PersonHolder {
        final ImageView cover;
        final TextView label;
        final TextView count;
        volatile long boundId;
        PersonHolder(ImageView cover, TextView label, TextView count) {
            this.cover = cover;
            this.label = label;
            this.count = count;
        }
    }

    private final class PhotosAdapter extends BaseAdapter {
        @Override public int getCount() { return photoList.size(); }
        @Override public Object getItem(int position) { return photoList.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            ImageView view = recycled instanceof ImageView ? (ImageView) recycled
                    : new ImageView(MainActivity.this);
            int width = photoThumbPx > 0 ? photoThumbPx
                    : (getResources().getDisplayMetrics().widthPixels - dp(30)) / 3;
            view.setLayoutParams(new AbsListView.LayoutParams(width, width));
            view.setScaleType(ImageView.ScaleType.CENTER_CROP);
            view.setBackgroundColor(Color.LTGRAY);
            String uri = photoList.get(position);
            view.setTag(uri);
            view.setContentDescription("Photo " + (position + 1));
            view.setImageDrawable(null);
            Bitmap cached = photoCache.get(uri);
            if (cached != null) view.setImageBitmap(cached);
            else images.execute(() -> {
                if (!uri.equals(view.getTag())) return;
                try {
                    Bitmap bitmap = Engine.thumbnail(getApplicationContext(), uri,
                            Math.max(256, width));
                    photoCache.put(uri, bitmap);
                    runOnUiThread(() -> {
                        if (!isDestroyed() && uri.equals(view.getTag())) view.setImageBitmap(bitmap);
                    });
                } catch (Exception error) {
                    runOnUiThread(() -> {
                        if (!isDestroyed() && uri.equals(view.getTag()))
                            view.setImageResource(android.R.drawable.ic_menu_report_image);
                    });
                }
            });
            return view;
        }
    }

    private abstract class PhotoIcon extends View {
        final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);

        PhotoIcon(Context context) {
            super(context);
            line.setColor(Color.WHITE);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(dp(2));
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setShadowLayer(dp(2), 0, dp(1), Color.BLACK);
            setLayerType(View.LAYER_TYPE_SOFTWARE, line);
            setClickable(true);
            setFocusable(true);
            setBackgroundColor(Color.TRANSPARENT);
        }

        void setLineColor(int color) {
            line.clearShadowLayer();
            line.setColor(color);
            invalidate();
        }
    }

    private final class VectorIcon extends PhotoIcon {
        private final android.graphics.drawable.Drawable icon;
        VectorIcon(Context context, int resource) {
            super(context);
            icon = context.getDrawable(resource).mutate();
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int size = dp(24), left = (getWidth() - size) / 2, top = (getHeight() - size) / 2;
            icon.setBounds(left, top, left + size, top + size);
            if (line.getColor() == Color.WHITE) {
                // ponytail: offset dark copy stands in for the blurred shadow white icons get over photos
                icon.setTint(0x99000000);
                canvas.save();
                canvas.translate(0, dp(1));
                icon.draw(canvas);
                canvas.restore();
            }
            icon.setTint(line.getColor());
            icon.draw(canvas);
        }
    }

    private final class MoreIcon extends PhotoIcon {
        MoreIcon(Context context) { super(context); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            line.setStyle(Paint.Style.FILL);
            float x=getWidth()/2f, y=getHeight()/2f;
            canvas.drawCircle(x,y-dp(7),dp(2),line);
            canvas.drawCircle(x,y,dp(2),line);
            canvas.drawCircle(x,y+dp(7),dp(2),line);
            line.setStyle(Paint.Style.STROKE);
        }
    }

    private final class EyeIcon extends PhotoIcon {
        private final Path iconPath = new Path();
        EyeIcon(Context context) { super(context); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float x=getWidth()/2f, y=getHeight()/2f;
            iconPath.reset();
            iconPath.moveTo(x-dp(11),y);
            iconPath.quadTo(x,y-dp(9),x+dp(11),y);
            iconPath.quadTo(x,y+dp(9),x-dp(11),y);
            canvas.drawPath(iconPath,line);
            line.setStyle(Paint.Style.FILL);
            canvas.drawCircle(x,y,dp(2.5f),line);
            line.setStyle(Paint.Style.STROKE);
        }
    }

    private final class PhotoPager extends ViewGroup {
        private final int gap = dp(12);
        private final int touchSlop;
        private final DecelerateInterpolator settleInterpolator = new DecelerateInterpolator();
        private ZoomImage previousPage;
        private ZoomImage currentPage;
        private ZoomImage nextPage;
        private String currentUri;
        private String fullQualityUri;
        private float downX;
        private float downY;
        private float dragOffset;
        private long gestureDownTime;
        private boolean dragging;
        private boolean swipingUp;
        private boolean settling;
        private VelocityTracker velocityTracker;

        PhotoPager(Context context) {
            super(context);
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            setClipChildren(true);
            setClipToPadding(true);
        }

        void rebuildForUri(String uri) {
            cancelPageAnimations();
            removeAllViews();
            currentUri = uri;
            fullQualityUri = null;
            int index = photoList.indexOf(uri);
            previousPage = index > 0 ? createPage(photoList.get(index - 1)) : null;
            currentPage = createPage(uri);
            nextPage = index >= 0 && index + 1 < photoList.size()
                    ? createPage(photoList.get(index + 1)) : null;
            if (previousPage != null) addView(previousPage, fullPageParams());
            addView(currentPage, fullPageParams());
            if (nextPage != null) addView(nextPage, fullPageParams());
            dragOffset = 0f;
            dragging = false;
            settling = false;
            requestLayout();
        }

        private LayoutParams fullPageParams() {
            return new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        }

        private ZoomImage createPage(String uri) {
            ZoomImage page = new ZoomImage(MainActivity.this);
            page.setTag(uri);
            page.setBackgroundColor(Color.BLACK);
            page.setContentDescription("Photo");
            page.setOnSingleTap(MainActivity.this::togglePhotoControls);
            Bitmap preview = pagerPreview(uri);
            if (preview != null) {
                page.setPreviewBitmap(preview);
            }
            return page;
        }

        void onPreviewLoaded(String uri, Bitmap bitmap) {
            applyPreview(previousPage, uri, bitmap);
            applyPreview(currentPage, uri, bitmap);
            applyPreview(nextPage, uri, bitmap);
        }

        private void applyPreview(ZoomImage page, String uri, Bitmap bitmap) {
            if (page == null || !uri.equals(page.getTag())) return;
            page.setPreviewBitmap(bitmap);
        }

        boolean setCurrentFullBitmap(String uri, Bitmap bitmap) {
            if (currentPage == null || !uri.equals(currentUri) || !uri.equals(currentPage.getTag()))
                return false;
            fullQualityUri = uri;
            currentPage.setFullResolutionBitmap(bitmap);
            return true;
        }

        private void completePageChange(int direction) {
            int oldIndex = photoList.indexOf(currentUri);
            int newIndex = oldIndex + direction;
            if (oldIndex < 0 || newIndex < 0 || newIndex >= photoList.size()) {
                rebuildForUri(currentUri);
                return;
            }

            if (direction > 0) {
                if (previousPage != null) removeView(previousPage);
                previousPage = currentPage;
                currentPage = nextPage;
                previousPage.releaseFullResolutionAndRestorePreview();
                currentUri = photoList.get(newIndex);
                nextPage = newIndex + 1 < photoList.size()
                        ? createPage(photoList.get(newIndex + 1)) : null;
                if (nextPage != null) addView(nextPage, fullPageParams());
            } else {
                if (nextPage != null) removeView(nextPage);
                nextPage = currentPage;
                currentPage = previousPage;
                nextPage.releaseFullResolutionAndRestorePreview();
                currentUri = photoList.get(newIndex);
                previousPage = newIndex > 0
                        ? createPage(photoList.get(newIndex - 1)) : null;
                if (previousPage != null) addView(previousPage, 0, fullPageParams());
            }

            fullQualityUri = null;
            dragging = false;
            settling = false;
            applyPageOffset(0f);
            requestLayout();
            onPagerPhotoSelected(currentUri, this);
        }

        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = MeasureSpec.getSize(heightMeasureSpec);
            setMeasuredDimension(width, height);
            int childWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
            int childHeight = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
            for (int index = 0; index < getChildCount(); index++)
                getChildAt(index).measure(childWidth, childHeight);
        }

        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int width = right - left;
            int height = bottom - top;
            for (int index = 0; index < getChildCount(); index++)
                getChildAt(index).layout(0, 0, width, height);
            if (!dragging && !settling) applyPageOffset(0f);
        }

        private int pageDistance() { return getWidth() + gap; }

        private void applyPageOffset(float offset) {
            dragOffset = offset;
            int distance = pageDistance();
            if (previousPage != null) previousPage.setTranslationX(-distance + offset);
            if (currentPage != null) currentPage.setTranslationX(offset);
            if (nextPage != null) nextPage.setTranslationX(distance + offset);
        }

        private boolean hasPage(int direction) {
            return direction > 0 ? nextPage != null : previousPage != null;
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            if (settling) return true;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                downX = event.getX();
                downY = event.getY();
                gestureDownTime = event.getEventTime();
                dragging = false;
                swipingUp = false;
                recycleVelocityTracker();
                return false;
            }
            if (action == MotionEvent.ACTION_MOVE && event.getPointerCount() == 1
                    && currentPage != null && currentPage.allowsPaging()) {
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.1f) {
                    dragging = true;
                    velocityTracker = VelocityTracker.obtain();
                    MotionEvent down = MotionEvent.obtain(gestureDownTime, gestureDownTime,
                            MotionEvent.ACTION_DOWN, downX, downY, 0);
                    velocityTracker.addMovement(down);
                    down.recycle();
                    velocityTracker.addMovement(event);
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                if (-dy > touchSlop && -dy > Math.abs(dx) * 1.1f) {
                    swipingUp = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
            }
            return false;
        }

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (settling) return true;
            if (velocityTracker != null) velocityTracker.addMovement(event);
            int action = event.getActionMasked();
            if (swipingUp) {
                float lift = Math.max(0f, downY - event.getY());
                if (action == MotionEvent.ACTION_MOVE) currentPage.setTranslationY(-lift * .35f);
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    swipingUp = false;
                    currentPage.animate().translationY(0f).setDuration(180L).start();
                    if (action == MotionEvent.ACTION_UP && lift >= dp(80)) sharePhoto();
                }
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE && dragging && event.getPointerCount() == 1) {
                float offset = event.getX() - downX;
                int direction = offset < 0 ? 1 : -1;
                if (!hasPage(direction)) offset *= .28f;
                applyPageOffset(offset);
                return true;
            }
            if (action == MotionEvent.ACTION_UP && dragging) {
                float velocityX = 0f;
                if (velocityTracker != null) {
                    velocityTracker.computeCurrentVelocity(1000);
                    velocityX = velocityTracker.getXVelocity();
                }
                finishDrag(velocityX);
                recycleVelocityTracker();
                return true;
            }
            if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN) {
                if (dragging) settleBack();
                recycleVelocityTracker();
                return true;
            }
            return true;
        }

        private void finishDrag(float velocityX) {
            float minimumFling = dp(650);
            boolean fast = Math.abs(velocityX) >= minimumFling;
            int direction = fast ? (velocityX < 0 ? 1 : -1) : (dragOffset < 0 ? 1 : -1);
            boolean farEnough = Math.abs(dragOffset) >= getWidth() * .22f;
            if (hasPage(direction) && (fast || farEnough)) {
                float target = direction > 0 ? -pageDistance() : pageDistance();
                float remaining = Math.abs(target - dragOffset);
                long duration = fast ? (long) (1000f * remaining / Math.max(1f, Math.abs(velocityX))) : 220L;
                duration = Math.max(120L, Math.min(280L, duration));
                settleTo(target, duration, () -> {
                    completePageChange(direction);
                });
            } else settleBack();
        }

        private void settleBack() {
            settleTo(0f, 180L, () -> applyPageOffset(0f));
        }

        private void settleTo(float target, long duration, Runnable completion) {
            dragging = false;
            settling = true;
            int distance = pageDistance();
            if (previousPage != null) previousPage.animate().translationX(-distance + target)
                    .setDuration(duration).setInterpolator(settleInterpolator).start();
            if (nextPage != null) nextPage.animate().translationX(distance + target)
                    .setDuration(duration).setInterpolator(settleInterpolator).start();
            currentPage.animate().translationX(target).setDuration(duration)
                    .setInterpolator(settleInterpolator).withEndAction(() -> {
                        settling = false;
                        dragOffset = target;
                        completion.run();
                    }).start();
        }

        private void cancelPageAnimations() {
            if (previousPage != null) previousPage.animate().cancel();
            if (currentPage != null) currentPage.animate().cancel();
            if (nextPage != null) nextPage.animate().cancel();
            recycleVelocityTracker();
        }

        private void recycleVelocityTracker() {
            if (velocityTracker != null) velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    private final class ZoomImage extends ImageView {
        private final Matrix transform = new Matrix();
        private final ScaleGestureDetector scaleDetector;
        private final GestureDetector tapDetector;
        private final int minimumPanFlingVelocity;
        private final int maximumPanFlingVelocity;
        private final float[] matrixValues = new float[9];
        private Bitmap pagePreview;
        private Bitmap fullResolution;
        private boolean displayingFullResolution;
        private ValueAnimator panFlingAnimator;
        private VelocityTracker panVelocityTracker;
        private boolean panHadMultiplePointers;
        private float lastX, lastY, scale = 1f, widthFit = 1f, heightFit = 1f;
        private int zoomStage;
        private Runnable singleTap;

        ZoomImage(Context context) {
            super(context);
            setScaleType(ScaleType.MATRIX);
            ViewConfiguration configuration = ViewConfiguration.get(context);
            minimumPanFlingVelocity = configuration.getScaledMinimumFlingVelocity();
            maximumPanFlingVelocity = configuration.getScaledMaximumFlingVelocity();
            scaleDetector = new ScaleGestureDetector(context,
                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override public boolean onScale(ScaleGestureDetector gesture) {
                            ensureFullResolution();
                            float maximum = Math.max(Math.max(1f, widthFit * 12f), heightFit * 8f);
                            float next = Math.max(baseFit(),
                                    Math.min(maximum, scale * gesture.getScaleFactor()));
                            applyScale(next, gesture.getFocusX(), gesture.getFocusY());
                            zoomStage = 2;
                            return true;
                        }
                    });
            tapDetector = new GestureDetector(context,
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override public boolean onDown(MotionEvent event) { return true; }
                        @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                            performClick();
                            if(singleTap!=null)singleTap.run();
                            return true;
                        }
                        @Override public boolean onDoubleTap(MotionEvent event) {
                            cycleZoom(event.getX(),event.getY());
                            return true;
                        }
                    });
        }

        void setOnSingleTap(Runnable action) { singleTap=action; }
        boolean allowsPaging() { return zoomStage == 0 && !scaleDetector.isInProgress(); }

        void setPreviewBitmap(Bitmap bitmap) {
            pagePreview = bitmap;
            if (displayingFullResolution) return;
            setImageBitmap(bitmap);
            fitInside();
        }

        void setFullResolutionBitmap(Bitmap bitmap) {
            fullResolution = bitmap;
        }

        void releaseFullResolutionAndRestorePreview() {
            fullResolution = null;
            if (!displayingFullResolution) return;
            displayingFullResolution = false;
            if (pagePreview != null && !pagePreview.isRecycled()) {
                setImageBitmap(pagePreview);
                fitInside();
            }
        }

        private void ensureFullResolution() {
            if (displayingFullResolution || zoomStage != 0 || fullResolution == null
                    || fullResolution.isRecycled() || getWidth() == 0 || getHeight() == 0) return;
            displayingFullResolution = true;
            setImageBitmap(fullResolution);
            float imageWidth = getDrawable().getIntrinsicWidth();
            float imageHeight = getDrawable().getIntrinsicHeight();
            widthFit = getWidth() / imageWidth;
            heightFit = getHeight() / imageHeight;
            resetScale(baseFit());
        }

        private float baseFit() {
            return Math.min(widthFit, heightFit);
        }

        void fitInside() {
            post(() -> {
                if (getDrawable() == null || getWidth() == 0 || getHeight() == 0) return;
                float imageWidth = getDrawable().getIntrinsicWidth();
                float imageHeight = getDrawable().getIntrinsicHeight();
                widthFit = getWidth() / imageWidth;
                heightFit = getHeight() / imageHeight;
                zoomStage = 0;
                resetScale(baseFit());
            });
        }

        private void cycleZoom(float focusX,float focusY) {
            if(getDrawable()==null)return;
            ensureFullResolution();
            if(zoomStage==0){
                float fillHeight=Math.max(widthFit,heightFit);
                applyScale(fillHeight,focusX,focusY);
                zoomStage=1;
            }else if(zoomStage==1){
                float nativeSize=Math.max(Math.max(widthFit,heightFit),1f);
                if(Math.abs(nativeSize-scale)<.01f)nativeSize=scale*2f;
                applyScale(nativeSize,focusX,focusY);
                zoomStage=2;
            }else{
                zoomStage=0;
                resetScale(baseFit());
            }
        }

        private void resetScale(float target) {
            stopPanFling();
            float imageWidth=getDrawable().getIntrinsicWidth();
            float imageHeight=getDrawable().getIntrinsicHeight();
            scale=target;
            transform.setScale(target,target);
            transform.postTranslate((getWidth()-imageWidth*target)/2f,
                    (getHeight()-imageHeight*target)/2f);
            setImageMatrix(transform);
        }

        private void applyScale(float target,float focusX,float focusY) {
            if(scale<=0)return;
            stopPanFling();
            float factor=target/scale;
            scale=target;
            transform.postScale(factor,factor,focusX,focusY);
            constrain();
        }

        private void constrain() {
            if(getDrawable()==null)return;
            RectF bounds=new RectF(0,0,getDrawable().getIntrinsicWidth(),
                    getDrawable().getIntrinsicHeight());
            transform.mapRect(bounds);
            float dx=0,dy=0;
            if(bounds.width()<=getWidth())dx=(getWidth()-bounds.width())/2f-bounds.left;
            else if(bounds.left>0)dx=-bounds.left;
            else if(bounds.right<getWidth())dx=getWidth()-bounds.right;
            if(bounds.height()<=getHeight())dy=(getHeight()-bounds.height())/2f-bounds.top;
            else if(bounds.top>0)dy=-bounds.top;
            else if(bounds.bottom<getHeight())dy=getHeight()-bounds.bottom;
            transform.postTranslate(dx,dy);
            setImageMatrix(transform);
        }

        private void startPanFling(float velocityX, float velocityY) {
            if (getDrawable() == null || zoomStage == 0) return;
            float imageWidth = getDrawable().getIntrinsicWidth() * scale;
            float imageHeight = getDrawable().getIntrinsicHeight() * scale;
            int minimumX;
            int maximumX;
            int minimumY;
            int maximumY;
            if (imageWidth <= getWidth()) {
                minimumX = maximumX = Math.round((getWidth() - imageWidth) / 2f);
                velocityX = 0f;
            } else {
                minimumX = Math.round(getWidth() - imageWidth);
                maximumX = 0;
            }
            if (imageHeight <= getHeight()) {
                minimumY = maximumY = Math.round((getHeight() - imageHeight) / 2f);
                velocityY = 0f;
            } else {
                minimumY = Math.round(getHeight() - imageHeight);
                maximumY = 0;
            }

            if (Math.abs(velocityX) < minimumPanFlingVelocity) velocityX = 0f;
            if (Math.abs(velocityY) < minimumPanFlingVelocity) velocityY = 0f;
            velocityX = Math.max(-maximumPanFlingVelocity,
                    Math.min(maximumPanFlingVelocity, velocityX));
            velocityY = Math.max(-maximumPanFlingVelocity,
                    Math.min(maximumPanFlingVelocity, velocityY));
            transform.getValues(matrixValues);
            float startX = matrixValues[Matrix.MTRANS_X];
            float startY = matrixValues[Matrix.MTRANS_Y];
            if ((startX >= maximumX - 1f && velocityX > 0f)
                    || (startX <= minimumX + 1f && velocityX < 0f)) velocityX = 0f;
            if ((startY >= maximumY - 1f && velocityY > 0f)
                    || (startY <= minimumY + 1f && velocityY < 0f)) velocityY = 0f;

            float speed = (float) Math.hypot(velocityX, velocityY);
            if (speed < minimumPanFlingVelocity) return;
            float directionX = velocityX / speed;
            float directionY = velocityY / speed;
            float maximumTravel = Float.POSITIVE_INFINITY;
            if (directionX > .0001f)
                maximumTravel = Math.min(maximumTravel, (maximumX - startX) / directionX);
            else if (directionX < -.0001f)
                maximumTravel = Math.min(maximumTravel, (minimumX - startX) / directionX);
            if (directionY > .0001f)
                maximumTravel = Math.min(maximumTravel, (maximumY - startY) / directionY);
            else if (directionY < -.0001f)
                maximumTravel = Math.min(maximumTravel, (minimumY - startY) / directionY);
            if (!Float.isFinite(maximumTravel) || maximumTravel <= .5f) return;

            float deceleration = dp(2200);
            float naturalTravel = speed * speed / (2f * deceleration);
            float travel = Math.min(naturalTravel, maximumTravel);
            long duration = naturalTravel <= maximumTravel
                    ? Math.round(1000f * speed / deceleration)
                    : Math.round(2000f * travel / speed);
            duration = Math.max(120L, Math.min(850L, duration));
            float targetX = startX + directionX * travel;
            float targetY = startY + directionY * travel;

            stopPanFling();
            panFlingAnimator = ValueAnimator.ofFloat(0f, 1f);
            panFlingAnimator.setDuration(duration);
            panFlingAnimator.setInterpolator(new DecelerateInterpolator());
            panFlingAnimator.addUpdateListener(animation -> {
                float progress = (float) animation.getAnimatedValue();
                transform.getValues(matrixValues);
                matrixValues[Matrix.MTRANS_X] = startX + (targetX - startX) * progress;
                matrixValues[Matrix.MTRANS_Y] = startY + (targetY - startY) * progress;
                transform.setValues(matrixValues);
                setImageMatrix(transform);
            });
            panFlingAnimator.start();
        }

        private void stopPanFling() {
            if (panFlingAnimator != null) {
                panFlingAnimator.cancel();
                panFlingAnimator = null;
            }
        }

        private void recyclePanVelocityTracker() {
            if (panVelocityTracker != null) panVelocityTracker.recycle();
            panVelocityTracker = null;
        }

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        @Override public boolean onTouchEvent(MotionEvent event) {
            tapDetector.onTouchEvent(event);
            scaleDetector.onTouchEvent(event);
            int action=event.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN){
                stopPanFling();
                recyclePanVelocityTracker();
                panVelocityTracker = VelocityTracker.obtain();
                panVelocityTracker.addMovement(event);
                panHadMultiplePointers = false;
                lastX=event.getX();lastY=event.getY();
            }
            else if(action==MotionEvent.ACTION_MOVE&&!scaleDetector.isInProgress()
                    &&event.getPointerCount()==1){
                if (panVelocityTracker != null) panVelocityTracker.addMovement(event);
                if (zoomStage != 0) {
                    transform.postTranslate(event.getX()-lastX,event.getY()-lastY);
                    constrain();
                }
                lastX=event.getX();lastY=event.getY();
            }else if(action==MotionEvent.ACTION_POINTER_UP||action==MotionEvent.ACTION_POINTER_DOWN){
                panHadMultiplePointers = true;
                if (panVelocityTracker != null) panVelocityTracker.addMovement(event);
                int pointer = action == MotionEvent.ACTION_POINTER_UP
                        && event.getActionIndex() == 0 && event.getPointerCount() > 1 ? 1 : 0;
                lastX=event.getX(pointer);lastY=event.getY(pointer);
            }else if(action==MotionEvent.ACTION_UP){
                if (panVelocityTracker != null) {
                    panVelocityTracker.addMovement(event);
                    panVelocityTracker.computeCurrentVelocity(1000, maximumPanFlingVelocity);
                    if (!panHadMultiplePointers && zoomStage != 0) {
                        startPanFling(panVelocityTracker.getXVelocity(),
                                panVelocityTracker.getYVelocity());
                    }
                }
                recyclePanVelocityTracker();
            }else if(action==MotionEvent.ACTION_CANCEL){
                recyclePanVelocityTracker();
            }
            return true;
        }

        @Override protected void onDetachedFromWindow() {
            stopPanFling();
            recyclePanVelocityTracker();
            super.onDetachedFromWindow();
        }

        @Override public boolean performClick() {
            super.performClick();
            return true;
        }
    }
}
