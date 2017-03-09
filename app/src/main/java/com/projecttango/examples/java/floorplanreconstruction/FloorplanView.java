/*
 * Copyright 2017 Google Inc. All Rights Reserved.
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

package com.projecttango.examples.java.floorplanreconstruction;

import com.google.atap.tango.reconstruction.TangoPolygon;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Custom view to represent a floorplan.
 *
 * It is implemented as a regular SurfaceView with its own render thread running at a fixed 10Hz
 * rate.
 * The floorplan is drawn using standard canvas draw methods.
 */
public class FloorplanView extends SurfaceView implements SurfaceHolder.Callback {
    private static final String TAG = FloorplanView.class.getSimpleName();

    // Scale between meters and pixels. Hardcoded to a reasonable default.
    private static final float SCALE = 100f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paint2 = new Paint(Paint.ANTI_ALIAS_FLAG);

    private volatile List<TangoPolygon> mPolygons = new ArrayList<>();

    private Paint mWallPaint;
    private Paint mSpacePaint;
    private Paint mFurniturePaint;
    private Paint mUserMarkerPaint;

    private Path mUserMarkerPath;

    private Matrix mCamera;
    private Matrix mCameraInverse;

    private boolean mIsDrawing = false;
    private SurfaceHolder mSurfaceHolder;

    public int width;
    public int height;


    /**
     * Custom render thread, running at a fixed 10Hz rate.
     */
    private class RenderThread extends Thread {
        @Override
        public void run() {
            while (mIsDrawing) {
                Canvas canvas = mSurfaceHolder.lockCanvas();
                if (canvas != null) {
                    doDraw(canvas);
                    mSurfaceHolder.unlockCanvasAndPost(canvas);
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                }
            }
        }
    };
    private RenderThread mDrawThread;



    /**
     * Pre drawing callback.
     */
    public interface DrawingCallback {
        /**
         * Called during onDraw, before any element is drawn to the view canvas.
         */
        void onPreDrawing();
    }

    private DrawingCallback mCallback;

    public FloorplanView(Context context) {
        super(context);
        init();
    }

    public FloorplanView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public FloorplanView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // Pre-create graphics objects.
        mWallPaint = new Paint();
        mWallPaint.setColor(getResources().getColor(android.R.color.black));
        mWallPaint.setStyle(Paint.Style.STROKE);
        mWallPaint.setStrokeWidth(3);
        mSpacePaint = new Paint();
        mSpacePaint.setColor(getResources().getColor(R.color.explored_space));
        mSpacePaint.setStyle(Paint.Style.FILL);
        mFurniturePaint = new Paint();
        mFurniturePaint.setColor(getResources().getColor(R.color.furniture));
        mFurniturePaint.setStyle(Paint.Style.FILL);
        mUserMarkerPaint = new Paint();
        mUserMarkerPaint.setColor(getResources().getColor(R.color.user_marker));
        mUserMarkerPaint.setStyle(Paint.Style.FILL);
        mUserMarkerPath = new Path();
        mUserMarkerPath.lineTo(-0.2f * SCALE, 0);
        mUserMarkerPath.lineTo(-0.2f * SCALE, -0.05f * SCALE);
        mUserMarkerPath.lineTo(0.2f * SCALE, -0.05f * SCALE);
        mUserMarkerPath.lineTo(0.2f * SCALE, 0);
        mUserMarkerPath.lineTo(0, 0);
        mUserMarkerPath.lineTo(0, -0.05f * SCALE);
        mUserMarkerPath.lineTo(-0.4f * SCALE, -0.5f  * SCALE);
        mUserMarkerPath.lineTo(0.4f  * SCALE, -0.5f * SCALE);
        mUserMarkerPath.lineTo(0, 0);
        mCamera = new Matrix();
        mCameraInverse = new Matrix();
        paint.setColor(Color.rgb(0, 0 ,0));
        paint.setStyle(Paint.Style.FILL);
        paint2.setColor(Color.rgb(0, 255 ,0));
        paint2.setStyle(Paint.Style.FILL);

        // Register for surface callback events.
        getHolder().addCallback(this);
    }

    @Override
    public void surfaceCreated(SurfaceHolder surfaceHolder) {
        mSurfaceHolder = surfaceHolder;
        mIsDrawing = true;
        mDrawThread = new RenderThread();
        mDrawThread.start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder surfaceHolder, int i, int i1, int i2) {
        mSurfaceHolder = surfaceHolder;
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
        mIsDrawing = false;
    }

    public void doDraw(Canvas canvas) {
        // Notify the activity so that it can use Tango to query the current device pose.
        if (mCallback != null) {
            mCallback.onPreDrawing();
        }

        // Erase the previous canvas image.
        canvas.drawColor(getResources().getColor(android.R.color.white));

        // Start drawing from the center of the canvas.
        float translationX = canvas.getWidth() / 2f;
        float translationY = canvas.getHeight() / 2f;

        canvas.translate(translationX, translationY);

        // Update position and orientation based on the device position and orientation.
        canvas.concat(mCamera);

        // Draw all the polygons. Make a shallow copy in case mPolygons is reset while rendering.
        List<TangoPolygon> drawPolygons = mPolygons;
        for (TangoPolygon polygon : drawPolygons) {
            if (polygon.vertices2d.size() > 1) {
                Paint paint;
                switch(polygon.layer) {
                    case TangoPolygon.TANGO_3DR_LAYER_FURNITURE:
                        paint = mFurniturePaint;
                        break;
                    case TangoPolygon.TANGO_3DR_LAYER_SPACE:
                        paint = mSpacePaint;
                        break;
                    case TangoPolygon.TANGO_3DR_LAYER_WALLS:
                        paint = mWallPaint;
                        break;
                    default:
                        Log.w(TAG, "Ignoring polygon with unknown layer value: " + polygon.layer);
                        continue;
                }
                Path path = new Path();
                float[] p = polygon.vertices2d.get(0);
                path.moveTo(p[0] * SCALE, p[1] * SCALE);
                for (int i = 1; i < polygon.vertices2d.size(); i++) {
                    float[] point = polygon.vertices2d.get(i);
                    path.lineTo(point[0] * SCALE, point[1] * SCALE);
                }
                if (polygon.isClosed) {
                    path.close();
                }
                canvas.drawPath(path, paint);
            }
        }

        // Draw a user / device marker.
        canvas.concat(mCameraInverse);
        canvas.drawPath(mUserMarkerPath, mUserMarkerPaint);

        //draw the points to the canvas(more than one will stay)
        for(Point point: FloorPlanReconstructionActivity.points)
        {
            canvas.concat(mCamera);
            canvas.drawCircle((point.x-703), (point.y-1350), 20, paint);
            canvas.drawPoint((point.x-703), (point.y-1350), paint2);

        }



        //invalidate();
    }

    /**
     * Sets the new floorplan polygons model.
     */
    public void setFloorplan(List<TangoPolygon> polygons) {
        mPolygons = polygons;
    }

    public void registerCallback(DrawingCallback callback) {
        mCallback = callback;
    }

    /**
     * Updates the current rotation and translation to be used for the map. This is called with the
     * current device position and orientation.
     */
    public void updateCameraMatrix(float translationX, float translationY, float yawRadians) {
        mCamera.setTranslate(-translationX * SCALE, translationY * SCALE);
        mCamera.preRotate((float) Math.toDegrees(yawRadians), translationX * SCALE, -translationY
                * SCALE);
        mCamera.invert(mCameraInverse);
    }

    public Canvas getCanvas() {
        Canvas canvas = mSurfaceHolder.lockCanvas();
        //mSurfaceHolder.unlockCanvasAndPost(canvas);
        return canvas;
    }

    public void releaseCanvas(Canvas c) {
        mSurfaceHolder.unlockCanvasAndPost(c);
    }



}
