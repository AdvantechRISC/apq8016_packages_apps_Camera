/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.camera.ui;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import com.android.camera.R;

import java.util.ArrayList;
import java.util.List;

public class PieRenderer extends OverlayRenderer {

    private static final String TAG = "CAM Pie";

    private static final long PIE_OPEN_DELAY = 200;

    private static final int MSG_OPEN = 2;
    private static final int MSG_CLOSE = 3;
    private static final int MSG_SUBMENU = 4;
    private static final float PIE_SWEEP = (float)(Math.PI * 2 / 3);
    // geometry
    private Point mCenter;
    private int mRadius;
    private int mRadiusInc;
    private int mSlop;
    // the detection if touch is inside a slice is offset
    // inbounds by this amount to allow the selection to show before the
    // finger covers it
    private int mTouchOffset;

    private boolean mOpen;

    private List<PieItem> mItems;

    private PieItem mOpenItem;

    private Paint mNormalPaint;
    private Paint mSelectedPaint;
    private Paint mSubPaint;

    // touch handling
    private PieItem mCurrentItem;

    private boolean mAnimating;

    // TODO: use mCenter
    private int mDownX;
    private int mDownY;

    private Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            switch(msg.what) {
            case MSG_OPEN:
                if (mListener != null) {
                    mListener.onPieOpened(mCenter.x, mCenter.y);
                }
                break;
            case MSG_CLOSE:
                if (mListener != null) {
                    mListener.onPieClosed();
                }
                break;
            case MSG_SUBMENU:
                openCurrentItem();
                break;
            }
        }
    };

    private PieListener mListener;

    static public interface PieListener {
        public void onPieOpened(int centerX, int centerY);
        public void onPieClosed();
    }

    public void setPieListener(PieListener pl) {
        mListener = pl;
    }

    public PieRenderer(Context context) {
        init(context);
    }
    private void init(Context ctx) {
        mItems = new ArrayList<PieItem>();
        Resources res = ctx.getResources();
        mRadius = (int) res.getDimensionPixelSize(R.dimen.pie_radius_start);
        mRadiusInc =  (int) res.getDimensionPixelSize(R.dimen.pie_radius_increment);
        mSlop = (int) res.getDimensionPixelSize(R.dimen.pie_touch_slop);
        mTouchOffset = (int) res.getDimensionPixelSize(R.dimen.pie_touch_offset);
        mOpen = false;
        mCenter = new Point(0,0);
        mNormalPaint = new Paint();
        mNormalPaint.setColor(Color.argb(0, 0, 0, 0));
        mNormalPaint.setAntiAlias(true);
        mSelectedPaint = new Paint();
        mSelectedPaint.setColor(Color.argb(128, 0, 0, 0)); //res.getColor(R.color.qc_selected));
        mSelectedPaint.setAntiAlias(true);
        mSubPaint = new Paint();
        mSubPaint.setAntiAlias(true);
        mSubPaint.setColor(Color.argb(200, 250, 230, 128)); //res.getColor(R.color.qc_sub));
    }

    public void addItem(PieItem item) {
        // add the item to the pie itself
        mItems.add(item);
    }

    public void removeItem(PieItem item) {
        mItems.remove(item);
    }

    public void clearItems() {
        mItems.clear();
    }

    /**
     * guaranteed has center set
     * @param show
     */
    private void show(boolean show) {
        if (show) {
            // ensure clean state
            mAnimating = false;
            mCurrentItem = null;
            mOpenItem = null;
            for (PieItem item : mItems) {
                item.setSelected(false);
            }
            layoutPie();
        }
        mOpen = show;
        mHandler.sendEmptyMessage(mOpen ? MSG_OPEN : MSG_CLOSE);
    }

    public boolean isOpen() {
        return mOpen;
    }

    private void setCenter(int x, int y) {
        mCenter.x = x;
        mCenter.y = y;
    }

    private void layoutPie() {
        int rgap = 2;
        int inner = mRadius + rgap;
        int outer = mRadius + mRadiusInc - rgap;
        int gap = 1;
        layoutItems(mItems, (float) (Math.PI / 2), inner, outer, gap);
    }

    private void layoutItems(List<PieItem> items, float centerAngle, int inner,
            int outer, int gap) {
        float emptyangle = PIE_SWEEP / 16;
        float sweep = (float) (PIE_SWEEP - 2 * emptyangle) / items.size();
        float angle = centerAngle - PIE_SWEEP / 2 + emptyangle + sweep / 2;
        // check if we have custom geometry
        // first item we find triggers custom sweep for all
        // this allows us to re-use the path
        for (PieItem item : items) {
            if (item.getCenter() >= 0) {
                sweep = item.getSweep();
                break;
            }
        }
        Path path = makeSlice(getDegrees(0) - gap, getDegrees(sweep) + gap,
                outer, inner, mCenter);
        for (PieItem item : items) {
            // shared between items
            item.setPath(path);
            View view = item.getView();
            if (item.getCenter() >= 0) {
                angle = item.getCenter();
            }
            if (view != null) {
                view.measure(view.getLayoutParams().width,
                        view.getLayoutParams().height);
                int w = view.getMeasuredWidth();
                int h = view.getMeasuredHeight();
                // move views to outer border
                int r = inner + (outer - inner) * 2 / 3;
                int x = (int) (r * Math.cos(angle));
                int y = mCenter.y - (int) (r * Math.sin(angle)) - h / 2;
                x = mCenter.x + x - w / 2;
                view.layout(x, y, x + w, y + h);
            }
            float itemstart = angle - sweep / 2;
            item.setGeometry(itemstart, sweep, inner, outer);
            if (item.hasItems()) {
                layoutItems(item.getItems(), angle, inner,
                        outer + mRadiusInc / 2, gap);
            }
            angle += sweep;
        }
    }

    private Path makeSlice(float start, float end, int outer, int inner, Point center) {
        outer = inner + (outer - inner) * 2 / 3;
        RectF bb =
                new RectF(center.x - outer, center.y - outer, center.x + outer,
                        center.y + outer);
        RectF bbi =
                new RectF(center.x - inner, center.y - inner, center.x + inner,
                        center.y + inner);
        Path path = new Path();
        path.arcTo(bb, start, end - start, true);
        path.arcTo(bbi, end, start - end);
        path.close();
        return path;
    }

    /**
     * converts a
     * @param angle from 0..PI to Android degrees (clockwise starting at 3 o'clock)
     * @return skia angle
     */
    private float getDegrees(double angle) {
        return (float) (360 - 180 * angle / Math.PI);
    }

    @Override
    public void onDraw(Canvas canvas) {
        if (mOpen) {
            if (mOpenItem == null) {
                // draw base menu
                for (PieItem item : mItems) {
                    drawItem(canvas, item);
                }
            } else {
                for (PieItem inner : mOpenItem.getItems()) {
                    drawItem(canvas, inner);
                }
            }
        }
    }

    private void drawItem(Canvas canvas, PieItem item) {
        if (item.getView() != null) {
            Paint p = item.isSelected() ? mSelectedPaint : mNormalPaint;
            int state = canvas.save();
            float r = getDegrees(item.getStartAngle());
            canvas.rotate(r, mCenter.x, mCenter.y);
            canvas.drawPath(item.getPath(), p);
            canvas.restoreToCount(state);
            // draw the item view
            View view = item.getView();
            state = canvas.save();
            canvas.translate(view.getX(), view.getY());
            view.draw(canvas);
            canvas.restoreToCount(state);
        }
    }

    // touch handling for pie

    @Override
    public boolean onTouchEvent(MotionEvent evt) {
        float x = evt.getX();
        float y = evt.getY();
        int action = evt.getActionMasked();
        if (MotionEvent.ACTION_DOWN == action) {
            mDownX = (int) x;
            mDownY = (int) y;
            setCenter((int) x, (int) y);
            show(true);
            return true;
        } else if (MotionEvent.ACTION_UP == action) {
            if (mOpen) {
                PieItem item = mCurrentItem;
                if (!mAnimating) {
                    deselect();
                }
                show(false);
                if ((item != null) && (item.getView() != null)) {
                    if ((item == mOpenItem) || !mAnimating) {
                        item.getView().performClick();
                    }
                }
                return true;
            }
        } else if (MotionEvent.ACTION_CANCEL == action) {
            if (mOpen) {
                show(false);
            }
            if (!mAnimating) {
                deselect();
            }
            return false;
        } else if (MotionEvent.ACTION_MOVE == action) {
            if (mAnimating) return false;
            PointF polar = getPolar(x, y);
            int maxr = mRadius + mRadiusInc + 50;
            if (polar.y < mRadius) {
                if (mOpenItem != null) {
                    mOpenItem = null;
                } else if (!mAnimating) {
                    deselect();
                }
                return false;
            }
            if (polar.y > maxr) {
                deselect();
                show(false);
                evt.setAction(MotionEvent.ACTION_DOWN);
                return false;
            }
            PieItem item = findItem(polar);
            if (item == null) {
            } else if (mCurrentItem != item) {
                onEnter(item);
            }
        }
        return false;
    }

    /**
     * enter a slice for a view
     * updates model only
     * @param item
     */
    private void onEnter(PieItem item) {
        if (mCurrentItem != null) {
            mCurrentItem.setSelected(false);
        }
        if (item != null && item.isEnabled()) {
            item.setSelected(true);
            mCurrentItem = item;
            if ((mCurrentItem != mOpenItem) && mCurrentItem.hasItems()) {
                mHandler.sendEmptyMessageDelayed(MSG_SUBMENU, PIE_OPEN_DELAY);
            }
        } else {
            mCurrentItem = null;
        }
    }

    private void deselect() {
        if (mCurrentItem != null) {
            mCurrentItem.setSelected(false);
            mHandler.removeMessages(MSG_SUBMENU);
        }
        if (mOpenItem != null) {
            mOpenItem = null;
        }
        mCurrentItem = null;
    }

    private void openCurrentItem() {
        if ((mCurrentItem != null) && mCurrentItem.hasItems()) {
            mOpenItem = mCurrentItem;
        }
    }

    private PointF getPolar(float x, float y) {
        PointF res = new PointF();
        // get angle and radius from x/y
        res.x = (float) Math.PI / 2;
        x = x - mCenter.x;
        y = mCenter.y - y;
        res.y = (float) Math.sqrt(x * x + y * y);
        if (x != 0) {
            res.x = (float) Math.atan2(y,  x);
            if (res.x < 0) {
                res.x = (float) (2 * Math.PI + res.x);
            }
        }
        res.y = res.y + mTouchOffset;
        return res;
    }

    /**
     * @param polar x: angle, y: dist
     * @return the item at angle/dist or null
     */
    private PieItem findItem(PointF polar) {
        // find the matching item:
        List<PieItem> items = (mOpenItem != null) ? mOpenItem.getItems() : mItems;
        for (PieItem item : items) {
            if (inside(polar, item)) {
                return item;
            }
        }
        return null;
    }

    private boolean inside(PointF polar, PieItem item) {
        return (item.getInnerRadius() < polar.y)
        && (item.getOuterRadius() > polar.y)
        && (item.getStartAngle() < polar.x)
        && (item.getStartAngle() + item.getSweep() > polar.x);
    }

    @Override
    public boolean handlesTouch() {
        return true;
    }

    @Override
    public void layout(int l, int t, int r, int b) {
        super.layout(l, t, r, b);
    }

}
