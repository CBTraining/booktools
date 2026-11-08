package com.example.topbardrop;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages BookTools FancyZones ("Window Snap Zones") layout configuration and zone calculation.
 * Supports 3 user-selectable segment modes:
 *  - LAYOUT_QUARTERS (0): 4 Quarters (2x2 grid: Top-Left, Top-Right, Bottom-Left, Bottom-Right)
 *  - LAYOUT_HALF_HALF (1): Half & Half (2 side-by-side columns: Left 50%, Right 50%)
 *  - LAYOUT_TRIPLE_COLUMN (2): Triple Column (3 columns: Left 33%, Center 34%, Right 33%)
 */
public class SnapZonesManager {

    public static final int LAYOUT_QUARTERS = 0;
    public static final int LAYOUT_HALF_HALF = 1;
    public static final int LAYOUT_TRIPLE_COLUMN = 2;

    private static final String PREFS_NAME = "booktools_snap_zones_prefs";
    private static final String KEY_LAYOUT_MODE = "snap_zone_layout_mode";

    public static class SnapZone {
        public final int index;
        public final String title;
        public final String subtitle;
        public final Rect bounds;

        public SnapZone(int index, String title, String subtitle, Rect bounds) {
            this.index = index;
            this.title = title;
            this.subtitle = subtitle;
            this.bounds = bounds;
        }
    }

    public static int getLayoutMode(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int mode = prefs.getInt(KEY_LAYOUT_MODE, LAYOUT_QUARTERS);
        if (mode < LAYOUT_QUARTERS || mode > LAYOUT_TRIPLE_COLUMN) {
            return LAYOUT_QUARTERS;
        }
        return mode;
    }

    public static void setLayoutMode(Context context, int mode) {
        if (mode < LAYOUT_QUARTERS || mode > LAYOUT_TRIPLE_COLUMN) {
            mode = LAYOUT_QUARTERS;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putInt(KEY_LAYOUT_MODE, mode).apply();
    }

    public static String getLayoutDisplayName(int mode) {
        switch (mode) {
            case LAYOUT_HALF_HALF:
                return "Half & Half (2 Zones)";
            case LAYOUT_TRIPLE_COLUMN:
                return "Triple Column (3 Zones)";
            case LAYOUT_QUARTERS:
            default:
                return "4 Quarters (2×2 Grid)";
        }
    }

    /**
     * Computes the list of SnapZones for the given usable desktop work area.
     */
    public static List<SnapZone> buildZones(int mode, Rect workArea, int gapPx) {
        List<SnapZone> zones = new ArrayList<>();
        int left = workArea.left + gapPx;
        int top = workArea.top + gapPx;
        int right = workArea.right - gapPx;
        int bottom = workArea.bottom - gapPx;
        int totalW = Math.max(200, right - left);
        int totalH = Math.max(200, bottom - top);
        int halfGap = Math.max(2, gapPx / 2);

        if (mode == LAYOUT_HALF_HALF) {
            int midX = left + totalW / 2;
            zones.add(new SnapZone(
                    0,
                    "Left Half",
                    "50% Width · Full Height",
                    new Rect(left, top, midX - halfGap, bottom)
            ));
            zones.add(new SnapZone(
                    1,
                    "Right Half",
                    "50% Width · Full Height",
                    new Rect(midX + halfGap, top, right, bottom)
            ));
        } else if (mode == LAYOUT_TRIPLE_COLUMN) {
            int colW = (totalW - gapPx * 2) / 3;
            int x1 = left + colW;
            int x2 = x1 + gapPx;
            int x3 = x2 + colW;
            int x4 = x3 + gapPx;
            zones.add(new SnapZone(
                    0,
                    "Left Column",
                    "33% Width · Full Height",
                    new Rect(left, top, x1, bottom)
            ));
            zones.add(new SnapZone(
                    1,
                    "Center Column",
                    "34% Width · Full Height",
                    new Rect(x2, top, x3, bottom)
            ));
            zones.add(new SnapZone(
                    2,
                    "Right Column",
                    "33% Width · Full Height",
                    new Rect(x4, top, right, bottom)
            ));
        } else {
            // Default: LAYOUT_QUARTERS (4 Quarters)
            int midX = left + totalW / 2;
            int midY = top + totalH / 2;
            zones.add(new SnapZone(
                    0,
                    "Top-Left Quarter",
                    "25% Screen · Top Left",
                    new Rect(left, top, midX - halfGap, midY - halfGap)
            ));
            zones.add(new SnapZone(
                    1,
                    "Top-Right Quarter",
                    "25% Screen · Top Right",
                    new Rect(midX + halfGap, top, right, midY - halfGap)
            ));
            zones.add(new SnapZone(
                    2,
                    "Bottom-Left Quarter",
                    "25% Screen · Bottom Left",
                    new Rect(left, midY + halfGap, midX - halfGap, bottom)
            ));
            zones.add(new SnapZone(
                    3,
                    "Bottom-Right Quarter",
                    "25% Screen · Bottom Right",
                    new Rect(midX + halfGap, midY + halfGap, right, bottom)
            ));
        }
        return zones;
    }

    /**
     * Finds the zone containing (x, y), or the nearest zone if slightly on a gap.
     */
    public static int findZoneIndexAt(List<SnapZone> zones, float x, float y) {
        if (zones == null || zones.isEmpty()) return -1;
        int ix = Math.round(x);
        int iy = Math.round(y);
        for (int i = 0; i < zones.size(); i++) {
            if (zones.get(i).bounds.contains(ix, iy)) {
                return i;
            }
        }
        // If inside the small gap between zones, pick the closest zone center
        int bestIdx = -1;
        double bestDistSq = Double.MAX_VALUE;
        for (int i = 0; i < zones.size(); i++) {
            Rect r = zones.get(i).bounds;
            double dx = x - r.exactCenterX();
            double dy = y - r.exactCenterY();
            double d2 = dx * dx + dy * dy;
            if (d2 < bestDistSq) {
                bestDistSq = d2;
                bestIdx = i;
            }
        }
        return bestIdx;
    }
}
